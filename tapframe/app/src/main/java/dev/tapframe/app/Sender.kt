package dev.tapframe.app

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import dev.tapframe.FrameSession
import dev.tapframe.protocol.Apdu
import dev.tapframe.protocol.Lzo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the send sheet shows. */
sealed class SendState {
    data object Idle : SendState()
    /** Armed: waiting for the phone to touch the frame. */
    data class Waiting(val label: String, val retrying: Boolean = false) : SendState()
    /** [fraction] of the image uploaded. */
    data class Uploading(val fraction: Float) : SendState()
    /** The panel is redrawing; typically takes about [TYPICAL_REFRESH_MS]. */
    data class Refreshing(val elapsedMs: Long) : SendState()
    data class Done(val seconds: Long) : SendState()
    /** Still armed: lifting the phone and tapping again retries. */
    data class Failed(val message: String) : SendState()

    companion object {
        const val TYPICAL_REFRESH_MS = 17_500L
    }
}

/**
 * Holds one pending image and sends it when the phone touches the frame (reader-mode callback).
 * Uploads are LZO-compressed unless disabled; if the frame rejects a compressed block, the next tap
 * retries uncompressed. A failed attempt stays armed, so lifting and re-tapping retries.
 */
class Sender(private val compression: () -> Boolean) {

    private class Job(val id: String, val label: String, val packed: ByteArray, var compressed: Boolean, val onDone: () -> Unit)

    private val _state = MutableStateFlow<SendState>(SendState.Idle)
    val state: StateFlow<SendState> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var busy = false

    /** Arms [packed] (Frame.IMAGE_BYTES). [onDone] runs after a successful send (any thread). */
    fun arm(id: String, label: String, packed: ByteArray, onDone: () -> Unit = {}) {
        if (busy) return
        job = Job(id, label, packed, compression(), onDone)
        _state.value = SendState.Waiting(label)
    }

    fun cancel() {
        if (busy) return
        job = null
        _state.value = SendState.Idle
    }

    /** Dismisses a finished state (Done) back to Idle. */
    fun acknowledge() {
        if (_state.value is SendState.Done) _state.value = SendState.Idle
    }

    /** Reader-mode callback, on a binder thread; blocking here is fine. */
    fun onTag(tag: Tag) {
        Log.d(TAG, "tag discovered: armed=${job?.id} busy=$busy")
        val j = job ?: return
        if (busy) return
        val iso = IsoDep.get(tag) ?: return
        busy = true
        _state.value = SendState.Uploading(0f)
        val start = System.currentTimeMillis()
        try {
            val upload = if (j.compressed) Apdu.buildUpload(j.packed, Lzo::compress) else Apdu.buildUpload(j.packed)
            Log.d(TAG, "SEND ${j.id} (${upload.size} blocks, compressed=${j.compressed})")
            FrameSession(iso, { Log.d(TAG, it) }) { p ->
                _state.value = when (p) {
                    is FrameSession.Progress.Uploading -> SendState.Uploading(p.done.toFloat() / p.total)
                    is FrameSession.Progress.Refreshing -> SendState.Refreshing(p.elapsedMs)
                }
            }.send(upload)
            job = null
            Log.d(TAG, "DONE ${j.id}")
            _state.value = SendState.Done((System.currentTimeMillis() - start) / 1000)
            j.onDone()
        } catch (e: FrameSession.UploadRejected) {
            Log.d(TAG, "FAIL ${j.id}: $e")
            if (j.compressed) {
                j.compressed = false
                _state.value = SendState.Failed("The frame didn't accept the compressed image. Tap again to send it uncompressed.")
            } else {
                _state.value = SendState.Failed("The frame rejected the image (${e.message}). Lift the phone and tap again.")
            }
        } catch (e: Exception) {
            Log.d(TAG, "FAIL ${j.id}: $e")
            _state.value = SendState.Failed(friendly(e))
        } finally {
            busy = false
        }
    }

    private fun friendly(e: Exception): String = when {
        e.message?.contains("refused the refresh") == true ->
            "The frame couldn't start updating. Hold the phone flat on the frame, then tap again."
        e is java.io.IOException || e is SecurityException ->
            "The connection dropped. Keep the phone still on the frame until it finishes, then tap again."
        else -> "Something went wrong (${e.message}). Lift the phone and tap again."
    }

    private companion object {
        const val TAG = "TapFrame"
    }
}
