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
import android.provider.OpenableColumns
import dev.tapframe.ai.Catalog
import dev.tapframe.ai.ModelStore
import dev.tapframe.ai.Stylizer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens of the photo flow. */
enum class Screen { HOME, CROP, EXAM, RESULT, SETTINGS, CALIBRATE, ADVANCED, MODELS }

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
    /** The crop at the bundled AI model's input size, and at the downloaded styles' source size. */
    private var aiInput: IntArray? = null
    private var aiSource: IntArray? = null
    /** AI cartoons of the current photo by style id (computed on first use). */
    private val aiCartoons = ConcurrentHashMap<String, IntArray>()
    private val cartoonModel by lazy { CartoonModel(getApplication()) }

    // ---- Downloadable AI styles ----------------------------------------------------------------

    val models = ModelStore(app.filesDir)
    /** Downloaded styles whose files are all imported. */
    var aiStyles by mutableStateOf(models.readyStyles())
        private set
    /** Progress or result of the last import, for the AI styles screen. */
    var importStatus by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    private val stylizers = HashMap<String, Stylizer>()

    fun openModels() { screen = Screen.MODELS }

    /** Imports picked files (blocking work on IO); unknown files are reported, not kept. */
    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty() || importing) return
        importing = true
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val results = withContext(Dispatchers.IO) {
                uris.mapIndexed { i, uri ->
                    var name = uri.lastPathSegment ?: "file"; var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
                    }
                    models.import(name, size, { resolver.openInputStream(uri) ?: error("cannot open") }) { f ->
                        importStatus = "Importing $name (${i + 1} of ${uris.size}) · ${(f * 100).toInt()}%"
                    }
                }
            }
            importing = false
            importStatus = results.joinToString("\n") {
                when (it) {
                    is ModelStore.Imported.Added -> "Added ${it.file.name}"
                    is ModelStore.Imported.AlreadyThere -> "Already imported: ${it.file.name}"
                    is ModelStore.Imported.Unknown -> "Not a known model file (not kept): ${it.name}"
                    is ModelStore.Imported.Failed -> "Couldn't import ${it.name}: ${it.reason}"
                }
            }
            refreshStyles()
        }
    }

    fun deletePack(pack: Catalog.Pack) {
        synchronized(stylizers) {
            pack.styles.forEach { s -> stylizers.remove(s.id)?.close(); aiCartoons.remove(s.id) }
        }
        models.delete(pack)
        refreshStyles()
    }

    private fun refreshStyles() {
        aiStyles = models.readyStyles()
        // The style list depends on the installed styles.
        if (fitted != null) restartExam()
    }

    fun aiStyleLabel(id: String): String = if (id == Settings.WHITEBOX) "Cartoon (AI)" else Catalog.style(id)?.label ?: id
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
            Screen.CALIBRATE, Screen.MODELS -> Screen.SETTINGS
            Screen.ADVANCED -> advancedReturn
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

    /**
     * The user framed the photo: prepare it, then start the exam with the ranked style list, or
     * with [advanced] open the manual editor on the most likely style's saved settings.
     */
    fun confirmCrop(crop: PhotoPipeline.Crop, advanced: Boolean = false) {
        val bmp = source ?: return
        busyMessage = "Preparing…"
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                fitted = PhotoPipeline.fitPortrait(argb, bmp.width, bmp.height, crop = crop)
                fittedNearest = PhotoPipeline.fitPortrait(argb, bmp.width, bmp.height, nearest = true, crop = crop)
                aiInput = PhotoPipeline.fit(argb, bmp.width, bmp.height, crop, CartoonModel.SIZE, CartoonModel.SIZE)
                aiSource = PhotoPipeline.fit(argb, bmp.width, bmp.height, crop, Stylizer.SOURCE_W, Stylizer.SOURCE_H)
                aiCartoons.clear()
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
            if (advanced) openAdvanced(profiles.manual(), from = Screen.CROP) else screen = Screen.EXAM
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
        val e = Exam(ranking, profiles::get, aiStyles = aiStyles.map { it.id to it.label })
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

    private fun codesFor(settings: Settings): ByteArray = codes.getOrPut(settings) { renderCodes(settings) }

    /** Renders [settings] for the current photo (uncached). */
    private fun renderCodes(settings: Settings): ByteArray =
        when {
            settings.effect == Effect.AI_CARTOON -> PhotoPipeline.render(fitted!!, settings, aiCartoon = aiCartoonImage(settings.aiStyle))
            settings.nearest -> PhotoPipeline.render(fittedNearest!!, settings)
            else -> PhotoPipeline.render(fitted!!, settings, faces)
        }

    /** The AI cartoon of the current photo in [styleId] (bundled model if that style isn't installed). */
    private fun aiCartoonImage(styleId: String): IntArray {
        aiCartoons[styleId]?.let { return it }
        // One model at a time: they are memory-hungry, and SD-Turbo takes many seconds.
        synchronized(stylizers) {
            aiCartoons[styleId]?.let { return it }
            val style = Catalog.style(styleId)?.takeIf(models::ready)
            val image = if (style == null) cartoonModel.cartoonize(aiInput!!) else {
                val stylizer = stylizers.getOrPut(style.id) { Stylizer.create(style, models) }
                val big = stylizer.stylize(aiSource!!, faces)
                PhotoPipeline.fit(big, Stylizer.SOURCE_W, Stylizer.SOURCE_H, PhotoPipeline.Crop(0f, 0f, Stylizer.SOURCE_W.toFloat(), Stylizer.SOURCE_H.toFloat()), PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H)
            }
            return image.also { aiCartoons[styleId] = it }
        }
    }

    override fun onCleared() {
        runCatching { cartoonModel.close() }
        synchronized(stylizers) { stylizers.values.forEach { runCatching { it.close() } }; stylizers.clear() }
    }

    // ---- Advanced editing --------------------------------------------------------------------

    /** Settings being edited by hand. */
    var advanced by mutableStateOf<Settings?>(null)
        private set
    /** The latest finished preview of [advanced]; may lag a slider by one render. */
    var advancedPreview by mutableStateOf<Bitmap?>(null)
        private set
    var advancedRendering by mutableStateOf(false)
        private set
    private var advancedReturn = Screen.CROP
    private val advancedRequests = MutableStateFlow<Settings?>(null)

    /** The fitted photo before any processing, for press-and-hold comparison. */
    val originalPreview: Bitmap? get() = fitted?.let { f ->
        Bitmap.createBitmap(IntArray(f.size) { f[it] or (0xFF shl 24) }, PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888)
    }

    init {
        // One renderer: always works on the newest settings and skips the ones it couldn't get to,
        // so dragging a slider never queues up stale renders.
        viewModelScope.launch {
            advancedRequests.filterNotNull().conflate().collect { s ->
                advancedRendering = true
                val currentLook = look
                val started = System.currentTimeMillis()
                val bmp = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(
                        currentLook.apply(PhotoPipeline.simulatedPortrait(renderCodes(s))),
                        PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888,
                    )
                }
                android.util.Log.d("TapFrame", "advanced render ${System.currentTimeMillis() - started} ms")
                if (advancedRequests.value == s) advancedRendering = false
                advancedPreview = bmp
            }
        }
    }

    /** Opens the manual editor on [start]; back returns to [from]. */
    fun openAdvanced(start: Settings, from: Screen = screen) {
        if (fitted == null) return
        advancedReturn = from
        advancedPreview = null
        updateAdvanced(start)
        screen = Screen.ADVANCED
    }

    fun updateAdvanced(s: Settings) {
        advanced = s
        advancedRequests.value = s
    }

    /**
     * Switches from guided tuning to manual editing at any point, keeping what was chosen so far
     * (from the style list, before anything was chosen: manual editing's own starting point).
     */
    fun editManually() {
        val start = when (val s = step) {
            is Exam.Step.StyleList -> profiles.manual()
            is Exam.Step.Done -> s.result
            else -> exam?.current ?: profiles.manual()
        }
        openAdvanced(start)
    }

    /** Back to manual editing's built-in defaults (Vivid, Atkinson). */
    fun resetAdvanced() = updateAdvanced(Profiles.MANUAL_DEFAULT)

    /** Saves the edited settings as where manual editing starts. */
    fun saveAdvancedAsDefault() {
        advanced?.let(profiles::putManual)
    }

    fun sendAdvanced() {
        val settings = advanced ?: return
        val type = currentType
        viewModelScope.launch {
            val c = withContext(Dispatchers.Default) { renderCodes(settings) }
            val packed = Frame.packPixels(c)
            val label = "${type.label} · manual · ${settings.method.label}"
            sender.arm("advanced ${describe(settings)}", label, packed) { record(packed, c, label) }
        }
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
        buildString {
            append("${s.tone.label}, ${s.method.label}, brightness %+.1f, contrast %+.1f, detail ×%.1f, color ×%.2f".format(s.brightness, s.contrast, s.detail, s.saturation))
            if (s.highlights != 0f) append(", highlights %+.2f".format(s.highlights))
            if (s.shadows != 0f) append(", shadows %+.2f".format(s.shadows))
            if (s.hue != 0f) append(", hue %+.0f°".format(s.hue))
            listOf("red" to s.redDepth, "yellow" to s.yellowDepth, "green" to s.greenDepth, "blue" to s.blueDepth)
                .filter { it.second != 0f }.forEach { (name, v) -> append(", $name depth %.1f".format(v)) }
            if (s.effect == Effect.AI_CARTOON) append(", ${aiStyleLabel(s.aiStyle)}")
            if (s.keepHue) append(", true hues")
        }
}
