package com.lalilu.lmusic.agent

import org.json.JSONObject

data class AgentCommandResult(
    val ok: Boolean,
    val command: String,
    val message: String,
    val commandId: String? = null,
    val outputPath: String? = null,
    val resultPath: String? = null,
    val exportedSongs: Int = 0,
    val exportedHistories: Int = 0,
    val clearedHistories: Int = 0,
    val historySnapshotId: String? = null,
    val importedHistories: Int = 0,
    val restoredHistories: Int = 0,
    val mergedHistories: Int = 0,
    val importedWorks: Int = 0,
    val importedGroups: Int = 0,
    val skipped: Int = 0,
    val duplicates: Int = 0,
    val committedSongs: Int = 0,
    val alreadyPresent: Boolean = false,
    val committedSyncId: String? = null,
    val verifiedSongs: Int = 0,
    val currentRevisionHash: String? = null,
    val leaseToken: String? = null,
    val leaseExpiresAt: Long = 0L,
    val wishlistCategories: Int = 0,
    val wishlistItems: Int = 0,
    val wishlistSnapshotId: String? = null,
    val playlistCount: Int = 0,
    val playlistItems: Int = 0,
    val playlistSnapshotId: String? = null,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("ok", ok)
            .put("command", command)
            .put("message", message)
            .put("commandId", commandId.orEmpty())
            .put("outputPath", outputPath.orEmpty())
            .put("resultPath", resultPath.orEmpty())
            .put("exportedSongs", exportedSongs)
            .put("exportedHistories", exportedHistories)
            .put("clearedHistories", clearedHistories)
            .put("historySnapshotId", historySnapshotId.orEmpty())
            .put("importedHistories", importedHistories)
            .put("restoredHistories", restoredHistories)
            .put("mergedHistories", mergedHistories)
            .put("importedWorks", importedWorks)
            .put("importedGroups", importedGroups)
            .put("skipped", skipped)
            .put("duplicates", duplicates)
            .put("committedSongs", committedSongs)
            .put("alreadyPresent", alreadyPresent)
            .put("committedSyncId", committedSyncId.orEmpty())
            .put("verifiedSongs", verifiedSongs)
            .put("currentRevisionHash", currentRevisionHash.orEmpty())
            .put("leaseToken", leaseToken.orEmpty())
            .put("leaseExpiresAt", leaseExpiresAt)
            .put("wishlistCategories", wishlistCategories)
            .put("wishlistItems", wishlistItems)
            .put("wishlistSnapshotId", wishlistSnapshotId.orEmpty())
            .put("playlistCount", playlistCount)
            .put("playlistItems", playlistItems)
            .put("playlistSnapshotId", playlistSnapshotId.orEmpty())
    }
}
