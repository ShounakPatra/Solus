package com.shounak.localmeshai.ai

/** Remove complete old exchanges; preserve system instructions and the current user turn. */
internal object PortableHistoryBudget {
    fun dropOldestTurn(turns: List<Pair<String, String>>): List<Pair<String, String>>? {
        val users = turns.indices.filter { turns[it].first == "user" }
        if (users.size < 2) return null
        return turns.filterIndexed { index, turn -> turn.first == "system" || index !in users[0] until users[1] }
    }
}
