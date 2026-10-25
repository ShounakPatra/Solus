package com.shounak.localmeshai.data

/** A disk read may finish after a chat was created, updated or deleted in memory. */
internal fun <T> mergeSessionSnapshots(
    disk: List<T>,
    visible: List<T>,
    id: (T) -> String,
    updatedAt: (T) -> Long,
    isDeleted: (String) -> Boolean
): List<T> {
    val latest = linkedMapOf<String, T>()
    for (session in disk + visible) {
        val key = id(session)
        if (isDeleted(key)) continue
        val previous = latest[key]
        if (previous == null || updatedAt(session) >= updatedAt(previous)) latest[key] = session
    }
    return latest.values.sortedByDescending(updatedAt)
}
