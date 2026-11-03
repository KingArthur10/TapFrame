package dev.tapframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.tapframe.render.ContentType
import dev.tapframe.render.Dither
import dev.tapframe.render.PhotoPipeline
import dev.tapframe.render.Settings
import dev.tapframe.render.ToneMap
import java.io.File
import org.json.JSONObject

/** The user's preferred settings per photo style, saved when an exam finishes. */
class Profiles(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun get(type: ContentType): Settings {
        val saved = prefs.getString(type.name, null) ?: return type.defaults
        return runCatching {
            val f = saved.split(",")
            Settings(Dither.Method.valueOf(f[1]), ToneMap.valueOf(f[0]), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f[6].toBoolean())
        }.getOrDefault(type.defaults)
    }

    fun put(type: ContentType, s: Settings) {
        prefs.edit().putString(type.name, "${s.tone.name},${s.method.name},${s.brightness},${s.contrast},${s.saturation},${s.detail},${s.nearest}").apply()
    }

    fun reset() = prefs.edit().clear().apply()
}

/** A previously sent image: the exact pixel codes plus a preview, so it can be resent instantly. */
class HistoryItem(val id: String, val time: Long, val description: String, val dir: File) {
    val preview: Bitmap? get() = BitmapFactory.decodeFile(File(dir, "preview.png").path)
    val packed: ByteArray get() = File(dir, "image.bin").readBytes()
}

/** Sent images in app-private storage (files/history), newest first, capped at [MAX]. */
class History(context: Context) {
    private val root = File(context.filesDir, "history").apply { mkdirs() }

    fun list(): List<HistoryItem> = root.listFiles().orEmpty().mapNotNull { dir ->
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            HistoryItem(dir.name, meta.getLong("time"), meta.getString("description"), dir)
        }.getOrNull()
    }.sortedByDescending { it.time }

    /** Stores [packed] (Frame.IMAGE_BYTES) with its simulated preview [codes]. Returns the item. */
    fun add(packed: ByteArray, codes: ByteArray, description: String): HistoryItem {
        val time = System.currentTimeMillis()
        val dir = File(root, time.toString()).apply { mkdirs() }
        File(dir, "image.bin").writeBytes(packed)
        val preview = Bitmap.createBitmap(
            PhotoPipeline.simulatedPortrait(codes), PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888,
        )
        File(dir, "preview.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "meta.json").writeText(JSONObject().put("time", time).put("description", description).toString())
        list().drop(MAX).forEach { delete(it) }
        return HistoryItem(dir.name, time, description, dir)
    }

    fun delete(item: HistoryItem) {
        item.dir.deleteRecursively()
    }

    private companion object {
        const val MAX = 50
    }
}
