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

    fun initializeStt() {
        onLog("STT init -> Loading models from assets...")

        try {
            // 1. Initialize English Zipformer (Transducer)
            val englishTransducer = OfflineTransducerModelConfig(
                encoder = "$STT_EN_MODEL_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                decoder = "$STT_EN_MODEL_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                joiner = "$STT_EN_MODEL_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
            )
            val englishModelConfig = OfflineModelConfig(
                transducer = englishTransducer,
                tokens = "$STT_EN_MODEL_DIR/tokens.txt",
                numThreads = 2,
                debug = false
            )
            englishRecognizer = OfflineRecognizer(
                context.assets,
                OfflineRecognizerConfig(modelConfig = englishModelConfig)
            )
            activeRecognizer = englishRecognizer
            onLog("STT init -> English Zipformer loaded & active!")

            // 2. Initialize Telugu IndicConformer (NeMo CTC)
            // 2. Initialize Telugu IndicConformer
            val teluguModelConfig = OfflineModelConfig(
                tokens = "$STT_INDIC_MODEL_DIR/tokens.txt",
                numThreads = 2,
                debug = false
            )

            // Assign the NeMo/CTC config directly to the property
            teluguModelConfig.nemo  = OfflineNemoEncDecCtcModelConfig(
                model = "$STT_INDIC_MODEL_DIR/model.int8.onnx"
            )

            teluguRecognizer = OfflineRecognizer(
                context.assets,
                OfflineRecognizerConfig(modelConfig = teluguModelConfig)
            )
            onLog("STT init -> Telugu IndicConformer loaded!")

        } catch (e: Exception) {
            onLog("STT init error -> ${e.message}")
        }
    }

    fun initializeTts() {
        onLog("TTS init -> Initializing English and Telugu MMS-TTS models...")
        try {
            // 1. Initialize English MMS-TTS
            val enVitsConfig = OfflineTtsVitsModelConfig(
                model = "$TTS_EN_MODEL_DIR/model.onnx",
                tokens = "$TTS_EN_MODEL_DIR/tokens.txt"
            )
            val enTtsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = enVitsConfig,
                    numThreads = 2
                )
            )
            englishTts = OfflineTts(context.assets, enTtsConfig)
            activeTts = englishTts
            onLog("TTS init -> English MMS-TTS loaded & active!")

            // 2. Initialize Telugu MMS-TTS
            val teVitsConfig = OfflineTtsVitsModelConfig(
                model = "$TTS_TE_MODEL_DIR/model.onnx",
                tokens = "$TTS_TE_MODEL_DIR/tokens.txt"
            )
            val teTtsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = teVitsConfig,
                    numThreads = 2
                )
            )
            teluguTts = OfflineTts(context.assets, teTtsConfig)
            onLog("TTS init -> Telugu MMS-TTS loaded!")

        } catch (e: Exception) {
            onLog("TTS init error -> ${e.message}")
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
        const val STT_EN_MODEL_DIR = "sherpa-stt-en"
        const val STT_INDIC_MODEL_DIR = "sherpa-stt-indic"
        const val TTS_EN_MODEL_DIR = "sherpa-tts-en"
        const val TTS_TE_MODEL_DIR = "sherpa-tts-te"
    }
}