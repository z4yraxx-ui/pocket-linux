package com.pocketlinux.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class DiagItem(val label: String, val value: String)

/** Juntando os dados rápidos de diagnóstico (o tamanho do Debian demora mais e vem separado). */
internal fun collectDiagnostics(context: Context): List<DiagItem> {
    val app = context.applicationContext
    val paths = PocketSession.paths
    val items = ArrayList<DiagItem>()

    items += DiagItem("App", "PocketLinux ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    items += DiagItem("Aparelho", "${Build.MANUFACTURER} ${Build.MODEL}")
    items += DiagItem("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

    val debianVersion = try {
        File(paths.rootfsDir, "etc/debian_version").readText().trim().ifEmpty { "desconhecida" }
    } catch (e: Exception) {
        "não instalado"
    }
    items += DiagItem("Debian", debianVersion)

    val running = PocketSession.isRunning.value
    items += DiagItem("Shell do Debian", if (running) "em execução" else "parado")

    val (count, rssBytes) = debianProcessStats()
    items += DiagItem("Processos do Debian", count.toString())
    items += DiagItem("Memória dos processos do Debian", formatBytes(rssBytes))
    if (count > 28) {
        items += DiagItem(
            "Aviso",
            "Perto do limite de ~32 processos filhos que o Android 12+ aceita antes de encerrar o Debian."
        )
    }

    val am = app.getSystemService(ActivityManager::class.java)
    if (am != null) {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        items += DiagItem("Memória livre do aparelho", "${formatBytes(info.availMem)} de ${formatBytes(info.totalMem)}")
    }

    try {
        items += DiagItem("Espaço livre (armazenamento do app)", formatBytes(StatFs(app.filesDir.path).availableBytes))
    } catch (e: Exception) {
        items += DiagItem("Espaço livre (armazenamento do app)", "indisponível")
    }

    val phoneAccess = paths.hasPhoneStorageAccess()
    items += DiagItem(
        "Pasta ~/Android",
        if (phoneAccess) "armazenamento do celular (${paths.sharedHostDir().path})"
        else "pasta própria do app (${paths.sharedHostDir().path})"
    )
    items += DiagItem(
        "Isenção de bateria",
        if (BatteryOptimization.isIgnoring(app)) "concedida" else "não concedida"
    )
    items += DiagItem(
        "Notificações",
        if (NotificationManagerCompat.from(app).areNotificationsEnabled()) "ligadas" else "desligadas"
    )
    return items
}

/** Conta os processos do app (menos o próprio app) e soma a memória residente deles. */
private fun debianProcessStats(): Pair<Int, Long> {
    val myPid = Process.myPid()
    val myUid = Process.myUid()
    var count = 0
    var rssKb = 0L
    val dirs = File("/proc").listFiles() ?: return 0 to 0L
    for (dir in dirs) {
        val pid = dir.name.toIntOrNull() ?: continue
        if (pid == myPid) continue
        try {
            var uid = -1
            var rss = 0L
            File(dir, "status").forEachLine { line ->
                if (line.startsWith("Uid:")) {
                    uid = line.substringAfter(":").trim().split(Regex("\\s+")).first().toInt()
                } else if (line.startsWith("VmRSS:")) {
                    rss = line.substringAfter(":").trim().split(Regex("\\s+")).first().toLong()
                }
            }
            if (uid == myUid) {
                count++
                rssKb += rss
            }
        } catch (e: Exception) {
            // o processo acabou de sair ou não dá para ler: ignora
        }
    }
    return count to rssKb * 1024
}

/** Soma o tamanho de tudo que o Debian ocupa (sem seguir links). Pode demorar alguns segundos. */
internal fun rootfsSizeText(): String {
    val root = PocketSession.paths.baseDir.toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return "—"
    var total = 0L
    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
    } catch (e: Exception) {
        return "indisponível"
    }
    return formatBytes(total)
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var items by remember { mutableStateOf<List<DiagItem>?>(null) }
    var sizeText by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        items = null
        sizeText = null
        items = withContext(Dispatchers.IO) { collectDiagnostics(context) }
        sizeText = withContext(Dispatchers.IO) { rootfsSizeText() }
    }

    fun report(): String {
        val sb = StringBuilder("Relatório PocketLinux\n\n")
        for (item in items.orEmpty()) sb.append(item.label).append(": ").append(item.value).append('\n')
        sb.append("Tamanho do Debian: ").append(sizeText ?: "calculando").append('\n')
        val last = PocketSession.lastTerminalLines(40)
        if (last.isNotEmpty()) {
            sb.append("\nÚltimas linhas do terminal:\n")
            for (line in last) sb.append(line).append('\n')
        }
        return sb.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Mint)
            }
            Text("Diagnóstico", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload++ }) { Text("Atualizar", color = Mint) }
        }

        val list = items
        if (list == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (item in list) DiagRow(item.label, item.value)
                    DiagRow("Tamanho do Debian", sizeText ?: "calculando…")
                }
            }
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(report()))
                    Toast.makeText(context, "Relatório copiado", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Copiar relatório") }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 14.sp)
    }
}
