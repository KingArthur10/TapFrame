package dev.tapframe

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.tapframe.patterns.TestPatterns
import dev.tapframe.protocol.Apdu
import dev.tapframe.protocol.Frame
import dev.tapframe.protocol.Lzo
import dev.tapframe.render.PhotoPipeline
import dev.tapframe.render.Preview

/**
 * Lab: the panel test harness (Settings → Lab). Choose a target, check the preview, tap Send, then
 * hold the phone on the frame.
 *  - Replay: the exact upload Einky sent (assets/replay_capture1.txt), when present.
 *  - Test patterns: panel characterization patterns (see TESTPLAN.md), sent with our own encoder.
 */
class LabActivity : Activity() {

    /** Something that can be sent: an id for the log, a label, the packed image and its APDUs. */
    private class Target(val id: String, val label: String, val image: () -> ByteArray, val upload: (ByteArray) -> List<ByteArray>)

    private val targets: List<Target> by lazy {
        val replay = if ("replay_capture1.txt" in (assets.list("") ?: emptyArray())) {
            listOf(Target("replay", "Replay Einky capture", ::replayImage) { loadReplay() })
        } else emptyList()
        replay + TestPatterns.all.map { p ->
                Target(p.id, p.title, { Frame.packPixels(p.render()) }) { image -> Apdu.buildUpload(image) }
            }
    }

    private var selected = 0
    private lateinit var link: FrameLink
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 18f; text = "Choose a pattern, then tap Send." }
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@LabActivity, android.R.layout.simple_spinner_dropdown_item, targets.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = select(position)
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener { targets[selected].let { t -> link.send(t.id, t.label, t.upload(t.image())) } }
        }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(logView) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(picker, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f))
            addView(send, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        padForSystemBars(root, pad)
        setContentView(root)

        link = FrameLink(this, onStatus = { status.text = it }, onLog = { line ->
            logView.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        })
        if (!link.available) status.text = "This phone has no NFC."
    }

    override fun onResume() {
        super.onResume()
        link.resume()
    }

    override fun onPause() {
        super.onPause()
        link.pause()
    }

    private fun select(position: Int) {
        selected = position
        val codes = Frame.unpackPixels(targets[position].image())
        val w = PhotoPipeline.PORTRAIT_W; val h = PhotoPipeline.PORTRAIT_H
        val bmp = Bitmap.createBitmap(Preview.portraitArgb(codes), w, h, Bitmap.Config.ARGB_8888)
        // Nearest-neighbour upscale keeps single pixels crisp in the preview.
        preview.setImageBitmap(Bitmap.createScaledBitmap(bmp, w * 2, h * 2, false))
    }

    private fun loadReplay(): List<ByteArray> =
        assets.open("replay_capture1.txt").bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.startsWith("F0D3") }
            .map(Apdu::fromHex)

    /** The packed image inside the replay capture, for the preview. */
    private fun replayImage(): ByteArray =
        loadReplay().groupBy { it[2].toInt() to it[5].toInt() }.values
            .map { blocks -> Lzo.decompress(blocks.map { it.copyOfRange(7, it.size) }.reduce(ByteArray::plus)) }
            .reduce(ByteArray::plus)
}
