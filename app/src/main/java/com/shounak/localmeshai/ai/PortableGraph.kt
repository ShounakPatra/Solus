package com.shounak.localmeshai.ai

import ai.onnxruntime.*
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelTask
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.*

/** Explicit floating media graphs; token inputs remain integers and are never requantized. */
internal class PortableGraph(file: File, format: ModelFormat) : AutoCloseable {
    data class Input(val name: String, val shape: LongArray, val integer: Boolean)
    data class Value(val values: FloatArray, val shape: LongArray)
    private val ort = if (format == ModelFormat.Onnx) OrtSession.SessionOptions().use {
        it.setIntraOpNumThreads(2); OrtEnvironment.getEnvironment().createSession(file.path, it)
    } else null
    private val lite = if (format == ModelFormat.TensorFlowLite) Interpreter(file, Interpreter.Options().setNumThreads(2)) else null
    val inputs: List<Input> = try { ort?.inputInfo?.map { (name, node) ->
        val info = node.info as? TensorInfo ?: error("Expected graph tensor")
        require(info.type in setOf(OnnxJavaType.FLOAT, OnnxJavaType.INT32, OnnxJavaType.INT64)) { "Unsupported media input dtype ${info.type}" }
        Input(name, info.shape, info.type != OnnxJavaType.FLOAT)
    } ?: requireNotNull(lite).let { model -> (0 until model.inputTensorCount).map { i ->
        val tensor = model.getInputTensor(i)
        require(tensor.dataType() in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8, DataType.INT32, DataType.INT64)) { "Unsupported TFLite media dtype" }
        Input(PortableTextEngine.tokenInputName(tensor.name()), tensor.shapeSignature().map(Int::toLong).toLongArray(), tensor.dataType() in setOf(DataType.INT32, DataType.INT64))
    } } } catch (failure: Throwable) {
        runCatching { ort?.close() }.onFailure(failure::addSuppressed)
        runCatching { lite?.close() }.onFailure(failure::addSuppressed)
        throw failure
    }

    fun requireTask(task: ModelTask) {
        when (task) {
            ModelTask.ImageClassification -> require(inputs.size == 1 && !inputs[0].integer && inputs[0].shape.size == 4) { "Classifier must expose one RGB image tensor" }
            ModelTask.VisionLanguage -> {
                require(inputs.count { !it.integer } == 1 && inputs.single { !it.integer }.shape.size == 4) { "Expected a fused RGB image + token decoder; separate vision encoders need another adapter" }
                require(inputs.any { it.name == "input_ids" && it.integer } && inputs.filter { it.integer }.all { it.name in setOf("input_ids","attention_mask","position_ids") }) { "Unsupported fused vision decoder inputs" }
            }
            ModelTask.SpeechRecognition -> require(inputs.count { !it.integer && it.name != "attention_mask" && it.shape.size in 1..2 } == 1 &&
                inputs.all { it.name == "attention_mask" || (!it.integer && it.shape.size in 1..2) }) { "Expected a CTC waveform input and optional attention mask" }
            else -> error("Unsupported single graph task")
        }
    }

    fun run(floating: Map<String, Value>, integers: Map<String, Pair<IntArray, LongArray>> = emptyMap()): Value {
        require((floating.keys + integers.keys) == inputs.map { it.name }.toSet()) { "Graph inputs do not match this task pipeline" }
        val session = ort
        if (session != null) {
            val owned = mutableListOf<OnnxTensor>()
            try {
                val feed = inputs.associate { input ->
                    val tensor = floating[input.name]?.let { value -> OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(value.values), value.shape) }
                        ?: integers.getValue(input.name).let { (values, shape) ->
                            val info = session.inputInfo.getValue(input.name).info as TensorInfo
                            if (info.type == OnnxJavaType.INT32) OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), IntBuffer.wrap(values), shape)
                            else OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), LongBuffer.wrap(LongArray(values.size) { values[it].toLong() }), shape)
                        }
                    owned.add(tensor); input.name to tensor
                }
                val output = listOf("logits", "last_hidden_state", "output").firstOrNull { it in session.outputNames }
                    ?: session.outputNames.singleOrNull() ?: error("Ambiguous graph outputs")
                session.run(feed, setOf(output)).use { result ->
                    val tensor = result.get(output).orElse(null) as? OnnxTensor ?: error("Missing graph output")
                    val buffer = requireNotNull(tensor.floatBuffer) { "Expected floating graph output" }
                    return Value(FloatArray(buffer.remaining()).also(buffer::get), tensor.info.shape)
                }
            } finally { owned.forEach { it.close() } }
        }
        val model = requireNotNull(lite)
        for ((index, input) in inputs.withIndex()) {
            val shape = floating[input.name]?.shape ?: integers.getValue(input.name).second
            require(shape.all { it in 1..Int.MAX_VALUE }) { "Invalid TFLite tensor shape" }
            model.resizeInput(index, shape.map(Long::toInt).toIntArray(), true)
        }
        model.allocateTensors()
        val feed = inputs.mapIndexed { index, input ->
            val tensor = model.getInputTensor(index)
            val buffer = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
            val floats = floating[input.name]
            if (floats != null) {
                val quant = tensor.quantizationParams()
                when (tensor.dataType()) {
                    DataType.FLOAT32 -> floats.values.forEach(buffer::putFloat)
                    DataType.INT8, DataType.UINT8 -> floats.values.forEach { value -> buffer.put(PortableTensorMath.quantize(value, quant.scale, quant.zeroPoint, tensor.dataType() == DataType.UINT8).toByte()) }
                    else -> error("Expected floating or quantized media input")
                }
            } else integers.getValue(input.name).first.forEach { value ->
                if (tensor.dataType() == DataType.INT64) buffer.putLong(value.toLong()) else buffer.putInt(value)
            }
            buffer.rewind(); buffer as Any
        }.toTypedArray()
        require(model.outputTensorCount == 1) { "This TFLite task requires one output tensor" }
        val before = model.getOutputTensor(0)
        require(before.numBytes() in 1..268_435_456) { "Graph output is too large" }
        val output = ByteBuffer.allocateDirect(before.numBytes()).order(ByteOrder.nativeOrder())
        model.runForMultipleInputsOutputs(feed, mutableMapOf(0 to output as Any))
        output.rewind()
        val tensor = model.getOutputTensor(0)
        val quant = tensor.quantizationParams()
        val values = when (tensor.dataType()) {
            DataType.FLOAT32 -> FloatArray(tensor.numElements()) { output.float }
            DataType.INT8, DataType.UINT8 -> FloatArray(tensor.numElements()) {
                val raw = output.get().toInt().let { if (tensor.dataType() == DataType.UINT8) it and 255 else it }
                PortableTensorMath.dequantize(raw, quant.scale, quant.zeroPoint)
            }
            else -> error("Expected floating or quantized media output")
        }
        return Value(values, tensor.shape().map(Int::toLong).toLongArray())
    }
    override fun close() {
        try { ort?.close() } finally { lite?.close() }
    }
}
