package com.pocketlinux.app

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF101522)
internal val Panel = Color(0xFF1B2433)
internal val Mint = Color(0xFF77E0B5)
internal val Muted = Color(0xFF9BA9BC)
private val ErrorRed = Color(0xFFFF8A80)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        PocketSession.init(applicationContext)
        setContent { PocketLinuxApp() }
    }
}

private enum class Screen { Home, Terminal, Diagnostics }

@Composable
fun PocketLinuxApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    // Mantém a tela ligada enquanto baixa/extrai o Debian ou atualiza pacotes,
    // para o Android não suspender o app no meio.
    val install by PocketSession.installState.collectAsState()
    val update by PocketSession.updateState.collectAsState()
    val view = LocalView.current
    val hermesInstall by HermesSession.state.collectAsState()
    val keepOn = install is InstallState.Working || update.isBusy() || hermesInstall is HermesState.Installing
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Panel,
            primary = Mint,
            onPrimary = Ink,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    onOpenTerminal = { screen = Screen.Terminal },
                    onOpenDiagnostics = { screen = Screen.Diagnostics }
                )
                Screen.Terminal -> TerminalScreen(onBack = { screen = Screen.Home })
                Screen.Diagnostics -> DiagnosticsScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenTerminal: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val state by PocketSession.installState.collectAsState()
    val running by PocketSession.isRunning.collectAsState()
    val update by PocketSession.updateState.collectAsState()
    val desktop by DesktopSession.state.collectAsState()
    val hermes by HermesSession.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirmDelete by remember { mutableStateOf(false) }

    // Acesso ao armazenamento do celular (pasta ~/Android no Debian).
    var showStorage by remember { mutableStateOf(false) }
    var storageGranted by remember { mutableStateOf(false) }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { storageGranted = PocketSession.hasPhoneStorageAccess() }

    val installed = state is InstallState.Installed
    var askBattery by remember { mutableStateOf(false) }
    // Pedido pelo botão "Abrir Hermes": digita o comando do Hermes no terminal recém-aberto.
    var wantHermes by remember { mutableStateOf(false) }
    val startAndOpen = {
        PocketSession.startShell()
        if (wantHermes) {
            wantHermes = false
            HermesSession.typeLaunchCommand()
        }
        onOpenTerminal()
    }
    // Na primeira vez, explica e pede a isenção de bateria antes de ligar o Debian.
    val openTerminal = {
        if (BatteryOptimization.shouldAsk(context)) {
            askBattery = true
        } else {
            startAndOpen()
        }
    }
    val openHermes = {
        if (running) {
            // O terminal já está aberto: não digita por cima de um programa em uso.
            Toast.makeText(context, "O terminal já está rodando. Digite hermes nele.", Toast.LENGTH_LONG).show()
            onOpenTerminal()
        } else {
            wantHermes = true
            openTerminal()
        }
    }

    // Confere se o Hermes já está instalado (e refaz a conferência quando o Debian muda).
    LaunchedEffect(state) { HermesSession.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PocketLinux", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Seu ambiente Linux no Android", color = Muted, fontSize = 13.sp)
            }
            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                Text("PL", modifier = Modifier.padding(14.dp), color = Mint, fontWeight = FontWeight.Bold)
            }
        }

        Surface(
            color = Panel,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DEBIAN", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    val label = when (val s = state) {
                        is InstallState.NotInstalled -> "Não instalado"
                        is InstallState.Working -> if (s.phase == "Apagando") "Apagando" else "Instalando"
                        is InstallState.Installed -> if (running) "Em execução" else "Instalado"
                        is InstallState.Failed -> "Erro"
                    }
                    StatusPill(label)
                }
                Text("Debian ARM64 + LXQt", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)

                when (val s = state) {
                    is InstallState.NotInstalled -> {
                        Text(
                            "Baixa o Debian já pronto e instala no armazenamento privado do aplicativo. " +
                                "Use Wi-Fi: o download é grande.",
                            color = Muted,
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = { PocketSession.startInstall() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Instalar Debian") }
                    }

                    is InstallState.Failed -> {
                        Text(s.message, color = ErrorRed, fontSize = 13.sp)
                        Button(
                            onClick = { PocketSession.startInstall() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Tentar de novo") }
                    }

                    is InstallState.Working -> {
                        val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else null
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        val detail = if (s.total > 0) {
                            "${s.phase}…  ${formatBytes(s.done)} de ${formatBytes(s.total)} " +
                                "(${(s.done * 100 / s.total).toInt()}%)"
                        } else {
                            "${s.phase}…"
                        }
                        Text(detail, color = Muted, fontSize = 13.sp)
                        if (s.phase != "Apagando") {
                            OutlinedButton(
                                onClick = { PocketSession.cancelInstall() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Cancelar") }
                        }
                    }

                    is InstallState.Installed -> {
                        Text(
                            "O Debian está instalado. Use o terminal de texto ou abra o desktop LXQt no cartão abaixo.",
                            color = Muted,
                            fontSize = 13.sp
                        )
                        if (running) {
                            Text(
                                "O Debian continua rodando com o app fechado. Para parar, use o botão abaixo " +
                                    "ou \"Encerrar\" na notificação.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Button(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) {
                                Text("Abrir terminal")
                            }
                            OutlinedButton(
                                onClick = { PocketSession.stopShell() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Encerrar Debian") }
                        } else {
                            Button(onClick = openTerminal, modifier = Modifier.fillMaxWidth()) {
                                Text("Iniciar Debian")
                            }
                        }
                        OutlinedButton(
                            onClick = { PocketSession.checkUpdates() },
                            enabled = !update.isBusy(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Atualizar pacotes") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Apagar Debian", color = Muted)
                        }
                    }
                }
            }
        }

        if (installed) {
            DesktopCard(
                state = desktop,
                onStart = { DesktopSession.start(context) },
                onStop = { DesktopSession.stop(context) },
                onCopyLog = { clipboard.setText(AnnotatedString(DesktopSession.fullLog())) }
            )
            HermesCard(
                state = hermes,
                onInstall = { HermesSession.install() },
                onOpen = openHermes,
                onDoctor = { HermesSession.runDoctor() },
                onCopyLog = { clipboard.setText(AnnotatedString(HermesSession.fullLog())) },
                onDismissError = { HermesSession.dismissFailure() }
            )
        }

        Text("Acesso rápido", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTile("Desktop", Icons.Default.Monitor, Modifier.weight(1f)) {
                if (installed) {
                    DesktopSession.start(context)
                } else {
                    Toast.makeText(context, "Instale o Debian primeiro", Toast.LENGTH_SHORT).show()
                }
            }
            QuickTile("Arquivos", Icons.Default.Folder, Modifier.weight(1f)) {
                storageGranted = PocketSession.hasPhoneStorageAccess()
                showStorage = true
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTile("Terminal", Icons.Default.Terminal, Modifier.weight(1f)) {
                if (installed) {
                    openTerminal()
                } else {
                    Toast.makeText(context, "Instale o Debian primeiro", Toast.LENGTH_SHORT).show()
                }
            }
            QuickTile("Diagnóstico", Icons.Default.Settings, Modifier.weight(1f)) { onOpenDiagnostics() }
        }

        Spacer(Modifier.weight(1f))
        Text("PocketLinux ${BuildConfig.VERSION_NAME} • fase 4 (Hermes)", color = Muted, fontSize = 11.sp)
    }

    UpdateDialog(onOpenTerminal = onOpenTerminal)

    if (showStorage) {
        AlertDialog(
            onDismissRequest = { showStorage = false },
            title = { Text("Arquivos do celular") },
            text = {
                Text(
                    if (storageGranted) {
                        "Acesso concedido. No Debian, a pasta ~/Android mostra o armazenamento do celular " +
                            "(Download, DCIM, Documentos e o resto). Se o Debian já está rodando, " +
                            "encerre e inicie de novo para aplicar."
                    } else {
                        "Para o Debian ver os arquivos do celular em ~/Android, permita o acesso ao " +
                            "armazenamento. Sem isso, ~/Android é uma pasta própria do app, que o Debian " +
                            "pode usar à vontade, mas que não mostra seus arquivos do celular.\n\n" +
                            "O acesso é de leitura e escrita: o Debian pode alterar e apagar arquivos ali."
                    }
                )
            },
            confirmButton = {
                if (storageGranted) {
                    TextButton(onClick = { showStorage = false }) { Text("Ok", color = Mint) }
                } else {
                    TextButton(onClick = {
                        storageLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }) { Text("Permitir acesso", color = Mint) }
                }
            },
            dismissButton = {
                if (!storageGranted) {
                    TextButton(onClick = { showStorage = false }) { Text("Usar só a pasta do app", color = Muted) }
                }
            }
        )
    }

    if (askBattery) {
        AlertDialog(
            onDismissRequest = {
                askBattery = false
                BatteryOptimization.markAsked(context)
                startAndOpen()
            },
            title = { Text("Manter o Debian em segundo plano") },
            text = {
                Text(
                    "Para o Debian continuar rodando com o app fechado, o Android precisa deixar o " +
                        "PocketLinux fora da otimização de bateria. Toque em Permitir e confirme na próxima tela.\n\n" +
                        "Em celulares Samsung, adicione também o PocketLinux em apps que nunca entram em " +
                        "suspensão (Configurações > Bateria > Limites de uso em segundo plano)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askBattery = false
                    BatteryOptimization.markAsked(context)
                    BatteryOptimization.request(context)
                    startAndOpen()
                }) { Text("Permitir", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askBattery = false
                    BatteryOptimization.markAsked(context)
                    startAndOpen()
                }) { Text("Agora não", color = Muted) }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Apagar o Debian?") },
            text = { Text("Todos os arquivos e programas dentro do Debian serão removidos. Isso não pode ser desfeito.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    PocketSession.uninstall()
                }) { Text("Apagar", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
internal fun StatusPill(label: String) {
    Surface(color = Color(0xFF263B3A), shape = RoundedCornerShape(50)) {
        Text(label, color = Mint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun QuickTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Mint)
            Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}
