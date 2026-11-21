package com.pocketlinux.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Estado único do app (vive enquanto o processo viver): instalação do Debian,
 * o shell em execução e a tela do terminal. A interface só observa daqui.
 */
object PocketSession {
    internal lateinit var paths: PocketPaths
        private set
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null

    internal val runner by lazy { DebianRunner(paths) }

    private val _installState = MutableStateFlow<InstallState>(InstallState.NotInstalled)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    /** A tela do terminal: o shell escreve nela, e a saída do "Atualizar" também. */
    val terminal = Terminal(24, 80).also { t ->
        t.onReply = { bytes -> runner.send(bytes) }
    }

    /** Último tamanho de tela conhecido (linhas, colunas); o shell já nasce com ele. */
    @Volatile
    private var termRows = 24

    @Volatile
    private var termCols = 80

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /** Verdadeiro enquanto algo precisa manter o processo vivo: o shell ou uma atualização. */
    val isBusy: Flow<Boolean> = combine(
        _isRunning,
        _updateState,
        DesktopSession.state,
        HermesSession.state
    ) { running, update, desktop, hermes ->
        running || update.isBusy() || desktop.isBusy() || hermes.isBusy()
    }

    /** Vira verdadeiro se o PRoot só funcionou sem seccomp; comandos avulsos seguem o mesmo modo. */
    @Volatile
    private var noSeccompNeeded = false

    @Volatile
    private var userStopped = false

    /** O PRoot só funcionou sem seccomp neste aparelho? Os outros programas seguem o mesmo modo. */
    internal fun noSeccomp(): Boolean = noSeccompNeeded

    fun init(context: Context) {
        if (::paths.isInitialized) return
        appContext = context.applicationContext
        paths = PocketPaths(context)
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        _installState.value =
            if (paths.installedMarker.exists() && paths.rootfsDir.isDirectory) InstallState.Installed
            else InstallState.NotInstalled
    }

    // ---------- instalação ----------

