package com.pocketlinux.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

sealed interface DesktopState {
    data object Stopped : DesktopState
    data class Starting(val step: String) : DesktopState
    data object Running : DesktopState
    data class Failed(val message: String) : DesktopState

    /** Verdadeiro enquanto o desktop precisa manter o app vivo em segundo plano. */
    fun isBusy(): Boolean = this is Starting || this is Running
}

/**
 * O desktop: sobe o servidor X (Termux:X11, módulo :lorie), abre a tela dele e inicia o LXQt
 * dentro do Debian apontando para esse servidor.
 *
 * O servidor X roda em um processo próprio (app_process com o APK do app no CLASSPATH), como no
 * Termux:X11 original. Ele cria o socket em /tmp/.X11-unix do Debian, então os programas do Debian
 * se conectam a ele direto, sem rede e sem compressão de imagem.
 */
object DesktopSession {
    private const val DISPLAY = ":1"
    private const val SOCKET_NAME = "X1"
    private const val X11_ACTIVITY = "com.termux.x11.MainActivity"
    private const val X11_STOP_ACTION = "com.termux.x11.ACTION_STOP"
    private const val MAX_LOG_LINES = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val _state = MutableStateFlow<DesktopState>(DesktopState.Stopped)
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    private val logLines = ArrayDeque<String>()

    @Volatile
    private var xServer: Process? = null

    @Volatile
    private var session: Process? = null

    @Volatile
    private var stopRequested = false

    // ---------- log ----------

