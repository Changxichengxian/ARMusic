package com.lalilu.lmusic.compose.new_screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.RemixIcon
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.rememberFixedStatusBarHeightDp
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.remixicon.Design
import com.lalilu.remixicon.design.editBoxFill
import org.koin.compose.koinInject

object WishlistScreen : Screen, ScreenInfoFactory {
    private fun readResolve(): Any = WishlistScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { "\u613f\u671b\u5355" },
            icon = RemixIcon.Design.editBoxFill,
        )
    }

    @Composable
    override fun Content() {
        WishlistContent()
    }
}

@Composable
private fun WishlistContent(
    settingsSp: SettingsSp = koinInject(),
) {
    val context = LocalContext.current
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var pendingExportText by rememberSaveable { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<EditingMemoItem?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(pendingExportText)
            }
        }.onSuccess {
            ToastUtils.showShort("\u5df2\u5bfc\u51fa")
        }.onFailure {
            ToastUtils.showShort("\u5bfc\u51fa\u5931\u8d25")
        }
    }

    val categories = listOf(
        MemoCategory(
            title = "\u51c6\u5907\u542c",
            itemsState = settingsSp.wishlistItems,
            legacyTextState = settingsSp.wishlistText,
            placeholder = "\u60f3\u627e\u7684\u6b4c\u3001\u7248\u672c\u3001\u89c6\u9891",
            color = Color(0xFF2F7D73),
        ),
        MemoCategory(
            title = "\u52a8\u6f2b",
            itemsState = settingsSp.wishlistAnimeItems,
            legacyTextState = settingsSp.wishlistAnimeText,
            placeholder = "\u60f3\u8865\u7684\u52a8\u753b\u3001\u5267\u573a\u7248\u3001\u7247\u5355",
            color = Color(0xFFD05A4E),
        ),
        MemoCategory(
            title = "\u6f2b\u753b",
            itemsState = settingsSp.wishlistMangaItems,
            legacyTextState = settingsSp.wishlistMangaText,
            placeholder = "\u60f3\u770b\u7684\u6f2b\u753b\u3001\u770b\u5230\u7b2c\u51e0\u8bdd",
            color = Color(0xFF7666B0),
        ),
        MemoCategory(
            title = "\u5c0f\u8bf4",
            itemsState = settingsSp.wishlistNovelItems,
            legacyTextState = settingsSp.wishlistNovelText,
            placeholder = "\u60f3\u770b\u7684\u5c0f\u8bf4\u3001\u5377\u6570\u3001\u8fdb\u5ea6",
            color = Color(0xFFB97825),
        ),
    )

    LaunchedEffect(Unit) {
        if (!settingsSp.wishlistItemsMigrated.value) {
            categories.forEach { category ->
                if (category.itemsState.value.isEmpty()) {
                    val migrated = parseLegacyMemoItems(category.legacyTextState.value)
                    if (migrated.isNotEmpty()) {
                        category.itemsState.value = migrated
                    }
                }
            }
            settingsSp.wishlistItemsMigrated.value = true
        }
    }

    val current = categories[selectedIndex.coerceIn(categories.indices)]
    val groups = remember(current.itemsState.value, current.groupSize) {
        buildMemoGroups(current.itemsState.value, current.groupSize)
    }
    val groupIds = remember(groups) { groups.map { it.id } }
    val stats = remember(groups) { calculateMemoStats(groups) }
    var expandedGroupIds by rememberSaveable(selectedIndex, groupIds.joinToString("|")) {
        mutableStateOf(groupIds)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = rememberFixedStatusBarHeightDp()),
    ) {
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                backgroundColor = MaterialTheme.colors.background,
                contentColor = current.color,
                edgePadding = 20.dp,
            ) {
                categories.forEachIndexed { index, category ->
                    val categoryGroups = remember(category.itemsState.value, category.groupSize) {
                        buildMemoGroups(category.itemsState.value, category.groupSize)
                    }
                    val categoryStats = remember(categoryGroups) { calculateMemoStats(categoryGroups) }
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        selectedContentColor = category.color,
                        unselectedContentColor = category.color.copy(alpha = 0.58f),
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = category.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "\u5171 ${categoryStats.itemCount} \u6761",
                                    style = MaterialTheme.typography.caption,
                                    maxLines = 1,
                                )
                            }
                        }
                    )
                }
            }
        }

        item {
            MemoActionBar(
                stats = stats,
                color = current.color,
                onExpandAll = { expandedGroupIds = groupIds },
                onCollapseAll = { expandedGroupIds = emptyList() },
                onAdd = {
                    editingItem = EditingMemoItem(
                        categoryIndex = selectedIndex,
                        itemIndex = current.itemsState.value.size,
                        initialText = "",
                        isNew = true,
                    )
                },
                onExport = {
                    pendingExportText = buildMemoExportText(current.itemsState.value)
                    exportLauncher.launch("ARMusic-${current.title}.txt")
                },
            )
        }

        if (groups.isEmpty()) {
            item {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    text = current.placeholder,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.body2,
                )
            }
        } else {
            items(
                items = groups,
                key = { it.id },
                contentType = { MemoGroup::class },
            ) { group ->
                val expanded = group.id in expandedGroupIds
                MemoGroupCard(
                    group = group,
                    color = current.color,
                    expanded = expanded,
                    onGroupClick = {
                        expandedGroupIds = if (expanded) {
                            expandedGroupIds - group.id
                        } else {
                            expandedGroupIds + group.id
                        }
                    },
                    onItemClick = { item ->
                        editingItem = EditingMemoItem(
                            categoryIndex = selectedIndex,
                            itemIndex = item.index,
                            initialText = item.text,
                            isNew = false,
                        )
                    },
                )
            }
        }

        smartBarPadding()
    }

    editingItem?.let { item ->
        val category = categories.getOrNull(item.categoryIndex) ?: current
        MemoEditDialog(
            editingItem = item,
            category = category,
            onDismiss = { editingItem = null },
        )
    }
}

