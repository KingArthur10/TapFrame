package dev.tapframe.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tapframe.exam.Exam
import dev.tapframe.protocol.Frame
import dev.tapframe.render.ContentType
import dev.tapframe.render.Effect
import dev.tapframe.render.Face
import dev.tapframe.render.PhotoPipeline
import dev.tapframe.render.PreviewLook
import dev.tapframe.render.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens of the photo flow. */
enum class Screen { HOME, CROP, EXAM, RESULT, SETTINGS, CALIBRATE }

/**
 * App state: the chosen photo (rotated source, crop, faces, style ranking), the tuning exam,
 * cached previews, history and sending.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)
    val profiles = Profiles(app)
    private val history = History(app)
    val sender = Sender { compressionEnabled }

    var screen by mutableStateOf(Screen.HOME)
        private set
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var historyItems by mutableStateOf(history.list())
        private set

    var compressionEnabled by mutableStateOf(prefs.getBoolean("compress", true))
        private set

    /** The picked photo, rotated by the user (quarter turns), at most ~1600 px. */
    var source by mutableStateOf<Bitmap?>(null)
        private set
    private var original: Bitmap? = null
    private var quarterTurns = 0

    private var fitted: IntArray? = null
    private var fittedNearest: IntArray? = null
    /** The crop at the AI model's input size, and the model's cartoon (computed on first use). */
    private var aiInput: IntArray? = null
    @Volatile private var aiCartoon: IntArray? = null
    private val cartoonModel by lazy { CartoonModel(getApplication()) }
    private var faces: List<Face> = emptyList()
    var faceCount by mutableStateOf(0)
        private set
    private var ranking: List<ContentType> = ContentType.entries

    var exam by mutableStateOf<Exam?>(null)
        private set
    var step by mutableStateOf<Exam.Step?>(null)
        private set

    /** Simulated frame previews, keyed by settings; filled in the background. */
    val previews = mutableStateMapOf<Settings, Bitmap>()
    private val codes = ConcurrentHashMap<Settings, ByteArray>()

    // ---- Navigation -------------------------------------------------------------------------

    fun openSettings() { screen = Screen.SETTINGS }

    // ---- Preview calibration -----------------------------------------------------------------

    /** The saved on-screen look of previews (see [PreviewLook]). */
    var look by mutableStateOf(loadLook())
        private set

    private fun loadLook() = PreviewLook(
        prefs.getFloat("look.brightness", 1f), prefs.getFloat("look.contrast", 1f), prefs.getFloat("look.saturation", 1f),
        prefs.getFloat("look.warmth", 0f), prefs.getFloat("look.tint", 0f),
    )

    private val calibrationCodes by lazy { PreviewLook.pattern() }
    private val calibrationSim by lazy { PhotoPipeline.simulatedPortrait(calibrationCodes) }

    fun openCalibration() { screen = Screen.CALIBRATE }

    /** The calibration image's preview under [candidate] (computed off the main thread). */
    suspend fun calibrationPreview(candidate: PreviewLook): Bitmap = withContext(Dispatchers.Default) {
        Bitmap.createBitmap(candidate.apply(calibrationSim), PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888)
    }

    fun sendCalibration() {
        sender.arm("calibration", "Preview calibration image", Frame.packPixels(calibrationCodes))
    }

    /** Saves [newLook]; previews are re-rendered with it. */
    fun saveLook(newLook: PreviewLook) {
        look = newLook
        prefs.edit()
            .putFloat("look.brightness", newLook.brightness).putFloat("look.contrast", newLook.contrast)
            .putFloat("look.saturation", newLook.saturation).putFloat("look.warmth", newLook.warmth)
            .putFloat("look.tint", newLook.tint).apply()
        previews.clear()
    }

    /** A stored (uncalibrated) preview bitmap shown with the current look. */
    fun withLook(bmp: Bitmap): Bitmap {
        if (look.isIdentity) return bmp
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return Bitmap.createBitmap(look.apply(px), bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    }

    /** System back: returns true if handled. */
    fun back(): Boolean {
        screen = when (screen) {
            Screen.HOME -> return false
            Screen.CROP, Screen.SETTINGS -> Screen.HOME
            Screen.CALIBRATE -> Screen.SETTINGS
            Screen.EXAM -> Screen.CROP
            Screen.RESULT -> Screen.EXAM.also { restartExam() }
        }
        return true
    }

    // ---- Photo -------------------------------------------------------------------------------

    fun onPhotoPicked(uri: Uri) {
        busyMessage = "Opening photo…"
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 1600))
                    }
                }.getOrNull()
            }
            busyMessage = null
            if (bmp == null) return@launch
            original = bmp
            quarterTurns = 0
            source = bmp
            screen = Screen.CROP
        }
    }

    /** Rotates the photo a quarter turn clockwise (for landscape photos on a sideways card). */
    fun rotate() {
        val bmp = original ?: return
        quarterTurns = (quarterTurns + 1) % 4
        source = if (quarterTurns == 0) bmp else
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(90f * quarterTurns) }, true)
    }

    /** The user framed the photo: prepare it and start the exam with the ranked style list. */
    fun confirmCrop(crop: PhotoPipeline.Crop) {
        val bmp = source ?: return
        busyMessage = "Preparing…"
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                fitted = PhotoPipeline.fitPortrait(argb, bmp.width, bmp.height, crop = crop)
                fittedNearest = PhotoPipeline.fitPortrait(argb, bmp.width, bmp.height, nearest = true, crop = crop)
                aiInput = PhotoPipeline.fit(argb, bmp.width, bmp.height, crop, CartoonModel.SIZE, CartoonModel.SIZE)
                aiCartoon = null
                val found = detectFaces(bmp)
                faces = found.mapNotNull { (x, y, d) ->
                    val (px, py) = crop.toPortrait(x, y)
                    if (px in 0f..PhotoPipeline.PORTRAIT_W.toFloat() && py in 0f..PhotoPipeline.PORTRAIT_H.toFloat())
                        Face(px, py, crop.toPortraitLength(d)) else null
                }
                ranking = ContentType.rank(argb, bmp.width, bmp.height, faces.size)
            }
            faceCount = faces.size
            busyMessage = null
            restartExam()
            screen = Screen.EXAM
        }
    }

    /** Android's built-in face detector (offline). It needs RGB_565 and an even width. */
    private fun detectFaces(bmp: Bitmap): List<Triple<Float, Float, Float>> {
        val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
        val w = ((bmp.width * scale).toInt() / 2) * 2
        val h = (bmp.height * scale).toInt()
        val small = Bitmap.createScaledBitmap(bmp, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val found = arrayOfNulls<FaceDetector.Face>(8)
        val count = FaceDetector(w, h, found.size).findFaces(small, found)
        val mid = PointF()
        return found.take(count).filterNotNull().map { f ->
            f.getMidPoint(mid)
            Triple(mid.x / scale, mid.y / scale, f.eyesDistance() / scale)
        }
    }

    // ---- Exam --------------------------------------------------------------------------------

    fun restartExam() {
        codes.clear(); previews.clear()
        val e = Exam(ranking, profiles::get)
        exam = e
        step = e.step()
    }

    fun choose(index: Int) {
        val e = exam ?: return
        e.choose(index)
        val next = e.step()
        step = next
        if (next is Exam.Step.Done) {
            profiles.put(e.type, next.result)
            screen = Screen.RESULT
        }
    }

    /** Skips the remaining rounds and goes to the result with the current settings. */
    fun finishEarly() {
        val e = exam ?: return
        profiles.put(e.type, e.current)
        step = Exam.Step.Done(e.current)
        screen = Screen.RESULT
    }

    val currentType: ContentType get() = exam?.type ?: ranking.first()

    /** Requests the simulated preview for [settings]; it appears in [previews] when ready. */
    fun requestPreview(settings: Settings) {
        if (previews.containsKey(settings)) return
        val currentLook = look
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(
                    currentLook.apply(PhotoPipeline.simulatedPortrait(codesFor(settings))),
                    PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888,
                )
            }
            previews[settings] = bmp
        }
    }

    private fun codesFor(settings: Settings): ByteArray = codes.getOrPut(settings) {
        when {
            settings.effect == Effect.AI_CARTOON -> PhotoPipeline.render(fitted!!, settings, aiCartoon = aiCartoonImage())
            settings.nearest -> PhotoPipeline.render(fittedNearest!!, settings)
            else -> PhotoPipeline.render(fitted!!, settings, faces)
        }
    }

    @Synchronized
    private fun aiCartoonImage(): IntArray = aiCartoon ?: cartoonModel.cartoonize(aiInput!!).also { aiCartoon = it }

    override fun onCleared() {
        runCatching { cartoonModel.close() }
    }

    // ---- Sending -----------------------------------------------------------------------------

    /** Sends the exam's current settings; the image is added to history after a successful send. */
    fun sendCurrent() {
        val settings = exam?.current ?: return
        val type = currentType
        viewModelScope.launch {
            val c = withContext(Dispatchers.Default) { codesFor(settings) }
            val packed = Frame.packPixels(c)
            val label = "${type.label} · ${settings.tone.label} · ${settings.method.label}"
            sender.arm("photo ${type.name} ${describe(settings)}", label, packed) { record(packed, c, label) }
        }
    }

    /** Skips tuning: renders with the most likely style's saved settings and sends. */
    fun quickSend() {
        if (fitted == null) return
        val settings = profiles.get(ranking.first())
        viewModelScope.launch {
            val c = withContext(Dispatchers.Default) { codesFor(settings) }
            val packed = Frame.packPixels(c)
            val label = "${ranking.first().label} · ${settings.tone.label} · ${settings.method.label}"
            sender.arm("quick ${describe(settings)}", label, packed) { record(packed, c, label) }
        }
    }

    /** After a successful send (binder thread): store in history, refresh the list on the main thread. */
    private fun record(packed: ByteArray, c: ByteArray, label: String) {
        history.add(packed, c, label)
        viewModelScope.launch { historyItems = history.list() }
    }

    fun resend(item: HistoryItem) {
        sender.arm("resend ${item.id}", item.description, item.packed)
    }

    fun delete(item: HistoryItem) {
        history.delete(item)
        historyItems = history.list()
    }

    fun setCompression(enabled: Boolean) {
        compressionEnabled = enabled
        prefs.edit().putBoolean("compress", enabled).apply()
    }

    fun resetProfiles() = profiles.reset()

    fun describe(s: Settings) =
        "${s.tone.label}, ${s.method.label}, brightness %+.1f, contrast %+.1f, detail ×%.1f, color ×%.2f%s"
            .format(s.brightness, s.contrast, s.detail, s.saturation, if (s.keepHue) ", true hues" else "")
}
