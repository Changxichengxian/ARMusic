package com.lalilu.lhistory.repository

import com.lalilu.lhistory.entity.LHistory

data class HistoryAliasMergeStats(
    val affectedRows: Int = 0,
    val movedRows: Int = 0,
    val updatedRows: Int = 0,
    val removedDuplicates: Int = 0,
)

internal data class HistoryAliasMergePlan(
    val updates: List<LHistory>,
    val deletes: List<LHistory>,
    val stats: HistoryAliasMergeStats,
)

/**
 * Builds a conservative alias migration plan. Rows are deduplicated only when both their
 * canonical content id and exact start time match; histories from any other instant survive.
 */
internal fun planHistoryContentIdAliasMerge(
    histories: List<LHistory>,
    aliases: Map<String, String>,
): HistoryAliasMergePlan {
    val normalizedAliases = aliases
        .mapKeys { (source, _) -> source.trim() }
        .mapValues { (_, target) -> target.trim() }
        .filter { (source, target) -> source.isNotBlank() && target.isNotBlank() && source != target }
    if (normalizedAliases.isEmpty()) return HistoryAliasMergePlan(
        updates = emptyList(),
        deletes = emptyList(),
        stats = HistoryAliasMergeStats(),
    )

    // Resolve every configured edge up front so a bad cyclic alias table cannot mutate history.
    val canonicalById = (normalizedAliases.keys + normalizedAliases.values)
        .associateWith { id -> canonicalHistoryContentId(id, normalizedAliases) }
    val relevantIds = canonicalById.keys
    val affected = histories.filter { it.contentId in relevantIds }
    val updates = mutableListOf<LHistory>()
    val deletes = mutableListOf<LHistory>()
    var movedRows = 0

    affected
        .groupBy { history ->
            val canonicalId = canonicalById[history.contentId] ?: history.contentId
            canonicalId to history.startTime
        }
        .values
        .forEach { rows ->
            val canonicalId = canonicalById[rows.first().contentId] ?: rows.first().contentId
            val survivor = rows
                .filter { it.contentId == canonicalId }
                .minByOrNull { it.id }
                ?: rows.minBy { it.id }
            val metadataPriority = rows.sortedWith(
                compareByDescending<LHistory> { it.contentId == canonicalId }
                    .thenBy { it.id }
            )
            fun chooseMetadata(read: (LHistory) -> String): String =
                read(survivor).takeIf(String::isNotBlank)
                    ?: metadataPriority.firstNotNullOfOrNull { row ->
                        read(row).takeIf(String::isNotBlank)
                    }.orEmpty()

            val merged = survivor.copy(
                contentId = canonicalId,
                contentTitle = chooseMetadata(LHistory::contentTitle),
                parentId = chooseMetadata(LHistory::parentId),
                parentTitle = chooseMetadata(LHistory::parentTitle),
                duration = rows.maxOf { it.duration },
                repeatCount = rows.maxOf { it.repeatCount },
            )
            movedRows += rows.count { it.contentId != canonicalId }
            if (merged != survivor) updates += merged
            deletes += rows.filterNot { it.id == survivor.id }
        }

    return HistoryAliasMergePlan(
        updates = updates,
        deletes = deletes,
        stats = HistoryAliasMergeStats(
            affectedRows = affected.size,
            movedRows = movedRows,
            updatedRows = updates.size,
            removedDuplicates = deletes.size,
        ),
    )
}

internal fun canonicalHistoryContentId(
    contentId: String,
    aliases: Map<String, String>,
): String {
    var current = contentId
    val visited = linkedSetOf<String>()
    while (true) {
        check(visited.add(current)) { "历史媒体 ID 别名存在循环：${visited.joinToString(" -> ")} -> $current" }
        val next = aliases[current] ?: return current
        if (next == current) return current
        current = next
    }
}
