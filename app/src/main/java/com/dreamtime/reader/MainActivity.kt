package com.dreamtime.reader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val Forest = Color(0xFF293C32)
private val Cream = Color(0xFFFCFAF5)
private data class DreamPalette(val name: String, val primary: Color, val lightBackground: Color, val darkBackground: Color) {
    val lightSurface: Color get() = Color(0xFFFFFEFB)
    val darkSurface: Color get() = Color(0xFF242824)
}
private val palettes = listOf(
    DreamPalette("جنگلی", Color(0xFF496A55), Color(0xFFF5F4EC), Color(0xFF171D19)),
    DreamPalette("یاسی", Color(0xFF745C91), Color(0xFFF6F1F9), Color(0xFF1D1922)),
    DreamPalette("اقیانوسی", Color(0xFF397486), Color(0xFFEEF6F7), Color(0xFF151D20)),
    DreamPalette("رز", Color(0xFF9B5E68), Color(0xFFFAF1F1), Color(0xFF21191B)),
    DreamPalette("کهربایی", Color(0xFF956A32), Color(0xFFFAF5EB), Color(0xFF211D16))
)
private val highlightColors = listOf(
    0xCCFFF0A8.toInt(), // soft yellow
    0xCCBBDDF5.toInt(), // soft blue
    0xCCD9C8F0.toInt(), // soft purple
    0xCCCDE6C6.toInt()  // soft green
)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: BookPrefs
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = BookPrefs(this)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching {
                    controller = future.get()
                    controller?.let { mediaController ->
                        if (mediaController.mediaItemCount == 0) {
                            val savedBook = BookShelf(this).active()
                            val savedAudio = savedBook?.audioUri ?: prefs.audioUri
                            savedAudio?.let { uri ->
                                mediaController.setMediaItem(mediaItem(uri))
                                mediaController.prepare()
                                mediaController.seekTo(savedBook?.positionMs ?: prefs.audioPosition)
                                mediaController.setPlaybackSpeed(prefs.speed)
                            }
                        }
                    }
                }
            }, MoreExecutors.directExecutor())
        }
        setContent { DreamTimeScreen(prefs, controller) }
    }

    private fun mediaItem(uri: Uri) = MediaItem.Builder().setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(uri.lastPathSegment ?: "Dream Time").build())
        .build()

    override fun onStop() {
        controller?.let { prefs.audioPosition = it.currentPosition }
        BookShelf(this).updateProgress(BookShelf(this).activeId, prefs.page, prefs.audioPosition)
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DreamTimeScreen(prefs: BookPrefs, player: MediaController?) {
    val context = LocalContext.current
    val shelf = remember { BookShelf(context).also { store ->
        if (store.active() == null && (prefs.pdfUri != null || prefs.audioUri != null)) {
            store.select(BookProject("legacy", "کتاب من", pdfUri = prefs.pdfUri, audioUri = prefs.audioUri, page = prefs.page, positionMs = prefs.audioPosition))
        }
        prefs.activeBookId = store.activeId
    } }
    var books by remember { mutableStateOf(shelf.all()) }
    var activeBook by remember { mutableStateOf(shelf.active()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(prefs.page) }
    var pageCount by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(prefs.audioPosition) }
    var lastProgressSave by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf("") }
    var autoPage by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var highlightMode by remember { mutableStateOf(false) }
    var eraseMode by remember { mutableStateOf(false) }
    var highlightColorIndex by remember { mutableIntStateOf(prefs.highlightColor.coerceIn(highlightColors.indices)) }
    var highlights by remember(activeBook?.id, page) { mutableStateOf(prefs.highlights(page)) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteTargetIndex by remember { mutableIntStateOf(-1) }
    var noteDraft by remember { mutableStateOf("") }
    var sleepTimerEnd by remember { mutableLongStateOf(prefs.sleepTimerEnd) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSleepMenu by remember { mutableStateOf(false) }
    var rotationLocked by remember { mutableStateOf(prefs.rotationLocked) }
    var screenBrightness by remember { mutableFloatStateOf(prefs.screenBrightness) }
    var captions by remember { mutableStateOf<List<TimedCaption>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var darkTheme by remember { mutableStateOf(prefs.darkTheme) }
    var paletteIndex by remember { mutableIntStateOf(prefs.palette.coerceIn(palettes.indices)) }
    val palette = palettes[paletteIndex]
    val background = if (darkTheme) palette.darkBackground else palette.lightBackground
    val surface = if (darkTheme) palette.darkSurface else palette.lightSurface
    val scheme = if (darkTheme) darkColorScheme(
        primary = palette.primary, onPrimary = Color.White, background = background,
        onBackground = Color(0xFFF2F0EA), surface = surface, onSurface = Color(0xFFF2F0EA),
        surfaceVariant = surface, onSurfaceVariant = Color(0xFFD0CDC5)
    ) else lightColorScheme(
        primary = palette.primary, onPrimary = Color.White, background = background,
        onBackground = Color(0xFF292722), surface = surface, onSurface = Color(0xFF292722),
        surfaceVariant = Color(0xFFECE9E0), onSurfaceVariant = Color(0xFF514F49)
    )

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val book = shelf.updateAudio(it)
            activeBook = book; books = shelf.all(); prefs.activeBookId = book.id
            prefs.audioUri = it
            prefs.audioPosition = 0
            player?.setMediaItem(MediaItem.Builder().setUri(it).setMediaMetadata(MediaMetadata.Builder().setTitle(it.lastPathSegment ?: "Dream Time").build()).build())
            player?.prepare()
            player?.seekTo(0)
            player?.setPlaybackSpeed(prefs.speed)
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val book = shelf.updatePdf(it)
            activeBook = book; books = shelf.all(); prefs.activeBookId = book.id
            prefs.pdfUri = it; prefs.page = 0; prefs.audioPosition = book.positionMs; page = 0
            book.audioUri?.let { audio -> player?.seekTo(0); player?.setPlaybackSpeed(prefs.speed); prefs.audioUri = audio }
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { captionUri ->
            runCatching { context.contentResolver.takePersistableUriPermission(captionUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val book = shelf.updateSubtitle(captionUri)
            activeBook = book; books = shelf.all(); prefs.activeBookId = book.id
            scope.launch {
                val pdf = book.pdfUri
                if (pdf == null) { message = "اول PDF این کتاب را انتخاب کن."; return@launch }
                message = "زیرنویس وارد شد؛ دارم صفحه‌ها را با متن تطبیق می‌دهم…"
                runCatching {
                    val cues = withContext(Dispatchers.IO) { TextSync.readCaptions(context, captionUri) }
                    val match = withContext(Dispatchers.IO) { TextSync.align(context, pdf, cues) }
                    captions = cues
                    match.anchors.forEach { (pageNumber, timeMs) -> prefs.saveAnchor(pageNumber, timeMs) }
                    message = if (match.anchors.isEmpty()) "زیرنویس خوانده شد، اما تطبیقی پیدا نشد؛ PDF شاید اسکن‌شده باشد یا متن روایت فرق کند. از نشانک دستی استفاده کن."
                        else "${match.anchors.size} صفحه از ${match.pdfPages} صفحه با زیرنویس هماهنگ شد."
                }.onFailure { message = "همگام‌سازی انجام نشد؛ فایل SRT یا VTT و PDF را بررسی کن." }
            }
        }
    }
    val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { target -> runCatching {
            val output = context.contentResolver.openOutputStream(target) ?: error("فایل ذخیره نشد")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(BackupSupport(context).export()) }
        }.onFailure { message = "پشتیبان‌گیری انجام نشد." }.onSuccess { message = "پشتیبان Dream Time ذخیره شد." } }
    }
    val backupReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { source -> runCatching {
            val data = context.contentResolver.openInputStream(source)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("فایل باز نشد")
            BackupSupport(context).restore(data)
            val store = BookShelf(context)
            val restored = store.active()
            activeBook = restored; books = store.all(); prefs.activeBookId = store.activeId
            prefs.pdfUri = restored?.pdfUri; prefs.audioUri = restored?.audioUri; prefs.page = restored?.page ?: 0
            prefs.audioPosition = restored?.positionMs ?: 0L; page = prefs.page; position = prefs.audioPosition
            darkTheme = prefs.darkTheme; paletteIndex = prefs.palette.coerceIn(palettes.indices)
            screenBrightness = prefs.screenBrightness; rotationLocked = prefs.rotationLocked
            sleepTimerEnd = prefs.sleepTimerEnd; speed = prefs.speed
            if (restored?.audioUri != null) {
                player?.setMediaItem(MediaItem.Builder().setUri(restored.audioUri).setMediaMetadata(MediaMetadata.Builder().setTitle(restored.title).build()).build())
                player?.prepare(); player?.seekTo(restored.positionMs); player?.setPlaybackSpeed(prefs.speed)
            } else { player?.pause(); player?.clearMediaItems() }
            selectedTab = 1; message = "پشتیبان بازیابی شد؛ برای فایل‌های کتاب ممکن است لازم باشد دوباره دسترسی بدهی."
        }.onFailure { message = "فایل پشتیبان معتبر نیست یا باز نمی‌شود." } }
    }

    fun selectBook(book: BookProject) {
        shelf.select(book); activeBook = book; books = shelf.all(); prefs.activeBookId = book.id
        prefs.pdfUri = book.pdfUri; prefs.audioUri = book.audioUri; prefs.page = book.page; prefs.audioPosition = book.positionMs
        page = book.page; position = book.positionMs
        if (book.audioUri != null) {
            player?.setMediaItem(MediaItem.Builder().setUri(book.audioUri).setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).build()).build())
            player?.prepare(); player?.seekTo(book.positionMs); player?.setPlaybackSpeed(prefs.speed)
        } else player?.clearMediaItems()
        selectedTab = 0
    }

    fun startNewBook(openPicker: Boolean = true) {
        val book = shelf.newBook()
        activeBook = book; books = shelf.all(); prefs.activeBookId = book.id
        prefs.pdfUri = null; prefs.audioUri = null; prefs.page = 0; prefs.audioPosition = 0L
        page = 0; position = 0L; player?.clearMediaItems(); selectedTab = 0
        if (openPicker) pdfPicker.launch(arrayOf("application/pdf"))
    }

    LaunchedEffect(player, activeBook?.id) {
        while (true) {
            player?.let {
                if (!scrubbing) position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.coerceAtLeast(0L)
                playing = it.isPlaying
                if (autoPage) it.currentPosition.let { pos -> prefs.pageAt(pos)?.let { anchoredPage ->
                    if (anchoredPage != page) { page = anchoredPage; prefs.page = anchoredPage }
                } }
            }
            val clockNow = System.currentTimeMillis()
            if (clockNow - lastProgressSave >= 2_000L) {
                prefs.page = page; prefs.audioPosition = position
                shelf.updateProgress(activeBook?.id, page, position)
                if (selectedTab == 1) books = shelf.all()
                lastProgressSave = clockNow
            }
            delay(500)
        }
    }

    LaunchedEffect(sleepTimerEnd) {
        while (sleepTimerEnd > 0L) {
            val remaining = sleepTimerEnd - System.currentTimeMillis()
            if (remaining <= 0L) {
                player?.pause(); sleepTimerEnd = 0L; prefs.sleepTimerEnd = 0L
                message = "تایمر خواب صدا را متوقف کرد."
            } else delay(minOf(remaining, 1_000L))
        }
    }
    LaunchedEffect(Unit) {
        while (true) { nowMillis = System.currentTimeMillis(); delay(1_000L) }
    }

    LaunchedEffect(screenBrightness) {
        (context as? ComponentActivity)?.window?.let { window ->
            val attributes = window.attributes
            attributes.screenBrightness = screenBrightness
            window.attributes = attributes
        }
    }

    LaunchedEffect(rotationLocked) {
        prefs.rotationLocked = rotationLocked
        (context as? ComponentActivity)?.requestedOrientation = if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    LaunchedEffect(prefs.pdfUri, page) {
        val uri = prefs.pdfUri ?: run { bitmap = null; pageCount = 0; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val safePage = page.coerceIn(0, renderer.pageCount - 1)
                        renderer.openPage(safePage).use { pdfPage ->
                            val image = Bitmap.createBitmap(pdfPage.width * 2, pdfPage.height * 2, Bitmap.Config.ARGB_8888)
                            pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image to renderer.pageCount
                        }
                    }
                }
            }.getOrNull()
        }
        if (loaded != null) { bitmap = loaded.first; pageCount = loaded.second }
        else message = "PDF باز نشد؛ فایل را دوباره انتخاب کن."
    }

    LaunchedEffect(activeBook?.id, activeBook?.subtitleUri) {
        captions = activeBook?.subtitleUri?.let { uri ->
            withContext(Dispatchers.IO) { runCatching { TextSync.readCaptions(context, uri) }.getOrDefault(emptyList()) }
        } ?: emptyList()
    }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(containerColor = background, topBar = {
                TopAppBar(title = { Column {
                    Text("Dream Time", fontWeight = FontWeight.Bold)
                    Text(activeBook?.title ?: "کتاب را ببین، صدایش را بشنو", fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = background))
            }, bottomBar = {
                NavigationBar(containerColor = surface) {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.MenuBook, null) }, label = { Text("مطالعه") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1; books = shelf.all() }, icon = { Icon(Icons.Default.Bookmarks, null) }, label = { Text("قفسهٔ کتاب") })
                }
            }) { padding ->
              if (selectedTab == 1) {
                ShelfPage(books = books, onOpen = ::selectBook, onNew = { startNewBook() }, onComplete = { book, done -> shelf.setComplete(book, done); books = shelf.all() }, onDelete = { book -> shelf.remove(book); books = shelf.all(); if (activeBook?.id == book.id) { activeBook = null; prefs.activeBookId = null; prefs.pdfUri = null; prefs.audioUri = null; player?.pause(); player?.clearMediaItems() } }, modifier = Modifier.padding(padding))
              } else {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(22.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.dream_time_icon), null, Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)))
                            Spacer(Modifier.width(13.dp))
                            Column {
                                Text("جای دنج تو برای کتاب‌خواندن", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("کتابت را ببین، صدایش را بشنو", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("انتخاب PDF")
                        }
                        OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/mpeg", "audio/mp4", "video/mp4", "audio/*", "video/*")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Headphones, null); Spacer(Modifier.width(6.dp)); Text("MP3 / MP4")
                        }
                    }
                    OutlinedButton(onClick = { subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Subtitles, null); Spacer(Modifier.width(7.dp)); Text("همگام‌سازی با زیرنویس SRT / VTT")
                    }
                    Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ظاهر اپ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = !darkTheme, onClick = { darkTheme = false; prefs.darkTheme = false }, label = { Text("روشن", fontSize = 11.sp) })
                                    FilterChip(selected = darkTheme, onClick = { darkTheme = true; prefs.darkTheme = true }, label = { Text("تاریک", fontSize = 11.sp) })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("رنگ محیط", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    palettes.forEachIndexed { index, option ->
                                        Surface(
                                            modifier = Modifier.size(30.dp).clickable { paletteIndex = index; prefs.palette = index },
                                            shape = RoundedCornerShape(50), color = option.primary,
                                            border = if (index == paletteIndex) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                                            shadowElevation = 1.dp
                                        ) {}
                                    }
                                }
                            }
                            Text(palette.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("قفل چرخش صفحه", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Switch(checked = rotationLocked, onCheckedChange = { rotationLocked = it })
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("نور صفحه", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(value = screenBrightness, onValueChange = { screenBrightness = it; prefs.screenBrightness = it }, valueRange = .2f..1f, modifier = Modifier.weight(1f))
                                Text("${(screenBrightness * 100).toInt()}٪", fontSize = 10.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { backupWriter.launch("Dream-Time-Backup.json") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(5.dp)); Text("پشتیبان‌گیری") }
                                OutlinedButton(onClick = { backupReader.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(5.dp)); Text("بازیابی") }
                            }
                            Text("فایل‌های کتاب کپی نمی‌شوند؛ پشتیبان شامل قفسه و تنظیمات است.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { zoom = (zoom - .2f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Icon(Icons.Default.ZoomOut, "کوچک‌نمایی") }
                                Text("${(zoom * 100).toInt()}٪", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                IconButton(onClick = { zoom = (zoom + .2f).coerceAtMost(2.6f) }) { Icon(Icons.Default.ZoomIn, "بزرگ‌نمایی") }
                            }
                            FilledTonalButton(onClick = { highlightMode = !highlightMode }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (highlightMode) palette.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (highlightMode) Color.White else MaterialTheme.colorScheme.onSurface)) {
                                Icon(Icons.Default.Highlight, null); Spacer(Modifier.width(5.dp)); Text(if (highlightMode) "هایلایت فعاله" else "هایلایت")
                            }
                          }
                          if (highlightMode) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                Text("رنگ ملایم", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                highlightColors.forEachIndexed { index, argb ->
                                    Surface(modifier = Modifier.size(25.dp).clickable { highlightColorIndex = index; prefs.highlightColor = index }, shape = RoundedCornerShape(50), color = Color(argb), border = if (index == highlightColorIndex) BorderStroke(2.dp, palette.primary) else BorderStroke(1.dp, Color(0xFFCECBC3))) {}
                                }
                                FilterChip(selected = eraseMode, onClick = { eraseMode = !eraseMode }, label = { Text(if (eraseMode) "پاک‌کن فعاله" else "پاک‌کن") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                                TextButton(onClick = { highlights = highlights.dropLast(1); prefs.saveHighlights(page, highlights) }, enabled = highlights.isNotEmpty()) { Text("واگرد") }
                                TextButton(onClick = { highlights = emptyList(); prefs.saveHighlights(page, emptyList()) }, enabled = highlights.isNotEmpty()) { Text("پاک‌کردن همه") }
                            }
                          }
                        }
                    }
                    Card(Modifier.fillMaxWidth().heightIn(min = 300.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(8.dp)) {
                            if (bitmap != null) {
                                val scaledWidth = maxWidth * zoom
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                  Box(Modifier.width(scaledWidth)) {
                                    Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxWidth())
                                    Canvas(Modifier.matchParentSize().pointerInput(highlightMode, eraseMode, page, scaledWidth, highlights, highlightColorIndex) {
                                        if (highlightMode) detectDragGestures(
                                            onDragStart = { dragStart = it; dragEnd = it },
                                            onDrag = { change, _ -> dragEnd = change.position },
                                            onDragEnd = {
                                                val start = dragStart; val end = dragEnd
                                                if (start != null && end != null && size.width > 0 && size.height > 0) {
                                                    val region = HighlightMark(
                                                        (minOf(start.x, end.x) / size.width).coerceIn(0f, 1f),
                                                        (minOf(start.y, end.y) / size.height).coerceIn(0f, 1f),
                                                        (maxOf(start.x, end.x) / size.width).coerceIn(0f, 1f),
                                                        (maxOf(start.y, end.y) / size.height).coerceIn(0f, 1f),
                                                        highlightColors[highlightColorIndex]
                                                    )
                                                    if (region.right - region.left > .01f && region.bottom - region.top > .003f) {
                                                      if (eraseMode) {
                                                        highlights = highlights.filterNot { mark -> mark.left < region.right && mark.right > region.left && mark.top < region.bottom && mark.bottom > region.top }
                                                      } else {
                                                        highlights = highlights + region
                                                      }
                                                      if (!eraseMode || highlights.size < prefs.highlights(page).size) {
                                                        prefs.saveHighlights(page, highlights)
                                                      }
                                                    }
                                                }
                                                dragStart = null; dragEnd = null
                                            }
                                        ) {}
                                    }) {
                                        highlights.forEach { mark ->
                                            drawRect(Color(mark.color), Offset(mark.left * size.width, mark.top * size.height), Size((mark.right - mark.left) * size.width, (mark.bottom - mark.top) * size.height))
                                        }
                                        val start = dragStart; val end = dragEnd
                                        if (start != null && end != null) {
                                            val left = minOf(start.x, end.x); val top = minOf(start.y, end.y)
                                            drawRect(Color(highlightColors[highlightColorIndex]), Offset(left, top), Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)))
                                            drawRect(palette.primary, Offset(left, top), Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)), style = Stroke(width = 2.dp.toPx()))
                                        }
                                    }
                                  }
                                }
                            }
                            else Text(if (prefs.pdfUri == null) "فایل PDF را انتخاب کن تا کتاب اینجا باز شود" else "در حال بازکردن صفحهٔ کتاب…", color = Color.Gray, modifier = Modifier.padding(40.dp))
                        }
                    }
                    if (highlightMode) Text(if (eraseMode) "برای پاک‌کردن، روی هایلایت موردنظر بکش." else "برای چند هایلایت، روی هر بخش جداگانه بکش.", Modifier.padding(top = 7.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (page > 0) { page--; prefs.page = page; prefs.saveAnchor(page, player?.currentPosition ?: position) } }) { Text("صفحهٔ قبل") }
                        Text("${page + 1} / ${if (pageCount > 0) pageCount else "—"}", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { if (pageCount > 0 && page < pageCount - 1) { page++; prefs.page = page; prefs.saveAnchor(page, player?.currentPosition ?: position) } }) { Text("صفحهٔ بعد") }
                    }
                    if (highlights.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("یادداشت‌های این صفحه", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                highlights.forEachIndexed { index, mark ->
                                    TextButton(onClick = { noteTargetIndex = index; noteDraft = mark.note; showNoteDialog = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                        Surface(Modifier.size(12.dp), shape = RoundedCornerShape(50), color = Color(mark.color)) {}
                                        Spacer(Modifier.width(8.dp))
                                        Text(mark.note.ifBlank { "افزودن یادداشت به هایلایت ${index + 1}" }, fontSize = 12.sp, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("کتاب صوتی", fontWeight = FontWeight.SemiBold)
                            captions.lastOrNull { position >= it.startMs && position < it.endMs }?.let { cue ->
                                Text(cue.text, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 3)
                            }
                            Slider(value = if (duration > 0) position.toFloat().coerceAtMost(duration.toFloat()) else 0f,
                                onValueChange = { scrubbing = true; position = it.toLong() },
                                onValueChangeFinished = { player?.seekTo(position); scrubbing = false },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(position)); Text(clock(duration)) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                IconButton(onClick = { player?.seekTo((position - 15_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "۱۵ ثانیه عقب") }
                                FilledIconButton(onClick = {
                                    if (player == null) message = "اول یک فایل صوتی انتخاب کن"
                                    else if (playing) player.pause() else player.play()
                                }, modifier = Modifier.size(58.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "پخش یا مکث") }
                                IconButton(onClick = { player?.seekTo(position + 15_000) }) { Icon(Icons.Default.Forward10, "۱۵ ثانیه جلو") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("سرعت", fontSize = 12.sp)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                                        FilterChip(selected = speed == rate, onClick = { speed = rate; prefs.speed = rate; player?.setPlaybackSpeed(rate) }, label = { Text("${rate}×", fontSize = 10.sp) })
                                    }
                                }
                            }
                            Box {
                                TextButton(onClick = { showSleepMenu = true }) {
                                    Icon(Icons.Default.Bedtime, null); Spacer(Modifier.width(5.dp))
                                    Text(if (sleepTimerEnd > nowMillis) "تایمر خواب · ${clock(sleepTimerEnd - nowMillis)}" else "تایمر خواب")
                                }
                                DropdownMenu(expanded = showSleepMenu, onDismissRequest = { showSleepMenu = false }) {
                                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                                        DropdownMenuItem(text = { Text("توقف پس از $minutes دقیقه") }, onClick = {
                                            sleepTimerEnd = System.currentTimeMillis() + minutes * 60_000L
                                            prefs.sleepTimerEnd = sleepTimerEnd; showSleepMenu = false
                                        })
                                    }
                                    if (sleepTimerEnd > nowMillis) DropdownMenuItem(text = { Text("لغو تایمر") }, onClick = { sleepTimerEnd = 0L; prefs.sleepTimerEnd = 0L; showSleepMenu = false })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoPage, onCheckedChange = { autoPage = it })
                        Text("تعویض خودکار صفحه با نشانک‌ها", fontSize = 12.sp)
                    }
                    Button(onClick = {
                        player?.let { prefs.saveAnchor(page, it.currentPosition); message = "نشانک صفحه ذخیره شد؛ صفحه با نشانک بعدی عوض می‌شود." }
                            ?: run { message = "اول فایل صوتی را انتخاب کن" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(7.dp)); Text("ثبت این صفحه در زمان فعلی صدا")
                    }
                    if (message.isNotBlank()) Text(message, Modifier.padding(8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text("صفحه و زمان پخش ذخیره می‌شوند. برای هر صفحه نشانک بگذار تا جابه‌جایی خودکار دقیق باشد.", Modifier.padding(vertical = 10.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
              if (showNoteDialog) AlertDialog(
                  onDismissRequest = { showNoteDialog = false },
                  title = { Text("یادداشت برای هایلایت") },
                  text = { OutlinedTextField(value = noteDraft, onValueChange = { noteDraft = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("چرا این بخش را نگه داشتی؟") }) },
                  confirmButton = { TextButton(onClick = {
                      val updated = highlights.toMutableList()
                      if (noteTargetIndex in updated.indices) updated[noteTargetIndex] = updated[noteTargetIndex].copy(note = noteDraft.trim())
                      highlights = updated; prefs.saveHighlights(page, updated); showNoteDialog = false
                  }) { Text("ذخیره") } },
                  dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("بعداً") } }
              )
            }
        }
    }
}

@Composable
private fun ShelfPage(
    books: List<BookProject>,
    onOpen: (BookProject) -> Unit,
    onNew: () -> Unit,
    onComplete: (BookProject, Boolean) -> Unit,
    onDelete: (BookProject) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var deleteTarget by remember { mutableStateOf<BookProject?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("قفسهٔ کتاب‌های تو", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${books.size} کتاب · ${books.count { it.complete }} تمام‌شده · ${clock(books.sumOf { it.listenedMs })} شنیده‌شده", color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
                FilledTonalButton(onClick = onNew) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(5.dp)); Text("کتاب تازه") }
            }
        }
        if (books.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AutoStories, null, Modifier.size(42.dp), tint = colors.primary)
                    Spacer(Modifier.height(10.dp)); Text("قفسه‌ات هنوز خالیه", fontWeight = FontWeight.SemiBold)
                    Text("کتاب اولت را اضافه کن تا همیشه آمادهٔ ادامه‌دادن باشد.", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp)); Button(onClick = onNew) { Text("افزودن کتاب") }
                }
            }
        } else {
            Text("در حال خواندن", Modifier.padding(top = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
            books.filterNot { it.complete }.forEach { book -> ShelfBookCard(book, onOpen, onComplete, { deleteTarget = book }) }
            books.filter { it.complete }.takeIf { it.isNotEmpty() }?.let { completed ->
                Text("تمام‌شده‌ها", Modifier.padding(top = 5.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                completed.forEach { book -> ShelfBookCard(book, onOpen, onComplete, { deleteTarget = book }) }
            }
        }
    }
    deleteTarget?.let { book ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("حذف از قفسه؟") }, text = { Text("«${book.title}» از Dream Time برداشته می‌شود. فایل اصلی پاک نمی‌شود.") },
            confirmButton = { TextButton(onClick = { onDelete(book); deleteTarget = null }) { Text("حذف از قفسه") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("لغو") } })
    }
}

@Composable
private fun ShelfBookCard(book: BookProject, onOpen: (BookProject) -> Unit, onComplete: (BookProject, Boolean) -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable { onOpen(book) }, colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(19.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.dream_time_icon), null, Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (book.pdfUri != null && book.audioUri != null) "PDF و کتاب صوتی" else if (book.pdfUri != null) "PDF · فایل صوتی اضافه نشده" else "فایل صوتی · PDF اضافه نشده", fontSize = 11.sp, color = colors.onSurfaceVariant)
                Text("صفحهٔ ${book.page + 1} · ادامه از ${clock(book.positionMs)}", fontSize = 11.sp, color = colors.primary)
                Text("${clock(book.listenedMs)} شنیده‌شده", fontSize = 10.sp, color = colors.onSurfaceVariant)
                TextButton(onClick = { onOpen(book) }, contentPadding = PaddingValues(0.dp)) { Text("ادامهٔ خواندن") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(checked = book.complete, onCheckedChange = { onComplete(book, it) })
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "حذف از قفسه", tint = colors.onSurfaceVariant) }
            }
        }
    }
}

private fun clock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
