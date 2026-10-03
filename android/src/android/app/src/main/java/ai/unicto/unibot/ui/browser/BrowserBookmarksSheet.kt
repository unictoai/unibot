package ai.unicto.unibot.ui.browser

import ai.unicto.unibot.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.theme.staggeredEntrance

/**
 * Bookmark manager: folder filter chips, bookmark list, and folder
 * create/rename/delete. Tapping a bookmark navigates the current tab.
 * Everything is stored on-device by [BrowserBookmarkStore].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserBookmarksSheet(
    store: BrowserBookmarkStore,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var version by remember { mutableStateOf(0) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BrowserBookmarkFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<BrowserBookmarkFolder?>(null) }

    val folders = remember(version) { store.getFolders() }
    val bookmarks = remember(version, selectedFolderId) { store.getBookmarks(selectedFolderId) }
    val selectedFolder = folders.firstOrNull { it.id == selectedFolderId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.browser_bookmarks_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                UnibotTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.browser_bookmarks_done))
                }
            }

            // Folder chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    FolderChip(
                        label = stringResource(R.string.browser_bookmarks_all),
                        selected = selectedFolderId == null,
                        onClick = { selectedFolderId = null },
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    FolderChip(
                        label = folder.name,
                        selected = selectedFolderId == folder.id,
                        onClick = { selectedFolderId = folder.id },
                    )
                }
                item {
                    FolderChip(
                        label = stringResource(R.string.browser_bookmarks_new_folder),
                        selected = false,
                        leadingIcon = {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        onClick = { showNewFolder = true },
                    )
                }
            }

            // Selected-folder actions
            if (selectedFolder != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        selectedFolder.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { renameTarget = selectedFolder },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.browser_bookmarks_rename),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { deleteTarget = selectedFolder },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.browser_bookmarks_delete_folder),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.browser_bookmarks_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.browser_bookmarks_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(bookmarks, key = { _, b -> b.id }) { index, bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(bookmark.url) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .staggeredEntrance(index),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    bookmark.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    bookmark.url,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = {
                                    store.removeBookmark(bookmark.id)
                                    version++
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.browser_bookmarks_remove),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        FolderNameDialog(
            title = stringResource(R.string.browser_bookmarks_new_folder),
            initial = "",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                store.addFolder(name)
                version++
                showNewFolder = false
            },
        )
    }

    renameTarget?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.browser_bookmarks_rename_folder),
            initial = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                store.renameFolder(folder.id, name)
                version++
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.browser_bookmarks_delete_folder)) },
            text = { Text(stringResource(R.string.browser_bookmarks_delete_folder_confirm, folder.name)) },
            confirmButton = {
                UnibotTextButton(onClick = {
                    store.deleteFolder(folder.id)
                    selectedFolderId = null
                    version++
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.browser_bookmarks_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                UnibotTextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.invoke()
        if (leadingIcon != null) Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.browser_bookmarks_folder_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            UnibotTextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
            ) { Text(stringResource(R.string.browser_bookmark_save)) }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * "Save bookmark" dialog shown from the address-bar star: editable title
 * plus a folder picker (or no folder). [onSaved] fires after the bookmark
 * lands in [store].
 */
@Composable
fun BrowserSaveBookmarkDialog(
    store: BrowserBookmarkStore,
    url: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle.ifBlank { url }) }
    val folders = remember { store.getFolders() }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_bookmark_save_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(url.take(48)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.browser_bookmarks_move_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FolderChip(
                            label = stringResource(R.string.browser_bookmarks_no_folder),
                            selected = selectedFolderId == null,
                            onClick = { selectedFolderId = null },
                        )
                    }
                    items(folders, key = { it.id }) { folder ->
                        FolderChip(
                            label = folder.name,
                            selected = selectedFolderId == folder.id,
                            onClick = { selectedFolderId = folder.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            UnibotTextButton(onClick = {
                store.addBookmark(url, title.trim().ifBlank { url }, selectedFolderId)
                onSaved()
            }) { Text(stringResource(R.string.browser_bookmark_save)) }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
