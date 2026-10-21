package com.shounak.localmeshai.features

import org.json.JSONArray
import org.json.JSONObject

data class RagPassage(val documentId: String, val documentName: String, val chunkIndex: Int, val text: String) {
    fun toJson() = JSONObject().apply {
        put("documentId", documentId); put("documentName", documentName)
        put("chunkIndex", chunkIndex); put("text", text)
    }
    companion object {
        fun encode(items: List<RagPassage>) = JSONArray().apply { items.forEach { put(it.toJson()) } }
        fun decode(array: JSONArray?): List<RagPassage> = if (array == null) emptyList() else
            (0 until array.length()).map { array.getJSONObject(it).let { json ->
                RagPassage(json.getString("documentId"), json.getString("documentName"), json.getInt("chunkIndex"), json.getString("text"))
            } }
    }
}
