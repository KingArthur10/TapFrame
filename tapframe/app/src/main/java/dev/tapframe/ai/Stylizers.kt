package dev.tapframe.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import dev.tapframe.render.Face
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/** Turns a portrait photo into a stylized portrait. Implementations are not thread-safe. */
interface Stylizer : AutoCloseable {
    /**
     * [src] is a [SOURCE_W] x [SOURCE_H] 0xRRGGBB portrait; [faces] are in 304 x 400 portrait
     * coordinates. Returns a [SOURCE_W] x [SOURCE_H] 0xRRGGBB image.
     */
    fun stylize(src: IntArray, faces: List<Face>): IntArray

    companion object {
        /** Twice the card's portrait size: enough detail for every model's input. */
        const val SOURCE_W = 608
        const val SOURCE_H = 800

        fun create(style: Catalog.Style, store: ModelStore): Stylizer = when (style.pack.kind) {
            Catalog.Kind.ANIMEGAN -> AnimeGanStylizer(store.path(style.file))
            Catalog.Kind.DCTNET -> DctNetStylizer(store.path(style.file), style.faces?.let(store::path))
            Catalog.Kind.SD_TURBO -> SdTurboStylizer(store, requireNotNull(style.prompt))
        }
    }
}

/** Bilinear resizing and float conversions for model inputs and outputs. */
internal object Images {
    fun resize(src: IntArray, w: Int, h: Int, outW: Int, outH: Int): IntArray {
        if (w == outW && h == outH) return src
        val sx = w.toFloat() / outW; val sy = h.toFloat() / outH
        return IntArray(outW * outH) { i ->
            val fx = ((i % outW) + 0.5f) * sx - 0.5f; val fy = ((i / outW) + 0.5f) * sy - 0.5f
            val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
            val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
            val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
            var out = 0
            for (shift in intArrayOf(16, 8, 0)) {
                fun c(x: Int, y: Int) = (src[y * w + x] shr shift and 0xFF).toFloat()
                val top = c(x0, y0) + (c(x1, y0) - c(x0, y0)) * ax
                val bottom = c(x0, y1) + (c(x1, y1) - c(x0, y1)) * ax
                out = out or ((top + (bottom - top) * ay).toInt().coerceIn(0, 255) shl shift)
            }
            out
        }
    }

    /** 0xRRGGBB -> channel value at [shift] mapped by [f]. */
    inline fun channel(c: Int, shift: Int, f: (Float) -> Float) = f((c shr shift and 0xFF).toFloat())

    fun pack(r: Float, g: Float, b: Float): Int {
        fun q(v: Float) = v.toInt().coerceIn(0, 255)
        return (q(r) shl 16) or (q(g) shl 8) or q(b)
    }

    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun session(file: File, threads: Int = 4): OrtSession =
        env.createSession(file.path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
}

/** AnimeGANv3 (ONNX): NHWC RGB in [-1, 1], any size (multiples of 8 are safest). */
class AnimeGanStylizer(model: File) : Stylizer {
    private val session = Images.session(model)

    override fun stylize(src: IntArray, faces: List<Face>): IntArray {
        val w = 512; val h = 672
        val input = Images.resize(src, Stylizer.SOURCE_W, Stylizer.SOURCE_H, w, h)
        val buf = FloatBuffer.allocate(w * h * 3)
        for (c in input) for (s in intArrayOf(16, 8, 0)) buf.put(Images.channel(c, s) { it / 127.5f - 1f })
        buf.rewind()
        val name = session.inputNames.first()
        val out = OnnxTensor.createTensor(Images.env, buf, longArrayOf(1, h.toLong(), w.toLong(), 3)).use { t ->
            session.run(mapOf(name to t)).use { r -> (r[0] as OnnxTensor).floatBuffer }
        }
        fun v() = (out.get().coerceIn(-1f, 1f) + 1f) * 127.5f
        val img = IntArray(w * h) { Images.pack(v(), v(), v()) }
        return Images.resize(img, w, h, Stylizer.SOURCE_W, Stylizer.SOURCE_H)
    }

    override fun close() = session.close()
}

/**
 * DCT-Net (converted on import): the background/body model on the whole picture, then the head
 * model on a square around each detected face, blended in with a soft round mask. NCHW BGR 0..255.
 */
class DctNetStylizer(body: File, head: File?) : Stylizer {
    private val bodySession = Images.session(body)
    private val headSession = if (head != null && head != body) Images.session(head) else if (head != null) bodySession else null

