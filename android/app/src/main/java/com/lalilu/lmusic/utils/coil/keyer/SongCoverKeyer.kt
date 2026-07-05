package com.lalilu.lmusic.utils.coil.keyer

import androidx.media3.common.MediaItem
import coil3.key.Keyer
import coil3.request.Options
import com.lalilu.lmedia.entity.LAlbum
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.utils.coil.fetcher.IndexedMediaItemCover


class LSongCoverKeyer : Keyer<LSong> {
    override fun key(data: LSong, options: Options): String {
        return "LSONG_${data.id}_${data.metadata.dateModified}_${data.metadata.hashCode()}_${options.size.width}_${options.size.height}"
    }
}

class LAlbumCoverKeyer : Keyer<LAlbum> {
    override fun key(data: LAlbum, options: Options): String {
        return "LALBUM_${data.id}_${options.size.width}_${options.size.height}"
    }
}

class MediaItemKeyer : Keyer<MediaItem> {
    override fun key(data: MediaItem, options: Options): String {
        return "MEDIA_ITEM_${data.mediaId}_${data.mediaMetadata.hashCode()}_${options.size.width}_${options.size.height}"
    }
}

class IndexedMediaItemCoverKeyer : Keyer<IndexedMediaItemCover> {
    override fun key(data: IndexedMediaItemCover, options: Options): String {
        return "MEDIA_ITEM_${data.item.mediaId}_${data.item.mediaMetadata.hashCode()}_${data.index}_${options.size.width}_${options.size.height}"
    }
}
