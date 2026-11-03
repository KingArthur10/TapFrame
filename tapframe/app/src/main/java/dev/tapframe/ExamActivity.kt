package dev.tapframe

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.media.FaceDetector
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.tapframe.exam.Exam
import dev.tapframe.protocol.Apdu
import dev.tapframe.protocol.Frame
import dev.tapframe.render.ContentType
import dev.tapframe.render.Dither
import dev.tapframe.render.Face
import dev.tapframe.render.PhotoPipeline
import dev.tapframe.render.Preview
import dev.tapframe.render.Settings
import dev.tapframe.render.ToneMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Photo "eye exam": a scrolling list of every photo style (content type), most likely first, then
 * quads of four options: tone mapping, dithering algorithm, brightness, contrast, detail and color,
 * with occasional challengers. Previews simulate how the frame will look (see FrameSim). The
 * current best can be sent to the frame at any time; the result is saved per content type.
 */
class ExamActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val rendered = ConcurrentHashMap<Settings, ByteArray>()

    /** The fitted portrait, averaged (photos) and nearest-neighbour (pixel art). */
    private var fitted: IntArray? = null
    private var fittedNearest: IntArray? = null
    private var source: Bitmap? = null
    /** Faces in source-image coordinates (x, y between the eyes; eye distance). */
    private var sourceFaces: List<Triple<Float, Float, Float>> = emptyList()
    /** The same faces in fitted-portrait coordinates, for the tone map. */
    private var faces: List<Face> = emptyList()
    /** Every content type for the current photo, most likely first. */
    private var ranking: List<ContentType> = ContentType.entries
    private var exam: Exam? = null
    private var photoStamp = ""

    private lateinit var link: FrameLink
    private lateinit var title: TextView
    private lateinit var typeLabel: TextView
    private lateinit var content: FrameLayout
    private lateinit var choiceRow: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        title = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; text = "Pick a photo to start" }
        typeLabel = TextView(this).apply { textSize = 15f }
        content = FrameLayout(this)
        choiceRow = LinearLayout(this)
        val send = Button(this).apply { text = "Send current to frame"; setOnClickListener { sendCurrent() } }
        val newPhoto = Button(this).apply { text = "New photo"; setOnClickListener { pickPhoto() } }
        val actions = LinearLayout(this).apply {
            addView(send, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
            addView(newPhoto, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        status = TextView(this).apply { textSize = 13f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(typeLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(choiceRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status)
        }
        padForSystemBars(root, pad)
        setContentView(root)
        link = FrameLink(this, onStatus = { status.text = it })
        if (savedInstanceState == null) pickPhoto()
    }

    override fun onResume() {
        super.onResume()
        link.resume()
    }

    override fun onPause() {
        super.onPause()
        link.pause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun pickPhoto() {
        // The system photo picker (Android 13+) and GET_CONTENT need no storage permission.
        val action = if (Build.VERSION.SDK_INT >= 33) MediaStore.ACTION_PICK_IMAGES else Intent.ACTION_GET_CONTENT
        startActivityForResult(Intent(action).setType("image/*"), PICK_PHOTO)
    }

    @Deprecated("Activity result API on the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK || uri == null) {
            if (fitted == null) finish()
            return
        }
        title.text = "Preparing photo…"
        worker.execute { load(uri) }
    }

    /** Decodes the photo, counts faces, guesses the content type and starts the exam. */
    private fun load(uri: Uri) {
        try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 1600))
            }
            val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            source = bmp
            sourceFaces = detectFaces(bmp)
            val ranked = ContentType.rank(argb, bmp.width, bmp.height, sourceFaces.size)
            photoStamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            runOnUiThread { start(ranked) }
        } catch (e: Exception) {
            runOnUiThread { title.text = "Could not open photo: ${e.message}" }
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
        val mid = android.graphics.PointF()
        return found.take(count).filterNotNull().map { f ->
            f.getMidPoint(mid)
            Triple(mid.x / scale, mid.y / scale, f.eyesDistance() / scale)
        }
    }

    /** (Re)starts the exam: fits the photo both ways and starts with the ranked style list. */
    private fun start(ranking: List<ContentType>) {
        this.ranking = ranking
        val bmp = source ?: return
        val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        fitted = PhotoPipeline.fitPortrait(argb, bmp.width, bmp.height)
        fittedNearest = PhotoPipeline.fitPortrait(argb, bmp.width, bmp.height, nearest = true)
        faces = sourceFaces.map { (x, y, d) ->
            val (px, py) = PhotoPipeline.toPortraitPoint(x, y, bmp.width, bmp.height)
            Face(px, py, PhotoPipeline.toPortraitLength(d, bmp.width, bmp.height))
        }
        rendered.clear()
        exam = Exam(ranking, ::loadProfile)
        show()
    }

    /** Renders the exam's current step: always a 2x2 quad, or the final result. */
    private fun show() {
        val exam = exam ?: return
        val facesNote = if (faces.isNotEmpty()) "${faces.size} face${if (faces.size > 1) "s" else ""} found" else "no faces found"
        typeLabel.text = if (exam.step() is Exam.Step.StyleList)
            "Scroll through the styles, most likely first ($facesNote). Tap one to use it."
        else "Style: ${exam.type.label}"
        content.removeAllViews()
        choiceRow.removeAllViews()
        when (val step = exam.step()) {
            is Exam.Step.StyleList -> {
                title.text = "${step.title}  (${step.round}/${step.rounds})"
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                step.options.forEachIndexed { i, option ->
                    list.addView(tile("${i + 1}. ${step.labels[i]}", option, fixedHeight = true) {
                        exam.choose(i); show()
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                content.addView(android.widget.ScrollView(this).apply { addView(list) })
            }
            is Exam.Step.Quad -> {
                title.text = "${step.title}: which looks best?  (${step.round}/${step.rounds})"
                val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (row in 0 until 2) {
                    val line = LinearLayout(this)
                    for (col in 0 until 2) {
                        val i = row * 2 + col
                        line.addView(tile("ABCD"[i] + ": " + caption(step.options[i], exam.current), step.options[i]) {
                            exam.choose(i); show()
                        }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                    }
                    grid.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                }
                content.addView(grid)
                choiceRow.addView(TextView(this).apply {
                    text = "Tap the best one. Press and hold to see it full screen."
                    textSize = 12f
                })
            }
            is Exam.Step.Done -> {
                saveProfile(exam.type, step.result)
                title.text = "Your best version"
                content.addView(tile(describe(step.result), step.result) {})
                choiceRow.addView(Button(this).apply {
                    text = "Start over"
                    setOnClickListener { start(ranking) }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
    }

    /** Short label: what differs from the current best (or "current"). */
    private fun caption(s: Settings, current: Settings): String = when {
        s == current -> "current"
        s.tone != current.tone -> s.tone.label
        s.method != current.method -> s.method.label
        s.brightness != current.brightness -> if (s.brightness > current.brightness) "brighter" else "darker"
        s.contrast != current.contrast -> if (s.contrast > current.contrast) "more contrast" else "less contrast"
        s.detail != current.detail -> if (s.detail > current.detail) "more detail" else "less detail"
        s.saturation != current.saturation -> if (s.saturation > current.saturation) "more color" else "less color"
        else -> "current"
    }

    /**
     * An image cell with a caption; rendered in the background. Long-press shows it full screen.
     * [fixedHeight] sizes the image from its width (for scrolling lists) instead of filling the cell.
     */
    private fun tile(caption: String, settings: Settings, fixedHeight: Boolean = false, onPick: () -> Unit): View {
        val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(this).apply { text = caption; gravity = Gravity.CENTER; textSize = if (fixedHeight) 16f else 13f }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, if (fixedHeight) 24 else 6, 6, 6)
            if (fixedHeight) {
                addView(label)
                addView(image, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            } else {
                addView(image, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                addView(label)
            }
        }
        for (v in listOf(cell, image)) {
            v.setOnClickListener { onPick() }
            v.setOnLongClickListener { (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let(::showFull); true }
        }
        worker.execute {
            val bmp = Bitmap.createBitmap(
                PhotoPipeline.simulatedPortrait(codesFor(settings)),
                PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888,
            )
            runOnUiThread { image.setImageBitmap(bmp) }
        }
        return cell
    }

    /** Full-screen view of one preview; tap anywhere to close. */
    private fun showFull(bmp: Bitmap) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dialog.dismiss() }
        })
        dialog.show()
    }

    private fun codesFor(settings: Settings): ByteArray =
        rendered.getOrPut(settings) {
            // Faces are found on the averaged image; pixel art does not need skin protection.
            if (settings.nearest) PhotoPipeline.render(fittedNearest!!, settings)
            else PhotoPipeline.render(fitted!!, settings, faces)
        }

    private fun sendCurrent() {
        val current = exam?.current ?: return
        worker.execute {
            val codes = codesFor(current)
            savePngs(current, codes)
            val upload = Apdu.buildUpload(Frame.packPixels(codes))
            val type = exam?.type ?: ranking.first()
            runOnUiThread { link.send("exam P$photoStamp ${type.name} ${describe(current)}", describe(current), upload) }
        }
    }

    /** Keeps what was sent (ink map and simulated preview) for later comparison with frame photos. */
    private fun savePngs(settings: Settings, codes: ByteArray) {
        val dir = getExternalFilesDir("exam") ?: return
        val name = "P$photoStamp-${settings.tone.name.lowercase()}-${settings.method.name.lowercase()}-b${settings.brightness}-c${settings.contrast}-s${settings.saturation}-d${settings.detail}"
        fun save(suffix: String, argb: IntArray) = File(dir, "$name-$suffix.png").outputStream().use {
            Bitmap.createBitmap(argb, PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        save("ink", Preview.portraitArgb(codes))
        save("sim", PhotoPipeline.simulatedPortrait(codes))
    }

    private fun describe(s: Settings) =
        "${s.tone.label}, ${s.method.label}, brightness %+.1f, contrast %+.1f, detail ×%.1f, color ×%.2f"
            .format(s.brightness, s.contrast, s.detail, s.saturation)

    private fun loadProfile(type: ContentType): Settings {
        val saved = getSharedPreferences("profiles", MODE_PRIVATE).getString(type.name, null) ?: return type.defaults
        return runCatching {
            val f = saved.split(",")
            Settings(Dither.Method.valueOf(f[1]), ToneMap.valueOf(f[0]), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f[6].toBoolean())
        }.getOrDefault(type.defaults)
    }

    private fun saveProfile(type: ContentType, s: Settings) {
        getSharedPreferences("profiles", MODE_PRIVATE).edit()
            .putString(type.name, "${s.tone.name},${s.method.name},${s.brightness},${s.contrast},${s.saturation},${s.detail},${s.nearest}").apply()
    }

    private companion object {
        const val PICK_PHOTO = 1
    }
}
