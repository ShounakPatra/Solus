package com.shounak.localmeshai.utils

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

data class OnnxTensorInfo(
    val name: String,
    val elemType: Int,
    val dims: List<Long>
)

data class OnnxT2iGraphSpec(
    val inputIdsName: String,
    val inputIdsElemType: Int,
    val attentionMaskName: String?,
    val attentionMaskElemType: Int?,
    val sequenceLength: Int,
    val noiseInputName: String,
    val noiseElemType: Int,
    val noiseShape: List<Long>,
    val outputName: String,
    val outputElemType: Int,
    val outputShape: List<Long>
) {
    val imageWidth: Int get() = outputShape.getOrNull(3)?.toInt()?.takeIf { it > 0 } ?: 512
    val imageHeight: Int get() = outputShape.getOrNull(2)?.toInt()?.takeIf { it > 0 } ?: 512
}

/** Walk ONNX metadata without reading tensor payloads; external weights must stay in this generation. */
internal object OnnxBundleValidation {
    private enum class Kind { Model, Graph, Node, Attribute, Tensor, Sparse, Function }

    private const val ELEM_FLOAT = 1
    private const val ELEM_INT32 = 6
    private const val ELEM_INT64 = 7
    private const val ELEM_FLOAT16 = 10

    private data class CachedT2iSpec(val spec: OnnxT2iGraphSpec?)
    private val t2iSpecCache = ConcurrentHashMap<String, CachedT2iSpec>()

    fun inspectSingleGraphT2i(file: File): OnnxT2iGraphSpec? {
        if (!file.isFile) return null
        val len = file.length()
        if (len <= 0L) return null
        val cacheKey = "${file.absolutePath}|$len|${file.lastModified()}"
        t2iSpecCache[cacheKey]?.let { return it.spec }
        val spec = computeSingleGraphT2i(file)
        if (t2iSpecCache.size > 64) t2iSpecCache.clear()
        t2iSpecCache[cacheKey] = CachedT2iSpec(spec)
        return spec
    }

    private fun computeSingleGraphT2i(file: File): OnnxT2iGraphSpec? {
        val (inputs, outputs) = runCatching { readGraphIo(file) }.getOrNull() ?: return null
        if (inputs.isEmpty() || outputs.isEmpty()) return null

        val intInputs = inputs.filter { (it.elemType == ELEM_INT64 || it.elemType == ELEM_INT32) && it.dims.size == 2 }
        val inputIds = intInputs.firstOrNull { it.name.equals("input_ids", ignoreCase = true) }
            ?: intInputs.firstOrNull { it.name.contains("token", ignoreCase = true) || it.name.contains("input", ignoreCase = true) }
            ?: return null
        val attentionMask = intInputs.firstOrNull { it != inputIds && it.name.contains("mask", ignoreCase = true) }
            ?: intInputs.firstOrNull { it != inputIds }

        val noiseInput = inputs.firstOrNull {
            (it.elemType == ELEM_FLOAT || it.elemType == ELEM_FLOAT16) &&
                it.dims.size == 4 &&
                (it.name.equals("noise", ignoreCase = true) ||
                    it.name.contains("latent", ignoreCase = true) ||
                    it.name.contains("sample", ignoreCase = true))
        } ?: inputs.firstOrNull {
            (it.elemType == ELEM_FLOAT || it.elemType == ELEM_FLOAT16) &&
                it.dims.size == 4 &&
                (it.dims[2] >= 16L || it.dims[2] <= 0L) &&
                (it.dims[3] >= 16L || it.dims[3] <= 0L)
        } ?: return null

        val imageOutput = outputs.firstOrNull {
            (it.elemType == ELEM_FLOAT || it.elemType == ELEM_FLOAT16) &&
                it.dims.size == 4 &&
                (it.name.equals("image", ignoreCase = true) ||
                    it.name.contains("image", ignoreCase = true) ||
                    it.name.contains("sample", ignoreCase = true) ||
                    it.name.contains("output", ignoreCase = true))
        } ?: outputs.firstOrNull {
            (it.elemType == ELEM_FLOAT || it.elemType == ELEM_FLOAT16) &&
                it.dims.size == 4 &&
                (it.dims[1] == 3L || it.dims[3] == 3L)
        } ?: return null

        val seqLen = inputIds.dims.getOrNull(1)?.toInt()?.takeIf { it in 8..4096 } ?: 256
        val normalizedNoise = listOf(
            noiseInput.dims.getOrNull(0)?.takeIf { it > 0L } ?: 1L,
            noiseInput.dims.getOrNull(1)?.takeIf { it > 0L } ?: 3L,
            noiseInput.dims.getOrNull(2)?.takeIf { it > 0L } ?: 512L,
            noiseInput.dims.getOrNull(3)?.takeIf { it > 0L } ?: 512L
        )
        val normalizedOut = listOf(
            imageOutput.dims.getOrNull(0)?.takeIf { it > 0L } ?: 1L,
            imageOutput.dims.getOrNull(1)?.takeIf { it > 0L } ?: 3L,
            imageOutput.dims.getOrNull(2)?.takeIf { it > 0L } ?: normalizedNoise[2],
            imageOutput.dims.getOrNull(3)?.takeIf { it > 0L } ?: normalizedNoise[3]
        )

        return OnnxT2iGraphSpec(
            inputIdsName = inputIds.name,
            inputIdsElemType = inputIds.elemType,
            attentionMaskName = attentionMask?.name,
            attentionMaskElemType = attentionMask?.elemType,
            sequenceLength = seqLen,
            noiseInputName = noiseInput.name,
            noiseElemType = noiseInput.elemType,
            noiseShape = normalizedNoise,
            outputName = imageOutput.name,
            outputElemType = imageOutput.elemType,
            outputShape = normalizedOut
        )
    }

