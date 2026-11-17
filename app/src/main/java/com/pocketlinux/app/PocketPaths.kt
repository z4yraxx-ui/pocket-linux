package com.pocketlinux.app

import android.content.Context
import android.os.Environment
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Onde cada coisa fica no armazenamento privado do app. */
class PocketPaths(context: Context) {
    private val app = context.applicationContext

    val baseDir = File(app.filesDir, "debian")
    val rootfsDir = File(baseDir, "rootfs")
    val rootfsTmpDir = File(baseDir, "rootfs.tmp")
    val downloadFile = File(baseDir, "rootfs.tar.gz.part")
    val prootTmpDir = File(baseDir, "tmp")
    val installedMarker = File(baseDir, "installed")

    /** Os binários do PRoot vão no APK como bibliotecas nativas (a única pasta executável). */
    private val nativeDir = File(app.applicationInfo.nativeLibraryDir)
    val prootBin = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot-loader.so")

    /** Onde a pasta do celular aparece dentro do Debian (~/Android). */
    val sharedMount = File(rootfsDir, "root/Android")

    /** Verdadeiro se o app pode mesmo ler o armazenamento do celular (permissão concedida). */
    fun hasPhoneStorageAccess(): Boolean = try {
        Environment.getExternalStorageDirectory().list() != null
    } catch (e: Exception) {
        false
    }

    /**
     * Pasta do lado do Android que vira ~/Android: o armazenamento do celular se houver
     * permissão; senão, uma pasta própria do app que o Debian pode usar à vontade.
     */
    fun sharedHostDir(): File {
        if (hasPhoneStorageAccess()) return Environment.getExternalStorageDirectory()
        val own = app.getExternalFilesDir(null) ?: File(baseDir, "compartilhado")
        own.mkdirs()
        return own
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    do {
        value /= 1024
        i++
    } while (value >= 1024 && i < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[i])
}

fun chmod(path: Path, mode: Int) {
    try {
        Os.chmod(path.toString(), mode)
    } catch (e: Exception) {
        // Alguns sistemas de arquivos recusam; o PRoot finge as permissões, então segue.
    }
}

/**
 * Apaga uma árvore inteira sem seguir links simbólicos (o Debian tem vários links
 * absolutos; seguir um deles poderia apagar arquivos de fora da pasta).
 */
fun deleteTree(dir: File) {
    val root = dir.toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
            chmod(d, 0x1C0) // 0700: garante que dá para listar e apagar o conteúdo
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(d)
            return FileVisitResult.CONTINUE
        }
    })
}
