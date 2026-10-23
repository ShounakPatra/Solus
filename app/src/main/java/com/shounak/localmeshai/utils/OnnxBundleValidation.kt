package com.shounak.localmeshai.utils

import java.io.File
import java.io.RandomAccessFile

/** Walk ONNX metadata without reading tensor payloads; external weights must stay in this generation. */
internal object OnnxBundleValidation {
    private enum class Kind { Model, Graph, Node, Attribute, Tensor, Sparse, Function }

    fun validate(file: File, companions: Map<String, File> = emptyMap(), checkExternal: Boolean = true, logicalPath: String = file.name): Set<String> = RandomAccessFile(file, "r").use { input ->
        var version = false
        var graph = false
        val references = linkedSetOf<String>()
        fun varint(end: Long): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(input.filePointer < end) { "Truncated ONNX metadata" }
                val byte = input.readUnsignedByte()
                require(shift < 63 || byte <= 1)
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("Invalid ONNX integer")
        }
        fun end(parent: Long): Long {
            val length = varint(parent)
            require(length >= 0 && length <= parent - input.filePointer) { "Invalid ONNX message length" }
            return input.filePointer + length
        }
        fun string(limit: Long): String {
            val stop = end(limit)
            require(stop - input.filePointer <= 4096) { "Oversized ONNX external-data entry" }
            return ByteArray((stop - input.filePointer).toInt()).also(input::readFully).toString(Charsets.UTF_8)
        }
        fun skip(wire: Int, stop: Long) {
            when (wire) {
                0 -> varint(stop)
                1 -> { require(input.filePointer <= stop - 8); input.seek(input.filePointer + 8) }
                2 -> input.seek(end(stop))
                5 -> { require(input.filePointer <= stop - 4); input.seek(input.filePointer + 4) }
                else -> error("Unsupported ONNX protobuf wire type")
            }
        }
        fun visit(kind: Kind, stop: Long, depth: Int) {
            require(depth <= 64) { "ONNX graph nesting is too deep" }
            val external = mutableMapOf<String, String>()
            var externalLocation = false
            while (input.filePointer < stop) {
                val tag = varint(stop)
                require(tag > 0 && tag ushr 3 in 1..536870911L)
                val number = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
                if (kind == Kind.Model && number == 1 && wire == 0) { version = varint(stop) > 0; continue }
                if (kind == Kind.Tensor && number == 14 && wire == 0) { externalLocation = varint(stop) == 1L; continue }
                if (kind == Kind.Tensor && number == 13 && wire == 2) {
                    val entryEnd = end(stop)
                    var key = ""; var value = ""
                    while (input.filePointer < entryEnd) {
                        val entryTag = varint(entryEnd)
                        when (entryTag) { 10L -> key = string(entryEnd); 18L -> value = string(entryEnd); else -> skip((entryTag and 7).toInt(), entryEnd) }
                    }
                    require(key.isNotEmpty() && !external.containsKey(key)) { "Invalid ONNX external data" }
                    external[key] = value
                    continue
                }
                val child = when (kind) {
                    Kind.Model -> when (number) { 7 -> Kind.Graph; 25 -> Kind.Function; else -> null }
                    Kind.Graph -> when (number) { 1 -> Kind.Node; 5 -> Kind.Tensor; 15 -> Kind.Sparse; else -> null }
                    Kind.Node -> if (number == 5) Kind.Attribute else null
                    Kind.Attribute -> when (number) { 5, 10 -> Kind.Tensor; 6, 11 -> Kind.Graph; 22, 23 -> Kind.Sparse; else -> null }
                    Kind.Sparse -> if (number in 1..2) Kind.Tensor else null
                    Kind.Function -> when (number) { 7 -> Kind.Node; 11 -> Kind.Attribute; else -> null }
                    Kind.Tensor -> null
                }
                if (child != null) {
                    require(wire == 2) { "Invalid ONNX message type" }
                    if (kind == Kind.Model && number == 7) graph = true
                    visit(child, end(stop), depth + 1)
                } else skip(wire, stop)
            }
            require(input.filePointer == stop)
            if (externalLocation || external.isNotEmpty()) {
                val name = requireNotNull(external["location"]) { "Missing ONNX external-data location" }
                PortableBundlePath.normalize(name)
                references.add(name)
                if (!checkExternal) return
                val path = logicalPath.substringBeforeLast('/', "").let { if (it.isBlank()) name else "$it/$name" }
                val target = companions[path] ?: companions[name] ?: File(file.parentFile, name)
                require(target.isFile) { "Select the ONNX external weight file: $name" }
                val offset = external["offset"]?.toLong() ?: 0L
                val length = external["length"]?.toLong() ?: (target.length() - offset)
                require(offset >= 0 && offset <= target.length() && length >= 0 && length <= target.length() - offset) { "Truncated ONNX external weights: $name" }
            }
        }
        visit(Kind.Model, input.length(), 0)
        require(version && graph) { "Missing ONNX model version or graph" }
        references
    }
}
