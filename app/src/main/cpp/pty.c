/*
 * Pseudoterminal (PTY) para o PocketLinux.
 *
 * Abre /dev/ptmx, cria um processo filho com o lado "escravo" como terminal de controle
 * (stdin/stdout/stderr) e expõe leitura, escrita, redimensionamento e espera ao Kotlin.
 * Código próprio, sem terceiros.
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, msg);
    }
}

/* Copia um String[] do Java para um vetor terminado em NULL (para execve). */
static char **make_vector(JNIEnv *env, jobjectArray arr) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **)calloc((size_t)n + 1, sizeof(char *));
    if (out == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = c != NULL ? strdup(c) : strdup("");
        if (c != NULL) {
            (*env)->ReleaseStringUTFChars(env, s, c);
        }
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_vector(char **v) {
    if (v == NULL) {
        return;
    }
    for (char **p = v; *p != NULL; p++) {
        free(*p);
    }
    free(v);
}

/* Devolve {fd do lado mestre, pid do filho}. */
JNIEXPORT jintArray JNICALL
Java_com_pocketlinux_app_PtyNative_createSubprocess(JNIEnv *env, jobject thiz, jstring cwd,
                                                    jobjectArray argv, jobjectArray envp,
                                                    jint rows, jint cols) {
    (void)thiz;
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        throw_io(env, "Não consegui abrir /dev/ptmx");
        return NULL;
    }

    char devname[64];
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 || ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        throw_io(env, "Não consegui preparar o pseudoterminal");
        return NULL;
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(ptm, TIOCSWINSZ, &ws);

    /* Tudo que usa o JNI/malloc é preparado antes do fork. */
    char **c_argv = make_vector(env, argv);
    char **c_envp = make_vector(env, envp);
    const char *c_cwd = cwd != NULL ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;
    if (c_argv == NULL || c_envp == NULL || c_argv[0] == NULL) {
        free_vector(c_argv);
        free_vector(c_envp);
        if (c_cwd != NULL) {
            (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
        }
        close(ptm);
        throw_io(env, "Argumentos inválidos para iniciar o processo");
        return NULL;
    }

    pid_t pid = fork();
    if (pid < 0) {
        free_vector(c_argv);
        free_vector(c_envp);
        if (c_cwd != NULL) {
            (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
        }
        close(ptm);
        throw_io(env, "fork falhou");
        return NULL;
    }

    if (pid == 0) {
        /* Filho: só chamadas seguras até o execve. */
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGTERM, SIG_DFL);

        setsid();
        int pts = open(devname, O_RDWR);
        if (pts < 0) {
            _exit(126);
        }
        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        for (int fd = 3; fd < 256; fd++) {
            close(fd);
        }
        if (c_cwd != NULL) {
            if (chdir(c_cwd) != 0) {
                /* segue no diretório atual */
            }
        }
        execve(c_argv[0], c_argv, c_envp);
        _exit(127);
    }

    /* Pai. */
    free_vector(c_argv);
    free_vector(c_envp);
    if (c_cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
    }

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) {
        return NULL;
    }
    jint values[2];
    values[0] = ptm;
    values[1] = (jint)pid;
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

/*
 * Lê até o tamanho do buffer. Devolve: > 0 bytes lidos, 0 fim, -1 erro (por exemplo EIO quando
 * o filho fechou o terminal), -2 se nada chegou dentro do tempo (o Kotlin decide se continua).
 */
JNIEXPORT jint JNICALL
Java_com_pocketlinux_app_PtyNative_readFd(JNIEnv *env, jobject thiz, jint fd, jbyteArray buf,
                                          jint timeout_ms) {
    (void)thiz;
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int pr;
    do {
        pr = poll(&pfd, 1, timeout_ms);
    } while (pr < 0 && errno == EINTR);
    if (pr < 0) {
        return -1;
    }
    if (pr == 0) {
        return -2;
    }

    jsize len = (*env)->GetArrayLength(env, buf);
    jbyte *tmp = (jbyte *)malloc((size_t)len);
    if (tmp == NULL) {
        return -1;
    }
    ssize_t n;
    do {
        n = read(fd, tmp, (size_t)len);
    } while (n < 0 && errno == EINTR);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, 0, (jsize)n, tmp);
    }
    free(tmp);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_app_PtyNative_writeFd(JNIEnv *env, jobject thiz, jint fd, jbyteArray buf,
                                           jint off, jint len) {
    (void)thiz;
    if (len <= 0) {
        return 0;
    }
    jbyte *tmp = (jbyte *)malloc((size_t)len);
    if (tmp == NULL) {
        return -1;
    }
    (*env)->GetByteArrayRegion(env, buf, off, len, tmp);
    size_t done = 0;
    while (done < (size_t)len) {
        ssize_t n = write(fd, tmp + done, (size_t)len - done);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            free(tmp);
            return -1;
        }
        done += (size_t)n;
    }
    free(tmp);
    return len;
}

JNIEXPORT void JNICALL
Java_com_pocketlinux_app_PtyNative_setWindowSize(JNIEnv *env, jobject thiz, jint fd, jint rows,
                                                 jint cols) {
    (void)env;
    (void)thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/* Espera o filho acabar. Devolve o código de saída, ou 128 + sinal se foi morto. */
JNIEXPORT jint JNICALL
Java_com_pocketlinux_app_PtyNative_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    (void)env;
    (void)thiz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t)pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

/* Manda um sinal ao grupo de processos do filho (ele é líder de sessão: pgid == pid). */
JNIEXPORT void JNICALL
Java_com_pocketlinux_app_PtyNative_killGroup(JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void)env;
    (void)thiz;
    if (pid > 1) {
        kill(-(pid_t)pid, sig);
    }
}

JNIEXPORT void JNICALL
Java_com_pocketlinux_app_PtyNative_closeFd(JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    close(fd);
}