    fun startInstall() {
        if (installJob?.isActive == true) return
        _installState.value = InstallState.Working("Preparando")
        installJob = scope.launch {
            try {
                RootfsInstaller(paths).install { _installState.value = it }
                _installState.value = InstallState.Installed
            } catch (e: CancellationException) {
                _installState.value = InstallState.NotInstalled
                throw e
            } catch (e: InstallException) {
                _installState.value = InstallState.Failed(e.message ?: "Erro desconhecido")
            } catch (e: IOException) {
                _installState.value = InstallState.Failed("Falha de rede ou de disco: ${e.message}")
            } catch (e: Exception) {
                _installState.value = InstallState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    fun uninstall() {
        if (installJob?.isActive == true) return
        stopShell()
        _installState.value = InstallState.Working("Apagando")
        installJob = scope.launch {
            try {
                deleteTree(paths.baseDir)
                _installState.value = InstallState.NotInstalled
            } catch (e: Exception) {
                _installState.value = InstallState.Failed("Não consegui apagar: ${e.message}")
            }
        }
    }

    // ---------- shell ----------

    /** Escreve um aviso do app na tela do terminal (as quebras de linha viram CR+LF). */
    private fun say(text: String) {
        val fixed = text.replace("\r\n", "\n").replace("\n", "\r\n")
        terminal.feed(fixed.toByteArray(Charsets.UTF_8))
    }

    fun startShell() {
        if (_isRunning.value) return
        if (_installState.value !is InstallState.Installed) return
        userStopped = false
        // Não apaga a tela se uma atualização está escrevendo nela.
        if (!_updateState.value.isBusy()) terminal.reset()
        launchShell(noSeccomp = noSeccompNeeded)
        startBackgroundService()
    }

    /**
     * Liga o serviço em primeiro plano que segura o processo com o app fechado.
     * Ele se desliga sozinho quando [isBusy] vira falso. Se o Android recusar
     * (app fora de primeiro plano), o Debian segue rodando só enquanto o app viver.
     */
    internal fun startBackgroundService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, PocketService::class.java))
        } catch (e: Exception) {
            say("[Aviso: não consegui ligar o serviço em segundo plano: ${e.message}]\n")
        }
    }

    private fun launchShell(noSeccomp: Boolean) {
        if (noSeccomp) noSeccompNeeded = true
        _isRunning.value = true
        val startedAt = SystemClock.elapsedRealtime()
        try {
            runner.start(
                noSeccomp = noSeccomp,
                rows = termRows,
                cols = termCols,
                terminal = terminal
            ) { code ->
                val quickFailure = SystemClock.elapsedRealtime() - startedAt < 4000
                if (code != 0 && quickFailure && !noSeccomp && !userStopped) {
                    say("\n[O PRoot fechou logo (código $code). Tentando de novo sem seccomp...]\n")
                    launchShell(noSeccomp = true)
                } else {
                    say("\n[sessão encerrada, código $code]\n")
                    _isRunning.value = false
                }
            }
        } catch (e: IOException) {
            say("[Não consegui executar o PRoot: ${e.message}]\n[Caminho: ${paths.prootBin.path}]\n")
            _isRunning.value = false
        } catch (e: UnsatisfiedLinkError) {
            say("[Não consegui carregar o código nativo do terminal: ${e.message}]\n")
            _isRunning.value = false
        }
    }

    /** Envia o que foi digitado ao Debian (já com as sequências de teclas prontas). */
    fun writeInput(text: String) {
        if (!_isRunning.value) return
        runner.send(text.toByteArray(Charsets.UTF_8))
    }

    /** Cola texto; se o programa pediu "bracketed paste", avisa que é uma colagem. */
    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val bracketed = terminal.read { it.bracketedPaste }
        writeInput(if (bracketed) "\u001B[200~$text\u001B[201~" else text.replace("\r\n", "\n").replace("\n", "\r"))
    }

    /** A tela mudou de tamanho (teclado abriu, tela girou): ajusta a grade e avisa o shell. */
    fun resizeTerminal(rows: Int, cols: Int) {
        if (rows < 2 || cols < 2) return
        if (rows == termRows && cols == termCols) return
        termRows = rows
        termCols = cols
        terminal.resize(rows, cols)
        runner.resizePty(rows, cols)
    }

    fun stopShell() {
        userStopped = true
        runner.stop()
    }

    // ---------- atualizar ----------

    /** Passo 1: atualiza a lista de pacotes e conta quantos têm atualização (ainda não instala nada). */
    fun checkUpdates() {
        if (_installState.value !is InstallState.Installed) return
        if (_updateState.value.isBusy()) return
        _updateState.value = UpdateState.Checking
        startBackgroundService()
        scope.launch {
            try {
                say("\n[Atualizar] Buscando a lista de pacotes...\n")
                val code = runner.exec("apt-get update 2>&1", noSeccompNeeded) { say(it + "\n") }
                if (code != 0) {
                    _updateState.value =
                        UpdateState.Failed("O apt update terminou com erro (código $code). Veja o terminal.")
                    return@launch
                }
                var count = 0
                runner.exec("apt-get -s upgrade 2>&1", noSeccompNeeded) { line ->
                    if (line.startsWith("Inst ")) count++
                }
                if (count == 0) {
                    say("[Atualizar] Tudo atualizado.\n")
                    _updateState.value = UpdateState.UpToDate
                } else {
                    say("[Atualizar] $count pacote(s) com atualização.\n")
                    _updateState.value = UpdateState.Available(count)
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Failed("Não consegui rodar o apt: ${e.message}")
            }
        }
    }

    /** Passo 2: instala as atualizações achadas no passo 1. */
    fun applyUpdates() {
        if (_updateState.value !is UpdateState.Available) return
        _updateState.value = UpdateState.Upgrading
        startBackgroundService()
        scope.launch {
            try {
                say("\n[Atualizar] Instalando as atualizações...\n")
                val code = runner.exec(
                    "apt-get upgrade -y -o Dpkg::Options::=--force-confold 2>&1",
                    noSeccompNeeded
                ) { say(it + "\n") }
                if (code == 0) {
                    say("[Atualizar] Concluído.\n")
                    _updateState.value = UpdateState.Done
                } else {
                    _updateState.value =
                        UpdateState.Failed("A atualização terminou com erro (código $code). Veja o terminal.")
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Failed("Não consegui rodar o apt: ${e.message}")
            }
        }
    }

    fun dismissUpdate() {
        if (!_updateState.value.isBusy()) _updateState.value = UpdateState.Idle
    }

    // ---------- diagnóstico e armazenamento ----------

    fun hasPhoneStorageAccess(): Boolean = paths.hasPhoneStorageAccess()

    /** Últimas linhas do terminal, para o relatório de diagnóstico. */
    fun lastTerminalLines(count: Int): List<String> {
        val text = terminal.transcript(count)
        return if (text.isEmpty()) emptyList() else text.split("\n")
    }
}
