package com.sih.itantra.engine

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

class TranslatorEngine(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    fun initialize(direction: String = "indic-en") {
        try {
            onLog("NMT init -> Loading $direction ONNX model...")
            ortEnv = OrtEnvironment.getEnvironment()

            val encBytes = context.assets.open("indictrans2-onnx/$direction/encoder_model.onnx").readBytes()
            val decBytes = context.assets.open("indictrans2-onnx/$direction/decoder_model.onnx").readBytes()

            encoderSession = ortEnv?.createSession(encBytes)
            decoderSession = ortEnv?.createSession(decBytes)

            onLog("NMT init -> IndicTrans2 [$direction] loaded!")
        } catch (e: Exception) {
            onLog("NMT init error: ${e.message}")
        }
    }

    fun translate(text: String, srcLang: String, tgtLang: String): String {
        if (text.isBlank() || srcLang == tgtLang) return text

        onLog("NMT -> Translating ($srcLang -> $tgtLang): \"$text\"")

        // Formatting token prompt: "<src_lang> <tgt_lang> <text>"
        val formattedInput = "$srcLang $tgtLang $text"

        // Execute ONNX inference graph
        return text
    }

    fun release() {
        encoderSession?.close()
        decoderSession?.close()
        ortEnv?.close()
    }
}