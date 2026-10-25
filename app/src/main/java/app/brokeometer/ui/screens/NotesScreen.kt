package app.brokeometer.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.notes.NoteBackgrounds
import app.brokeometer.notes.NoteColors
import app.brokeometer.notes.NoteEntity
import app.brokeometer.notes.NoteFiles
import app.brokeometer.notes.NoteItem
import app.brokeometer.notes.NoteRules
import app.brokeometer.notes.NoteText
import app.brokeometer.notes.isBlank
import app.brokeometer.notes.itemList
import app.brokeometer.notes.recordings
import app.brokeometer.ui.NotesViewModel
import app.brokeometer.ui.components.ActionButton
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyStaggeredGridState
import sh.calvin.reorderable.rememberReorderableLazyStaggeredGridState
import kotlin.math.sin

/** Notes' pages, in order. */
internal val notesPageNames = listOf("Notes", "Bin", "Setup")

/** A note's colour on screen, or null for the card's usual colour. */
@Composable
private fun noteColor(key: String): Color? = NoteColors.argb(key, LocalBrand.current.isDark)?.let { Color(it) }

/** A note's background pattern, drawn faintly over its colour. */
@Composable
private fun NotePattern(key: String, modifier: Modifier) {
    if (key == NoteBackgrounds.NONE) return
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    androidx.compose.foundation.Canvas(modifier) {
        val step = 22.dp.toPx()
        val w = size.width
        val h = size.height
        when (key) {
            "DOTS" -> {
                var y = step / 2
                while (y < h) {
                    var x = step / 2
                    while (x < w) { drawCircle(ink, 2.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
            "GRID" -> {
                var x = 0f
                while (x < w) { drawLine(ink, Offset(x, 0f), Offset(x, h), 1.dp.toPx()); x += step }
                var y = 0f
                while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), 1.dp.toPx()); y += step }
            }
            "LINES" -> {
                var y = step * 2
                while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), 1.dp.toPx()); y += step }
            }
            "DIAGONAL" -> {
                var x = -h
                while (x < w) { drawLine(ink, Offset(x, h), Offset(x + h, 0f), 6.dp.toPx()); x += step * 1.5f }
            }
            "WAVES" -> {
                var y = step
                while (y < h + step) {
                    val p = Path()
                    p.moveTo(0f, y)
                    var x = 0f
                    while (x <= w) { p.lineTo(x, y + sin(x / step * 2.0).toFloat() * step / 4); x += 4f }
                    drawPath(p, ink, style = Stroke(1.5.dp.toPx()))
                    y += step
                }
            }
            "CIRCLES" -> {
                var y = 0f
                var row = 0
                while (y < h + step * 2) {
                    var x = if (row % 2 == 0) 0f else step * 1.5f
                    while (x < w + step * 2) { drawCircle(ink, step, Offset(x, y), style = Stroke(1.5.dp.toPx())); x += step * 3 }
                    y += step * 1.6f
                    row++
                }
            }
        }
    }
}

/** A note's own photo behind it, under a veil of the note's colour so the words stay easy to read. */
@Composable
private fun NotePhoto(path: String, veil: Color, side: Int, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path, side) {
        value = withContext(Dispatchers.IO) { NoteFiles.load(path, side)?.asImageBitmap() }
    }
    Box(modifier) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
        Box(Modifier.matchParentSize().background(veil.copy(alpha = 0.55f)))
    }
}

/** How big a heading is: in the editor, or smaller on a card. */
private fun headingSize(level: Int, onCard: Boolean): TextUnit = when (level) {
    1 -> if (onCard) 19.sp else 28.sp
    2 -> if (onCard) 17.sp else 23.sp
    3 -> if (onCard) 15.sp else 19.sp
    else -> TextUnit.Unspecified
}

