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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import dev.tapframe.ai.Catalog
import dev.tapframe.app.AppViewModel
import dev.tapframe.app.HistoryItem
import dev.tapframe.app.Screen
import dev.tapframe.app.SendState
import dev.tapframe.exam.Exam
import dev.tapframe.render.PhotoPipeline
import dev.tapframe.render.Dither
import dev.tapframe.render.Effect
import dev.tapframe.render.PreviewLook
import dev.tapframe.render.ToneMap
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
    onImportModels: () -> Unit,
    onOpenUrl: (String) -> Unit,
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
        Screen.CALIBRATE -> "Match preview to frame"
        Screen.ADVANCED -> "Edit manually"
        Screen.MODELS -> "AI styles"
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
                    if (vm.screen == Screen.EXAM || vm.screen == Screen.RESULT) IconButton(onClick = vm::editManually) {
                        Icon(Icons.Filled.Tune, contentDescription = "Edit manually")
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
                Screen.CALIBRATE -> CalibrationScreen(vm)
                Screen.ADVANCED -> AdvancedScreen(vm)
                Screen.MODELS -> ModelsScreen(vm, onImportModels, onOpenUrl)
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
            val bmp = remember(item.id, vm.look) { item.preview?.let(vm::withLook) }
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
                remember(item.id, vm.look) { item.preview?.let(vm::withLook) }?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)) }
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
                    // Rounding can make these slightly negative when the photo exactly fits.
                    val maxX = ((w - size.width) / 2).coerceAtLeast(0f); val maxY = ((h - size.height) / 2).coerceAtLeast(0f)
                    offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                    val left = (size.width - w) / 2 + offset.x
                    val top = (size.height - h) / 2 + offset.y
                    drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                    crop = PhotoPipeline.Crop(-left / fill, -top / fill, size.width / fill, size.height / fill)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = vm::rotate, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.RotateRight, null); Spacer(Modifier.width(6.dp)); Text("Rotate")
        }
        Spacer(Modifier.height(8.dp))
        // Two equal ways forward: guided (pick from previews) or manual (sliders).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.confirmCrop(crop) }, Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Guided")
            }
            FilledTonalButton(onClick = { vm.confirmCrop(crop, advanced = true) }, Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Filled.Tune, null); Spacer(Modifier.width(8.dp)); Text("Edit manually")
            }
        }
        Text(
            "Guided: pick the best of four previews, round by round. Manual: set every option yourself with sliders.",
            Modifier.padding(top = 6.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = vm::quickSend, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Quick send")
                }
                FilledTonalButton(onClick = vm::editManually, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Tune, null); Spacer(Modifier.width(8.dp)); Text("Edit manually")
                }
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
        FilledTonalButton(onClick = vm::editManually, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Filled.Tune, null); Spacer(Modifier.width(8.dp)); Text("Edit manually from here")
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
    s.keepHue != current.keepHue -> if (s.keepHue) "True hues" else "Bolder hues"
    s.aiStyle != current.aiStyle -> "Other AI style"
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
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = vm::editManually, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Tune, null); Spacer(Modifier.width(8.dp)); Text("Fine-tune manually")
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
                headlineContent = { Text("AI styles") },
                supportingContent = { Text(
                    if (vm.aiStyles.isEmpty()) "Download extra cartoon and painting styles (AnimeGAN, DCT-Net, SD-Turbo)."
                    else "${vm.aiStyles.size} downloaded style${if (vm.aiStyles.size > 1) "s" else ""} ready. Tap to manage.",
                ) },
                modifier = Modifier.clickable(onClick = vm::openModels),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Match preview to frame") },
                supportingContent = { Text(
                    if (vm.look.isIdentity) "Adjust how previews look so they match your frame in your light."
                    else "Previews are adjusted to match your frame. Tap to change.",
                ) },
                modifier = Modifier.clickable(onClick = vm::openCalibration),
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
                supportingContent = { Text(
                    "TapFrame 1.0 · an independent, offline app for Tapmora-style NFC e-ink frames.\n" +
                        "The AI cartoon style uses White-box Cartoonization (Wang & Yu, CVPR 2020), " +
                        "CC BY-NC-SA 4.0: non-commercial use only.",
                ) },
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

