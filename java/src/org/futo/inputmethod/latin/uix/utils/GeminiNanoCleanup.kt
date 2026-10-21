package org.futo.inputmethod.latin.uix.utils

import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rewrites dictation with Gemini Nano through ML Kit's GenAI Prompt API, like Gboard's Rambler.
 *
 * Gemini Nano runs in the system's AICore service, only on supported devices (such as the
 * Pixel 9 and 10), and AICore only serves the app in the foreground. A keyboard draws over
 * another app, so AICore may refuse it; callers fall back to [SpeechCleanup] on any failure.
 */
object GeminiNanoCleanup {
    enum class Status { Unsupported, Unavailable, Downloadable, Downloading, Available }

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    // ML Kit's GenAI APIs require Android 8
    private val isSupportedSdk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    // The Prompt API accepts up to 4000 tokens of input, keep well clear of it
    private const val MAX_INPUT_CHARS = 4000

    private const val TIMEOUT_MS = 15_000L

    private const val PROMPT = "You clean up dictated text. Rewrite the text below the way the speaker " +
            "meant to type it. Remove filler words such as um, uh, like and you know, stutters and " +
            "repeated words. When the speaker corrects themselves, for example with \"no wait\", " +
            "\"I mean\", \"sorry\" or \"scratch that\", keep only the corrected version. Fix punctuation " +
            "and capitalization. Keep the speaker's own words, tone and meaning; do not answer, " +
            "summarize, explain or add anything. Reply with only the rewritten text.\n\nText: "

    private var client: GenerativeModel? = null

    @Synchronized
    private fun client(): GenerativeModel = client ?: Generation.getClient().also { client = it }

    suspend fun status(): Status {
        if (!isSupportedSdk) return Status.Unsupported

        return try {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.Available
                FeatureStatus.DOWNLOADING -> Status.Downloading
                FeatureStatus.DOWNLOADABLE -> Status.Downloadable
                else -> Status.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Status.Unavailable
        }
    }

    // AICore downloads the model, not the keyboard
    fun download(): Flow<DownloadStatus> =
        if (isSupportedSdk) client().download() else emptyFlow()

    suspend fun clean(text: String): Result {
        if (!isSupportedSdk) return Result.Failure("needs Android 8 or newer")

        val input = text.trim()
        if (input.isEmpty()) return Result.Success("")
        if (input.length > MAX_INPUT_CHARS) return Result.Failure("text too long")

        return try {
            val request = generateContentRequest(TextPart(PROMPT + input)) {
                temperature = 0.0f
                topK = 1
                maxOutputTokens = 512
            }

            val response = withTimeoutOrNull(TIMEOUT_MS) { client().generateContent(request) }
                ?: return Result.Failure("timed out")

            val output = response.candidates.firstOrNull()?.text?.trim()?.removeSurrounding("\"")?.trim()

            when {
                output.isNullOrEmpty() -> Result.Failure("empty response")
                // A rewrite that grows a lot has likely answered or added to the text instead
                output.length > input.length * 2 + 40 -> Result.Failure("unexpected response")
                else -> Result.Success(output)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            Result.Failure(describeError(e.errorCode))
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun describeError(code: Int): String = when (code) {
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "blocked because the keyboard is not the foreground app (code $code)"
        GenAiException.ErrorCode.NOT_AVAILABLE -> "Gemini Nano is not available (code $code)"
        GenAiException.ErrorCode.BUSY -> "Gemini Nano is busy (code $code)"
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "daily usage quota reached (code $code)"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "needs a system update (code $code)"
        else -> "error code $code"
    }
}
