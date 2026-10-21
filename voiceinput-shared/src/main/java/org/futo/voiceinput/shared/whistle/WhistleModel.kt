package org.futo.voiceinput.shared.whistle

import androidx.annotation.Keep
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.ggml.BailLanguageException
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.ggml.inferenceContext
import org.futo.voiceinput.shared.types.SpeechModel
import org.json.JSONObject
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Languages supported by Whistle, see https://huggingface.co/Cactus-Compute/whistle
val WHISTLE_LANGUAGES = setOf("en", "de", "fr", "es", "it", "nl", "pl")

// Little-endian tag at the start of every .cact container
private const val CACT_MAGIC = 0x05E12A84

fun isWhistleModel(buffer: ByteBuffer): Boolean {
    if(buffer.capacity() < 4) return false
    return buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(0) == CACT_MAGIC
}

class WhistleUnavailableException : InvalidModelException("Whistle is not available in this build or on this device")
class InvalidWhistleModelException(reason: String) : InvalidModelException("The Whistle model could not be loaded: $reason")

/**
 * Speech recognition using Cactus Whistle through the Needle engine.
 *
 * The engine holds one process-global speech model, so instances share it and reload their
 * own buffer when another instance was the last one used. All engine calls run on
 * [inferenceContext], which is single-threaded.
 */
@Keep
class WhistleModel(
    private val modelBuffer: Buffer
) : SpeechModel {
    @Volatile
    private var cancelled = false
    private var closed = false

    init {
        if(!isAvailableNative()) throw WhistleUnavailableException()
        synchronized(engineLock) { load() }
    }

    private fun load() {
        if(loadedModel === this) return
        loadedModel = null

        val error = loadNative(modelBuffer)
        if(error != null) throw InvalidWhistleModelException(error)

        loadedModel = this
    }

    @Throws(BailLanguageException::class, InferenceCancelledException::class)
    override suspend fun infer(
        samples: FloatArray,
        glossary: List<String>,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String = withContext(inferenceContext) {
        if(closed) {
            throw IllegalStateException("WhistleModel has already been closed, cannot infer")
        }
        cancelled = false

        // Whistle can only be forced to one language, otherwise it detects among all of its own
        val language = languages.singleOrNull()?.takeIf { it in WHISTLE_LANGUAGES }
        val keywords = glossary.joinToString(separator = "\n").ifEmpty { null }

        val output = synchronized(engineLock) {
            load()
            transcribeNative(samples, language, keywords)
        } ?: throw IllegalStateException("Whistle transcription failed: ${lastErrorNative()}")

        if(cancelled) throw InferenceCancelledException()

        val json = JSONObject(output)
        val detectedLanguage = json.optString("language")
        if(detectedLanguage.isNotEmpty() && bailLanguages.contains(detectedLanguage)) {
            throw BailLanguageException(detectedLanguage)
        }

        val text = json.optString("text").trim()
        partialResultCallback(text)

        return@withContext text
    }

    // The engine has no way to abort a transcription; it is short, so drop its result instead
    override fun cancel() {
        cancelled = true
    }

    // The engine cannot unload a model, it is only replaced by the next load
    override suspend fun close() = withContext(inferenceContext) {
        synchronized(engineLock) {
            if(loadedModel === this@WhistleModel) loadedModel = null
        }
        closed = true
    }

    private external fun isAvailableNative(): Boolean
    private external fun loadNative(buffer: Buffer): String?
    private external fun transcribeNative(samples: FloatArray, language: String?, keywords: String?): String?
    private external fun lastErrorNative(): String

    companion object {
        private val engineLock = Any()
        private var loadedModel: WhistleModel? = null
    }
}
