package com.lalilu.lmusic.agent

import org.json.JSONObject

data class AgentCommandResult(
    val ok: Boolean,
    val command: String,
    val message: String,
    val outputPath: String? = null,
    val resultPath: String? = null,
    val exportedSongs: Int = 0,
    val importedHistories: Int = 0,
    val importedWorks: Int = 0,
    val importedGroups: Int = 0,
    val skipped: Int = 0,
    val duplicates: Int = 0,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("ok", ok)
            .put("command", command)
            .put("message", message)
            .put("outputPath", outputPath.orEmpty())
            .put("resultPath", resultPath.orEmpty())
            .put("exportedSongs", exportedSongs)
            .put("importedHistories", importedHistories)
            .put("importedWorks", importedWorks)
            .put("importedGroups", importedGroups)
            .put("skipped", skipped)
            .put("duplicates", duplicates)
    }
}
