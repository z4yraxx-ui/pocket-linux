package com.pocketlinux.app

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Working(val phase: String, val done: Long = 0, val total: Long = 0) : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

class InstallException(message: String) : Exception(message)

/** De onde o rootfs é baixado: a Release "rootfs-latest" do próprio repositório. */
object RootfsSource {
    private const val TAG = "rootfs-latest"
    private const val FILE = "debian-arm64-lxqt.tar.gz"

    val isConfigured: Boolean get() = !BuildConfig.ROOTFS_REPO.startsWith("SEU_USUARIO")
    val url: String get() = "https://github.com/${BuildConfig.ROOTFS_REPO}/releases/download/$TAG/$FILE"
    val shaUrl: String get() = "$url.sha256"
}

/** Baixa, confere (SHA-256) e extrai o rootfs do Debian no armazenamento privado do app. */
class RootfsInstaller(private val paths: PocketPaths) {

    suspend fun install(report: (InstallState.Working) -> Unit) = withContext(Dispatchers.IO) {
        if (!RootfsSource.isConfigured) {
            throw InstallException(
                "Este APK não sabe de qual repositório baixar o Debian. " +
                    "Compile pelo workflow do GitHub (ele informa o repositório) " +
                    "ou use -ProotfsRepo=usuario/repositorio."
            )
        }
        if (!paths.prootBin.exists() || !paths.prootLoader.exists()) {
            throw InstallException("O PRoot não veio dentro do APK (libproot.so ausente). Recompile o app.")
        }

        paths.baseDir.mkdirs()
        report(InstallState.Working("Preparando"))
        val expectedSha = fetchSha()
        download(report)
        verify(expectedSha, report)
        extract(report)
        report(InstallState.Working("Finalizando"))
        deleteTree(paths.rootfsDir)
        if (!paths.rootfsTmpDir.renameTo(paths.rootfsDir)) {
            throw InstallException("Não consegui mover o Debian para a pasta final.")
        }
        paths.downloadFile.delete()
        paths.installedMarker.writeText("ok\n")
    }

    // ---------- rede ----------

    private fun open(url: String, rangeStart: Long = 0): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "PocketLinux/${BuildConfig.VERSION_NAME}")
        if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
        return conn
    }

    private fun fetchSha(): String {
        val conn = open(RootfsSource.shaUrl)
        try {
            if (conn.responseCode != 200) {
                throw InstallException(
                    "Não achei o Debian na Release do GitHub (HTTP ${conn.responseCode}). " +
                        "Rode o workflow \"Build Debian rootfs\" e confira se o repositório é público."
                )
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val hash = text.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
            if (!Regex("[0-9a-f]{64}").matches(hash)) {
                throw InstallException("O arquivo .sha256 da Release está num formato inesperado.")
            }
            return hash
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(report: (InstallState.Working) -> Unit) {
        val part = paths.downloadFile
        var start = if (part.exists()) part.length() else 0L
        var conn = open(RootfsSource.url, start)
        try {
            var code = conn.responseCode
            if (code == 416) { // o arquivo parcial não serve mais: recomeça do zero
                conn.disconnect()
                part.delete()
                start = 0
                conn = open(RootfsSource.url, 0)
                code = conn.responseCode
            }
            if (code != 200 && code != 206) {
                throw InstallException("Falha ao baixar o Debian (HTTP $code).")
            }
            if (code == 200 && start > 0) { // o servidor ignorou o "continuar de onde parou"
                part.delete()
                start = 0
            }
            val total = if (code == 206) {
                parseRangeTotal(conn.getHeaderField("Content-Range")) ?: (start + conn.contentLengthLong)
            } else {
                conn.contentLengthLong
            }

            val free = paths.baseDir.usableSpace
            if (total > 0 && free < total * 4) {
                throw InstallException(
                    "Espaço insuficiente: preciso de uns ${formatBytes(total * 4)} livres " +
                        "e só há ${formatBytes(free)}."
                )
            }

            conn.inputStream.use { input ->
                FileOutputStream(part, start > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 250) {
                            lastReport = now
                            report(InstallState.Working("Baixando", done, total))
                        }
                    }
                    report(InstallState.Working("Baixando", done, total))
                    if (total > 0 && done < total) {
                        throw InstallException(
                            "O download parou no meio. Toque em Instalar de novo para continuar de onde parou."
                        )
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    private suspend fun verify(expected: String, report: (InstallState.Working) -> Unit) {
        val file = paths.downloadFile
        val total = file.length()
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 250) {
                    lastReport = now
                    report(InstallState.Working("Verificando", done, total))
                }
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw InstallException("O arquivo baixado veio corrompido (SHA-256 diferente). Tente instalar de novo.")
        }
    }

    // ---------- extração ----------

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    private suspend fun extract(report: (InstallState.Working) -> Unit) {
        val dest = paths.rootfsTmpDir
        deleteTree(dest)
        dest.mkdirs()
        val destPath = dest.toPath()
        val archive = paths.downloadFile
        val total = archive.length()
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024))
        var lastReport = 0L

        TarArchiveInputStream(GZIPInputStream(counting, 256 * 1024)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                extractEntry(tar, entry, destPath)
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 250) {
                    lastReport = now
                    report(InstallState.Working("Extraindo", counting.count, total))
                }
            }
        }
        report(InstallState.Working("Extraindo", total, total))
        configure(dest)
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, root: Path) {
        val rel = entry.name.removePrefix("./").trimStart('/')
        if (rel.isEmpty() || rel == ".") return
        val target = root.resolve(rel).normalize()
        if (!target.startsWith(root)) return // nunca escreve fora da pasta do Debian
        val mode = entry.mode and 0xFFF

        try {
            when {
                entry.isDirectory -> {
                    Files.createDirectories(target)
                    // Garante que o app (dono dos arquivos) sempre consegue ler e escrever.
                    chmod(target, mode or 0x1C0)
                }

                entry.isSymbolicLink -> {
                    target.parent?.let { Files.createDirectories(it) }
                    Files.deleteIfExists(target)
                    Os.symlink(entry.linkName, target.toString())
                }

                entry.isLink -> { // link físico (hard link)
                    val src = root.resolve(entry.linkName.removePrefix("./").trimStart('/')).normalize()
                    if (!src.startsWith(root)) return
                    target.parent?.let { Files.createDirectories(it) }
                    Files.deleteIfExists(target)
                    try {
                        Os.link(src.toString(), target.toString())
                    } catch (e: ErrnoException) {
                        Files.copy(src, target) // o Android pode proibir hard links: copia
                    }
                }

                entry.isFile -> {
                    target.parent?.let { Files.createDirectories(it) }
                    Files.deleteIfExists(target)
                    FileOutputStream(target.toFile()).use { out -> tar.copyTo(out) }
                    chmod(target, mode or 0x180) // 0600 + bits originais (executável etc.)
                }

                else -> Unit // dispositivos, pipes: o /dev vem do próprio Android
            }
        } catch (e: Exception) {
            throw InstallException("Erro ao extrair \"$rel\": ${e.message}")
        }
    }

    /** Ajustes que o Debian precisa para funcionar dentro do app. */
    private fun configure(root: File) {
        fun write(rel: String, text: String) {
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            Files.deleteIfExists(f.toPath())
            f.writeText(text)
        }
        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(root, "tmp").mkdirs()
        File(root, "root").mkdirs()
        if (!Files.exists(File(root, "bin/bash").toPath())) {
            throw InstallException("O Debian baixado parece incompleto (não tem /bin/bash).")
        }
    }
}
