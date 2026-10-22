package com.shounak.localmeshai.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real ORT execution with tiny generated graphs, not mocks of the cache/lifetime implementation. */
class OnnxTextExecutionTest {
    private fun number(value: Long): ByteArray {
        var remaining = value
        val bytes = ByteArrayOutputStream()
        do { bytes.write(((remaining and 127) or if (remaining ushr 7 != 0L) 128 else 0).toInt()); remaining = remaining ushr 7 } while (remaining != 0L)
        return bytes.toByteArray()
    }
    private fun int(field: Int, value: Long) = number((field*8).toLong()) + number(value)
    private fun bytes(field: Int, value: ByteArray) = number((field*8+2).toLong()) + number(value.size.toLong()) + value
    private fun text(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun value(name: String, type: Int, vararg shape: Long): ByteArray {
        val dimensions = shape.mapIndexed { index, dim -> bytes(1, if (dim < 0) text(2,"dynamic_$index") else int(1,dim)) }.fold(byteArrayOf(), ByteArray::plus)
        return text(1,name) + bytes(2,bytes(1,int(1,type.toLong()) + bytes(2,dimensions)))
    }
    private fun tensor(name: String, type: Int, shape: LongArray, raw: ByteArray): ByteArray =
        shape.fold(byteArrayOf()) { a,d -> a+int(1,d) } + int(2,type.toLong()) + text(8,name) + bytes(9,raw)
    private fun attr(name: String, value: Int) = text(1,name)+int(3,value.toLong())+int(20,2)
    private fun node(op: String, inputs: List<String>, output: String, attributes: ByteArray = byteArrayOf()): ByteArray =
        inputs.fold(byteArrayOf()) { a,name -> a+text(1,name) } + text(2,output)+text(4,op)+attributes
    private fun graph(cached: Boolean, half: Boolean = false): ByteArray {
        var graph = text(2,"decoder")
        graph += bytes(11,value("input_ids",6,1,if(cached) 1 else -1))
        graph += bytes(5,tensor("axes",7,longArrayOf(1),ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(2).array()))
        val offsets = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putFloat(it.toFloat()) } }.array()
        graph += bytes(5,tensor("offsets",1,longArrayOf(4),offsets))
        graph += bytes(1,node("Cast",listOf("input_ids"),"ids_float",bytes(5,attr("to",1))))
        graph += bytes(1,node("Unsqueeze",listOf("ids_float","axes"),"ids_3d"))
        graph += bytes(1,node("Add",listOf("ids_3d","offsets"),"float_logits"))
        graph += bytes(1,node(if(half) "Cast" else "Identity",listOf("float_logits"),"logits",if(half) bytes(5,attr("to",10)) else byteArrayOf()))
        graph += bytes(12,value("logits",if(half)10 else 1,1,if(cached)1 else -1,4))
        if(cached) {
            for (kind in listOf("key","value")) {
                val input = "past_key_values.0.$kind"
                val output = "present.0.$kind"
                graph += bytes(11,value(input,1,1,2,-1,4))
                graph += bytes(5,tensor("new_$kind",1,longArrayOf(1,2,1,4),ByteArray(32)))
                graph += bytes(1,node("Concat",listOf(input,"new_$kind"),output,bytes(5,attr("axis",2))))
                graph += bytes(12,value(output,1,1,2,-1,4))
            }
        }
        return int(1,8)+text(2,"Solus tests")+bytes(7,graph)+bytes(8,int(2,13))
    }
    private fun execute(cached: Boolean, half: Boolean, test: (OnnxTextSession) -> Unit) {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            OrtEnvironment.getEnvironment().createSession(graph(cached,half),options).use { session ->
                OnnxTextSession(session,JSONObject("""{"num_attention_heads":2,"hidden_size":8}""")).use(test)
            }
        }
    }
    @Test fun int32DynamicFullPrefixGraphExecutes() = execute(false,false) { decoder ->
        assertArrayEquals(floatArrayOf(3f,4f,5f,6f),decoder.logits(listOf(1,2,3)),0f)
        assertArrayEquals(floatArrayOf(4f,5f,6f,7f),decoder.logits(listOf(1,2,3,4)),0f)
    }
    @Test fun fixedTokenCachePrefillDecodeAndResetExecute() = execute(true,false) { decoder ->
        assertArrayEquals(floatArrayOf(3f,4f,5f,6f),decoder.logits(listOf(1,2,3)),0f)
        repeat(12) { step ->
            val tokens = (1..(4+step)).toList()
            val last = tokens.last().toFloat()
            assertArrayEquals(floatArrayOf(last,last+1,last+2,last+3),decoder.logits(tokens),0f)
        }
        decoder.reset()
        assertArrayEquals(floatArrayOf(5f,6f,7f,8f),decoder.logits(listOf(5)),0f)
    }
    @Test fun fp16LogitsAreConvertedByOrt() = execute(true,true) { decoder ->
        assertArrayEquals(floatArrayOf(3f,4f,5f,6f),decoder.logits(listOf(1,2,3)),0f)
    }
}
