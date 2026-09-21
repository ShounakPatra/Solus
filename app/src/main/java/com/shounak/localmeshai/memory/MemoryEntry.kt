package com.shounak.localmeshai.memory

import java.util.UUID

/**
 * Categorization for persistent user memories.
 */
enum class MemoryCategory(val displayName: String, val icon: String) {
    FACT("Fact", "📌"),
    PREFERENCE("Preference", "⭐"),
    INSTRUCTION("Instruction", "📜"),
    GENERAL("General", "💡");

    companion object {
        fun fromString(value: String): MemoryCategory {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: GENERAL
        }
    }
}

/**
 * Represents a single persistent memory unit stored across conversations.
 * Completely separate and decoupled from RAG and llama.cpp.
 */
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val category: MemoryCategory = MemoryCategory.GENERAL,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
