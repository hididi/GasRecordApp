package com.hididi.gaslog.domain

/** Identity fields needed to decide whether a newly confirmed reading replaces one. */
data class MergeReading(
    val id: Long,
    val epochMillis: Long,
    val readingMilli: Long,
)

/**
 * A timestamp is the primary identity of a meter reading.  A matching reading is
 * the fallback identity.  When the two matches are different records, retain the
 * timestamp record and remove the duplicate reading record.
 */
object ReadingMerge {
    data class Resolution(
        val keepId: Long?,
        val deleteIds: Set<Long>,
    )

    fun resolve(
        existing: List<MergeReading>,
        incomingEpochMillis: Long,
        incomingReadingMilli: Long,
    ): Resolution {
        val timeMatch = existing.firstOrNull { it.epochMillis == incomingEpochMillis }
        val readingMatch = existing.firstOrNull { it.readingMilli == incomingReadingMilli }
        val keep = timeMatch ?: readingMatch
        return Resolution(
            keepId = keep?.id,
            deleteIds = if (timeMatch != null && readingMatch != null && timeMatch.id != readingMatch.id) {
                setOf(readingMatch.id)
            } else {
                emptySet()
            },
        )
    }
}