    override fun stylize(src: IntArray, faces: List<Face>): IntArray {
        val w = 544; val h = 720 // multiples of 16, close to the source aspect
        val bodyIn = Images.resize(src, Stylizer.SOURCE_W, Stylizer.SOURCE_H, w, h)
        val out = Images.resize(run(bodySession, bodyIn, w, h), w, h, Stylizer.SOURCE_W, Stylizer.SOURCE_H)
        val headModel = headSession ?: return out
        val scale = Stylizer.SOURCE_W / 304f
        for (face in faces) {
            // Square crop around the eyes, sized like the model's training crops (head and hair).
            val size = (face.eyeDistance * scale * HEAD_TO_EYES).toInt().coerceIn(64, Stylizer.SOURCE_W)
            val left = (face.x * scale - size / 2f).toInt()
            val top = (face.y * scale - size * EYES_FROM_TOP).toInt()
            val crop = IntArray(size * size) { i ->
                val x = (left + i % size).coerceIn(0, Stylizer.SOURCE_W - 1); val y = (top + i / size).coerceIn(0, Stylizer.SOURCE_H - 1)
                src[y * Stylizer.SOURCE_W + x]
            }
            val headIn = Images.resize(crop, size, size, HEAD, HEAD)
            val headOut = Images.resize(run(headModel, headIn, HEAD, HEAD), HEAD, HEAD, size, size)
            for (i in headOut.indices) {
                val cx = i % size; val cy = i / size
                val x = left + cx; val y = top + cy
                if (x !in 0 until Stylizer.SOURCE_W || y !in 0 until Stylizer.SOURCE_H) continue
                // Soft round mask: full inside 70% of the radius, cosine falloff to the edge.
                val r = sqrt(((cx + 0.5f) / size - 0.5f).let { it * it } + ((cy + 0.5f) / size - 0.5f).let { it * it }) * 2f
                val a = when { r <= 0.7f -> 1f; r >= 1f -> 0f; else -> 0.5f + 0.5f * cos(PI.toFloat() * (r - 0.7f) / 0.3f) }
                if (a <= 0f) continue
                val j = y * Stylizer.SOURCE_W + x
                val o = out[j]; val n = headOut[i]
                out[j] = Images.pack(
                    Images.channel(o, 16) { it } * (1 - a) + Images.channel(n, 16) { it } * a,
                    Images.channel(o, 8) { it } * (1 - a) + Images.channel(n, 8) { it } * a,
                    Images.channel(o, 0) { it } * (1 - a) + Images.channel(n, 0) { it } * a,
                )
            }
        }
        return out
    }

    private fun run(session: OrtSession, rgb: IntArray, w: Int, h: Int): IntArray {
        val plane = w * h
        val buf = FloatBuffer.allocate(plane * 3)
        for (i in 0 until plane) {
            val c = rgb[i]
            buf.put(i, (c and 0xFF).toFloat())                       // B
            buf.put(plane + i, (c shr 8 and 0xFF).toFloat())         // G
            buf.put(2 * plane + i, (c shr 16 and 0xFF).toFloat())    // R
        }
        val out = OnnxTensor.createTensor(Images.env, buf, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            session.run(mapOf("input" to t)).use { r -> (r[0] as OnnxTensor).floatBuffer }
        }
        return IntArray(plane) { i -> Images.pack(out.get(2 * plane + i), out.get(plane + i), out.get(i)) }
    }

    override fun close() {
        bodySession.close()
        if (headSession != null && headSession !== bodySession) headSession.close()
    }

    private companion object {
        const val HEAD = 288
        const val HEAD_TO_EYES = 3.6f
        const val EYES_FROM_TOP = 0.42f
    }
}

/**
 * SD-Turbo image-to-image (xay2001/sd-turbo-android export): TAESD-encode the picture, add noise at
 * t = 499, one Euler step of the UNet guided by [prompt] (guidance scale 0), TAESD-decode. 512 x 512:
 * the portrait is stretched to a square and back. Sessions open on first use; the prompt's text
 * embedding is computed once.
 */
class SdTurboStylizer(private val store: ModelStore, private val prompt: String) : Stylizer {
    private var embedding: FloatArray? = null

