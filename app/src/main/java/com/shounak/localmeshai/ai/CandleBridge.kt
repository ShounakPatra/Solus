package com.shounak.localmeshai.ai

/** CPU Candle and Hugging Face tokenizers, compiled for Android with cargo-ndk. */
internal object CandleBridge {
    init { System.loadLibrary("solus_candle") }
    external fun tokenId(handle: Long, token: String): Int
    external fun languageTokens(handle: Long): IntArray
    external fun whisperFeatures(pcm: FloatArray, melBins: Int): FloatArray
    external fun diffusionStep(latents: FloatArray, prediction: FloatArray, index: Int, vPrediction: Boolean): FloatArray
    external fun createMedia(directory: String, weights: Array<String>, task: String): Long
    external fun classifyImage(handle: Long, pixels: FloatArray): FloatArray
    external fun transcribeAudio(handle: Long, pcm: FloatArray): String
    external fun generateImage(handle: Long, prompt: String): FloatArray
    external fun resetMedia(handle: Long)
    external fun stopMedia(handle: Long)
    external fun closeMedia(handle: Long)
    external fun create(directory: String, weights: Array<String>, contextTokens: Int): Long
    external fun encodeRendered(handle: Long, text: String): IntArray
    external fun encode(handle: Long, text: String): IntArray
    external fun decode(handle: Long, tokens: IntArray): String
    external fun forward(handle: Long, tokens: IntArray, position: Int): FloatArray
    external fun reset(handle: Long)
    external fun close(handle: Long)
}
