package org.futo.voiceinput.shared.types

import org.futo.voiceinput.shared.ggml.BailLanguageException
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.InferenceCancelledException

// A loaded speech-to-text model, backed by either Whisper (GGML) or Whistle (Needle)
interface SpeechModel {
    // empty languages = autodetect any language
    // 1 language = will force that language
    // 2 or more languages = autodetect between those languages
    @Throws(BailLanguageException::class, InferenceCancelledException::class)
    suspend fun infer(
        samples: FloatArray,
        glossary: List<String>,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String

    fun cancel()

    suspend fun close()
}

// Words newly confirmed by a streaming pass, and the unconfirmed tail after them
data class StreamingUpdate(
    val committed: String,
    val pending: String
)

// A model that can also transcribe audio live while it is being recorded
interface StreamingSpeechModel : SpeechModel {
    // Discards any previous stream so the next process call starts a new one
    suspend fun resetStream()

    // Appends the next samples of the stream, ideally about one second at a time
    suspend fun processStream(
        samples: FloatArray,
        glossary: List<String>,
        languages: Array<String>
    ): StreamingUpdate
}
