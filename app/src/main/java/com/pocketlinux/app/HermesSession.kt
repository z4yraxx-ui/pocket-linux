package com.pocketlinux.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface HermesState {
    /** O Hermes ainda não foi instalado no Debian. */
    data object NotInstalled : HermesState

    /** Instalando: [line] é a última linha de saída (mostra que está andando). */
    data class Installing(val line: String) : HermesState

    /** Instalado. [report] é o resultado do último diagnóstico; [checking] é verdadeiro enquanto roda. */
    data class Ready(val report: String? = null, val checking: Boolean = false) : HermesState

    data class Failed(val message: String) : HermesState

    /** Verdadeiro enquanto o Hermes precisa manter o app vivo em segundo plano. */
    fun isBusy(): Boolean = this is Installing || (this is Ready && checking)
}

/**
 * O Hermes Agent dentro do Debian: instala (Python + ambiente virtual + pip), abre no terminal
 * e roda o diagnóstico. O Hermes não roda como serviço aqui; ele roda no terminal do app.
 *
 * Instala com o pip em vez do instalador oficial: o instalador usa o `uv`, que cria hardlinks, e o
 * PRoot (com --link2symlink) não deixa. O pip copia os arquivos e funciona.
 */
object HermesSession {
    private const val VENV = "/root/hermes-venv"
    private const val HERMES_BIN = "$VENV/bin/hermes"
    private const val MAX_LOG_LINES = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<HermesState>(HermesState.NotInstalled)
    val state: StateFlow<HermesState> = _state.asStateFlow()

    private val logLines = ArrayDeque<String>()

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

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

    private fun hermesFile(): File = File(PocketSession.paths.rootfsDir, "root/hermes-venv/bin/hermes")

    /** Confere se o Hermes já está no Debian. Não mexe no estado enquanto algo está rodando. */
    fun refresh() {
        if (_state.value.isBusy()) return
        if (!PocketSession.paths.installedMarker.exists()) {
            _state.value = HermesState.NotInstalled
            return
        }
        val current = _state.value
        if (hermesFile().exists()) {
            if (current !is HermesState.Ready) _state.value = HermesState.Ready()
        } else if (current !is HermesState.Failed) {
            _state.value = HermesState.NotInstalled
        }
    }

    // ---------- instalar ----------

    private val installScript = listOf(
        "set -e",
        "export PIP_DISABLE_PIP_VERSION_CHECK=1",
        "echo '[1/4] Atualizando a lista de pacotes'",
        "apt-get update",
        "echo '[2/4] Instalando Python e ferramentas'",
        "apt-get install -y python3 python3-venv python3-pip git curl ca-certificates ripgrep",
        "echo '[3/4] Criando o ambiente do Hermes'",
        "python3 -m venv $VENV",
        "echo '[4/4] Baixando o Hermes (pode demorar alguns minutos)'",
        "$VENV/bin/pip install --no-cache-dir hermes-agent",
        // deixa o comando `hermes` disponível em qualquer terminal do Debian
        "grep -qs hermes-venv /root/.bashrc || echo 'export PATH=\"$VENV/bin:\$PATH\"' >> /root/.bashrc",
        "test -x $HERMES_BIN",
        "echo 'Pronto.'"
    ).joinToString("\n")

    fun install() {
        if (_state.value.isBusy()) return
        if (!PocketSession.paths.installedMarker.exists()) {
            _state.value = HermesState.Failed("Instale o Debian primeiro.")
            return
        }
        if (PocketSession.updateState.value.isBusy()) {
            _state.value = HermesState.Failed("Espere a atualização de pacotes terminar e tente de novo.")
            return
        }
        synchronized(logLines) { logLines.clear() }
        _state.value = HermesState.Installing("Preparando")
        PocketSession.startBackgroundService()
        job = scope.launch {
            try {
                val code = PocketSession.runner.exec(installScript, PocketSession.noSeccomp()) { raw ->
                    val line = raw.replace(ansi, "").trimEnd()
                    if (line.isNotBlank()) {
                        log(line)
                        _state.value = HermesState.Installing(line.trim().take(110))
                    }
                }
                if (code == 0 && hermesFile().exists()) {
                    _state.value = HermesState.Ready()
                } else {
                    val tail = logTail(10)
                    _state.value = HermesState.Failed(
                        "A instalação terminou com erro (código $code)." + if (tail.isEmpty()) "" else "\n\n$tail"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = HermesState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ---------- diagnóstico ----------

    fun runDoctor() {
        val current = _state.value as? HermesState.Ready ?: return
        if (current.checking) return
        if (PocketSession.updateState.value.isBusy()) {
            _state.value = current.copy(report = "Espere a atualização de pacotes terminar e tente de novo.")
            return
        }
        _state.value = HermesState.Ready(report = null, checking = true)
        PocketSession.startBackgroundService()
        job = scope.launch {
            val out = StringBuilder()
            try {
                PocketSession.runner.exec("$HERMES_BIN doctor 2>&1", PocketSession.noSeccomp()) { raw ->
                    val line = raw.replace(ansi, "").trimEnd()
                    out.appendLine(line)
                }
                _state.value = HermesState.Ready(report = out.toString().trim().ifEmpty { "Sem saída." })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = HermesState.Ready(report = "Não consegui rodar o diagnóstico: ${e.message}")
            }
        }
    }

    fun dismissFailure() {
        if (_state.value is HermesState.Failed) {
            _state.value = HermesState.NotInstalled
            refresh()
        }
    }

    // ---------- abrir ----------

    /**
     * Digita o comando do Hermes no terminal que acabou de ser aberto.
     * Só deve ser chamado logo depois de ligar o shell (não escreve em um programa que já esteja rodando).
     */
    fun typeLaunchCommand() {
        PocketSession.writeInput("$HERMES_BIN\r")
    }
}
