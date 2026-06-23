package com.lalilu.lmusic.compose.new_screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import kotlin.random.Random

object WishlistScreen : Screen, ScreenInfoFactory {
    private fun readResolve(): Any = WishlistScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { "愿望单" },
            icon = RemixIcon.Design.editBoxFill,
        )
    }

    @Composable
    override fun Content() {
        WishlistContent()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WishlistContent(
    settingsSp: SettingsSp = koinInject(),
) {
    val context = LocalContext.current
    val categoriesJson = settingsSp.wishlistCategoriesJson.value
    val categories = remember(categoriesJson) {
        decodeMemoCategories(categoriesJson).ifEmpty {
            buildInitialMemoCategories(settingsSp)
        }
    }
    val selectedIndexMax = (categories.size - 1).coerceAtLeast(0)
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var pendingExportText by rememberSaveable { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<EditingMemoItem?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    var deletingCategory by remember { mutableStateOf<MemoCategory?>(null) }

    fun saveCategories(next: List<MemoCategory>) {
        settingsSp.wishlistCategoriesJson.value = encodeMemoCategories(next)
    }

    fun updateCategoryItems(categoryId: String, items: List<String>) {
        saveCategories(
            categories.map { category ->
                if (category.id == categoryId) category.copy(items = items) else category
            }
        )
    }

    LaunchedEffect(Unit) {
        if (settingsSp.wishlistCategoriesJson.value.isBlank()) {
            settingsSp.wishlistCategoriesJson.value = encodeMemoCategories(categories)
        }
    }

    LaunchedEffect(categories.size) {
        if (selectedIndex > selectedIndexMax) {
            selectedIndex = selectedIndexMax
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(pendingExportText)
            }
        }.onSuccess {
            ToastUtils.showShort("已导出")
        }.onFailure {
            ToastUtils.showShort("导出失败")
        }
    }

    val current = categories.getOrNull(selectedIndex.coerceIn(0, selectedIndexMax))
    val groups = remember(current?.items, current?.groupSize) {
        current?.let { buildMemoGroups(it.items, it.groupSize) }.orEmpty()
    }
    val groupIds = remember(groups) { groups.map { it.id } }
    val stats = remember(groups) { calculateMemoStats(groups) }
    var expandedGroupIds by rememberSaveable(current?.id, groupIds.joinToString("|")) {
        mutableStateOf(groupIds)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = rememberFixedStatusBarHeightDp()),
    ) {
        item {
            ScrollableTabRow(
                selectedTabIndex = if (categories.isEmpty()) 0 else selectedIndex.coerceIn(0, selectedIndexMax),
                backgroundColor = MaterialTheme.colors.background,
                contentColor = current?.color ?: MaterialTheme.colors.primary,
                edgePadding = 20.dp,
            ) {
                categories.forEachIndexed { index, category ->
                    val categoryGroups = remember(category.items, category.groupSize) {
                        buildMemoGroups(category.items, category.groupSize)
                    }
                    val categoryStats = remember(categoryGroups) { calculateMemoStats(categoryGroups) }
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        selectedContentColor = category.color,
                        unselectedContentColor = category.color.copy(alpha = 0.58f),
                        text = {
                            Column(
                                modifier = Modifier.combinedClickable(
                                    onClick = { selectedIndex = index },
                                    onLongClick = { deletingCategory = category },
                                ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = category.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "共 ${categoryStats.itemCount} 条",
                                    style = MaterialTheme.typography.caption,
                                    maxLines = 1,
                                )
                            }
                        }
                    )
                }

                Tab(
                    selected = false,
                    onClick = { addingCategory = true },
                    selectedContentColor = MaterialTheme.colors.primary,
                    unselectedContentColor = MaterialTheme.colors.primary,
                    text = {
                        Text(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            text = "+",
                            style = MaterialTheme.typography.h6,
                        )
                    }
                )
            }
        }

        if (current == null) {
            item {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    text = "点右上方 + 新建一个栏目",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.body2,
                )
            }
        } else {
            item {
                MemoActionBar(
                    stats = stats,
                    color = current.color,
                    onExpandAll = { expandedGroupIds = groupIds },
                    onCollapseAll = { expandedGroupIds = emptyList() },
                    onAdd = {
                        editingItem = EditingMemoItem(
                            categoryId = current.id,
                            itemIndex = current.items.size,
                            initialText = "",
                            isNew = true,
                        )
                    },
                    onExport = {
                        pendingExportText = buildMemoExportText(current.items)
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
                                categoryId = current.id,
                                itemIndex = item.index,
                                initialText = item.text,
                                isNew = false,
                            )
                        },
                    )
                }
            }
        }

        smartBarPadding()
    }

    editingItem?.let { item ->
        val category = categories.firstOrNull { it.id == item.categoryId }
        if (category == null) {
            editingItem = null
            return@let
        }

        MemoEditDialog(
            editingItem = item,
            category = category,
            onDismiss = { editingItem = null },
            onSave = { text ->
                val normalized = text.trim()
                val nextItems = if (normalized.isBlank()) {
                    if (item.isNew) category.items else category.items.minusIndex(item.itemIndex)
                } else if (item.isNew) {
                    category.items + normalized
                } else {
                    category.items.replaceAt(item.itemIndex, normalized)
                }
                updateCategoryItems(category.id, nextItems)
                editingItem = null
            },
            onDelete = {
                updateCategoryItems(category.id, category.items.minusIndex(item.itemIndex))
                editingItem = null
            }
        )
    }

    if (addingCategory) {
        MemoCategoryDialog(
            onDismiss = { addingCategory = false },
            onConfirm = { title, colorArgb ->
                val next = categories + MemoCategory(
                    id = newMemoCategoryId(),
                    title = title.trim(),
                    colorArgb = colorArgb,
                    items = emptyList(),
                )
                saveCategories(next)
                selectedIndex = next.lastIndex
                addingCategory = false
            }
        )
    }

    deletingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("删除${category.title}") },
            text = { Text("这个栏目和里面的 ${category.items.size} 条内容都会删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val next = categories.filterNot { it.id == category.id }
                        saveCategories(next)
                        selectedIndex = selectedIndex.coerceAtMost((next.size - 1).coerceAtLeast(0))
                        deletingCategory = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCategory = null }) {
                    Text("取消")
                }
            },
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
                text = "共 ${stats.itemCount} 条 · ${stats.groupCount} 组",
                style = MaterialTheme.typography.body2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemoActionButton("全部开启", color, onExpandAll)
                MemoActionButton("全部关闭", color, onCollapseAll)
                MemoActionButton("新增", color, onAdd)
                MemoActionButton("导出TXT", color, onExport)
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
                    text = "共 ${group.items.size} 条",
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
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by rememberSaveable(editingItem.categoryId, editingItem.itemIndex, editingItem.initialText) {
        mutableStateOf(editingItem.initialText)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingItem.isNew) "新增${category.title}" else "编辑${category.title}") },
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
            TextButton(onClick = { onSave(text) }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (!editingItem.isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}

@Composable
private fun MemoCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var selectedColor by rememberSaveable { mutableStateOf(memoCategoryColors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建栏目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("栏目名") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    memoCategoryColors.forEach { colorArgb ->
                        val color = Color(colorArgb)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .border(
                                    width = if (selectedColor == colorArgb) 3.dp else 1.dp,
                                    color = if (selectedColor == colorArgb) color else color.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(100)
                                )
                                .padding(4.dp)
                                .clickable { selectedColor = colorArgb },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                modifier = Modifier.size(18.dp),
                                shape = RoundedCornerShape(100),
                                color = color,
                                content = {}
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = title.trim()
                    if (normalized.isBlank()) {
                        ToastUtils.showShort("先写栏目名")
                    } else {
                        onConfirm(normalized, selectedColor)
                    }
                }
            ) {
                Text("新建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private data class MemoCategory(
    val id: String,
    val title: String,
    val colorArgb: Long,
    val items: List<String>,
    val groupSize: Int = 10,
) {
    val color: Color
        get() = Color(colorArgb)

    val placeholder: String
        get() = "点新增写一条${title}备忘"
}

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
    val categoryId: String,
    val itemIndex: Int,
    val initialText: String,
    val isNew: Boolean,
)

private data class LegacyMemoSeed(
    val title: String,
    val colorArgb: Long,
    val items: List<String>,
    val legacyText: String,
)

private val memoCategoryColors = listOf(
    0xFF2F7D73,
    0xFFD05A4E,
    0xFF7666B0,
    0xFFB97825,
    0xFF2F6FBE,
    0xFF8A6A2D,
)

private val memoGroupMarkerPattern = Regex("""^\d+[.、]?$""")
private val memoCategoryHeaders = setOf(
    "愿望单",
    "准备听",
    "准备听的音乐",
    "动漫",
    "漫画",
    "小说",
)

private fun buildInitialMemoCategories(settingsSp: SettingsSp): List<MemoCategory> {
    val seeds = listOf(
        LegacyMemoSeed(
            title = "准备听",
            colorArgb = memoCategoryColors[0],
            items = settingsSp.wishlistItems.value,
            legacyText = settingsSp.wishlistText.value,
        ),
        LegacyMemoSeed(
            title = "动漫",
            colorArgb = memoCategoryColors[1],
            items = settingsSp.wishlistAnimeItems.value,
            legacyText = settingsSp.wishlistAnimeText.value,
        ),
        LegacyMemoSeed(
            title = "漫画",
            colorArgb = memoCategoryColors[2],
            items = settingsSp.wishlistMangaItems.value,
            legacyText = settingsSp.wishlistMangaText.value,
        ),
        LegacyMemoSeed(
            title = "小说",
            colorArgb = memoCategoryColors[3],
            items = settingsSp.wishlistNovelItems.value,
            legacyText = settingsSp.wishlistNovelText.value,
        ),
    )

    val migrated = seeds.mapNotNull { seed ->
        val items = seed.items.ifEmpty { parseLegacyMemoItems(seed.legacyText) }
        if (items.isEmpty()) return@mapNotNull null

        MemoCategory(
            id = newMemoCategoryId(seed.title),
            title = seed.title,
            colorArgb = seed.colorArgb,
            items = items,
        )
    }

    return migrated.ifEmpty {
        listOf(
            MemoCategory(
                id = newMemoCategoryId("准备听"),
                title = "准备听",
                colorArgb = memoCategoryColors[0],
                items = listOf("想听的歌", "想听的歌2"),
            )
        )
    }
}

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

private fun encodeMemoCategories(categories: List<MemoCategory>): String {
    val array = JSONArray()
    categories.forEach { category ->
        val items = JSONArray()
        category.items.forEach { items.put(it) }
        array.put(
            JSONObject()
                .put("id", category.id)
                .put("title", category.title)
                .put("color", category.colorArgb)
                .put("items", items)
        )
    }
    return array.toString()
}

private fun decodeMemoCategories(json: String): List<MemoCategory> {
    if (json.isBlank()) return emptyList()

    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val itemsArray = item.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (itemIndex in 0 until itemsArray.length()) {
                        itemsArray.optString(itemIndex)
                            .trim()
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
                val title = item.optString("title").trim().ifBlank { "未命名" }
                add(
                    MemoCategory(
                        id = item.optString("id").ifBlank { newMemoCategoryId(title) },
                        title = title,
                        colorArgb = item.optLong("color", memoCategoryColors[index % memoCategoryColors.size]),
                        items = items,
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun newMemoCategoryId(seed: String = "category"): String {
    return "${seed}-${System.currentTimeMillis().toString(36)}-${Random.nextInt(1000, 9999)}"
}

private fun List<String>.replaceAt(index: Int, value: String): List<String> {
    if (index !in indices) return this
    return toMutableList().apply { set(index, value) }
}

private fun List<String>.minusIndex(index: Int): List<String> {
    if (index !in indices) return this
    return toMutableList().apply { removeAt(index) }
}
