package app.haul.ui.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import app.haul.ui.components.AudioTile
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Divider
import app.haul.ui.components.FilterChip
import app.haul.ui.components.HButton
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.IconButton44
import app.haul.ui.components.Radio
import app.haul.ui.components.SearchField
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.Tag
import app.haul.ui.components.Thumb
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.components.typeTag
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.ui.theme.JetBrainsMono
import app.haul.ui.theme.MoreDots
import app.haul.util.Pretty

/** Screens 14 (Library) and 15 (empty Library). The item actions sheet (18) is shown by the activity. */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    snack: SnackState,
    saveLabel: String,
    onPlay: (DownloadEntity) -> Unit,
    onGrab: () -> Unit,
) {
    val c = Haul.colors
    val all by vm.items.collectAsState()
    val list = all ?: return
    Box(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            LibraryEmpty(saveLabel, vm.importing, onGrab) {
                vm.importExisting { n ->
                    snack.show(if (n == 0) "No files to import in $saveLabel." else if (n == 1) "Imported 1 file." else "Imported $n files.")
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    T("Library", Haul.type.title, c.textHi, Modifier.weight(1f))
                    IconButton44(HaulIcon.Search, "Search", { vm.searchOpen = true })
                    IconButton44(HaulIcon.Sort, "Sort: ${vm.sort.label.lowercase()}", { vm.sortSheet = true })
                }
                if (vm.searchOpen) {
                    SearchField(
                        vm.query, { vm.query = it },
                        onClose = { vm.query = ""; vm.searchOpen = false },
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val nv = list.count { it.mediaType == MediaType.VIDEO }
                    FilterChip("All", vm.filter == null, { vm.filter = null }, count = "${list.size}", hPad = 12.dp)
                    FilterChip("Video", vm.filter == MediaType.VIDEO, { vm.filter = MediaType.VIDEO }, count = "$nv", hPad = 12.dp)
                    FilterChip("Audio", vm.filter == MediaType.AUDIO, { vm.filter = MediaType.AUDIO }, count = "${list.size - nv}", hPad = 12.dp)
                }
                val shown = vm.visible(list)
                T(
                    "${Pretty.bytes(shown.sumOf { it.fileSize })} · ${saveLabel.uppercase()}",
                    mono(11.sp, tracking = 0.04f), c.textLow,
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
                Divider()
                if (shown.isEmpty()) {
                    T(
                        if (vm.query.isNotBlank()) "Nothing matches “${vm.query.trim()}”." else "Nothing here yet.",
                        sans(14.sp), c.textLow, Modifier.padding(20.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    vm.groups(shown).forEach { group ->
                        if (group.label != null) {
                            item(key = "g-${group.label}") {
                                T(
                                    group.label, mono(11.sp, tracking = 0.08f), c.textLow,
                                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(group.items, key = { it.id }) { item ->
                            LibraryRow(item, onOpen = {
                                if (vm.exists(item)) onPlay(item) else showMissing(item, vm, snack)
                            }, onMore = { vm.actionsFor = item })
                        }
                    }
                }
            }
        }
        SortSheet(vm)
    }
}

fun showMissing(item: DownloadEntity, vm: LibraryViewModel, snack: SnackState) {
    snack.show("This file was moved or deleted.", "Remove") { vm.forget(item) }
}

@Composable
private fun LibraryRow(item: DownloadEntity, onOpen: () -> Unit, onMore: () -> Unit) {
    val c = Haul.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.mediaType == MediaType.AUDIO) {
            Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioTile(54.dp, url = item.thumbnailUrl)
                T(Pretty.duration(item.durationSec), mono(10.sp), c.textLow, maxLines = 1)
            }
        } else {
            Thumb(item.thumbnailUrl, 96.dp, 54.dp, durationSec = item.durationSec)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            T(item.title.ifBlank { item.fileName.orEmpty() }, sans(14.sp, FontWeight.SemiBold, 18.sp), c.textHi, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (t, k) = typeTag(item)
                Tag(t, k)
                T(listOf(item.formatLabel, Pretty.bytes(item.fileSize)).filter { it.isNotBlank() && it != "—" }.joinToString(" · "), mono(11.sp), c.textMid, maxLines = 1)
            }
            T(
                listOf(item.uploader, Pretty.libraryTime(item.finishedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                sans(12.sp), c.textLow, maxLines = 1,
            )
        }
        Box(
            Modifier.size(36.dp, 44.dp).clickable(onClick = onMore).semantics { contentDescription = "More actions" },
            contentAlignment = Alignment.Center,
        ) { MoreDots(c.textLow) }
    }
}

@Composable
private fun LibraryEmpty(saveLabel: String, importing: Boolean, onGrab: () -> Unit, onImport: () -> Unit) {
    val c = Haul.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Library", Haul.type.title, c.textHi)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Shelf()
            T("0 FILES · 0 MB", mono(11.sp, tracking = 0.08f), c.textLow)
            T("Nothing on the shelf yet.", Haul.type.display, c.textHi)
            Text(
                buildAnnotatedString {
                    append("Finished downloads land here, newest first. They're saved in ")
                    withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.textHi)) { append(saveLabel) }
                    append(", so your gallery and music apps can open them too.")
                },
                style = sans(15.sp, lineHeight = 22.sp),
                color = c.textMid,
            )
            Column {
                Spacer(Modifier.height(8.dp))
                HButton("Paste a link", onGrab, Modifier.fillMaxWidth(), icon = HaulIcon.Link)
                Spacer(Modifier.height(20.dp))
                HButton(
                    if (importing) "Looking for files…" else "Import files already in $saveLabel",
                    onImport, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, height = 44.dp,
                    textColor = c.textMid, enabled = !importing,
                )
            }
        }
    }
}

/** Four spines on a shelf, one dashed in signal: the empty-state illustration. */
@Composable
private fun Shelf() {
    val c = Haul.colors
    val shape = RoundedCornerShape(3.dp)
    Column(Modifier.width(180.dp)) {
        Row(
            Modifier.height(90.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(22.dp, 64.dp).border(1.5.dp, c.dashed, shape))
            Box(Modifier.size(22.dp, 80.dp).border(1.5.dp, c.dashed, shape))
            Box(
                Modifier.size(22.dp, 56.dp).drawBehind {
                    val w = 1.5.dp.toPx()
                    drawRoundRect(
                        c.signal,
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                },
            )
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(22.dp, 72.dp)
                    .graphicsLayer { transformOrigin = TransformOrigin(0f, 1f); rotationZ = 14f }
                    .border(1.5.dp, c.dashed, shape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).drawBehind { drawRect(c.line) })
    }
}

@Composable
private fun SortSheet(vm: LibraryViewModel) {
    val c = Haul.colors
    HaulSheet(vm.sortSheet, { vm.sortSheet = false }, horizontalPadding = 0.dp) {
        T("Sort by", Haul.type.headline, c.textHi, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
        LibrarySort.entries.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { vm.sort = s; vm.sortSheet = false }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Radio(vm.sort == s)
                T(s.label, sans(15.sp, FontWeight.Medium), c.textHi)
            }
        }
    }
}
