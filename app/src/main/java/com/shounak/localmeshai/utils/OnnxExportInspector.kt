package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelTask

/** Walk top-level graph signatures and external-data locations, skipping tensor payloads. */
internal object OnnxExportInspector {
    data class Tensor(val name: String, val type: Long, val dimensions: List<Long>)
    data class Export(val inputs: List<Tensor>, val outputs: List<Tensor>, val external: Set<String>) {
        fun check(task: ModelTask) {
            if (task != ModelTask.Chat) return // Media uses its explicit component/processing contract.
            val names = inputs.map { it.name }.toSet()
            val caches = names.filter { it.startsWith("past_key_values.") }
            require("input_ids" in names && outputs.any { it.name == "logits" }) { "ONNX chat needs input_ids and logits; this is not a causal decoder export" }
            val known = setOf("input_ids", "attention_mask", "position_ids", "token_type_ids", "cache_position", "use_cache_branch", "past_sequence_length")
            require(names.all { it in known || it in caches }) { "Unsupported ONNX decoder inputs: ${names - known - caches.toSet()}" }
            val ids = inputs.single { it.name == "input_ids" }
            require(ids.type in setOf(6L, 7L) && ids.dimensions.size == 2 && ids.dimensions[0] in setOf(-1L, 1L)) { "ONNX input_ids must be integer [1, sequence]" }
            require(ids.dimensions[1] < 0 || (ids.dimensions[1] == 1L && caches.isNotEmpty())) { "This ONNX fixed-sequence export needs a padding/cache adapter" }
            caches.forEach { name ->
                val suffix = name.removePrefix("past_key_values.")
                require(outputs.any { it.name in setOf("present.$suffix", "present_key_values.$suffix", name) }) { "Missing ONNX cache output for $name" }
                val tensor = inputs.single { it.name == name }
                require(tensor.type in setOf(1L, 10L) && tensor.dimensions.size in 4..5) { "Unsupported ONNX cache layout for $name" }
            }
        }
    }

    fun inspect(length: Long, read: (Long, Int) -> ByteArray): Export {
        val reader = Reader(length, read)
        val inputs = mutableListOf<Tensor>(); val outputs = mutableListOf<Tensor>(); val external = linkedSetOf<String>()
        var graphFound = false; var version = 0L
        fun tensor(end: Long): Tensor {
            var name = ""; var type = 0L; val dimensions = mutableListOf<Long>()
            reader.fields(end) { field, wire -> when {
                field == 1 && wire == 2 -> { name = reader.string(end); true }
                field == 2 && wire == 2 -> reader.fields(reader.end(end)) { typeField, typeWire ->
                    if (typeField == 1 && typeWire == 2) reader.fields(reader.end(end)) { inner, innerWire -> when {
                        inner == 1 && innerWire == 0 -> { type = reader.number(end); true }
                        inner == 2 && innerWire == 2 -> {
                            reader.fields(reader.end(end)) { shape, shapeWire ->
                                if (shape == 1 && shapeWire == 2) {
                                    var dimension = -1L
                                    reader.fields(reader.end(end)) { dim, dimWire ->
                                        if (dim == 1 && dimWire == 0) { dimension = reader.number(end); true } else false
                                    }
                                    dimensions.add(dimension); true
                                } else false
                            }; true
                        }
                        else -> false
                    } }.let { true } else false
                }.let { true }
                else -> false
            } }
            return Tensor(name, type, dimensions)
        }
        reader.fields(length) { field, wire -> when {
            field == 1 && wire == 0 -> { version = reader.number(length); true }
            field == 7 && wire == 2 -> {
                graphFound = true
                reader.fields(reader.end(length)) { graph, graphWire -> when {
                    graph in 11..12 && graphWire == 2 -> {
                        val value = tensor(reader.end(length))
                        (if (graph == 11) inputs else outputs).add(value); true
                    }
                    graph == 5 && graphWire == 2 -> {
                        reader.fields(reader.end(length)) { weight, weightWire ->
                            if (weight == 13 && weightWire == 2) {
                                var key = ""; var value = ""
                                reader.fields(reader.end(length)) { entry, entryWire -> when {
                                    entry == 1 && entryWire == 2 -> { key = reader.string(length); true }
                                    entry == 2 && entryWire == 2 -> { value = reader.string(length); true }
                                    else -> false
                                } }
                                if (key == "location") external.add(PortableBundlePath.normalize(value))
                                true
                            } else false
                        }; true
                    }
                    else -> false
                } }; true
            }
            else -> false
        } }
        require(version > 0 && graphFound) { "Missing ONNX graph or model version" }
        return Export(inputs, outputs, external)
    }

    private class Reader(private val length: Long, private val read: (Long, Int) -> ByteArray) {
        private var at = 0L
        private var fields = 0
        fun number(end: Long): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(at < end) { "Truncated ONNX metadata" }
                val byte = read(at++, 1)[0].toInt() and 255
                require(shift != 63 || byte <= 1) { "Invalid ONNX integer" }
                result = result or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return result
            }
            error("Invalid ONNX integer")
        }
        fun end(parent: Long): Long {
            val size = number(parent)
            require(size >= 0 && size <= parent - at) { "Invalid ONNX metadata length" }
            return at + size
        }
        fun string(parent: Long): String {
            val stop = end(parent); require(stop - at <= 4096) { "Oversized ONNX name" }
            return String(read(at, (stop - at).toInt()), Charsets.UTF_8).also { at = stop }
        }
        fun fields(stop: Long, visit: (Int, Int) -> Boolean) {
            require(stop in at..length) { "Invalid ONNX message" }
            while (at < stop) {
                require(++fields <= 250_000) { "ONNX metadata preflight limit exceeded" }
                val tag = number(stop)
                require(tag > 0 && tag ushr 3 in 1..536870911L) { "Invalid ONNX field" }
                val wire = (tag and 7).toInt()
                if (!visit((tag ushr 3).toInt(), wire)) when (wire) {
                    0 -> number(stop)
                    1 -> at += 8
                    2 -> at = end(stop)
                    5 -> at += 4
                    else -> error("Unsupported ONNX field type")
                }
                require(at <= stop) { "Truncated ONNX metadata" }
            }
        }
    }
}
