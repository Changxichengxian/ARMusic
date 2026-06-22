package com.lalilu.lfolder.screen

import android.app.Application
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.google.accompanist.flowlayout.FlowRow
import com.lalilu.RemixIcon
import com.lalilu.component.IconTextButton
import com.lalilu.component.base.NavigatorHeader
import com.lalilu.component.base.screen.ScreenAction
import com.lalilu.component.base.screen.ScreenActionFactory
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.lfolder.R
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.extension.extraMediaId
import com.lalilu.lmedia.repository.LMediaSp
import com.lalilu.lmedia.scanner.FileSystemScanner
import com.lalilu.lmedia.scanner.FileSource
import com.lalilu.lmedia.scanner.PathExclusionMatcher
import com.lalilu.remixicon.Document
import com.lalilu.remixicon.System
import com.lalilu.remixicon.document.folderMusicLine
import com.lalilu.remixicon.system.addLine
import com.zhangke.krouter.annotation.Destination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.filepicker.FilePickerActivity
import me.rosuh.filepicker.bean.FileItemBeanImpl
import me.rosuh.filepicker.config.AbstractFileFilter
import me.rosuh.filepicker.config.FilePickerManager
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryScreenModel(
    private val application: Application,
    private val lMediaSp: LMediaSp,
    private val fileSystemScanner: FileSystemScanner,
    private val historyDao: HistoryDao,
) : ScreenModel {
    val targetDirectory = lMediaSp.includePath
        .flow(true)
        .mapLatest { str -> FileSource.from(str, application) }

    fun saveTargetUri(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        application.contentResolver.takePersistableUriPermission(uri, flags)

        lMediaSp.includePath.add(uri.toString())
        fileSystemScanner.updateAsync()
    }

    fun savePaths(strList: List<String>) {
        lMediaSp.includePath.add(strList)
        fileSystemScanner.updateAsync()
    }

    fun remove(str: String) {
        lMediaSp.includePath.remove(str)
        fileSystemScanner.updateAsync()
    }

    suspend fun importSongsToLibrary(uris: List<Uri>): ImportSongsResult = withContext(Dispatchers.IO) {
        val uniqueUris = uris.distinct()
        if (uniqueUris.isEmpty()) {
            return@withContext ImportSongsResult(message = "没有选择歌曲")
        }

        var imported = 0
        var moved = 0
        var retained = 0
        var failed = 0
        var skippedExcluded = 0
        val exclusionMatcher = PathExclusionMatcher(lMediaSp.excludePath.value)

        uniqueUris.forEach { uri ->
            if (exclusionMatcher.isExcluded(uri.toString()) || exclusionMatcher.isExcluded(uri.path)) {
                skippedExcluded += 1
                return@forEach
            }

            runCatching {
                persistSourcePermission(uri)
                val target = copyToARMusicLibrary(uri)
                relinkSongDataAfterMove(source = uri, target = target)
                imported += 1
                if (deleteSource(uri)) {
                    moved += 1
                } else {
                    retained += 1
                }
            }.onFailure {
                failed += 1
                LogUtils.e("[ARMusic] Import song failed: $uri", it)
            }
        }

        fileSystemScanner.updateAsync()

        ImportSongsResult(
            imported = imported,
            moved = moved,
            retained = retained,
            failed = failed,
            skippedExcluded = skippedExcluded,
            message = buildImportMessage(imported, moved, retained, failed, skippedExcluded)
        )
    }

    private fun persistSourcePermission(source: Uri) {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val writeFlag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            application.contentResolver.takePersistableUriPermission(source, readFlag or writeFlag)
        }.recoverCatching {
            application.contentResolver.takePersistableUriPermission(source, readFlag)
        }
    }

    private fun copyToARMusicLibrary(source: Uri): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyWithMediaStore(source)
        } else {
            copyWithFile(source)
        }
    }

    private fun copyWithMediaStore(source: Uri): Uri {
        val resolver = application.contentResolver
        val displayName = uniqueMediaStoreName(source.safeDisplayName())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, source.mimeType(displayName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$LIBRARY_RELATIVE_PATH/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建音乐文件")

        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target, "w")?.use { output ->
                    input.copyTo(output)
                } ?: error("无法写入音乐文件")
            } ?: error("无法读取原文件")

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            return target
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun copyWithFile(source: Uri): Uri {
        val displayName = source.safeDisplayName()
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            LIBRARY_DIR_NAME
        ).apply { mkdirs() }
        val target = dir.uniqueChild(displayName)

        application.contentResolver.openInputStream(source)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: error("无法读取原文件")

        MediaScannerConnection.scanFile(
            application,
            arrayOf(target.absolutePath),
            arrayOf(source.mimeType(displayName)),
            null
        )
        return Uri.fromFile(target)
    }

    private fun deleteSource(source: Uri): Boolean {
        return when (source.scheme) {
            "content" -> runCatching {
                DocumentsContract.deleteDocument(application.contentResolver, source)
            }.getOrDefault(false).takeIf { it }
                ?: runCatching {
                    application.contentResolver.delete(source, null, null) > 0
                }.getOrDefault(false)

            "file" -> runCatching {
                File(source.path.orEmpty()).delete()
            }.getOrDefault(false)

            else -> false
        }
    }

    private fun relinkSongDataAfterMove(source: Uri, target: Uri) {
        val oldId = source.extraMediaId(application)
            ?: source.mediaIdByPath()
            ?: return
        val newId = target.extraMediaId(application)
            ?: target.lastPathSegment?.substringAfterLast('/')
            ?: return
        if (oldId == newId) return

        val oldPath = mediaPathById(oldId) ?: LMedia.get<LSong>(oldId, blockFilter = false)?.fileInfo?.pathStr
        val newPath = mediaPathById(newId)
        val newTitle = LMedia.get<LSong>(newId, blockFilter = false)?.name
            ?: target.safeDisplayName().substringBeforeLast('.', target.safeDisplayName())

        historyDao.relinkContentId(
            oldContentId = oldId,
            newContentId = newId,
            newContentTitle = newTitle,
        )
        relinkPrefs(
            prefsName = "armusic_song_works",
            oldId = oldId,
            newId = newId,
            oldPath = oldPath,
            newPath = newPath,
        )
        val group = relinkPrefs(
            prefsName = "armusic_song_groups",
            oldId = oldId,
            newId = newId,
            oldPath = oldPath,
            newPath = newPath,
        )
        if (!group.isNullOrBlank()) {
            val statId = "armusic-group:${group.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)}"
            historyDao.updateParentForContentId(
                contentId = newId,
                parentId = statId,
                parentTitle = group.trim(),
            )
        }
    }

    private fun relinkPrefs(
        prefsName: String,
        oldId: String,
        newId: String,
        oldPath: String?,
        newPath: String?,
    ): String? {
        val prefs = application.getSharedPreferences(prefsName, Application.MODE_PRIVATE)
        val oldIdKey = "id:$oldId"
        val newIdKey = "id:$newId"
        val oldPathKey = oldPath?.let { "path:$it" }
        val newPathKey = newPath?.let { "path:$it" }
        val idValue = prefs.all[oldIdKey] as? String
        val pathValue = oldPathKey?.let { prefs.all[it] as? String }
        val preferredValue = idValue ?: pathValue

        prefs.edit().apply {
            if (idValue != null && !prefs.contains(newIdKey)) putString(newIdKey, idValue)
            if (pathValue != null && newPathKey != null && !prefs.contains(newPathKey)) {
                putString(newPathKey, pathValue)
            }
            prefs.all.forEach { (key, value) ->
                if (value == "song:$oldId") putString(key, "song:$newId")
            }
        }.apply()

        return preferredValue
    }

    private fun Uri.mediaIdByPath(): String? {
        val path = PathExclusionMatcher.normalizePath(toString()) ?: return null
        return queryAudio(
            projection = arrayOf(MediaStore.Audio.Media._ID),
            selection = "${MediaStore.Audio.Media.DATA}=?",
            selectionArgs = arrayOf(path),
        ) { cursor ->
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        }
    }

    private fun mediaPathById(mediaId: String): String? {
        return queryAudio(
            projection = arrayOf(MediaStore.Audio.Media.DATA),
            selection = "${MediaStore.Audio.Media._ID}=?",
            selectionArgs = arrayOf(mediaId),
        ) { cursor ->
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
        }
    }

    private fun <T> queryAudio(
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        block: (android.database.Cursor) -> T,
    ): T? {
        return application.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) block(cursor) else null
        }
    }

    private fun Uri.safeDisplayName(): String {
        val nameFromProvider = application.contentResolver.query(
            this,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }

        val fallback = lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
        return (nameFromProvider ?: fallback)
            ?.takeIf { it.isNotBlank() }
            ?.sanitizeFileName()
            ?: "ARMusic-${java.lang.System.currentTimeMillis()}.mp3"
    }

    private fun Uri.mimeType(displayName: String): String {
        return application.contentResolver.getType(this)
            ?: displayName.substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "audio/mpeg"
    }

    private fun uniqueMediaStoreName(fileName: String): String {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""

        var index = 0
        while (true) {
            val candidate = if (index == 0) fileName else "$baseName ($index)$extension"
            if (!mediaStoreFileExists(candidate)) return candidate
            index += 1
        }
    }

    private fun mediaStoreFileExists(fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return application.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?",
            arrayOf("$LIBRARY_RELATIVE_PATH/", fileName),
            null
        )?.use { it.moveToFirst() } ?: false
    }

    private fun File.uniqueChild(fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""

        var index = 0
        while (true) {
            val candidate = if (index == 0) {
                File(this, fileName)
            } else {
                File(this, "$baseName ($index)$extension")
            }
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun buildImportMessage(
        imported: Int,
        moved: Int,
        retained: Int,
        failed: Int,
        skippedExcluded: Int,
    ): String {
        if (imported == 0) {
            return buildString {
                append("没有导入歌曲")
                if (skippedExcluded > 0) append("，已跳过屏蔽目录 $skippedExcluded 首")
                if (failed > 0) append("，失败 $failed 首")
            }
        }

        return buildString {
            append("已导入 $imported 首到 Music/ARMusic")
            if (moved > 0) append("，已移走原文件 $moved 首")
            if (retained > 0) append("，$retained 首原文件无法删除，已保留")
            if (skippedExcluded > 0) append("，跳过屏蔽目录 $skippedExcluded 首")
            if (failed > 0) append("，失败 $failed 首")
        }
    }

    data class ImportSongsResult(
        val imported: Int = 0,
        val moved: Int = 0,
        val retained: Int = 0,
        val failed: Int = 0,
        val skippedExcluded: Int = 0,
        val message: String,
    )

    companion object {
        private const val LIBRARY_DIR_NAME = "ARMusic"
        private const val LIBRARY_RELATIVE_PATH = "Music/ARMusic"
    }
}

@Destination("/pages/folders")
object FoldersScreen : Screen, ScreenInfoFactory, ScreenActionFactory {
    private fun readResolve(): Any = FoldersScreen

    @Composable
    override fun provideScreenInfo(): com.lalilu.component.base.screen.ScreenInfo {
        return remember {
            com.lalilu.component.base.screen.ScreenInfo(
                title = { stringResource(R.string.folder_screen_title) },
                icon = RemixIcon.Document.folderMusicLine
            )
        }
    }

    @Composable
    override fun provideScreenActions(): List<ScreenAction> {
        val context = LocalContext.current
        val dictionarySM = getScreenModel<DictionaryScreenModel>()

        val pickFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { treeUri ->
            treeUri?.let { dictionarySM.saveTargetUri(it) }
            LogUtils.i(treeUri)
        }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = {
                val result = FilePickerManager.obtainData(true)

                dictionarySM.savePaths(result)
                LogUtils.i(it.data, it.resultCode, result)
            }
        )

        return remember {
            listOf(
                ScreenAction.Static(
                    title = { stringResource(R.string.folder_screen_title) },
                    icon = { RemixIcon.System.addLine },
                    color = { Color(0xFF037200) }
                ) {
                    runCatching { pickFileLauncher.launch(null) }
                        .getOrElse {
                            val activity =
                                ActivityUtils.getActivityByContext(context) ?: return@Static
                            FilePickerManager.from(activity)
                                .skipDirWhenSelect(false)
                                .maxSelectable(Int.MAX_VALUE)
                                .filter(object : AbstractFileFilter() {
                                    override fun doFilter(listData: ArrayList<FileItemBeanImpl>): ArrayList<FileItemBeanImpl> {
                                        return ArrayList(listData.filter { it.isDir })
                                    }
                                })
                            val intent = Intent(activity, FilePickerActivity::class.java)
                            filePickerLauncher.launch(intent)
                        }
                }
            )
        }
    }

    @Composable
    override fun Content() {
        val dictionarySM = getScreenModel<DictionaryScreenModel>()

        DictionaryScreen(dictionarySM = dictionarySM)
    }
}

