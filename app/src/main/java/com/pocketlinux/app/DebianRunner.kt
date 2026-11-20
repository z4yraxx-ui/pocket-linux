package com.pocketlinux.app

import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Roda o Debian dentro do PRoot: o shell interativo num pseudoterminal de verdade
 * e comandos avulsos (como o apt do botão "Atualizar") sem terminal.
 */
class DebianRunner(private val paths: PocketPaths) {

    @Volatile
    private var pty: PtySession? = null

    /** Processo de um comando avulso (atualização), separado do shell interativo. */
    @Volatile
    private var execProcess: Process? = null

    /**
     * PRoot com tudo configurado, faltando só o programa a executar.
     * [asUser] roda como o usuário comum "pocket" (usado pelo desktop); senão, como root simulado.
     */
    private fun baseCommand(asUser: Boolean = false): List<String> {
        // Pasta do celular (ou pasta própria do app, se não houver permissão) vira ~/Android.
        // Precisa existir no Debian antes de montar.
        val home = if (asUser) "/home/pocket" else "/root"
        val mountPoint = if (asUser) File(paths.rootfsDir, "home/pocket/Android") else paths.sharedMount
        mountPoint.mkdirs()
        val shared = paths.sharedHostDir()
        return listOf(
            paths.prootBin.path,
            "-r", paths.rootfsDir.path,
            // root simulado (apt, dpkg e sudo funcionam) ou o usuário comum "pocket" (uid 1000)
            if (asUser) "--change-id=1000:1000" else "-0",
            // TESTE: sem --link2symlink, para o instalador do Hermes conseguir hardlinks
            "--kill-on-exit",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${shared.path}:$home/Android",
            "-w", home
        )
    }

    private fun environment(noSeccomp: Boolean, term: String, asUser: Boolean = false): Map<String, String> {
        val user = if (asUser) "pocket" else "root"
        val env = linkedMapOf(
            "PROOT_LOADER" to paths.prootLoader.path,
            "PROOT_TMP_DIR" to paths.prootTmpDir.path,
            "HOME" to (if (asUser) "/home/pocket" else "/root"),
            "USER" to user,
            "LOGNAME" to user,
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to term,
            "LANG" to "pt_BR.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive"
        )
        if (term != "dumb") env["COLORTERM"] = "truecolor"
        if (noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        return env
    }

    /**
     * Inicia o shell num pseudoterminal. A saída vai direto para [terminal].
     * Lança IOException se o processo não puder ser criado.
     */
    @Throws(IOException::class)
    fun start(
        noSeccomp: Boolean,
        rows: Int,
        cols: Int,
        terminal: Terminal,
        onExit: (Int) -> Unit
    ) {
        paths.prootTmpDir.mkdirs()
        val argv = baseCommand() + listOf("/bin/bash", "-i")
        val env = environment(noSeccomp, "xterm-256color").map { "${it.key}=${it.value}" }
        pty = PtySession.start(argv, env, paths.baseDir.path, rows, cols, terminal, onExit)
    }

    /**
     * Roda um comando avulso no Debian (não interativo) e entrega a saída linha a linha.
     * Bloqueia até terminar: chamar fora da thread principal. Devolve o código de saída.
     */
    @Throws(IOException::class)
    fun exec(script: String, noSeccomp: Boolean, onLine: (String) -> Unit): Int {
        paths.prootTmpDir.mkdirs()
        val builder = ProcessBuilder(baseCommand() + listOf("/bin/bash", "-c", script))
        builder.redirectErrorStream(true)
        builder.directory(paths.baseDir)
        builder.environment().apply {
            clear()
            putAll(environment(noSeccomp, "dumb"))
        }
        val p = builder.start()
        execProcess = p
        try {
            p.outputStream.close() // nada vai ser digitado: evita o apt esperar entrada
        } catch (e: IOException) {
            // já fechou
        }
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
        } catch (e: IOException) {
            // processo morreu: segue para o waitFor
        }
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            -1
        }
        if (execProcess === p) execProcess = null
        return code
    }

    /**
     * Inicia, em segundo plano, um programa do Debian como usuário comum (o desktop LXQt).
     * A saída é entregue linha a linha em [onLine]; [onExit] avisa quando o processo acaba.
     * Lança IOException se o PRoot não puder ser executado.
     */
    @Throws(IOException::class)
    fun startAsUser(
        script: String,
        noSeccomp: Boolean,
        extraEnv: Map<String, String>,
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit
    ): Process {
        paths.prootTmpDir.mkdirs()
        val builder = ProcessBuilder(baseCommand(asUser = true) + listOf("/bin/bash", "-c", script))
        builder.redirectErrorStream(true)
        builder.directory(paths.baseDir)
        builder.environment().apply {
            clear()
            putAll(environment(noSeccomp, "dumb", asUser = true))
            putAll(extraEnv)
        }
        val p = builder.start()
        try {
            p.outputStream.close() // o desktop não lê nada da entrada
        } catch (e: IOException) {
            // já fechou
        }
        thread(name = "proot-desktop", isDaemon = true) {
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
            } catch (e: IOException) {
                // processo morreu: segue para o waitFor
            }
            val code = try {
                p.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            onExit(code)
        }
        return p
    }

    /** Envia teclas ao shell. Pode ser chamado de qualquer thread. */
    fun send(bytes: ByteArray) {
        pty?.write(bytes)
    }

    /** Avisa o shell do novo tamanho da tela. */
    fun resizePty(rows: Int, cols: Int) {
        pty?.resize(rows, cols)
    }

    fun stop() {
        pty?.stop()
        execProcess?.destroyForcibly()
    }
}
