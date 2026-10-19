package app.brokeometer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brokeometer.notes.NoteBackgrounds
import app.brokeometer.notes.NoteColors
import app.brokeometer.notes.NoteEntity
import app.brokeometer.notes.NoteItem
import app.brokeometer.notes.NoteText
import app.brokeometer.notes.isBlank
import app.brokeometer.notes.itemList
import app.brokeometer.ui.NotesViewModel
import app.brokeometer.ui.components.BrokeTopBar
import app.brokeometer.ui.components.EmptyNote
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.util.Dates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

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

/** A note's text with its look (marks hidden), for cards. */
@Composable
private fun styledText(text: String, headingSize: Float = 18f): AnnotatedString = remember(text) {
    buildAnnotatedString {
        NoteText.runs(text).forEach { r ->
            val style = SpanStyle(
                fontWeight = if (r.bold || r.heading) FontWeight.Bold else null,
                fontStyle = if (r.italic) FontStyle.Italic else null,
                fontSize = if (r.heading) headingSize.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            )
            pushStyle(style)
            append(r.text)
            pop()
        }
    }
}

/**
 * Notes, like Google Keep: a search box, then the notes in two columns, pinned ones first. Tap one to
 * open it; the buttons at the bottom start a note or a list.
 */
@Composable
fun NotesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: (checklist: Boolean) -> Unit, vm: NotesViewModel = viewModel()) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askImport by remember { mutableStateOf(false) }
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importKeep(uri) }
    LaunchedEffect(vm) {
        vm.done.collect { d ->
            scope.launch {
                val r = snackbar.showSnackbar(d.message, actionLabel = if (d.undo != null) "Undo" else null, duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) d.undo?.invoke()
            }
        }
    }
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) {
        val d = deleted ?: return@LaunchedEffect
        vm.deletedShown()
        val r = snackbar.showSnackbar("Note deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.restore(d)
    }
    val pinned = notes.filter { it.pinned }
    val others = notes.filter { !it.pinned }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrokeTopBar("Notes", onBack = onBack) {
                if (importing) {
                    CircularProgressIndicator(color = brand.action, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(22.dp))
                } else {
                    TextButton(onClick = { askImport = true }) { Text("Import", color = brand.action) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { onNew(true) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Rounded.CheckBox, contentDescription = "New list")
                }
                ExtendedFloatingActionButton(
                    onClick = { onNew(false) },
                    containerColor = brand.action,
                    contentColor = brand.onAction,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Note") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search, as in Keep: across titles, text and lists.
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
                        onValueChange = { vm.search(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(brand.action),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = muted, modifier = Modifier.clickable { vm.search("") })
                }
            }
            if (notes.isEmpty()) {
                EmptyNote(
                    title = if (query.isBlank()) "No notes yet" else "Nothing found",
                    body = if (query.isBlank()) "Write a note or a list with the buttons below, or bring your notes in from Google Keep with Import."
                    else "No note has \"${query.trim()}\" in it.",
                )
                return@Column
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                if (pinned.isNotEmpty()) {
                    item("pinned-head", span = StaggeredGridItemSpan.FullLine) { GridHeading("Pinned") }
                    items(pinned, key = { it.id }) { n -> NoteCard(n) { onOpen(n.id) } }
                    if (others.isNotEmpty()) item("others-head", span = StaggeredGridItemSpan.FullLine) { GridHeading("Others") }
                }
                items(others, key = { it.id }) { n -> NoteCard(n) { onOpen(n.id) } }
            }
        }
    }

    if (askImport) {
        AlertDialog(
            onDismissRequest = { askImport = false },
            title = { Text("Bring in Google Keep notes") },
            text = {
                Text(
                    "On takeout.google.com, untick everything, tick Keep, and export once as a .zip. Then pick that file here. " +
                        "Titles, text, lists, ticks, colours and pins come across. Notes in Keep's bin or archive are left out, " +
                        "and importing again won't add the same note twice."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askImport = false
                    // Any file: phones and Drive label .zip files in different ways, and a wrong label made it greyed out.
                    // The file itself is checked when it's read.
                    runCatching { pick.launch(arrayOf("*/*")) }
                }) { Text("Pick the .zip", color = brand.action) }
            },
            dismissButton = { TextButton(onClick = { askImport = false }) { Text("Cancel") } },
        )
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

@Composable
private fun NoteCard(n: NoteEntity, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = noteColor(n.color)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color ?: MaterialTheme.colorScheme.surfaceContainer)
            .then(if (color == null) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        NotePattern(n.background, Modifier.matchParentSize())
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (n.title.isNotBlank()) {
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (n.checklist) {
                val items = n.itemList
                val open = items.filter { !it.checked }
                open.take(8).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckBoxOutlineBlank, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val ticked = items.size - open.size
                if (open.size > 8) Text("…", style = MaterialTheme.typography.bodyMedium, color = muted)
                if (ticked > 0) Text("+ $ticked ticked", style = MaterialTheme.typography.bodySmall, color = muted)
            } else if (n.body.isNotBlank()) {
                Text(styledText(n.body, 16f), style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
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
                    NoteText.Kind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
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
 * on or off, and bold, italics and heading for text notes.
 */
@Composable
fun NoteEditorScreen(id: Long, newChecklist: Boolean, onBack: () -> Unit, vm: NotesViewModel = viewModel()) {
    var noteId by remember { mutableLongStateOf(id) }
    var loaded by remember { mutableStateOf<NoteEntity?>(null) }
    LaunchedEffect(Unit) {
        val n = if (id > 0) vm.load(id) else vm.load(vm.create(newChecklist))
        if (n == null) {
            onBack()
            return@LaunchedEffect
        }
        noteId = n.id
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
    var title by remember { mutableStateOf(start.title) }
    var body by remember { mutableStateOf(TextFieldValue(start.body)) }
    var checklist by remember { mutableStateOf(start.checklist) }
    var color by remember { mutableStateOf(start.color) }
    var background by remember { mutableStateOf(start.background) }
    var pinned by remember { mutableStateOf(start.pinned) }
    var nextKey by remember { mutableLongStateOf(0L) }
    val items = remember { mutableStateListOf<EditItem>().apply { start.itemList.forEach { add(EditItem(nextKey++, it.text, it.checked)) } } }
    var styling by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun current(): NoteEntity = start.copy(
        title = title,
        body = if (checklist) "" else body.text,
        items = if (checklist) NoteText.encodeItems(items.map { NoteItem(it.text, it.checked) }) else "",
        checklist = checklist,
        color = color,
        background = background,
        pinned = pinned,
    )

    // Saves a moment after each change, and when leaving.
    val snapshot = current()
    LaunchedEffect(snapshot) {
        delay(400)
        vm.save(snapshot)
    }
    DisposableEffect(Unit) {
        onDispose { vm.close(current()) }
    }
    BackHandler { onBack() }

    val noteBg = noteColor(color) ?: MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(noteBg)) {
        NotePattern(background, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.weight(1f))
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
            // The bottom bar.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                if (!checklist) {
                    IconButton(onClick = {
                        val (t, sel) = NoteText.wrap(body.text, body.selection.min, body.selection.max, "**")
                        body = TextFieldValue(t, TextRange(sel.first, sel.last))
                    }) { Icon(Icons.Rounded.FormatBold, contentDescription = "Bold") }
                    IconButton(onClick = {
                        val (t, sel) = NoteText.wrap(body.text, body.selection.min, body.selection.max, "*")
                        body = TextFieldValue(t, TextRange(sel.first, sel.last))
                    }) { Icon(Icons.Rounded.FormatItalic, contentDescription = "Italic") }
                    IconButton(onClick = {
                        val (t, c) = NoteText.toggleHeading(body.text, body.selection.start)
                        body = TextFieldValue(t, TextRange(c))
                    }) { Icon(Icons.Rounded.Title, contentDescription = "Heading") }
                }
                Spacer(Modifier.width(12.dp))
                Text("Edited ${Dates.clock(start.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }

    if (styling) {
        ModalBottomSheet(onDismissRequest = { styling = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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
                                .clickable { color = key },
                        )
                    }
                }
                Text(NoteColors.NAMES[color].orEmpty(), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 20.dp))
                Text("Background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NoteBackgrounds.ALL.forEach { key ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(noteColor(color) ?: MaterialTheme.colorScheme.background)
                                    .border(if (key == background) 3.dp else 1.dp, if (key == background) brand.action else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { background = key },
                            ) { NotePattern(key, Modifier.matchParentSize()) }
                            Text(NoteBackgrounds.NAMES[key].orEmpty(), style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("You can undo it for a few seconds from the Notes page.") },
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
