package com.pocketlinux.app

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Um processo rodando dentro de um pseudoterminal de verdade: tem controle de trabalhos,
 * Ctrl+C funciona e programas de tela cheia (nano, top, vim) enxergam um terminal.
 * Tudo que o processo escreve vai para o [Terminal].
 */
class PtySession private constructor(
    private val fd: Int,
    private val pid: Int
) {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pty-writer").apply { isDaemon = true }
    }

    @Volatile
    private var exited = false

    @Volatile
    private var closed = false

    /** Envia teclas ao processo. Pode ser chamado de qualquer thread. */
    fun write(bytes: ByteArray) {
        if (closed) return
        try {
            writer.execute {
                if (!closed) PtyNative.writeFd(fd, bytes, 0, bytes.size)
            }
        } catch (e: RejectedExecutionException) {
            // sessão já encerrou
        }
    }

    /** Avisa o processo do novo tamanho da tela (ele recebe SIGWINCH). */
    fun resize(rows: Int, cols: Int) {
        if (!closed) PtyNative.setWindowSize(fd, rows, cols)
    }

    /** Pede para o processo e tudo no grupo dele fecharem; se demorar, mata. */
    fun stop() {
        if (exited) return
        PtyNative.killGroup(pid, SIGHUP)
        thread(name = "pty-killer", isDaemon = true) {
            try {
                Thread.sleep(1200)
            } catch (e: InterruptedException) {
                return@thread
            }
            if (!exited) PtyNative.killGroup(pid, SIGKILL)
        }
    }

    private fun begin(terminal: Terminal, onExit: (Int) -> Unit) {
        val reader = thread(name = "pty-reader", isDaemon = true) {
            val buf = ByteArray(8192)
            while (true) {
                val n = PtyNative.readFd(fd, buf, 300)
                if (n == -2) {
                    if (exited) break else continue
                }
                if (n <= 0) break
                terminal.feed(buf, 0, n)
            }
        }
        thread(name = "pty-waiter", isDaemon = true) {
            val code = PtyNative.waitFor(pid)
            // Sobras no mesmo grupo (programas em segundo plano) morrem junto com o shell.
            PtyNative.killGroup(pid, SIGKILL)
            exited = true
            reader.join(2000)
            closed = true
            writer.shutdownNow()
            try {
                writer.awaitTermination(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // segue para fechar
            }
            PtyNative.closeFd(fd)
            onExit(code)
        }
    }

    companion object {
        private const val SIGHUP = 1
        private const val SIGKILL = 9

        /** Lança IOException se não der para criar o processo. */
        @Throws(IOException::class)
        fun start(
            argv: List<String>,
            env: List<String>,
            cwd: String,
            rows: Int,
            cols: Int,
            terminal: Terminal,
            onExit: (Int) -> Unit
        ): PtySession {
            val r = PtyNative.createSubprocess(cwd, argv.toTypedArray(), env.toTypedArray(), rows, cols)
            val session = PtySession(r[0], r[1])
            session.begin(terminal, onExit)
            return session
        }
    }
}
