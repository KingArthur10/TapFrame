package dev.tapframe

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log

/**
 * NFC reader mode plus one pending upload: [send] arms it, and the next time the phone touches the
 * frame the upload runs. A failed attempt stays armed so a re-tap retries. Callbacks run on the UI thread.
 */
class FrameLink(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit = {},
) : NfcAdapter.ReaderCallback {

    private class Job(val id: String, val label: String, val upload: List<ByteArray>)

    private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    @Volatile private var armed: Job? = null
    @Volatile var busy = false
        private set

    val available: Boolean get() = nfc != null

    fun resume() = startReaderMode()

    fun pause() {
        nfc?.disableReaderMode(activity)
    }

    /** Arms [upload]; it is sent on the next tap. [id] goes into the log for matching photos. */
    fun send(id: String, label: String, upload: List<ByteArray>) {
        if (busy) return
        armed = Job(id, label, upload)
        startReaderMode()
        status("$label: hold the phone on the frame…")
    }

    /**
     * (Re)enables reader mode. Android silently ignores the request if another app (e.g. the
     * camera) still counts as foreground when we resume, so this is repeated on every send.
     */
    private fun startReaderMode() {
        val adapter = nfc ?: return
        adapter.disableReaderMode(activity)
        // Same reader-mode flags as Einky: NFC-A only. A slower background presence check
        // (default 125 ms) is less likely to declare the frame lost while it is busy refreshing.
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_MS) }
        adapter.enableReaderMode(activity, this, NfcAdapter.FLAG_READER_NFC_A, extras)
        Log.d(TAG, "reader mode enabled (nfc on=${adapter.isEnabled})")
    }

    /** Called on a binder thread; blocking here is fine. */
    override fun onTagDiscovered(tag: Tag) {
        Log.d(TAG, "tag discovered: armed=${armed?.id} busy=$busy")
        val job = armed ?: return
        val iso = IsoDep.get(tag) ?: return log("Tag has no IsoDep; not a Tapmora frame?")
        busy = true
        status("Sending ${job.label}… keep the phone still.")
        try {
            val start = System.currentTimeMillis()
            log("SEND ${job.id}")
            FrameSession(iso, ::log).send(job.upload)
            armed = null
            log("DONE ${job.id}")
            status("Done: ${job.label} (${(System.currentTimeMillis() - start) / 1000} s).")
        } catch (e: Exception) {
            // Usually a shaky field while the phone approaches; stay armed so a re-tap retries.
            log("FAIL ${job.id}: $e")
            status("Failed: ${e.message}. Lift the phone and tap the frame again to retry.")
        } finally {
            busy = false
        }
    }

    private fun status(text: String) = activity.runOnUiThread { onStatus(text) }

    private fun log(line: String) {
        Log.d(TAG, line)
        activity.runOnUiThread { onLog(line) }
    }

    private companion object {
        const val TAG = "TapFrame"
        const val PRESENCE_CHECK_MS = 1_000
    }
}