    private fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            if (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    fun fullLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun logTail(count: Int): String = synchronized(logLines) {
        logLines.takeLast(count).joinToString("\n")
    }

    // ---------- controle ----------

    /** Liga o desktop; se já estiver ligado, só traz a tela de volta. */
    fun start(context: Context) {
        val app = context.applicationContext
        when (_state.value) {
            is DesktopState.Running -> {
                showScreen(app)
                return
            }

            is DesktopState.Starting -> return
            else -> Unit
        }
        stopRequested = false
        synchronized(logLines) { logLines.clear() }
        _state.value = DesktopState.Starting("Preparando")
        PocketSession.startBackgroundService()
        startJob = scope.launch {
            try {
                bringUp(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(app, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Desliga o LXQt e o servidor X e fecha a tela. */
    fun stop(context: Context) {
        val app = context.applicationContext
        stopRequested = true
        startJob?.cancel()
        killProcesses()
        closeScreen(app)
        _state.value = DesktopState.Stopped
    }

    private fun killProcesses() {
        session?.destroyForcibly()
        xServer?.destroyForcibly()
        session = null
        xServer = null
    }

    private fun fail(app: Context, message: String) {
        val tail = logTail(12)
        killProcesses()
        closeScreen(app)
        _state.value = DesktopState.Failed(if (tail.isEmpty()) message else "$message\n\n$tail")
    }

    // ---------- tela do servidor X ----------

    private fun showScreen(app: Context) {
        try {
            app.startActivity(
                Intent()
                    .setClassName(app.packageName, X11_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            log("[app] Não consegui abrir a tela do desktop: ${e.message}")
        }
    }

    private fun closeScreen(app: Context) {
        try {
            app.sendBroadcast(Intent(X11_STOP_ACTION).setPackage(app.packageName))
        } catch (e: Exception) {
            // a tela já fechou
        }
    }

    // ---------- subida ----------

    private suspend fun bringUp(app: Context) {
        val paths = PocketSession.paths
        val rootfs = paths.rootfsDir
        if (!paths.installedMarker.exists()) return fail(app, "Instale o Debian primeiro.")

        val xlorie = File(app.applicationInfo.nativeLibraryDir, "libXlorie.so")
        if (!xlorie.exists()) {
            return fail(
                app,
                "O servidor X não veio dentro do APK (libXlorie.so ausente). " +
                    "Compile com os submódulos baixados (checkout com submodules: recursive)."
            )
        }

        val xkbRoot = File(rootfs, "usr/share/X11/xkb")
        if (!File(xkbRoot, "rules/evdev").exists()) {
            return fail(
                app,
                "Faltam as regras de teclado do X (pacote xkb-data) no Debian. No terminal, rode:\n" +
                    "apt update && apt install -y xkb-data fonts-dejavu-core x11-xserver-utils\n" +
                    "ou apague e instale o Debian de novo com a base mais nova."
            )
        }

        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        val xDir = File(tmp, ".X11-unix")
        File(xDir, SOCKET_NAME).delete()
        File(tmp, ".X1-lock").delete()
        File(tmp, "runtime-pocket").apply {
            mkdirs()
            try {
                Os.chmod(path, 0x1C0) // 0700, exigido pelo XDG_RUNTIME_DIR
            } catch (e: Exception) {
                // segue: o Qt só avisa
            }
        }

        // 1) servidor X
        _state.value = DesktopState.Starting("Iniciando o servidor X")
        val pb = ProcessBuilder(
            "/system/bin/app_process", "-Xnoimage-dex2oat", "/",
            "--nice-name=pocketlinux-x11", "com.termux.x11.CmdEntryPoint", DISPLAY
        )
        pb.redirectErrorStream(true)
        pb.environment().apply {
            remove("LD_LIBRARY_PATH")
            remove("LD_PRELOAD")
            put("CLASSPATH", app.applicationInfo.sourceDir)
            put("TMPDIR", tmp.path)
            put("XKB_CONFIG_ROOT", xkbRoot.path)
            put("POCKETLINUX_XLORIE", xlorie.path)
            put("HOME", app.filesDir.path)
        }
        val server = pb.start()
        xServer = server
        thread(name = "x11-server-output", isDaemon = true) {
            try {
                server.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { log("[x11] $it") }
            } catch (e: Exception) {
                // processo morreu
            }
            val code = try {
                server.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            if (!stopRequested && xServer === server) {
                fail(app, "O servidor X parou (código $code).")
            }
        }

        val socket = File(xDir, SOCKET_NAME)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!socket.exists()) {
            ensureActive()
            if (!server.isAlive) return fail(app, "O servidor X fechou ao iniciar (código ${server.exitValue()}).")
            if (SystemClock.elapsedRealtime() > deadline) {
                return fail(app, "O servidor X demorou demais para iniciar.")
            }
            delay(100)
        }

        // 2) tela
        _state.value = DesktopState.Starting("Abrindo a tela")
        showScreen(app)

        // 3) LXQt dentro do Debian, como usuário comum, apontando para o servidor X
        _state.value = DesktopState.Starting("Iniciando o LXQt")
        val env = mapOf(
            "DISPLAY" to DISPLAY,
            "XDG_RUNTIME_DIR" to "/tmp/runtime-pocket",
            "XDG_SESSION_TYPE" to "x11",
            "XDG_CURRENT_DESKTOP" to "LXQt",
            "QT_QPA_PLATFORM" to "xcb",
            "QT_X11_NO_MITSHM" to "1",
            "_X11_NO_MITSHM" to "1",
            "GDK_BACKEND" to "x11",
            "LIBGL_ALWAYS_SOFTWARE" to "1"
        )
        val startedAt = SystemClock.elapsedRealtime()
        val proc = PocketSession.runner.startAsUser(
            script = "exec dbus-launch --exit-with-session startlxqt",
            noSeccomp = PocketSession.noSeccomp(),
            extraEnv = env,
            onLine = { log("[lxqt] $it") },
            onExit = { code -> onSessionExit(app, code, startedAt) }
        )
        session = proc
        _state.value = DesktopState.Running
    }

    private fun onSessionExit(app: Context, code: Int, startedAt: Long) {
        if (stopRequested) return
        val quick = SystemClock.elapsedRealtime() - startedAt < 8000
        if (code != 0 && quick) {
            fail(app, "O LXQt fechou logo ao iniciar (código $code).")
        } else {
            // o usuário saiu da sessão pelo menu do LXQt
            killProcesses()
            closeScreen(app)
            _state.value = DesktopState.Stopped
        }
    }
}
