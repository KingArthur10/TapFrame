package dev.tapframe.app

import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.tapframe.app.ui.TapFrameApp

/**
 * Single-activity host: Compose UI, the system photo picker (no storage permission), and NFC
 * reader mode, which stays on while the app is in front so a tap sends whatever is armed.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var nfc: NfcAdapter? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhotoPicked(uri)
    }

    /** Model files the user downloaded in the browser (no storage permission needed). */
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importModels(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent {
            TapFrameTheme {
                // Android silently ignores reader mode enabled while another app (e.g. the camera)
                // still counts as foreground, so re-enable it whenever an image is armed.
                val sendState by vm.sender.state.collectAsState()
                LaunchedEffect(sendState is SendState.Waiting) { if (sendState is SendState.Waiting) startReaderMode() }
                BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
                TapFrameApp(
                    vm = vm,
                    nfcAvailable = nfc != null,
                    nfcEnabled = { nfc?.isEnabled == true },
                    onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onOpenLab = { startActivity(android.content.Intent(this, dev.tapframe.LabActivity::class.java)) },
                    onImportModels = { modelPicker.launch(arrayOf("*/*")) },
                    // The browser does the downloading; TapFrame itself has no network access.
                    onOpenUrl = { url ->
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startReaderMode()
    }

    private fun startReaderMode() {
        val adapter = nfc ?: return
        adapter.disableReaderMode(this)
        // Same flags as Einky (NFC-A only); a slower presence check is less likely to drop the
        // frame while it is busy refreshing.
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1_000) }
        adapter.enableReaderMode(this, vm.sender::onTag, NfcAdapter.FLAG_READER_NFC_A, extras)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }
}

@Composable
fun TapFrameTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
