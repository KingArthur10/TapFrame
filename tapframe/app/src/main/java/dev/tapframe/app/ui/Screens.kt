package dev.tapframe.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tapframe.app.AppViewModel
import dev.tapframe.app.HistoryItem
import dev.tapframe.app.Screen
import dev.tapframe.app.SendState
import dev.tapframe.exam.Exam
import dev.tapframe.render.PhotoPipeline
import dev.tapframe.render.Settings
import java.text.DateFormat
import java.util.Date

private const val CARD_ASPECT = PhotoPipeline.PORTRAIT_W.toFloat() / PhotoPipeline.PORTRAIT_H

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapFrameApp(
    vm: AppViewModel,
    nfcAvailable: Boolean,
    nfcEnabled: () -> Boolean,
    onPickPhoto: () -> Unit,
    onOpenLab: () -> Unit,
) {
    val title = when (vm.screen) {
        Screen.HOME -> "TapFrame"
        Screen.CROP -> "Frame your photo"
        Screen.EXAM -> when (val s = vm.step) {
            is Exam.Step.StyleList -> "Choose a style"
            is Exam.Step.Quad -> "${s.title} · ${s.round} of ${s.rounds}"
            else -> "Tune"
        }
        Screen.RESULT -> "Ready to send"
        Screen.SETTINGS -> "Settings"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (vm.screen != Screen.HOME) IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.screen == Screen.HOME) IconButton(onClick = vm::openSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm, nfcAvailable, nfcEnabled, onPickPhoto)
                Screen.CROP -> CropScreen(vm)
                Screen.EXAM -> ExamScreen(vm)
                Screen.RESULT -> ResultScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm, onOpenLab)
            }
            vm.busyMessage?.let { Busy(it) }
        }
    }
    SendSheet(vm)
}

@Composable
private fun Busy(message: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
        Card { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(16.dp)); Text(message)
        } }
    }
}

// ---- Home ------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(vm: AppViewModel, nfcAvailable: Boolean, nfcEnabled: () -> Boolean, onPickPhoto: () -> Unit) {
    var selected by remember { mutableStateOf<HistoryItem?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Column {
                if (!nfcAvailable) Notice("This phone has no NFC, which TapFrame needs to reach the frame.")
                else if (!nfcEnabled()) Notice("NFC is turned off. Turn it on in your phone's settings to send to the frame.")
                Card(Modifier.fillMaxWidth().clickable(onClick = onPickPhoto)) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AddPhotoAlternate, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Send a photo", style = MaterialTheme.typography.titleLarge)
                            Text("Pick a photo, frame it, and tune it for the e-ink display.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (vm.historyItems.isEmpty()) "Photos you send will appear here." else "Recent · tap to send again",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        items(vm.historyItems, key = { it.id }) { item ->
            val bmp = remember(item.id) { item.preview }
            Card(Modifier.clickable { selected = item }) {
                if (bmp != null) Picture(bmp, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT))
            }
        }
    }
    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Send again?") },
            text = { Column {
                item.preview?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)) }
                Spacer(Modifier.height(8.dp))
                Text(item.description, style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.time)), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { Button(onClick = { vm.resend(item); selected = null }) { Text("Send to frame") } },
            dismissButton = { TextButton(onClick = { vm.delete(item); selected = null }) {
                Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(4.dp)); Text("Delete")
            } },
        )
    }
}

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A bitmap drawn without smoothing when enlarged, so dither dots stay crisp. */
@Composable
private fun Picture(bmp: Bitmap, modifier: Modifier) {
    Image(bmp.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
}

// ---- Crop ------------------------------------------------------------------------------------

@Composable
private fun CropScreen(vm: AppViewModel) {
    val bmp = vm.source ?: return
    var zoom by remember(bmp) { mutableFloatStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(Offset.Zero) }
    var crop by remember(bmp) { mutableStateOf(PhotoPipeline.centerCrop(bmp.width, bmp.height)) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pinch to zoom, drag to move. The frame shows the card held upright.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Box(
                Modifier.aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(4.dp))
                    .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
            ) {
                Canvas(Modifier.fillMaxSize().pointerInput(bmp) {
                    detectTransformGestures { _, pan, z, _ ->
                        zoom = (zoom * z).coerceIn(1f, 6f)
                        offset += pan
                    }
                }) {
                    // Fill the frame, apply zoom, keep the image covering the frame.
                    val fill = maxOf(size.width / bmp.width, size.height / bmp.height) * zoom
                    val w = bmp.width * fill; val h = bmp.height * fill
                    val maxX = (w - size.width) / 2; val maxY = (h - size.height) / 2
                    offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                    val left = (size.width - w) / 2 + offset.x
                    val top = (size.height - h) / 2 + offset.y
                    drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                    crop = PhotoPipeline.Crop(-left / fill, -top / fill, size.width / fill, size.height / fill)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::rotate, Modifier.weight(1f)) {
                Icon(Icons.Filled.RotateRight, null); Spacer(Modifier.width(6.dp)); Text("Rotate")
            }
            Button(onClick = { vm.confirmCrop(crop) }, Modifier.weight(1f)) { Text("Next") }
        }
    }
}