// ---- Advanced editing ------------------------------------------------------------------------

/**
 * Manual editor: every setting as a chip row or slider, with the frame preview re-rendered as you
 * drag (the newest settings win; a small spinner shows while a render is catching up). Touch and
 * hold the preview to see the photo before processing.
 */
@Composable
private fun AdvancedScreen(vm: AppViewModel) {
    val s = vm.advanced ?: return
    val set = vm::updateAdvanced
    var showOriginal by remember { mutableStateOf(false) }
    val original = remember(vm.source) { vm.originalPreview }
    var savedNote by remember { mutableStateOf(false) }
    val photo = s.effect == Effect.NONE
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().weight(0.9f).padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(Unit) { detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false }) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().aspectRatio(CARD_ASPECT, matchHeightConstraintsFirst = true).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                val bmp = if (showOriginal) original else vm.advancedPreview
                if (bmp != null) Picture(bmp, Modifier.fillMaxSize()) else CircularProgressIndicator(Modifier.size(24.dp))
                if (vm.advancedRendering && !showOriginal && bmp != null)
                    CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), strokeWidth = 2.dp)
                if (showOriginal) Text(
                    "Original", Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            "Touch and hold the picture to compare with the original.",
            Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val styles = listOf(
                "Photo" to (Effect.NONE to Settings.WHITEBOX),
                "Cartoon" to (Effect.CARTOON to Settings.WHITEBOX),
                "Cartoon (AI)" to (Effect.AI_CARTOON to Settings.WHITEBOX),
            ) + vm.aiStyles.map { "AI: ${it.label}" to (Effect.AI_CARTOON to it.id) }
            val selected = s.effect to (if (s.effect == Effect.AI_CARTOON) s.aiStyle else Settings.WHITEBOX)
            ChipRow("Style", styles, selected) { (effect, ai) -> set(s.copy(effect = effect, aiStyle = ai)) }
            if (vm.aiStyles.isEmpty()) Text(
                "More AI styles can be downloaded in Settings → AI styles.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (photo) ChipRow("Tone mapping", ToneMap.entries.map { it.label to it }, s.tone) { set(s.copy(tone = it)) }
            ChipRow("Dithering", Dither.Method.entries.map { it.label to it }, s.method) { set(s.copy(method = it)) }
            LabeledSlider("Brightness", s.brightness, Settings.BRIGHTNESS, "%+.2f") { set(s.copy(brightness = it)) }
            LabeledSlider("Contrast", s.contrast, Settings.CONTRAST, "%+.2f") { set(s.copy(contrast = it)) }
            LabeledSlider("Highlights", s.highlights, Settings.HIGHLIGHTS, "%+.2f") { set(s.copy(highlights = it)) }
            LabeledSlider("Shadows", s.shadows, Settings.SHADOWS, "%+.2f") { set(s.copy(shadows = it)) }
            LabeledSlider(if (photo) "Local contrast" else "Outlines", s.detail, Settings.DETAIL, "×%.1f") { set(s.copy(detail = it)) }
            LabeledSlider("Saturation", s.saturation, Settings.SATURATION, "×%.2f") { set(s.copy(saturation = it)) }
            LabeledSlider("Hue", s.hue, Settings.HUE, "%+.0f°") { set(s.copy(hue = it)) }
            SwitchRow(
                "True hues",
                if (photo && s.tone == ToneMap.VIVID) "Not used with Vivid tone mapping."
                else "Keep colors the inks can't fully make (magenta, deep skin) on their hue, a little paler.",
                s.keepHue,
            ) { set(s.copy(keepHue = it)) }
            SwitchRow("Pixel-sharp resize", "Scale without smoothing, for pixel art.", s.nearest) { set(s.copy(nearest = it)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = vm::resetAdvanced, Modifier.weight(1f)) { Text("Reset") }
                OutlinedButton(onClick = { vm.saveAdvancedAsDefault(); savedNote = true }, Modifier.weight(1f)) {
                    Text(if (savedNote) "Saved" else "Save as default")
                }
            }
            Text(
                "Save as default makes these where manual editing starts next time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = vm::sendAdvanced, Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Send to frame")
        }
    }
    LaunchedEffect(s) { savedNote = false }
}

