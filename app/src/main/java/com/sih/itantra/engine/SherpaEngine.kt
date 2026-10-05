package com.sih.itantra.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class SherpaEngine(
    private val context: Context,
    private val onLog: (message: String) -> Unit
) {
    private var englishRecognizer: OfflineRecognizer? = null
    private var teluguRecognizer: OfflineRecognizer? = null
    private var activeRecognizer: OfflineRecognizer? = null

    private var englishTts: OfflineTts? = null
    private var teluguTts: OfflineTts? = null
    private var activeTts: OfflineTts? = null

    private var currentLanguage: String = "en"

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val pcmBufferStream = ByteArrayOutputStream()

    private val sampleRate = 16000

    private fun assetExists(path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (e: Exception) {
            onLog("CRITICAL: Missing asset file -> $path")
            false
        }
    }

    fun initializeStt() {
        onLog("STT init -> Verifying and loading models...")

        try {
            val encoderPath = "$STT_EN_MODEL_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
            val decoderPath = "$STT_EN_MODEL_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
            val joinerPath  = "$STT_EN_MODEL_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
            val tokensPath  = "$STT_EN_MODEL_DIR/tokens.txt"

            if (!assetExists(encoderPath) || !assetExists(tokensPath)) {
                onLog("STT init skipped -> English asset files missing!")
                return
            }

            val englishTransducer = OfflineTransducerModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                joiner = joinerPath
            )
            val englishModelConfig = OfflineModelConfig(
                transducer = englishTransducer,
                tokens = tokensPath,
                numThreads = 2,
                debug = false
            )
            englishRecognizer = OfflineRecognizer(
                context.assets,
                OfflineRecognizerConfig(modelConfig = englishModelConfig)
            )
            activeRecognizer = englishRecognizer
            onLog("STT init -> English Zipformer loaded & active!")

            // 2. Initialize Telugu IndicConformer
            val teluguModelPath  = "$STT_INDIC_MODEL_DIR/model.int8.onnx"
            val teluguTokensPath = "$STT_INDIC_MODEL_DIR/tokens.txt"

            if (assetExists(teluguModelPath) && assetExists(teluguTokensPath)) {
                val teluguModelConfig = OfflineModelConfig(
                    tokens = teluguTokensPath,
                    numThreads = 2,
                    debug = false
                ).apply {
                    nemo = OfflineNemoEncDecCtcModelConfig(model = teluguModelPath)
                }

                teluguRecognizer = OfflineRecognizer(
                    context.assets,
                    OfflineRecognizerConfig(modelConfig = teluguModelConfig)
                )
                onLog("STT init -> Telugu IndicConformer loaded!")
            }

        } catch (t: Throwable) {
            onLog("STT init error -> ${t.localizedMessage}")
        }
    }

    fun initializeTts() {
        onLog("TTS init -> Initializing English and Telugu MMS-TTS models...")
        try {
            val enModel = "$TTS_EN_MODEL_DIR/model.onnx"
            val enTokens = "$TTS_EN_MODEL_DIR/tokens.txt"

            if (assetExists(enModel) && assetExists(enTokens)) {
                val enVitsConfig = OfflineTtsVitsModelConfig(
                    model = enModel,
                    tokens = enTokens
                )
                val enTtsConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = enVitsConfig, numThreads = 2)
                )
                englishTts = OfflineTts(context.assets, enTtsConfig)
                activeTts = englishTts
                onLog("TTS init -> English MMS-TTS loaded & active!")
            }

            val teModel = "$TTS_TE_MODEL_DIR/model.onnx"
            val teTokens = "$TTS_TE_MODEL_DIR/tokens.txt"

            if (assetExists(teModel) && assetExists(teTokens)) {
                val teVitsConfig = OfflineTtsVitsModelConfig(
                    model = teModel,
                    tokens = teTokens
                )
                val teTtsConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = teVitsConfig, numThreads = 2)
                )
                teluguTts = OfflineTts(context.assets, teTtsConfig)
                onLog("TTS init -> Telugu MMS-TTS loaded!")
            }

        } catch (t: Throwable) {
            onLog("TTS init error -> ${t.localizedMessage}")
        }
    }
    fun setLanguage(lang: String) {
        currentLanguage = lang
        when (lang) {
            "te" -> {
                activeRecognizer = teluguRecognizer ?: englishRecognizer
                activeTts = teluguTts ?: englishTts
                onLog("Language switched to: Telugu")
            }
            else -> {
                activeRecognizer = englishRecognizer
                activeTts = englishTts
                onLog("Language switched to: English")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (activeRecognizer == null) {
            onLog("STT listen error -> Active recognizer is null")
            return
        }

        isRecording.set(true)
        pcmBufferStream.reset()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        audioRecord?.startRecording()
        onLog("STT listen [$currentLanguage] -> Recording started (16kHz PCM16)")

        recordingThread = Thread {
            val rawBuffer = ShortArray(1600)
            while (isRecording.get()) {
                val readShorts = audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: 0
                if (readShorts > 0) {
                    for (i in 0 until readShorts) {
                        pcmBufferStream.write(rawBuffer[i].toInt() and 0xFF)
                        pcmBufferStream.write((rawBuffer[i].toInt() shr 8) and 0xFF)
                    }
                }
            }
        }.apply { start() }
    }

    fun stopListeningAndDecode(): String {
        isRecording.set(false)
        recordingThread?.join(500)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val recognizer = activeRecognizer
        if (recognizer == null) {
            onLog("STT decode error -> Active recognizer not initialized")
            return "Recognizer error"
        }

        onLog("STT decode [$currentLanguage] -> Running recognition...")

        val pcmBytes = pcmBufferStream.toByteArray()
        if (pcmBytes.isEmpty()) return "No audio recorded"

        val floatSamples = FloatArray(pcmBytes.size / 2)
        for (i in floatSamples.indices) {
            val shortVal = (pcmBytes[i * 2].toInt() and 0xFF) or (pcmBytes[i * 2 + 1].toInt() shl 8)
            floatSamples[i] = shortVal / 32768.0f
        }

        val stream = recognizer.createStream()
        stream.acceptWaveform(floatSamples, sampleRate)
        recognizer.decode(stream)

        val resultObj = recognizer.getResult(stream)
        val decodedText = resultObj.text.trim()
        stream.release()

        onLog("STT decoded [$currentLanguage]: \"$decodedText\"")
        return if (decodedText.isNotBlank()) decodedText else "No speech detected"
    }

    fun speak(text: String) {
        val tts = activeTts
        if (tts == null) {
            onLog("TTS speak error -> Active TTS engine not initialized")
            return
        }

        Thread {
            try {
                onLog("TTS speak [$currentLanguage] -> Synthesizing \"$text\"")
                val audio = tts.generate(text, sid = 0, speed = 1.0f)
                val samples = audio.samples

                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    audio.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    samples.size * 2,
                    AudioTrack.MODE_STATIC
                )

                val shortSamples = ShortArray(samples.size) { i ->
                    (samples[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }

                track.write(shortSamples, 0, shortSamples.size)
                track.play()
            } catch (e: Exception) {
                onLog("TTS playback failed: ${e.message}")
            }
        }.start()
    }

    fun shutdown() {
        isRecording.set(false)
        englishRecognizer?.release()
        teluguRecognizer?.release()
        englishTts?.release()
        teluguTts?.release()
        onLog("Sherpa engine hooks released")
    }

    companion object {
            const val STT_EN_MODEL_DIR = "sherpa-stt/sherpa_zipformer_en"
            const val STT_INDIC_MODEL_DIR = "sherpa-stt/sherpa_IndicConformer"
            const val TTS_EN_MODEL_DIR = "sherpa-tts/sherpa-tts-en"
            const val TTS_TE_MODEL_DIR = "sherpa-tts/sherpa-tts-te"
    }
}