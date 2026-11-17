package com.pocketlinux.app

import java.io.IOException

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

    /** PRoot com tudo configurado, faltando só o programa a executar. */
    private fun baseCommand(): List<String> {
        // Pasta do celular (ou pasta própria do app, se não houver permissão) vira ~/Android.
        // Precisa existir no Debian antes de montar.
        paths.sharedMount.mkdirs()
        val shared = paths.sharedHostDir()
        return listOf(
            paths.prootBin.path,
            "-r", paths.rootfsDir.path,
            "-0", // finge ser root (apt, dpkg e sudo funcionam)
            "--link2symlink", // o Android proíbe hard links: o PRoot troca por links simbólicos
            "--kill-on-exit",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${shared.path}:/root/Android",
            "-w", "/root"
        )
    }

    private fun environment(noSeccomp: Boolean, term: String): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_LOADER" to paths.prootLoader.path,
            "PROOT_TMP_DIR" to paths.prootTmpDir.path,
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
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
