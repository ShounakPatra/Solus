package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ai.TfliteTextPlan
import com.shounak.localmeshai.models.ModelTask
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads tables, not tensor weights or native kernels. Also usable with bounded HTTP ranges. */
internal object TfliteExportInspector {
    data class Export(val inputs: List<TfliteTextPlan.Tensor>, val outputs: List<TfliteTextPlan.Tensor>,
        val customOps: List<String>, val config: JSONObject?, val processor: JSONObject?) {
        fun check(config: JSONObject) {
            require(customOps.isEmpty()) { "This TFLite export requires custom operators (${customOps.joinToString()}). Use the author's .task or .litertlm package; adding config.json cannot supply those kernels." }
            if (PortableModelProfile.task(config) == ModelTask.Chat) {
                TfliteTextPlan(inputs, outputs, config.optString("model_type"), config.optInt("vocab_size", 0))
            }
            when (PortableModelProfile.task(config)) {
                ModelTask.ImageClassification -> require(inputs.size == 1 && inputs[0].shape.size == 4 &&
                    inputs[0].type in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8) &&
                    outputs.size == 1 && outputs[0].shape.size == 2) { "TFLite classification needs one RGB image input and one class-score output" }
                ModelTask.VisionLanguage -> require(inputs.count { it.type == DataType.FLOAT32 && it.shape.size == 4 } == 1 &&
                    inputs.any { it.name == "input_ids" && it.type in setOf(DataType.INT32, DataType.INT64) } &&
                    inputs.all { it.shape.size == 4 || it.name in setOf("input_ids", "attention_mask", "position_ids") } && outputs.size == 1) {
                    "This TFLite vision-language export needs a fused image/token decoder without required cache inputs"
                }
                ModelTask.SpeechRecognition -> if (PortableModelProfile.isCtc(config)) require(
                    inputs.count { it.type == DataType.FLOAT32 && it.shape.size in 1..2 } == 1 &&
                        inputs.all { it.type == DataType.FLOAT32 || it.name == "attention_mask" } && outputs.size == 1) {
                    "TFLite CTC transcription needs a waveform input and one frame-logits output"
                }
                else -> Unit
            }
        }
    }

    fun file(file: File): Export = RandomAccessFile(file, "r").use { input ->
        inspect(file.length()) { offset, count -> input.seek(offset); ByteArray(count).also(input::readFully) }
    }

    fun inspect(length: Long, read: (Long, Int) -> ByteArray): Export {
        val fb = Tables(length, read)
        require(String(fb.bytes(4, 4), Charsets.US_ASCII) == "TFL3") { "Invalid TensorFlow Lite header" }
        val model = fb.target(0)
        val graph = fb.tables(model, 2).firstOrNull() ?: error("TFLite has no graph")
        val tensors = fb.tables(graph, 0)
        fun tensor(index: Int): TfliteTextPlan.Tensor {
            require(index in tensors.indices) { "Invalid TFLite tensor index" }
            val table = tensors[index]
            val shape = fb.ints(table, 0)
            val name = com.shounak.localmeshai.ai.PortableTextEngine.tokenInputName(fb.string(table, 3))
            val typeCode = fb.byteField(table, 1)
            val type = when (typeCode) {
                0 -> DataType.FLOAT32; 2 -> DataType.INT32
                3 -> DataType.UINT8; 4 -> DataType.INT64; 9 -> DataType.INT8
                else -> throw IllegalArgumentException("This TFLite export requires unsupported I/O type $typeCode for '$name'. Use the author's packaged .task/.litertlm model or a compatible graph adapter.")
            }
            return TfliteTextPlan.Tensor(name, shape, fb.ints(table, 7).takeIf { it.isNotEmpty() } ?: shape, type)
        }
        val inputs = fb.ints(graph, 1).map(::tensor)
        val outputs = fb.ints(graph, 2).map(::tensor)
        val custom = fb.tables(model, 1).mapNotNull { op -> fb.string(op, 1).takeIf { it.isNotBlank() } }
        var derived: JSONObject? = null
        var processor: JSONObject? = null
        val metaEntry = fb.tables(model, 6).firstOrNull { fb.string(it, 0) == "TFLITE_METADATA" }
        if (metaEntry != null) {
            val buffers = fb.tables(model, 4)
            val index = fb.intField(metaEntry, 1)
            require(index in buffers.indices) { "Invalid metadata buffer index" }
            val data = fb.vector(buffers[index], 0)
            if (data != null) {
                val size = fb.int(data)
                require(size in 8..1_048_576) { "Oversized TFLite metadata" }
                val raw = fb.bytes(data + 4, size)
                val meta = Tables(raw.size.toLong()) { offset, count -> raw.copyOfRange(offset.toInt(), offset.toInt() + count) }
                require(String(meta.bytes(4, 4), Charsets.US_ASCII) == "M001") { "Unsupported TFLite metadata version" }
                val sub = meta.tables(meta.target(0), 3).firstOrNull()
                val inMeta = sub?.let { meta.tables(it, 2) }.orEmpty()
                val outMeta = sub?.let { meta.tables(it, 3) }.orEmpty()
                val input = inputs.singleOrNull()
                val output = outputs.singleOrNull()
                // A shape alone cannot distinguish a classifier from an arbitrary image graph.
                val content = inMeta.singleOrNull()?.let { meta.table(it, 3) }
                val rgb = content?.takeIf { meta.byteField(it, 0) == 2 }?.let { meta.table(it, 1) }
                    ?.let { meta.byteField(it, 0) == 1 } == true
                if (rgb && input != null && input.shape.size == 4 && input.shape[0] == 1 && input.shape[3] == 3 &&
                    output != null && output.shape.size == 2 && output.shape[0] == 1 && output.shape[1] > 1 &&
                    input.type in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8) &&
                    outMeta.singleOrNull()?.let { meta.tables(it, 4).isEmpty() } == true) {
                    val units = meta.tables(inMeta.single(), 4)
                    val norm = units.singleOrNull()?.takeIf { meta.byteField(it, 0) == 1 }?.let { meta.table(it, 1) }
                    if (norm != null || (units.isEmpty() && input.type == DataType.UINT8)) {
                        processor = JSONObject().put("do_rescale", false).put("do_normalize", norm != null)
                        if (norm != null) {
                            fun values(field: Int): JSONArray {
                                val vector = requireNotNull(meta.vector(norm, field))
                                val count = meta.int(vector)
                                require(count == 1 || count == 3) { "Unsupported image normalization channels" }
                                return JSONArray().apply { repeat(3) { channel ->
                                    val value = Float.fromBits(meta.int(vector + 4 + 4 * if (count == 1) 0 else channel))
                                    require(value.isFinite() && (field != 1 || value > 0)) { "Invalid image normalization" }
                                    put(value.toDouble())
                                } }
                            }
                            processor.put("image_mean", values(0)).put("image_std", values(1))
                        }
                        derived = JSONObject().put("solus_task", "image_classification")
                    }
                }
            }
        }
        return Export(inputs, outputs, custom, derived, processor)
    }

    /** Bounded FlatBuffer access with no recursion and no reads of weight vectors. */
    private class Tables(private val length: Long, private val read: (Long, Int) -> ByteArray) {
        private var budget = 2_097_152
        fun bytes(offset: Long, count: Int): ByteArray {
            require(offset >= 0 && count >= 0 && offset <= length - count && count <= budget) { "Invalid or oversized TFLite metadata" }
            budget -= count
            return read(offset, count).also { require(it.size == count) { "Truncated TFLite metadata" } }
        }
        fun int(offset: Long) = ByteBuffer.wrap(bytes(offset, 4)).order(ByteOrder.LITTLE_ENDIAN).int
        private fun short(offset: Long) = ByteBuffer.wrap(bytes(offset, 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
        fun target(offset: Long): Long = offset + (int(offset).toLong() and 0xffffffffL)
        private fun field(table: Long, index: Int): Long? {
            val vt = table - int(table)
            val slot = 4 + index * 2
            return if (slot + 2 > short(vt)) null else short(vt + slot).takeIf { it != 0 }?.let { table + it }
        }
        fun byteField(table: Long, index: Int) = field(table, index)?.let { bytes(it, 1)[0].toInt() and 255 } ?: 0
        fun intField(table: Long, index: Int) = field(table, index)?.let(::int) ?: 0
        fun table(table: Long, index: Int) = field(table, index)?.let(::target)
        fun vector(table: Long, index: Int) = table(table, index)
        fun tables(table: Long, index: Int): List<Long> {
            val vector = vector(table, index) ?: return emptyList()
            val count = int(vector); require(count in 0..100_000) { "Oversized TFLite table vector" }
            return List(count) { target(vector + 4 + it * 4L) }
        }
        fun ints(table: Long, index: Int): IntArray {
            val vector = vector(table, index) ?: return intArrayOf()
            val count = int(vector); require(count in 0..4096) { "Oversized TFLite dimensions" }
            return IntArray(count) { int(vector + 4 + it * 4L) }
        }
        fun string(table: Long, index: Int): String {
            val start = table(table, index) ?: return ""
            val count = int(start); require(count in 0..16384) { "Oversized TFLite name" }
            return String(bytes(start + 4, count), Charsets.UTF_8)
        }
    }
}