@Composable
private fun <T> ChipRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (name, value) -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(name) }) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(format.format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---- Downloadable AI styles -------------------------------------------------------------------

/**
 * Model manager. TapFrame has no internet permission, so "Download" opens the official file in the
 * browser; "Import" copies picked files in, keeping only those whose SHA-256 matches the catalog.
 */
@Composable
private fun ModelsScreen(vm: AppViewModel, onImport: () -> Unit, onOpenUrl: (String) -> Unit) {
    var confirmDelete by remember { mutableStateOf<Catalog.Pack?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(vm.aiStyles) { refresh++ }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "TapFrame can't use the internet. Tap Download to get a file from its official source in your browser, " +
                    "then Import to add the downloaded files. Pick several at once, in any order: each file is checked " +
                    "against its published fingerprint, and anything else is rejected. Afterwards you can delete the " +
                    "copies in your Downloads folder.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onImport, enabled = !vm.importing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import downloaded files")
            }
            if (vm.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            vm.importStatus?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
        for (pack in Catalog.packs) item(key = pack.id) {
            val installed = remember(refresh, pack.id) { vm.models.installedCount(pack) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(pack.title, style = MaterialTheme.typography.titleMedium)
                    Text(pack.summary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${megabytes(pack.size)} · $installed of ${pack.files.size} files imported",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val ready = vm.aiStyles.filter { it.pack == pack }
                    if (ready.isNotEmpty()) Text("Ready: " + ready.joinToString { it.label }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(pack.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    for (file in pack.files) {
                        val has = remember(refresh, file.name) { vm.models.has(file) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(megabytes(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (has) Icon(Icons.Filled.CheckCircle, "Imported", tint = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { onOpenUrl(file.url) }) { Text("Download") }
                        }
                    }
                    if (installed > 0) TextButton(onClick = { confirmDelete = pack }) {
                        Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(4.dp)); Text("Delete imported files")
                    }
                }
            }
        }
    }
    confirmDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${pack.title}?") },
            text = { Text("Frees ${megabytes(vm.models.used(pack))}. You can import the files again later.") },
            confirmButton = { TextButton(onClick = { vm.deletePack(pack); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(maxOf(1.0, bytes / 1e6))

// ---- Preview calibration -----------------------------------------------------------------------

/**
 * Match-by-eye calibration: send the calibration image, hold the phone beside the frame, and adjust
 * the preview until both look alike. Only changes how previews are shown.
 */
@Composable
private fun CalibrationScreen(vm: AppViewModel) {
    var draft by remember { mutableStateOf(vm.look) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(draft) { bmp = vm.calibrationPreview(draft) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "1. Send the calibration image to your frame.\n" +
                "2. Hold the phone beside the frame where you usually look at it, at the brightness you usually use.\n" +
                "3. Adjust until the picture below looks like the frame. Start with brightness; compare the white and black squares, then the colors.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = vm::sendCalibration, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Send calibration image")
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth(0.75f).align(Alignment.CenterHorizontally).aspectRatio(CARD_ASPECT)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Picture(it, Modifier.fillMaxSize()) } ?: CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        LookSlider("Brightness", draft.brightness, PreviewLook.BRIGHTNESS, "Darker", "Brighter") { draft = draft.copy(brightness = it) }
        LookSlider("Contrast", draft.contrast, PreviewLook.CONTRAST, "Flatter", "Punchier") { draft = draft.copy(contrast = it) }
        LookSlider("Color", draft.saturation, PreviewLook.SATURATION, "Muted", "Vivid") { draft = draft.copy(saturation = it) }
        LookSlider("Warmth", draft.warmth, PreviewLook.WARMTH, "Cooler", "Warmer") { draft = draft.copy(warmth = it) }
        LookSlider("Tint", draft.tint, PreviewLook.TINT, "Greener", "Pinker") { draft = draft.copy(tint = it) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { draft = PreviewLook() }, Modifier.weight(1f)) { Text("Reset") }
            Button(onClick = { vm.saveLook(draft); vm.back() }, Modifier.weight(1f)) { Text("Save") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "This only changes how previews look on your phone; images sent to the frame stay the same.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LookSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, low: String, high: String, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Slider(value = value, onValueChange = onChange, valueRange = range)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(high, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