@Composable
private fun MemoActionBar(
    stats: MemoStats,
    color: Color,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onAdd: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "\u5171 ${stats.itemCount} \u6761 \u00b7 ${stats.groupCount} \u7ec4",
                style = MaterialTheme.typography.body2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemoActionButton("\u5168\u90e8\u5f00\u542f", color, onExpandAll)
                MemoActionButton("\u5168\u90e8\u5173\u95ed", color, onCollapseAll)
                MemoActionButton("\u65b0\u589e", color, onAdd)
                MemoActionButton("\u5bfc\u51faTXT", color, onExport)
            }
        }
    }
}

@Composable
private fun MemoActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(100),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            text = text,
            style = MaterialTheme.typography.caption,
            maxLines = 1,
        )
    }
}

@Composable
private fun MemoGroupCard(
    group: MemoGroup,
    color: Color,
    expanded: Boolean,
    onGroupClick: () -> Unit,
    onItemClick: (MemoIndexedItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.045f),
        contentColor = MaterialTheme.colors.onBackground,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGroupClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.title,
                    color = color,
                    style = MaterialTheme.typography.subtitle1,
                )
                Text(
                    text = "\u5171 ${group.items.size} \u6761",
                    color = color.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.caption,
                )
            }

            if (expanded) {
                group.items.forEachIndexed { index, item ->
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .padding(
                                start = 18.dp,
                                end = 18.dp,
                                top = if (index == 0) 0.dp else 6.dp,
                                bottom = if (index == group.items.lastIndex) 14.dp else 0.dp,
                            ),
                        text = item.text,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoEditDialog(
    editingItem: EditingMemoItem,
    category: MemoCategory,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(editingItem.categoryIndex, editingItem.itemIndex, editingItem.initialText) {
        mutableStateOf(editingItem.initialText)
    }

    fun save() {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            if (!editingItem.isNew) {
                category.itemsState.value = category.itemsState.value.minusIndex(editingItem.itemIndex)
            }
            onDismiss()
            return
        }

        category.itemsState.value = if (editingItem.isNew) {
            category.itemsState.value + normalized
        } else {
            category.itemsState.value.replaceAt(editingItem.itemIndex, normalized)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingItem.isNew) "\u65b0\u589e${category.title}" else "\u7f16\u8f91${category.title}") },
        text = {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(category.placeholder) },
                minLines = 4,
            )
        },
        confirmButton = {
            TextButton(onClick = ::save) {
                Text("\u4fdd\u5b58")
            }
        },
        dismissButton = {
            Row {
                if (!editingItem.isNew) {
                    TextButton(
                        onClick = {
                            category.itemsState.value =
                                category.itemsState.value.minusIndex(editingItem.itemIndex)
                            onDismiss()
                        }
                    ) {
                        Text("\u5220\u9664")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("\u53d6\u6d88")
                }
            }
        },
    )
}

private data class MemoCategory(
    val title: String,
    val itemsState: MutableState<List<String>>,
    val legacyTextState: MutableState<String>,
    val placeholder: String,
    val color: Color,
    val groupSize: Int = 10,
)

private data class MemoIndexedItem(
    val index: Int,
    val text: String,
)

private data class MemoGroup(
    val id: String,
    val title: String,
    val items: List<MemoIndexedItem>,
)

private data class MemoStats(
    val itemCount: Int,
    val groupCount: Int,
)

private data class EditingMemoItem(
    val categoryIndex: Int,
    val itemIndex: Int,
    val initialText: String,
    val isNew: Boolean,
)

private val memoGroupMarkerPattern = Regex("""^\d+[.、]?$""")
private val memoCategoryHeaders = setOf(
    "\u613f\u671b\u5355",
    "\u51c6\u5907\u542c",
    "\u51c6\u5907\u542c\u7684\u97f3\u4e50",
    "\u52a8\u6f2b",
    "\u6f2b\u753b",
    "\u5c0f\u8bf4",
)

private fun buildMemoGroups(
    items: List<String>,
    groupSize: Int,
): List<MemoGroup> {
    return items
        .mapIndexed { index, text -> MemoIndexedItem(index, text) }
        .chunked(groupSize.coerceAtLeast(1))
        .mapIndexed { index, groupItems ->
            MemoGroup(
                id = "group-$index",
                title = (index + 1).toString(),
                items = groupItems,
            )
        }
}

private fun parseLegacyMemoItems(text: String): List<String> {
    return text.lineSequence()
        .map { it.trim().trim('\uFEFF') }
        .filter { it.isNotBlank() }
        .filterNot { it in memoCategoryHeaders }
        .filterNot { memoGroupMarkerPattern.matches(it) }
        .toList()
}

private fun calculateMemoStats(groups: List<MemoGroup>): MemoStats {
    return MemoStats(
        itemCount = groups.sumOf { it.items.size },
        groupCount = groups.size,
    )
}

private fun buildMemoExportText(items: List<String>): String {
    return items.joinToString(separator = "\n\n") { it.trim() }
}

private fun List<String>.replaceAt(index: Int, value: String): List<String> {
    if (index !in indices) return this
    return toMutableList().apply { set(index, value) }
}

private fun List<String>.minusIndex(index: Int): List<String> {
    if (index !in indices) return this
    return toMutableList().apply { removeAt(index) }
}
