package dev.tapframe.app

import android.content.Context
import dev.tapframe.render.PhotoPipeline
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * White-box Cartoonization (CVPR 2020) via LiteRT, from assets/cartoongan_dr.tflite: 512x512 RGB in
 * [-1, 1] in and out. Runs fully on device. See assets/cartoongan_LICENSE.txt (CC BY-NC-SA 4.0).
 */
class CartoonModel(context: Context) : AutoCloseable {
    private val interpreter: Interpreter

    init {
        val fd = context.assets.openFd("cartoongan_dr.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    }

    /** [input] is SIZE x SIZE 0xRRGGBB; returns the cartoonized portrait (304 x 400). */
    @Synchronized
    fun cartoonize(input: IntArray): IntArray {
        val inBuf = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in input) {
            inBuf.putFloat((c shr 16 and 0xFF) / 127.5f - 1f)
            inBuf.putFloat((c shr 8 and 0xFF) / 127.5f - 1f)
            inBuf.putFloat((c and 0xFF) / 127.5f - 1f)
        }
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        val square = IntArray(SIZE * SIZE) {
            fun ch() = (((outBuf.float.coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()).coerceIn(0, 255)
            (ch() shl 16) or (ch() shl 8) or ch()
        }
        // The portrait was stretched to a square for the model; stretch back.
        return PhotoPipeline.fit(square, SIZE, SIZE, PhotoPipeline.Crop(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), PhotoPipeline.PORTRAIT_W, PhotoPipeline.PORTRAIT_H)
    }

    override fun close() = interpreter.close()

    companion object {
        const val SIZE = 512
    }
}
