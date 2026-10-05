package com.sih.itantra.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File

class TranslatorEngine(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    companion object {
        private const val ROOT = "indictrans2-onnx"
        private const val MAX_SRC_TOKENS = 250
        private const val MAX_NEW_TOKENS = 200

        /** U+2581 — SentencePiece space marker */
        private const val SPM_SPACE = "▁"

        private val LANG_TAGS = mapOf(
            "en" to "eng_Latn", "te" to "tel_Telu", "hi" to "hin_Deva",
            "ta" to "tam_Taml", "kn" to "kan_Knda", "ml" to "mal_Mlym",
            "mr" to "mar_Deva", "bn" to "ben_Beng", "gu" to "guj_Gujr",
            "pa" to "pan_Guru", "or" to "ory_Orya", "ur" to "urd_Arab"
        )

        /** Unicode block base of each Indic script (for Devanagari unification) */
        private val SCRIPT_BASES = mapOf(
            "hi" to 0x0900, "te" to 0x0C00, "ta" to 0x0B80, "kn" to 0x0C80,
            "ml" to 0x0D00, "bn" to 0x0980, "gu" to 0x0A80, "pa" to 0x0A00,
            "or" to 0x0B00
        )
        private const val DEVA_BASE = 0x0900
    }

    private data class NmtConfig(val eosId: Int, val padId: Int, val decoderStartId: Int)

    private class Loaded(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val pieceToId: Map<String, Int>,
        val idToPiece: Map<Int, String>,
        val maxPieceLen: Int,
        val unkId: Int,
        val cfg: NmtConfig
    ) { fun close() { encoder.close(); decoder.close() } }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile private var loaded: Loaded? = null
    @Volatile private var loadedDirection: String? = null

    // ------------------------------------------------------------ public API

    fun initialize(srcLang: String, tgtLang: String) =
        ensureLoaded(directionOf(srcLang, tgtLang))

    fun prewarm(srcLang: String, tgtLang: String) =
        ensureLoaded(directionOf(srcLang, tgtLang))

    @Synchronized
    fun translate(text: String, srcLang: String, tgtLang: String): String {
        if (text.isBlank() || srcLang == tgtLang) return text
        val t0 = System.currentTimeMillis()
        return try {
            val model = ensureLoaded(directionOf(srcLang, tgtLang)) ?: return text
            val srcTag = LANG_TAGS[srcLang] ?: srcLang
            val tgtTag = LANG_TAGS[tgtLang] ?: tgtLang

            // 1. Preprocess: Indic source -> Devanagari (IndicTrans2 convention)
            val srcText = preprocessSource(text, srcLang)

            // 2. Tokenize:  [src_lang_token] pieces... [</s>]
            val langId = model.pieceToId[srcTag]
                ?: run { onLog("NMT error -> '$srcTag' missing from vocab.tsv"); return text }
            val body = encodeTokens(model, srcText).take(MAX_SRC_TOKENS)
            val inputIds = intArrayOf(langId) + body.toIntArray() + intArrayOf(model.cfg.eosId)
            onLog("NMT -> src token ids: ${inputIds.take(12).joinToString()} ... (${inputIds.size} total)")

            // 3. Encoder forward
            val idsTensor = OnnxTensor.createTensor(ortEnv, arrayOf(inputIds.map { it.toLong() }.toLongArray()))
            val maskTensor = OnnxTensor.createTensor(ortEnv, arrayOf(LongArray(inputIds.size) { 1L }))
            val encInputs = HashMap<String, OnnxTensor>()
            for (n in model.encoder.inputNames) {
                if (n.contains("input_ids")) encInputs[n] = idsTensor
                else if (n.contains("attention_mask")) encInputs[n] = maskTensor
            }
            val encResults = model.encoder.run(encInputs)

            // 4. Greedy autoregressive decode
            val outIds = try {
                val hidden = encResults.get(0).orElseThrow() as OnnxTensor
                greedyDecode(model, hidden, maskTensor, tgtTag)
            } finally {
                encResults.close(); idsTensor.close(); maskTensor.close()
            }

            // 5. Detokenize + Devanagari -> target script
            val out = postprocess(decodeTokens(model, outIds), tgtLang)
            onLog("NMT done in ${System.currentTimeMillis() - t0} ms (${outIds.size} out tokens)")
            out.ifBlank { text }
        } catch (t: Throwable) {
            onLog("NMT error -> ${t.message}")
            text   // graceful fallback: send untranslated rather than drop the message
        }
    }

    fun release() {
        loaded?.close()
        loaded = null
        loadedDirection = null
        onLog("NMT sessions released")
        // NOTE: never close ortEnv — it's a process-wide singleton (OrtEnvironment.getEnvironment())
    }

    // ------------------------------------------------------------ loading

    @Synchronized
    private fun ensureLoaded(direction: String): Loaded? {
        if (loaded != null && loadedDirection == direction) return loaded
        if (loaded != null) { loaded!!.close(); loaded = null }   // direction switched -> reload

        return try {
            onLog("NMT init -> loading $direction ...")
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val encoder = ortEnv.createSession(assetToFile(direction, "encoder_model.onnx"), opts)
            val decoder = ortEnv.createSession(assetToFile(direction, "decoder_model.onnx"), opts)
            opts.close()

            // Log real names once — confirms the generic matching below is right for your export
            onLog("NMT encoder inputs=${encoder.inputNames} outputs=${encoder.outputNames}")
            onLog("NMT decoder inputs=${decoder.inputNames} outputs=${decoder.outputNames}")

            val (pieceToId, idToPiece, maxLen) = loadVocab(direction)
            loaded = Loaded(
                encoder, decoder, pieceToId, idToPiece, maxLen,
                unkId = pieceToId["<unk>"] ?: 0,
                cfg = loadConfig(direction)
            )
            loadedDirection = direction
            onLog("NMT init -> $direction ready (vocab=${pieceToId.size})")
            loaded
        } catch (t: Throwable) {
            onLog("NMT init failed -> ${t.message}")
            loaded = null
            null
        }
    }

    /** Copy ONNX out of assets to filesDir so ORT can mmap it (avoids the giant readBytes heap copy). */
    private fun assetToFile(dir: String, name: String): String {
        val f = File(File(context.filesDir, "nmt/$dir"), name)
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            context.assets.open("$ROOT/$dir/$name").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return f.absolutePath
    }

    private fun loadVocab(dir: String): Triple<Map<String, Int>, Map<Int, String>, Int> {
        val pieceToId = HashMap<String, Int>(270_000)
        val idToPiece = HashMap<Int, String>(270_000)
        var maxLen = 1
        context.assets.open("$ROOT/$dir/vocab.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val id = line.substring(0, tab).trim().toIntOrNull() ?: continue
                val piece = line.substring(tab + 1)
                pieceToId[piece] = id
                idToPiece[id] = piece
                if (piece.length > maxLen) maxLen = piece.length
            }
        }
        return Triple(pieceToId, idToPiece, maxLen)
    }

    private fun loadConfig(dir: String): NmtConfig {
        val o = JSONObject(
            context.assets.open("$ROOT/$dir/nmt_config.json").bufferedReader().use { it.readText() }
        )
        return NmtConfig(
            eosId = o.optInt("eos_token_id", 2),
            padId = o.optInt("pad_token_id", 1),
            decoderStartId = o.optInt("decoder_start_token_id", 2)
        )
    }

    // ------------------------------------------------------------ tokenizer

    /** Greedy longest-match SPM encoding (word -> "▁word" -> vocab pieces). */
    private fun encodeTokens(model: Loaded, text: String): List<Int> {
        val out = ArrayList<Int>(text.length / 2 + 8)
        var unknowns = 0
        for (word in text.trim().split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val s = SPM_SPACE + word
            var i = 0
            while (i < s.length) {
                var j = minOf(s.length, i + model.maxPieceLen)
                var id: Int? = null
                while (j > i) {
                    id = model.pieceToId[s.substring(i, j)]
                    if (id != null) break
                    j--
                }
                if (id != null) { out.add(id); i = j }
                else { out.add(model.unkId); unknowns++; i++ }
            }
        }
        if (unknowns > 0) onLog("NMT warn -> $unknowns char(s) had no vocab match (<unk>)")
        return out
    }

    private fun decodeTokens(model: Loaded, ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            val piece = model.idToPiece[id] ?: continue
            if (piece.startsWith("<") && piece.endsWith(">")) continue  // skip specials
            sb.append(piece)
        }
        return sb.toString().replace(SPM_SPACE, " ").trim()
    }

    // ------------------------------------------------------------ pre/post-processing

    private fun preprocessSource(text: String, srcLang: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val base = SCRIPT_BASES[srcLang] ?: return t        // "en" -> untouched
        if (base == DEVA_BASE) return t
        return mapScript(t, from = base, to = DEVA_BASE)
    }

    private fun postprocess(text: String, tgtLang: String): String {
        val base = SCRIPT_BASES[tgtLang] ?: return text     // "en" -> untouched
        if (base == DEVA_BASE) return text
        return mapScript(text, from = DEVA_BASE, to = base)
    }

    /** Offset transliteration between Indic Unicode blocks (same trick as IndicNLP's
     *  UnicodeIndicTransliterator, minus its small exception table — fine for common text). */
    private fun mapScript(text: String, from: Int, to: Int): String = buildString(text.length) {
        for (c in text) {
            val code = c.code
            append(if (code in from until from + 128) ((code - from) + to).toChar() else c)
        }
    }

    // ------------------------------------------------------------ inference

    private fun greedyDecode(
        model: Loaded, hidden: OnnxTensor, encMask: OnnxTensor, tgtTag: String
    ): IntArray {
        val dec = model.decoder
        val names = dec.inputNames
        val idsName = names.first { it.contains("input_ids") }
        val hiddenName = names.firstOrNull { it.contains("encoder_hidden") }
            ?: names.firstOrNull { it.contains("hidden") }
            ?: error("decoder has no encoder_hidden_states input: $names")
        val maskName = names.firstOrNull { it.contains("encoder_attention_mask") }
            ?: names.firstOrNull { it.contains("attention_mask") }
            ?: error("decoder has no encoder_attention_mask input: $names")

        val tgtLangId = model.pieceToId[tgtTag] ?: error("'$tgtTag' missing from vocab.tsv")
        // MBart/NLLB convention: decoder starts with decoder_start_token, then the target language token
        val startIds = intArrayOf(model.cfg.decoderStartId, tgtLangId)

        val generated = ArrayList<Int>(MAX_NEW_TOKENS)
        for (step in 0 until MAX_NEW_TOKENS) {
            val decIds = startIds + generated.toIntArray()
            val decTensor = OnnxTensor.createTensor(ortEnv, arrayOf(decIds.map { it.toLong() }.toLongArray()))
            val results = dec.run(mapOf(idsName to decTensor, hiddenName to hidden, maskName to encMask))

            val logits = results.get(0).orElseThrow() as OnnxTensor
            val seqLen = logits.info.shape[1].toInt()
            val vocab = logits.info.shape[2].toInt()
            val fb = logits.floatBuffer
            val off = (seqLen - 1) * vocab
            var best = 0; var bestScore = -Float.MAX_VALUE
            for (v in 0 until vocab) {
                val s = fb.get(off + v)
                if (s > bestScore) { bestScore = s; best = v }
            }
            results.close(); decTensor.close()

            if (best == model.cfg.eosId || best == model.cfg.padId) break
            generated.add(best)
        }
        return generated.toIntArray()
    }

    private fun directionOf(src: String, tgt: String): String =
        if (src == "en") "en-indic" else "indic-en"
}
