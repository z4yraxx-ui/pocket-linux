package com.pocketlinux.app

/** Ponte para o código nativo (app/src/main/cpp/pty.c). */
object PtyNative {
    init {
        System.loadLibrary("pocketpty")
    }

    /** Devolve {fd do lado mestre, pid do filho}. Lança IOException se falhar. */
    external fun createSubprocess(
        cwd: String,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int
    ): IntArray

    /** > 0 bytes lidos; 0 ou -1 fim/erro; -2 nada chegou dentro do tempo. */
    external fun readFd(fd: Int, buf: ByteArray, timeoutMs: Int): Int

    external fun writeFd(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** Espera o filho acabar e devolve o código de saída (128 + sinal se morreu por sinal). */
    external fun waitFor(pid: Int): Int

    /** Manda o sinal ao grupo de processos do filho. */
    external fun killGroup(pid: Int, sig: Int)

    external fun closeFd(fd: Int)
}