// ---- Exam ------------------------------------------------------------------------------------

@Composable
private fun ExamScreen(vm: AppViewModel) {
    when (val step = vm.step) {
        is Exam.Step.StyleList -> StyleList(vm, step)
        is Exam.Step.Quad -> QuadView(vm, step)
        else -> {}
    }
}

@Composable
private fun StyleList(vm: AppViewModel, step: Exam.Step.StyleList) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(
                "Your photo in every style, most likely first" +
                    (if (vm.faceCount > 0) " (${vm.faceCount} face${if (vm.faceCount > 1) "s" else ""} found)" else "") +
                    ". Tap the one that suits it to fine-tune, or send it right away.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = vm::quickSend, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Quick send: ${step.labels.first().substringBefore(" (")}")
            }
        }
        itemsIndexed(step.options) { i, option ->
            Card(Modifier.fillMaxWidth().clickable { vm.choose(i) }) {
                Column(Modifier.padding(12.dp)) {
                    Text("${i + 1}. ${step.labels[i]}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Preview(vm, option, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QuadView(vm: AppViewModel, step: Exam.Step.Quad) {
    var enlarged by remember { mutableStateOf<Settings?>(null) }
    val current = vm.exam?.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LinearProgressIndicator(progress = { step.round.toFloat() / step.rounds }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Which looks best? Press and hold to see one full screen.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f)) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 2) {
                        val i = row * 2 + col
                        val option = step.options[i]
                        Column(
                            Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(8.dp))
                                .combinedClickable(onClick = { vm.choose(i) }, onLongClick = { enlarged = option }),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Preview(vm, option, Modifier.weight(1f).fillMaxWidth())
                            Text(caption(option, current), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::finishEarly, Modifier.weight(1f)) { Text("Keep current") }
            Button(onClick = vm::sendCurrent, Modifier.weight(1f)) { Text("Send current") }
        }
    }
    enlarged?.let { s ->
        Dialog(onDismissRequest = { enlarged = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { enlarged = null }, contentAlignment = Alignment.Center) {
                Preview(vm, s, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT))
            }
        }
    }
}

/** Short label: what differs from the current best. */
private fun caption(s: Settings, current: Settings?): String = when {
    current == null || s == current -> "Current"
    s.tone != current.tone -> s.tone.label
    s.method != current.method -> s.method.label
    s.brightness != current.brightness -> if (s.brightness > current.brightness) "Brighter" else "Darker"
    s.contrast != current.contrast -> if (s.contrast > current.contrast) "More contrast" else "Less contrast"
    s.detail != current.detail -> if (s.detail > current.detail) "More detail" else "Less detail"
    s.saturation != current.saturation -> if (s.saturation > current.saturation) "More color" else "Less color"
    else -> "Current"
}

