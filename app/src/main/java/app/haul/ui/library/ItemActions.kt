package app.haul.ui.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.HaulApp
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import app.haul.ui.components.AudioTile
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Divider
import app.haul.ui.components.HButton
import app.haul.ui.components.HaulDialog
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.Thumb
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty

/** Screen 18: actions for one file. */
@Composable
fun ItemActionsSheet(
    vm: LibraryViewModel,
    snack: SnackState,
    onPlay: (DownloadEntity) -> Unit,
    onRegrab: (String) -> Unit,
) {
    val c = Haul.colors
    val ctx = LocalContext.current
    // Keep the last item while the sheet animates out.
    var last by remember { mutableStateOf<DownloadEntity?>(null) }
    vm.actionsFor?.let { last = it }
    val item = last
    HaulSheet(vm.actionsFor != null, { vm.actionsFor = null }, horizontalPadding = 0.dp) {
        if (item == null) return@HaulSheet
        fun close() { vm.actionsFor = null }
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (item.mediaType == MediaType.AUDIO && item.thumbnailUrl == null) AudioTile(54.dp)
            else Thumb(item.thumbnailUrl, 96.dp, 54.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(item.title.ifBlank { item.fileName.orEmpty() }, sans(15.sp, FontWeight.SemiBold, 20.sp), c.textHi, maxLines = 2)
                T(
                    listOf(item.formatLabel, Pretty.bytes(item.fileSize)).filter { it.isNotBlank() && it != "—" }.joinToString(" · "),
                    mono(11.sp), c.textLow,
                )
            }
        }
        Divider(Modifier.padding(vertical = 8.dp))
        Action(HaulIcon.Play, "Play", filled = true) { close(); onPlay(item) }
        Action(HaulIcon.External, "Open with…") {
            close()
            val uri = HaulApp.graph(ctx).storage.shareUri(item)
            if (uri == null) snack.show("This file was moved or deleted.")
            else try {
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mimeType ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Open with",
                    ),
                )
            } catch (_: ActivityNotFoundException) { snack.show("No app on this phone can open this file.") }
        }
        Action(HaulIcon.Share, "Share file") {
            close()
            val uri = HaulApp.graph(ctx).storage.shareUri(item)
            if (uri == null) snack.show("This file was moved or deleted.")
            else ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(item.mimeType ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share file",
                ),
            )
        }
        if (item.url.isNotBlank()) {
            Action(HaulIcon.Copy, "Copy original link", hint = item.site.ifBlank { Pretty.shortUrl(item.url).substringBefore('/') }.uppercase()) {
                close()
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", item.url))
                snack.show("Link copied.")
            }
            Action(HaulIcon.Queue, "Save in another format") { close(); onRegrab(item.url) }
        }
        Action(HaulIcon.Rename, "Rename") { close(); vm.renaming = item }
        Action(HaulIcon.Delete, "Delete file", hint = "ASKS FIRST", color = c.err) { close(); vm.deleting = item }
    }
}

@Composable
private fun Action(
    icon: HaulIcon,
    label: String,
    hint: String = "",
    color: Color = Haul.colors.textHi,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HIcon(icon, color, size = 22.dp, filled = filled)
        T(label, sans(15.sp, FontWeight.Medium), color, Modifier.weight(1f))
        if (hint.isNotEmpty()) T(hint, mono(11.sp), Haul.colors.textLow, maxLines = 1)
    }
}

@Composable
fun RenameDialog(vm: LibraryViewModel, snack: SnackState) {
    val c = Haul.colors
    val item = vm.renaming
    HaulDialog(item != null, { vm.renaming = null }) {
        if (item == null) return@HaulDialog
        val start = item.fileName?.substringBeforeLast('.') ?: item.title
        var value by remember(item.id) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(item.id) { runCatching { focus.requestFocus() } }
        T("Rename file", Haul.type.headline, c.textHi)
        Spacer(Modifier.height(14.dp))
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = sans(15.sp).copy(color = c.textHi),
            cursorBrush = SolidColor(c.signal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeMd)
                .background(c.surface1, ShapeMd)
                .border(1.5.dp, c.signal, ShapeMd)
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .heightIn(min = 22.dp)
                .focusRequester(focus),
        )
        item.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.let {
            T("The .$it ending stays as it is.", sans(12.sp), c.textLow, Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Cancel", { vm.renaming = null }, Modifier.weight(1f), kind = ButtonKind.Secondary, height = 44.dp)
            HButton(
                "Rename",
                {
                    val name = value.text.trim()
                    vm.renaming = null
                    vm.rename(item, name) { ok -> if (!ok) snack.show("Couldn't rename this file.") }
                },
                Modifier.weight(1f),
                height = 44.dp,
                enabled = value.text.isNotBlank() && value.text.trim() != start,
            )
        }
    }
}

@Composable
fun DeleteDialog(vm: LibraryViewModel, snack: SnackState) {
    val c = Haul.colors
    val item = vm.deleting
    HaulDialog(item != null, { vm.deleting = null }) {
        if (item == null) return@HaulDialog
        T("Delete this file?", Haul.type.headline, c.textHi)
        Spacer(Modifier.height(8.dp))
        T(
            "“${item.title.ifBlank { item.fileName.orEmpty() }}” will be removed from your phone, not just from Haul. This can't be undone.",
            sans(14.sp, lineHeight = 20.sp), c.textMid,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Keep it", { vm.deleting = null }, Modifier.weight(1f), kind = ButtonKind.Secondary, height = 44.dp)
            HButton(
                "Delete",
                {
                    vm.deleting = null
                    vm.delete(item) { ok -> snack.show(if (ok) "File deleted." else "Removed from the Library. The file couldn't be deleted.") }
                },
                Modifier.weight(1f),
                kind = ButtonKind.Destructive,
                height = 44.dp,
            )
        }
    }
}