    fun readGraphIo(file: File): Pair<List<OnnxTensorInfo>, List<OnnxTensorInfo>> = RandomAccessFile(file, "r").use { input ->
        val inputs = mutableListOf<OnnxTensorInfo>()
        val outputs = mutableListOf<OnnxTensorInfo>()
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
            require(stop - input.filePointer <= 4096) { "Oversized ONNX identifier" }
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
        fun parseShape(stop: Long): List<Long> {
            val dims = mutableListOf<Long>()
            while (input.filePointer < stop) {
                val tag = varint(stop)
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (number == 1 && wire == 2) {
                    val dimEnd = end(stop)
                    var dimValue = -1L
                    while (input.filePointer < dimEnd) {
                        val dTag = varint(dimEnd)
                        val dNum = (dTag ushr 3).toInt()
                        val dWire = (dTag and 7).toInt()
                        if (dNum == 1 && dWire == 0) dimValue = varint(dimEnd)
                        else skip(dWire, dimEnd)
                    }
                    dims.add(dimValue)
                } else {
                    skip(wire, stop)
                }
            }
            return dims
        }
        fun parseTensorType(stop: Long): Pair<Int, List<Long>> {
            var elemType = 0
            var dims = emptyList<Long>()
            while (input.filePointer < stop) {
                val tag = varint(stop)
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    number == 1 && wire == 0 -> elemType = varint(stop).toInt()
                    number == 2 && wire == 2 -> dims = parseShape(end(stop))
                    else -> skip(wire, stop)
                }
            }
            return elemType to dims
        }
        fun parseTypeProto(stop: Long): Pair<Int, List<Long>> {
            var elemType = 0
            var dims = emptyList<Long>()
            while (input.filePointer < stop) {
                val tag = varint(stop)
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (number == 1 && wire == 2) {
                    val parsed = parseTensorType(end(stop))
                    elemType = parsed.first
                    dims = parsed.second
                } else {
                    skip(wire, stop)
                }
            }
            return elemType to dims
        }
        fun parseValueInfo(stop: Long): OnnxTensorInfo? {
            var name = ""
            var elemType = 0
            var dims = emptyList<Long>()
            while (input.filePointer < stop) {
                val tag = varint(stop)
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    number == 1 && wire == 2 -> name = string(stop)
                    number == 2 && wire == 2 -> {
                        val parsed = parseTypeProto(end(stop))
                        elemType = parsed.first
                        dims = parsed.second
                    }
                    else -> skip(wire, stop)
                }
            }
            return if (name.isNotBlank()) OnnxTensorInfo(name, elemType, dims) else null
        }
        fun parseGraph(stop: Long) {
            while (input.filePointer < stop) {
                val tag = varint(stop)
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    number == 11 && wire == 2 -> parseValueInfo(end(stop))?.let(inputs::add)
                    number == 12 && wire == 2 -> parseValueInfo(end(stop))?.let(outputs::add)
                    else -> skip(wire, stop)
                }
            }
        }
        val modelEnd = input.length()
        while (input.filePointer < modelEnd) {
            val tag = varint(modelEnd)
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (number == 7 && wire == 2) {
                parseGraph(end(modelEnd))
                break
            } else {
                skip(wire, modelEnd)
            }
        }
        inputs to outputs
    }

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