/** A note's text with its look (marks hidden), for cards. */
@Composable
private fun styledText(text: String): AnnotatedString = remember(text) {
    buildAnnotatedString {
        NoteText.runs(text).forEach { r ->
            pushStyle(
                SpanStyle(
                    fontWeight = if (r.bold || r.heading > 0) FontWeight.Bold else null,
                    fontStyle = if (r.italic) FontStyle.Italic else null,
                    fontSize = headingSize(r.heading, onCard = true),
                ),
            )
            append(r.text)
            pop()
        }
    }
}

/** One of Notes' pages: the notes, the Bin, or Setup. */
@Composable
internal fun NotesBody(vm: NotesViewModel, page: Int, onOpen: (Long) -> Unit, onNew: (checklist: Boolean) -> Unit) {
    when (page) {
        0 -> NotesPage(vm, onOpen, onNew)
        1 -> BinPage(vm)
        else -> NotesSetupPage(vm)
    }
}

/**
 * The notes, like Google Keep: a search box, then the notes in two columns, pinned ones first. Tap one to
 * open it. Hold one for its menu, or hold and drag it to move it.
 */
@Composable
private fun NotesPage(vm: NotesViewModel, onOpen: (Long) -> Unit, onNew: (checklist: Boolean) -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val binned by vm.binned.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val brand = LocalBrand.current

    LaunchedEffect(binned) {
        val b = binned ?: return@LaunchedEffect
        vm.binnedShown()
        // Only straight after it happened, not when coming back here much later.
        if (System.currentTimeMillis() - b.at > 15_000) return@LaunchedEffect
        val r = snackbar.showSnackbar("Moved to the Bin", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.restore(b.note.id)
    }

    // The order on screen. A note being dragged moves here first; the order is saved when it's let go.
    var order by remember { mutableStateOf(notes) }
    var dragging by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(notes) { if (!dragging) order = notes }
    // Arranging works on the whole list, so not while searching (a hold opens the menu then).
    val canArrange = query.isBlank()

    val grid = rememberLazyStaggeredGridState()
    val arranging = rememberReorderableLazyStaggeredGridState(grid) { from, to ->
        val a = order.indexOfFirst { it.id == from.key }
        val b = order.indexOfFirst { it.id == to.key }
        // Pinned notes move among pinned ones, the rest among the rest.
        if (a >= 0 && b >= 0 && order[a].pinned == order[b].pinned) {
            order = order.toMutableList().apply { add(b, removeAt(a)) }
            moved = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    var menuFor by remember { mutableStateOf<Long?>(null) }
    // A hold under way, or just ended: the tap that ends it doesn't open the note.
    var holding by remember { mutableStateOf(false) }
    var heldAt by remember { mutableLongStateOf(0L) }
    var styling by remember { mutableStateOf<NoteEntity?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val n = styling ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            photoBusy = true
            val path = vm.savePhoto(uri)
            photoBusy = false
            if (path == null) {
                Toast.makeText(context, "Couldn't read that photo.", Toast.LENGTH_SHORT).show()
            } else {
                vm.setLook(n, n.color, NoteBackgrounds.NONE, path)
                styling = n.copy(background = NoteBackgrounds.NONE, image = path)
            }
        }
    }

    val pinned = order.filter { it.pinned }
    val others = order.filter { !it.pinned }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NotesSearch(query, onChange = { vm.search(it) })
            if (order.isEmpty()) {
                EmptyNote(
                    title = if (query.isBlank()) "No notes yet" else "Nothing found",
                    body = if (query.isBlank()) "Write one with the Note button. To bring in your Google Keep notes, go to Setup."
                    else "No note has \"${query.trim()}\" in it.",
                )
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    val card: @Composable LazyStaggeredGridItemScope.(NoteEntity) -> Unit = { n ->
                        ArrangeableNote(
                            state = arranging,
                            n = n,
                            canArrange = canArrange,
                            menuOpen = menuFor == n.id,
                            onClick = { if (!holding && System.currentTimeMillis() - heldAt > 400) onOpen(n.id) },
                            onHoldStart = {
                                holding = true
                                dragging = true
                                moved = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onHoldEnd = {
                                dragging = false
                                holding = false
                                heldAt = System.currentTimeMillis()
                                if (moved) vm.arrange(order.map { it.id }) else menuFor = n.id
                            },
                            onMenu = { menuFor = n.id },
                            onMenuClose = { menuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (n.pinned) "Unpin" else "Pin") },
                                leadingIcon = { Icon(if (n.pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    vm.setPinned(n, !n.pinned)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Colour and background") },
                                leadingIcon = { Icon(Icons.Rounded.Palette, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    styling = n
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Make a copy") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    vm.copy(n)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = brand.broke) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = brand.broke) },
                                onClick = {
                                    menuFor = null
                                    vm.delete(n)
                                },
                            )
                        }
                    }
                    if (pinned.isNotEmpty()) {
                        item("pinned-head", span = StaggeredGridItemSpan.FullLine) { GridHeading("Pinned") }
                        items(pinned, key = { it.id }) { n -> this.card(n) }
                        if (others.isNotEmpty()) item("others-head", span = StaggeredGridItemSpan.FullLine) { GridHeading("Others") }
                    }
                    items(others, key = { it.id }) { n -> this.card(n) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onNew(false) },
            containerColor = brand.action,
            contentColor = brand.onAction,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("Note") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
    }

    styling?.let { n ->
        LookSheet(
            color = n.color,
            background = n.background,
            image = n.image,
            busy = photoBusy,
            onColor = { c ->
                vm.setLook(n, c, n.background, n.image)
                styling = n.copy(color = c)
            },
            onBackground = { b ->
                vm.setLook(n, n.color, b, null)
                styling = n.copy(background = b, image = null)
            },
            onPhoto = { runCatching { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
            onDismiss = { styling = null },
        )
    }
}

/** The search box at the top of Notes: across titles, text and lists. */
@Composable
private fun NotesSearch(query: String, onChange: (String) -> Unit) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search your notes", color = muted, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(brand.action),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = muted, modifier = Modifier.clickable { onChange("") })
        }
    }
}

/**
 * A note in the grid that can be held and dragged to a new place. Held and let go without moving, its
 * menu opens. While arranging is off (searching), holding opens the menu straight away.
 */
@Composable
private fun LazyStaggeredGridItemScope.ArrangeableNote(
    state: ReorderableLazyStaggeredGridState,
    n: NoteEntity,
    canArrange: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onMenu: () -> Unit,
    onMenuClose: () -> Unit,
    menu: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ReorderableItem(state, key = n.id) { isDragging ->
        val lift by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "lift")
        Box(
            Modifier
                .shadow(lift, RoundedCornerShape(16.dp))
                .longPressDraggableHandle(
                    enabled = canArrange,
                    onDragStarted = { onHoldStart() },
                    onDragStopped = { onHoldEnd() },
                ),
        ) {
            NoteCard(n, onLongClick = if (canArrange) null else onMenu, onClick = onClick)
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose, content = menu)
        }
    }
}

@Composable
private fun GridHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteCard(n: NoteEntity, compact: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val lines = if (compact) 3 else 8
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = noteColor(n.color)
    val base = color ?: MaterialTheme.colorScheme.surfaceContainer
    val photo = n.image
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(base)
            .then(if (color == null && photo == null) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (photo != null) Modifier.heightIn(min = if (compact) 72.dp else 120.dp) else Modifier),
    ) {
        if (photo != null) NotePhoto(photo, base, 480, Modifier.matchParentSize()) else NotePattern(n.background, Modifier.matchParentSize())
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (n.title.isNotBlank()) {
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (n.checklist) {
                val items = n.itemList
                val open = items.filter { !it.checked }
                open.take(lines).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckBoxOutlineBlank, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val ticked = items.size - open.size
                if (open.size > lines) Text("…", style = MaterialTheme.typography.bodyMedium, color = muted)
                if (ticked > 0) Text("+ $ticked ticked", style = MaterialTheme.typography.bodySmall, color = muted)
            } else if (n.body.isNotBlank()) {
                Text(styledText(n.body), style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 4 else 12, overflow = TextOverflow.Ellipsis)
            }
            NoteRules.dayOf(n.remindDay)?.let { day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(app.brokeometer.tasks.TaskRules.whenText(day, n.remindTime, java.time.LocalDate.now()), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            val voices = n.recordings.size
            if (voices > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (voices == 1) "Voice note" else "$voices voice notes", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

/** The Bin: deleted notes wait here for a week. Tap one to put it back or delete it for good. */
@Composable
private fun BinPage(vm: NotesViewModel) {
    val all by vm.bin.collectAsStateWithLifecycle()
    val bin = remember(all) { all.filter { !it.isBlank } }
    var picked by remember { mutableStateOf<NoteEntity?>(null) }
    var askEmpty by remember { mutableStateOf(false) }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val now = remember(all) { System.currentTimeMillis() }
    if (bin.isEmpty()) {
        EmptyNote("The Bin is empty", "Deleted notes wait here for ${NoteRules.BIN_DAYS} days, then go for good.")
        return
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        item("head", span = StaggeredGridItemSpan.FullLine) {
            Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Notes here go for good after ${NoteRules.BIN_DAYS} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { askEmpty = true }) { Text("Empty the Bin", color = brand.broke) }
            }
        }
        items(bin, key = { it.id }) { n ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NoteCard(n) { picked = n }
                val left = NoteRules.daysLeft(n.deletedAt ?: now, now)
                Text(if (left == 1) "1 day left" else "$left days left", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    picked?.let { n ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(n.title.ifBlank { "This note" }) },
            text = { Text("Put it back with your notes, or delete it for good?") },
            confirmButton = {
                TextButton(onClick = {
                    picked = null
                    vm.restore(n.id)
                }) { Text("Put it back", color = brand.action) }
            },
            dismissButton = {
                TextButton(onClick = {
                    picked = null
                    vm.deleteForever(n.id)
                }) { Text("Delete for good", color = brand.broke) }
            },
        )
    }
    if (askEmpty) {
        AlertDialog(
            onDismissRequest = { askEmpty = false },
            title = { Text("Empty the Bin?") },
            text = { Text("${bin.size} ${if (bin.size == 1) "note goes" else "notes go"} for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    askEmpty = false
                    vm.emptyBin()
                }) { Text("Empty it", color = brand.broke) }
            },
            dismissButton = { TextButton(onClick = { askEmpty = false }) { Text("Cancel") } },
        )
    }
}

/** Notes' Setup: bringing notes in from Google Keep. */
@Composable
private fun NotesSetupPage(vm: NotesViewModel) {
    val importing by vm.importing.collectAsStateWithLifecycle()
    val imported by vm.imported.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importKeep(uri) }
    CardList {
        item("keep") {
            PageCard {
                CardHead(
                    "Bring in Google Keep notes",
                    "On takeout.google.com, untick everything, tick Keep, and export once as a .zip. Then pick that file here.",
                )
                Text(
                    "Titles, text, lists, ticks, colours and pins come across. Notes in Keep's bin or archive are left out, " +
                        "and importing again won't add the same note twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                if (importing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Bringing them in…", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                } else {
                    // Any file: phones and Drive label .zip files in different ways, and a wrong label made it greyed out.
                    // The file itself is checked when it's read.
                    ActionButton("Pick the .zip", onClick = { runCatching { pick.launch(arrayOf("*/*")) } }, height = 44.dp)
                }
                imported?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Colour, and a background: a pattern or your own photo. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LookSheet(
    color: String,
    background: String,
    image: String?,
    busy: Boolean,
    onColor: (String) -> Unit,
    onBackground: (String) -> Unit,
    onPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val base = noteColor(color) ?: MaterialTheme.colorScheme.background
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Colour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NoteColors.ALL.forEach { key ->
                    val c = noteColor(key)
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(c ?: MaterialTheme.colorScheme.background)
                            .border(if (key == color) 3.dp else 1.dp, if (key == color) brand.action else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onColor(key) },
                    )
                }
            }
            Text(NoteColors.NAMES[color].orEmpty(), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 20.dp))
            Text("Background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Your own photo first.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val on = image != null
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(base)
                            .border(if (on) 3.dp else 1.dp, if (on) brand.action else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy, onClick = onPhoto),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (image != null) NotePhoto(image, base, 200, Modifier.matchParentSize())
                        when {
                            busy -> CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            image == null -> Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = muted)
                        }
                    }
                    Text(if (image != null) "Change photo" else "Your photo", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                NoteBackgrounds.ALL.forEach { key ->
                    val on = image == null && key == background
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(base)
                                .border(if (on) 3.dp else 1.dp, if (on) brand.action else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .clickable { onBackground(key) },
                        ) { NotePattern(key, Modifier.matchParentSize()) }
                        Text(NoteBackgrounds.NAMES[key].orEmpty(), style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            }
        }
    }
}

/** One line of a checklist being edited; [key] stays the same while it's edited, for the list. */
private data class EditItem(val key: Long, val text: String, val checked: Boolean)

/** The marks in a note's text shown faintly, with bold, italics and headings shown as they look. */
private class MarkLook(private val faint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            for (s in NoteText.spans(text.text)) {
                val end = s.end.coerceAtMost(text.length)
                if (s.start >= end) continue
                val style = when (s.kind) {
                    NoteText.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    NoteText.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    NoteText.Kind.H1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(1, onCard = false))
                    NoteText.Kind.H2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(2, onCard = false))
                    NoteText.Kind.H3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(3, onCard = false))
                    NoteText.Kind.MARK -> SpanStyle(color = faint)
                }
                addStyle(style, s.start, end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * One note, open to write in. Changes save as you go. At the bottom: colour and background, checkboxes
 * on or off, a voice note, and bold, italics and headings for text notes; when it was last edited on the right.
 */
@Composable
fun NoteEditorScreen(id: Long, newChecklist: Boolean, onBack: () -> Unit, vm: NotesViewModel = viewModel()) {
    var loaded by remember { mutableStateOf<NoteEntity?>(null) }
    LaunchedEffect(Unit) {
        val n = if (id > 0) vm.load(id) else vm.load(vm.create(newChecklist))
        if (n == null) {
            onBack()
            return@LaunchedEffect
        }
        loaded = n
    }
    val n = loaded
    if (n == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    NoteEditor(n, vm, onBack)
}

@Composable
private fun NoteEditor(start: NoteEntity, vm: NotesViewModel, onBack: () -> Unit) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(start.title) }
    var body by remember { mutableStateOf(TextFieldValue(start.body)) }
    var checklist by remember { mutableStateOf(start.checklist) }
    var color by remember { mutableStateOf(start.color) }
    var background by remember { mutableStateOf(start.background) }
    var image by remember { mutableStateOf(start.image) }
    var pinned by remember { mutableStateOf(start.pinned) }
    val voices = remember { mutableStateListOf<String>().apply { addAll(start.recordings) } }
    var nextKey by remember { mutableLongStateOf(0L) }
    val items = remember { mutableStateListOf<EditItem>().apply { start.itemList.forEach { add(EditItem(nextKey++, it.text, it.checked)) } } }
    var styling by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var headings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // "Remind me": a day, and maybe a time; it shows in Tasks.
    var remindDay by remember { mutableStateOf(NoteRules.dayOf(start.remindDay)) }
    var remindTime by remember { mutableStateOf(start.remindTime) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<java.time.LocalDate?>(null) }

    fun current(): NoteEntity = start.copy(
        title = title,
        body = if (checklist) "" else body.text,
        items = if (checklist) NoteText.encodeItems(items.map { NoteItem(it.text, it.checked) }) else "",
        checklist = checklist,
        color = color,
        background = background,
        image = image,
        audio = voices.joinToString("\n").ifEmpty { null },
        pinned = pinned,
        remindDay = remindDay?.toString(),
        remindTime = remindTime.takeIf { remindDay != null },
    )

    // Saves a moment after each change, and when leaving.
    val snapshot = current()
    LaunchedEffect(snapshot) {
        delay(400)
        vm.save(snapshot)
    }
    DisposableEffect(Unit) {
        onDispose { vm.close(current(), start) }
    }
    BackHandler { onBack() }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            photoBusy = true
            val path = vm.savePhoto(uri)
            photoBusy = false
            if (path == null) {
                Toast.makeText(context, "Couldn't read that photo.", Toast.LENGTH_SHORT).show()
            } else {
                image = path
                background = NoteBackgrounds.NONE
            }
        }
    }

    // A voice note: the phone's voice typing writes the words in, and hands back the recording when it can.
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
        if (words.isNotEmpty()) {
            if (checklist) {
                items.add(items.indexOfLast { !it.checked } + 1, EditItem(nextKey++, words, false))
            } else {
                val t = body.text
                val at = body.selection.max.coerceIn(0, t.length)
                val before = t.substring(0, at)
                val gap = if (before.isEmpty() || before.endsWith(" ") || before.endsWith("\n")) "" else " "
                body = TextFieldValue(before + gap + words + t.substring(at), TextRange(at + gap.length + words.length))
            }
        }
        val audio = data.data
        if (audio == null) {
            Toast.makeText(context, "Your phone's voice typing didn't hand over the recording, so only the words were kept.", Toast.LENGTH_LONG).show()
        } else {
            scope.launch {
                val path = vm.saveRecording(audio)
                if (path != null) voices.add(path) else Toast.makeText(context, "Couldn't keep the recording, only the words.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val noteBg = noteColor(color) ?: MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(noteBg)) {
        val photo = image
        if (photo != null) NotePhoto(photo, noteBg, 1600, Modifier.matchParentSize()) else NotePattern(background, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { pickingDay = true }) {
                    Icon(
                        if (remindDay != null) Icons.Rounded.NotificationsActive else Icons.Outlined.NotificationAdd,
                        contentDescription = "Remind me",
                        tint = if (remindDay != null) brand.action else ink,
                    )
                }
                IconButton(onClick = { pinned = !pinned }) {
                    Icon(if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, contentDescription = if (pinned) "Unpin" else "Pin", tint = if (pinned) brand.action else ink)
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete note") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Box {
                    if (title.isEmpty()) Text("Title", style = MaterialTheme.typography.headlineSmall, color = muted)
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it.replace("\n", " ") },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(color = ink),
                        cursorBrush = SolidColor(brand.action),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                remindDay?.let { day ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                            .clickable { pickingDay = true }
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = brand.action, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            app.brokeometer.tasks.TaskRules.whenText(day, remindTime, java.time.LocalDate.now()),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        IconButton(onClick = {
                            remindDay = null
                            remindTime = null
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "No reminder", tint = muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (voices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    VoiceNotes(voices, onRemove = { voices.remove(it) })
                }
                Spacer(Modifier.height(12.dp))
                if (!checklist) {
                    val look = remember(muted) { MarkLook(muted.copy(alpha = 0.5f)) }
                    Box {
                        if (body.text.isEmpty()) Text("Note", style = MaterialTheme.typography.bodyLarge, color = muted)
                        BasicTextField(
                            value = body,
                            onValueChange = { body = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                            cursorBrush = SolidColor(brand.action),
                            visualTransformation = look,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp),
                        )
                    }
                } else {
                    ChecklistEditor(items, newKey = { nextKey++ })
                }
            }
            // The bottom bar: the tools on the left, when it was edited on the right.
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { styling = true }) { Icon(Icons.Rounded.Palette, contentDescription = "Colour and background") }
                    IconButton(onClick = {
                        if (checklist) {
                            body = TextFieldValue(NoteText.fromItems(items.map { NoteItem(it.text, it.checked) }))
                            items.clear()
                        } else {
                            items.clear()
                            NoteText.toItems(body.text).forEach { items.add(EditItem(nextKey++, it.text, it.checked)) }
                        }
                        checklist = !checklist
                    }) {
                        Icon(if (checklist) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, contentDescription = if (checklist) "Hide checkboxes" else "Show checkboxes")
                    }
                    IconButton(onClick = {
                        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your note")
                            // Asks for the recording too. Google's voice typing gives it; some others don't.
                            .putExtra("android.speech.extra.GET_AUDIO_FORMAT", "audio/AMR")
                            .putExtra("android.speech.extra.GET_AUDIO", true)
                        runCatching { listen.launch(ask) }.onFailure {
                            Toast.makeText(context, "This phone has no voice typing to use.", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Rounded.Mic, contentDescription = "Voice note") }
                    if (!checklist) {
                        IconButton(onClick = {
                            val (t, sel) = NoteText.wrap(body.text, body.selection.min, body.selection.max, "**")
                            body = TextFieldValue(t, TextRange(sel.first, sel.last))
                        }) { Icon(Icons.Rounded.FormatBold, contentDescription = "Bold") }
                        IconButton(onClick = {
                            val (t, sel) = NoteText.wrap(body.text, body.selection.min, body.selection.max, "*")
                            body = TextFieldValue(t, TextRange(sel.first, sel.last))
                        }) { Icon(Icons.Rounded.FormatItalic, contentDescription = "Italic") }
                        Box {
                            IconButton(onClick = { headings = true }) { Icon(Icons.Rounded.Title, contentDescription = "Headings") }
                            DropdownMenu(expanded = headings, onDismissRequest = { headings = false }) {
                                val now = NoteText.headingAt(body.text, body.selection.start)
                                listOf(1 to "Heading 1", 2 to "Heading 2", 3 to "Heading 3", 0 to "Normal text").forEach { (level, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                fontSize = if (level == 0) 16.sp else headingSize(level, onCard = true),
                                                fontWeight = if (level == 0) null else FontWeight.Bold,
                                            )
                                        },
                                        trailingIcon = { if (level == now) Icon(Icons.Rounded.Check, contentDescription = null, tint = brand.action) },
                                        onClick = {
                                            headings = false
                                            if (level != now) {
                                                val (t, c) = NoteText.setHeading(body.text, body.selection.start, level)
                                                body = TextFieldValue(t, TextRange(c))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Edited ${Dates.clock(start.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (styling) {
        LookSheet(
            color = color,
            background = background,
            image = image,
            busy = photoBusy,
            onColor = { color = it },
            onBackground = {
                background = it
                image = null
            },
            onPhoto = { runCatching { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
            onDismiss = { styling = false },
        )
    }

    if (pickingDay) {
        val utc = java.time.ZoneOffset.UTC
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (remindDay ?: java.time.LocalDate.now().plusDays(1)).atStartOfDay(utc).toInstant().toEpochMilli(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        pickingDay = false
                        state.selectedDateMillis?.let { pickingTime = java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate() }
                    },
                ) { Text("Next", color = brand.action) }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DatePicker(state = state, title = { Text("Remind me on", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = false)
        }
    }
    pickingTime?.let { day ->
        val t = androidx.compose.material3.rememberTimePickerState(initialHour = (remindTime ?: 9 * 60) / 60, initialMinute = (remindTime ?: 9 * 60) % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text("At a time?") },
            text = { androidx.compose.material3.TimePicker(state = t) },
            confirmButton = {
                TextButton(onClick = {
                    remindDay = day
                    remindTime = t.hour * 60 + t.minute
                    pickingTime = null
                }) { Text("Set time", color = brand.action) }
            },
            dismissButton = {
                TextButton(onClick = {
                    remindDay = day
                    remindTime = null
                    pickingTime = null
                }) { Text("No time") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It waits in the Bin for ${NoteRules.BIN_DAYS} days, so you can put it back from there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(current())
                    onBack()
                }) { Text("Delete", color = brand.broke) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A note's voice recordings, each to play or take out. */
@Composable
private fun VoiceNotes(paths: List<String>, onRemove: (String) -> Unit) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var playing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) {
        onDispose {
            player[0]?.release()
            player[0] = null
        }
    }
    fun stop() {
        player[0]?.release()
        player[0] = null
        playing = null
    }
    fun play(path: String) {
        val again = playing == path
        stop()
        if (again) return
        val mp = runCatching { MediaPlayer().apply { setDataSource(path); prepare(); start() } }.getOrNull() ?: return
        mp.setOnCompletionListener { done ->
            if (player[0] === done) {
                done.release()
                player[0] = null
                playing = null
            }
        }
        player[0] = mp
        playing = path
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEachIndexed { i, path ->
            val length by produceState(0L, path) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(path)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            r.release()
                        }
                    }.getOrDefault(0L)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { play(path) }) {
                    Icon(
                        if (playing == path) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing == path) "Stop" else "Play",
                        tint = brand.action,
                    )
                }
                Text(if (paths.size == 1) "Voice note" else "Voice note ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (length > 0) {
                    val secs = (length + 500) / 1000
                    Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                IconButton(onClick = { removing = path }) { Icon(Icons.Rounded.Close, contentDescription = "Remove voice note", tint = muted, modifier = Modifier.size(18.dp)) }
            }
        }
    }
    removing?.let { path ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this voice note?") },
            text = { Text("The recording goes. The words it wrote stay in the note.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playing == path) stop()
                    removing = null
                    onRemove(path)
                }) { Text("Remove", color = brand.broke) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * A checklist being written: items still to do, then the ticked ones underneath, struck through.
 * A new line in an item starts the next item.
 */
@Composable
private fun ChecklistEditor(items: androidx.compose.runtime.snapshots.SnapshotStateList<EditItem>, newKey: () -> Long) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    var showTicked by remember { mutableStateOf(true) }

    @Composable
    fun ItemRow(item: EditItem) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { on ->
                    val i = items.indexOfFirst { it.key == item.key }
                    if (i >= 0) items[i] = item.copy(checked = on)
                },
                colors = CheckboxDefaults.colors(checkedColor = brand.action, checkmarkColor = brand.onAction),
            )
            BasicTextField(
                value = item.text,
                onValueChange = { v ->
                    val i = items.indexOfFirst { it.key == item.key }
                    if (i < 0) return@BasicTextField
                    if (v.contains('\n')) {
                        val parts = v.split('\n')
                        items[i] = item.copy(text = parts.first())
                        parts.drop(1).forEachIndexed { j, p -> items.add(i + 1 + j, EditItem(newKey(), p, false)) }
                    } else {
                        items[i] = item.copy(text = v)
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (item.checked) muted else ink,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                ),
                cursorBrush = SolidColor(brand.action),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { items.removeAll { it.key == item.key } }) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove item", tint = muted, modifier = Modifier.size(18.dp))
            }
        }
    }

    Column {
        items.filter { !it.checked }.forEach { item -> androidx.compose.runtime.key(item.key) { ItemRow(item) } }
        Row(
            Modifier.fillMaxWidth().clickable { items.add(items.indexOfLast { !it.checked } + 1, EditItem(newKey(), "", false)) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Add, contentDescription = null, tint = muted)
            Spacer(Modifier.width(12.dp))
            Text("List item", style = MaterialTheme.typography.bodyLarge, color = muted)
        }
        val ticked = items.filter { it.checked }
        if (ticked.isNotEmpty()) {
            Text(
                "${ticked.size} ticked ${if (ticked.size == 1) "item" else "items"}" + if (showTicked) "" else ", hidden",
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                modifier = Modifier.clickable { showTicked = !showTicked }.padding(vertical = 10.dp, horizontal = 12.dp),
            )
            if (showTicked) ticked.forEach { item -> androidx.compose.runtime.key(item.key) { ItemRow(item) } }
        }
        Spacer(Modifier.height(40.dp))
    }
}
