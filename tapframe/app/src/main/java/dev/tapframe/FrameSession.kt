package dev.tapframe

import android.nfc.tech.IsoDep
import dev.tapframe.protocol.Apdu
import dev.tapframe.protocol.DeviceInfo

/**
 * One battery-free update of a Tapmora frame over an already discovered [IsoDep] tag.
 * Mirrors the sequence and refresh handling of Einky 1.0.27 (see PROTOCOL.md).
 */
class FrameSession(
    private val iso: IsoDep,
    private val log: (String) -> Unit,
    private val onProgress: (Progress) -> Unit = {},
) {

    open class FrameException(message: String) : Exception(message)

    /** The frame refused an image block; with compressed data, a literal-only retry may succeed. */
    class UploadRejected(block: Int, status: String) : FrameException("Upload block $block rejected: $status")

    sealed class Progress {
        /** [done] of [total] image blocks sent. */
        data class Uploading(val done: Int, val total: Int) : Progress()
        /** The panel is redrawing; [elapsedMs] since the refresh command was accepted. */
        data class Refreshing(val elapsedMs: Long) : Progress()
    }

    /** True once every image block was accepted; a later failure may be resumable with [resumeRefresh]. */
    @Volatile var uploaded = false
        private set

    /** Connects, uploads [upload] (F0D3 APDUs), refreshes and waits for the panel to finish. */
    fun send(upload: List<ByteArray>) {
        iso.connect()
        iso.timeout = 50_000
        try {
            val info = DeviceInfo.parse(expectOk(Apdu.READ_INFO))
            log("Frame ${info.uid}: ${info.width}x${info.height}")
            expectOk(Apdu.READ_D8)

            val started = System.currentTimeMillis()
            upload.forEachIndexed { i, apdu ->
                val r = transceive(apdu, verbose = false)
                if (!Apdu.isOk(r)) throw UploadRejected(i, Apdu.toHex(r))
                if (i % 8 == 0 || i == upload.lastIndex) onProgress(Progress.Uploading(i + 1, upload.size))
            }
            log("Uploaded ${upload.size} blocks in ${System.currentTimeMillis() - started} ms")
            uploaded = true

            refresh()
        } finally {
            runCatching { iso.close() }
        }
    }

    /**
     * Refresh without uploading, after a connection dropped once the upload had finished. The
     * frame keeps the uploaded image while unpowered between taps (verified 2026-11-02).
     */
    fun resumeRefresh() {
        iso.connect()
        iso.timeout = 50_000
        try {
            val info = DeviceInfo.parse(expectOk(Apdu.READ_INFO))
            log("Frame ${info.uid}: resuming refresh without upload")
            expectOk(Apdu.READ_D8)
            uploaded = true
            refresh()
        } finally {
            runCatching { iso.close() }
        }
    }

    /**
     * Einky falls back to `F0 D4 85 00 00` on unexpected answers, but in testing that variant
     * returned 9000 without a visible refresh (after 68CA) or stalled 30 s (68CB), so any answer
     * other than success is reported as a failure and the user re-taps.
     */
    private fun refresh() {
        when (val r = Apdu.toHex(transceive(Apdu.refresh(0)))) {
            "009000", "9000" -> pollUntilDone()
            "019000" -> log("Refresh accepted")
            else -> throw FrameException("Frame refused the refresh ($r)")
        }
    }

    /**
     * F0DE: 01 9000 = refreshing, 00 9000 = idle. Einky waits at least 3 s and requires a busy state.
     * Poll from the start, like Einky: while the link is idle Android runs its own presence check,
     * which the panel fails mid-refresh, and the tag is then re-discovered ("out of date").
     */
    private fun pollUntilDone() {
        val start = System.currentTimeMillis()
        var sawBusy = false
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= 60_000) throw FrameException("Refresh timed out")
            onProgress(Progress.Refreshing(elapsed))
            when (val r = Apdu.toHex(transceive(Apdu.POLL, verbose = false))) {
                "019000" -> sawBusy = true
                "009000" -> if (elapsed >= 3_000) {
                    if (!sawBusy) throw FrameException("Panel never started refreshing")
                    log("Refresh done after $elapsed ms")
                    return
                }
                else -> throw FrameException("Unexpected poll response $r")
            }
            Thread.sleep(100)
        }
    }

    private fun expectOk(apdu: ByteArray): ByteArray {
        val r = transceive(apdu)
        if (!Apdu.isOk(r)) throw FrameException("${Apdu.toHex(apdu)} failed: ${Apdu.toHex(r)}")
        return r
    }

    private fun transceive(apdu: ByteArray, verbose: Boolean = true): ByteArray {
        if (verbose) log(">> ${Apdu.toHex(apdu)}")
        val r = iso.transceive(apdu)
        if (verbose) log("<< ${Apdu.toHex(r)}")
        return r
    }
}
