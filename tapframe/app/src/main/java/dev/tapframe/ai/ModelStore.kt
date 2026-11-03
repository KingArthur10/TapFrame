package dev.tapframe.ai

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Imported AI model files in app-private storage (files/models). Imports are copied while hashing;
 * a file is kept only if its SHA-256 matches the [Catalog]. DCT-Net's TensorFlow files are
 * converted to ONNX on import (see [DctNetConverter]) and only the converted model is kept.
 */
class ModelStore(root: File) {
    val dir: File = File(root, "models").apply { mkdirs() }

    sealed class Imported {
        data class Added(val file: Catalog.File) : Imported()
        data class AlreadyThere(val file: Catalog.File) : Imported()
        data class Unknown(val name: String) : Imported()
        data class Failed(val name: String, val reason: String) : Imported()
    }

    /** Where [file] lives once imported (DCT-Net files are stored converted). */
    fun path(file: Catalog.File): File = File(dir, if (file.name.endsWith(".pb")) file.name.removeSuffix(".pb") + ".onnx" else file.name)

    fun path(name: String): File = Catalog.packs.flatMap { it.files }.firstOrNull { it.name == name }?.let(::path) ?: File(dir, name)

    fun has(file: Catalog.File) = path(file).exists()

    fun installedCount(pack: Catalog.Pack) = pack.files.count(::has)

    /** Every file the style needs is imported. */
    fun ready(style: Catalog.Style): Boolean = when (style.pack.kind) {
        Catalog.Kind.SD_TURBO -> style.pack.files.all(::has)
        else -> listOfNotNull(style.file, style.faces).all { path(it).exists() }
    }

    fun readyStyles(): List<Catalog.Style> = Catalog.styles.filter(::ready)

    /**
     * Imports one picked file. [progress] gets the fraction copied. Blocking; call off the main
     * thread.
     */
    fun import(name: String, size: Long, open: () -> InputStream, progress: (Float) -> Unit = {}): Imported {
        val tmp = File(dir, ".import-${System.nanoTime()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            open().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n); out.write(buf, 0, n)
                        copied += n
                        if (size > 0) progress(copied.toFloat() / size)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val (_, file) = Catalog.fileByHash(hash) ?: return Imported.Unknown(name)
            val target = path(file)
            if (target.exists()) return Imported.AlreadyThere(file)
            if (file.name.endsWith(".pb")) {
                val onnx = DctNetConverter.convert(tmp.readBytes())
                val part = File(dir, target.name + ".part")
                part.writeBytes(onnx)
                if (!part.renameTo(target)) error("could not save")
            } else if (!tmp.renameTo(target)) error("could not save")
            return Imported.Added(file)
        } catch (e: Exception) {
            return Imported.Failed(name, e.message ?: e.toString())
        } finally {
            tmp.delete()
        }
    }

    fun delete(pack: Catalog.Pack) = pack.files.forEach { path(it).delete() }

    /** Bytes used by [pack]'s imported files. */
    fun used(pack: Catalog.Pack): Long = pack.files.sumOf { path(it).takeIf(File::exists)?.length() ?: 0L }
}