/** A simulated frame preview; rendered in the background on first use. */
@Composable
private fun Preview(vm: AppViewModel, settings: Settings, modifier: Modifier) {
    LaunchedEffect(settings) { vm.requestPreview(settings) }
    val bmp = vm.previews[settings]
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp == null) CircularProgressIndicator(Modifier.size(24.dp)) else Picture(bmp, Modifier.fillMaxSize())
    }
}

// ---- Result ----------------------------------------------------------------------------------

@Composable
private fun ResultScreen(vm: AppViewModel) {
    val result = (vm.step as? Exam.Step.Done)?.result ?: vm.exam?.current ?: return
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Preview(vm, result, Modifier.weight(1f).aspectRatio(CARD_ASPECT))
        Spacer(Modifier.height(12.dp))
        Text("${vm.currentType.label} · ${vm.describe(result)}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Text("Saved as your starting point for ${vm.currentType.label.lowercase()} photos.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.back() }, Modifier.weight(1f)) { Text("Tune again") }
            Button(onClick = vm::sendCurrent, Modifier.weight(1f)) {
                Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Send to frame")
            }
        }
    }
}

// ---- Settings --------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(vm: AppViewModel, onOpenLab: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    LazyColumn {
        item {
            ListItem(
                headlineContent = { Text("Compress images") },
                supportingContent = { Text("Sends about 25% faster. Turn off if sending keeps failing.") },
                trailingContent = { Switch(checked = vm.compressionEnabled, onCheckedChange = vm::setCompression) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Reset saved styles") },
                supportingContent = { Text("Forget the settings remembered for each photo style.") },
                modifier = Modifier.clickable { confirmReset = true },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Lab") },
                supportingContent = { Text("Test patterns for checking the panel.") },
                modifier = Modifier.clickable(onClick = onOpenLab),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Privacy") },
                supportingContent = { Text(
                    "TapFrame has no internet permission: Android does not let it reach the network at all. " +
                        "It has no accounts, analytics or ads. Photos you pick stay on this phone; sent images " +
                        "are kept in the app's private storage so you can send them again.",
                ) },
            )
            ListItem(
                headlineContent = { Text("About") },
                supportingContent = { Text("TapFrame 1.0 · an independent, offline app for Tapmora-style NFC e-ink frames.") },
            )
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset saved styles?") },
        text = { Text("Each photo style goes back to its default settings.") },
        confirmButton = { TextButton(onClick = { vm.resetProfiles(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

// ---- Sending ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(vm: AppViewModel) {
    val state by vm.sender.state.collectAsState()
    if (state is SendState.Idle) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (state is SendState.Done) vm.sender.acknowledge() else vm.sender.cancel() },
        sheetState = sheet,
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = state) {
                is SendState.Waiting -> {
                    Icon(Icons.Filled.Nfc, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Hold your phone on the frame", style = MaterialTheme.typography.titleLarge)
                    Text("Keep it still until the picture finishes updating, about 25 seconds.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = vm.sender::cancel) { Text("Cancel") }
                }
                is SendState.Uploading -> {
                    Text("Sending…", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp)); Text("Keep the phone still")
                }
                is SendState.Refreshing -> {
                    Text("Updating the display…", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { (s.elapsedMs.toFloat() / SendState.TYPICAL_REFRESH_MS).coerceAtMost(0.98f) },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp)); Text("Keep the phone still · ${s.elapsedMs / 1000} s")
                }
                is SendState.Done -> {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Done", style = MaterialTheme.typography.titleLarge)
                    Text("Sent in ${s.seconds} seconds.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm.sender::acknowledge) { Text("OK") }
                }
                is SendState.Failed -> {
                    Icon(Icons.Filled.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text("Didn't finish", style = MaterialTheme.typography.titleLarge)
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = vm.sender::cancel) { Text("Cancel") }
                }
                SendState.Idle -> {}
            }
        }
    }
}