@Composable
private fun DictionaryScreen(
    dictionarySM: DictionaryScreenModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val directory by dictionarySM.targetDirectory.collectAsState(initial = emptyList())
    var importing by remember { mutableStateOf(false) }

    val importSongsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uris = result.data.selectedUris()
        if (uris.isEmpty() || importing) return@rememberLauncherForActivityResult

        scope.launch {
            importing = true
            val result = dictionarySM.importSongsToLibrary(uris)
            ToastUtils.showLong(result.message)
            importing = false
        }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let {
            dictionarySM.saveTargetUri(it)
            ToastUtils.showLong("已添加扫描目录")
        }
        LogUtils.i(treeUri)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val result = FilePickerManager.obtainData(true)
        if (result.isNotEmpty()) {
            dictionarySM.savePaths(result)
            ToastUtils.showLong("已添加扫描目录")
        }
        LogUtils.i(it.data, it.resultCode, result)
    }

    LazyColumn(
        modifier = Modifier,
        contentPadding = WindowInsets.statusBars.asPaddingValues()
    ) {
        item {
            NavigatorHeader(
                title = "添加新歌",
                subTitle = ""
            )
        }

        item {
            FlowRow(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                mainAxisSpacing = 10.dp,
                crossAxisSpacing = 8.dp
            ) {
                IconTextButton(
                    text = if (importing) "导入中" else "移动歌曲到 ARMusic",
                    color = Color(0xFF006E7C),
                    onClick = {
                        if (importing) return@IconTextButton
                        importSongsLauncher.launch(createImportSongsIntent())
                    }
                )
                IconTextButton(
                    text = "添加扫描目录",
                    color = Color(0xFF6D5B00),
                    onClick = {
                        runCatching { pickFolderLauncher.launch(null) }
                            .onFailure {
                                val activity =
                                    ActivityUtils.getActivityByContext(context) ?: return@IconTextButton
                                FilePickerManager.from(activity)
                                    .skipDirWhenSelect(false)
                                    .maxSelectable(Int.MAX_VALUE)
                                    .filter(object : AbstractFileFilter() {
                                        override fun doFilter(listData: ArrayList<FileItemBeanImpl>): ArrayList<FileItemBeanImpl> {
                                            return ArrayList(listData.filter { it.isDir })
                                        }
                                    })
                                val intent = Intent(activity, FilePickerActivity::class.java)
                                filePickerLauncher.launch(intent)
                            }
                    }
                )
            }
        }

        item {
            Text(
                text = "已添加的扫描目录",
                style = MaterialTheme.typography.subtitle2,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        items(items = directory) {
            DirectoryCard(
                title = it.name() ?: "unknown",
                subTitle = it.path() ?: "unknown",
                onLongClick = {
                    val id = when (it) {
                        is FileSource.Document -> it.id
                        is FileSource.IOFile -> it.id
                    }
                    dictionarySM.remove(id)
                }
            )
        }
    }
}

private val SUPPORTED_AUDIO_TYPES = arrayOf(
    "audio/*",
    "application/ogg",
    "application/x-ogg",
    "application/octet-stream"
)

private fun createImportSongsIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("audio/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_AUDIO_TYPES)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

private fun Intent?.selectedUris(): List<Uri> {
    if (this == null) return emptyList()

    val clip = clipData
    if (clip != null) {
        return (0 until clip.itemCount)
            .mapNotNull { index -> clip.getItemAt(index)?.uri }
    }

    return data?.let(::listOf).orEmpty()
}

@Composable
fun DirectoryCard(
    title: String,
    subTitle: String,
    onLongClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onLongClick = onLongClick,
                onClick = {}
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.subtitle1)
        Text(text = subTitle, style = MaterialTheme.typography.subtitle2)
    }
}

@Preview
@Composable
fun DirectoryCardPreview() {
    DirectoryCard(
        title = "LocalMusic",
        subTitle = "/Music/LocalMusic/"
    )
}