    override fun stylize(src: IntArray, faces: List<Face>): IntArray {
        val s = 512; val l = 64
        val text = embedding ?: encodePrompt().also { embedding = it }
        val img = Images.resize(src, Stylizer.SOURCE_W, Stylizer.SOURCE_H, s, s)
        val plane = s * s
        val pixels = FloatBuffer.allocate(plane * 3)
        for (i in 0 until plane) {
            val c = img[i]
            pixels.put(i, (c shr 16 and 0xFF) / 127.5f - 1f)
            pixels.put(plane + i, (c shr 8 and 0xFF) / 127.5f - 1f)
            pixels.put(2 * plane + i, (c and 0xFF) / 127.5f - 1f)
        }
        val latent = runOnce("encoder_512.onnx", mapOf(0 to tensor(pixels, 1, 3, s, s)))
        // Same noise for the same picture, so previews and the sent image match.
        val rnd = Random(src.fold(17) { acc, c -> acc * 31 + c })
        val noisy = FloatArray(latent.size) { latent[it] + SIGMA * gaussian(rnd) }
        val scaled = FloatBuffer.wrap(FloatArray(noisy.size) { noisy[it] / sqrt(SIGMA * SIGMA + 1f) })
        val eps = Images.session(store.path("unet_512.onnx"), threads = 6).use { unet ->
            // Input names and the timestep's type differ between exports; go by name and declared type.
            val inputs = unet.inputInfo.entries.associate { (name, node) ->
                val info = node.info as TensorInfo
                name to when {
                    name.contains("time", ignoreCase = true) -> {
                        val shape = info.shape.map { maxOf(1L, it) }.toLongArray() // dynamic dims -> 1
                        val n = shape.fold(1L) { a, d -> a * d }.toInt()
                        if (info.type == OnnxJavaType.INT64) OnnxTensor.createTensor(Images.env, LongBuffer.wrap(LongArray(n) { TIMESTEP.toLong() }), shape)
                        else OnnxTensor.createTensor(Images.env, FloatBuffer.wrap(FloatArray(n) { TIMESTEP.toFloat() }), shape)
                    }
                    name.contains("hidden", ignoreCase = true) || name.contains("encoder", ignoreCase = true) ->
                        tensor(FloatBuffer.wrap(text), 1, ClipTokenizer.LENGTH, text.size / ClipTokenizer.LENGTH)
                    else -> tensor(scaled, 1, 4, l, l)
                }
            }
            try { unet.run(inputs).use { r -> (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } } }
            finally { inputs.values.forEach { it.close() } }
        }
        val denoised = FloatBuffer.wrap(FloatArray(noisy.size) { noisy[it] - SIGMA * eps[it] })
        val decoded = runOnce("decoder_512.onnx", mapOf(0 to tensor(denoised, 1, 4, l, l)))
        // TAESD decoders come as [-1, 1] or [0, 1]; tell them apart by range.
        val signed = decoded.any { it < -0.2f }
        fun px(v: Float) = if (signed) (v + 1f) * 127.5f else v * 255f
        val out = IntArray(plane) { i -> Images.pack(px(decoded[i]), px(decoded[plane + i]), px(decoded[2 * plane + i])) }
        return Images.resize(out, s, s, Stylizer.SOURCE_W, Stylizer.SOURCE_H)
    }

    private fun encodePrompt(): FloatArray {
        val tok = ClipTokenizer(store.path("vocab.json").readText(), store.path("merges.txt").readText())
        val ids = tok.encode(prompt)
        return Images.session(store.path("text_encoder.onnx")).use { enc ->
            val input = OnnxTensor.createTensor(Images.env, LongBuffer.wrap(ids), longArrayOf(1, ClipTokenizer.LENGTH.toLong()))
            input.use {
                enc.run(mapOf(enc.inputNames.first() to it)).use { r ->
                    // The per-token hidden states: the 3-D output.
                    val t = (0 until r.size()).map { i -> r[i] as OnnxTensor }.first { it.info.shape.size == 3 }
                    t.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                }
            }
        }
    }

    private fun runOnce(model: String, inputs: Map<Int, OnnxTensor>): FloatArray =
        Images.session(store.path(model)).use { s ->
            val named = inputs.mapKeys { (i, _) -> s.inputNames.elementAt(i) }
            try { s.run(named).use { r -> (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } } }
            finally { named.values.forEach { it.close() } }
        }

    private fun tensor(buf: FloatBuffer, vararg shape: Int): OnnxTensor =
        OnnxTensor.createTensor(Images.env, buf.also { it.rewind() }, shape.map { it.toLong() }.toLongArray())

    private fun gaussian(r: Random): Float {
        val u1 = r.nextDouble().coerceAtLeast(1e-12); val u2 = r.nextDouble()
        return (sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)).toFloat()
    }

    override fun close() {}

    private companion object {
        const val TIMESTEP = 499
        /** Euler sigma at t = 499, from the export's manifest. */
        const val SIGMA = 1.6128870248794556f
    }
}
