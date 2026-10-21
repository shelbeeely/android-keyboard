package org.futo.inputmethod.latin.uix.utils

/**
 * Tidies up English dictation the way people would have typed it: drops filler words, repeated
 * words from stumbling, and spoken self-corrections ("at 3, no wait, 4" becomes "at 4").
 *
 * This is rule based, so it only acts on clear patterns and leaves anything ambiguous alone.
 */
object SpeechCleanup {
    private class Token(val leading: String, val word: String, val trailing: String) {
        val core: String = word.lowercase()
        val endsSentence: Boolean get() = trailing.any { it == '.' || it == '!' || it == '?' }
        val endsClause: Boolean get() = endsSentence || trailing.any { it == ',' || it == ';' || it == ':' }

        fun withTrailing(trailing: String) = Token(leading, word, trailing)
        override fun toString() = leading + word + trailing
    }

    private val tokenRegex = Regex("^([^\\p{L}\\p{N}]*)(.*?)([^\\p{L}\\p{N}]*)$")

    private fun tokenize(text: String): MutableList<Token> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map {
            val match = tokenRegex.find(it)!!
            Token(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }.toMutableList()

    private val fillers = setOf(
        "um", "umm", "ummm", "uh", "uhh", "uhhh", "uhm", "er", "erm", "ah", "ahh", "hmm", "hm", "mm", "mmm", "mhm"
    )

    // Spoken cues that what comes next replaces what came just before
    private val correctionCues = listOf(
        listOf("no", "wait"),
        listOf("wait", "no"),
        listOf("no", "sorry"),
        listOf("sorry", "no"),
        listOf("sorry", "i", "mean"),
        listOf("no", "i", "mean"),
        listOf("or", "rather"),
    )

    // "I mean" is often not a correction, so it only counts after a comma and before a short fix
    private val weakCorrectionCue = listOf("i", "mean")
    private const val MAX_WEAK_CORRECTION_WORDS = 4

    // Spoken cues that discard the sentence before them
    private val discardCues = listOf(
        listOf("scratch", "that"),
        listOf("delete", "that"),
    )

    // Words that start a phrase added after the corrected words
    private val phraseStarters = setOf(
        "at", "on", "in", "for", "with", "by", "from", "to", "of", "and", "or", "but", "because", "so"
    )

    private fun String.isNumber() = isNotEmpty() && (all { it.isDigit() } || this in numberWords)

    private val numberWords = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "noon", "midnight"
    )

    // Words that are commonly repeated on purpose
    private val intentionalRepeats = setOf(
        "that", "had", "very", "really", "so", "no", "yes", "yeah", "bye", "ha", "haha", "ok", "okay", "now", "well"
    )

    private fun List<Token>.matchesAt(index: Int, cue: List<String>): Boolean =
        index + cue.size <= size && cue.indices.all { this[index + it].core == cue[it] }

    private fun List<Token>.sentenceStart(index: Int): Int {
        var i = index - 1
        while (i >= 0 && !this[i].endsSentence) i--
        return i + 1
    }

    private fun removeFillers(tokens: MutableList<Token>) {
        var i = 0
        while (i < tokens.size) {
            if (tokens[i].core in fillers) {
                val removed = tokens.removeAt(i)
                if (i > 0) {
                    val previous = tokens[i - 1]
                    if (removed.endsSentence && !previous.endsSentence) {
                        // Keep a sentence end that was attached to the filler
                        tokens[i - 1] = previous.withTrailing(removed.trailing)
                    } else if (removed.trailing == "," && previous.trailing == ",") {
                        // "ask not, uh, what" reads as "ask not what"
                        tokens[i - 1] = previous.withTrailing("")
                    }
                }
            } else {
                i++
            }
        }
    }

    private fun applyDiscards(tokens: MutableList<Token>): Boolean {
        for (i in tokens.indices) {
            val cue = discardCues.firstOrNull { tokens.matchesAt(i, it) } ?: continue

            var start = tokens.sentenceStart(i)
            // "... late. Scratch that." discards the previous sentence
            if (start == i && i > 0) start = tokens.sentenceStart(i - 1)
            if (start == i) continue

            tokens.subList(start, i + cue.size).clear()
            return true
        }
        return false
    }

    private fun applyCorrections(tokens: MutableList<Token>): Boolean {
        for (i in tokens.indices) {
            val strongCue = correctionCues.firstOrNull { tokens.matchesAt(i, it) }
            val cue = strongCue ?: weakCorrectionCue.takeIf { tokens.matchesAt(i, it) } ?: continue

            val sentenceStart = tokens.sentenceStart(i)
            if (sentenceStart == i) continue
            // Spoken cues are set apart by punctuation, which tells "3, no wait, 4" from "no wait time"
            if (strongCue == null && !tokens[i - 1].trailing.contains(',')) continue
            if (!tokens[i - 1].endsClause && !tokens[i + cue.size - 1].endsClause) continue

            // The replacement runs from after the cue to the end of its clause
            val replacementStart = i + cue.size
            var replacementEnd = replacementStart
            while (replacementEnd < tokens.size && !tokens[replacementEnd].endsClause) replacementEnd++
            if (replacementEnd < tokens.size) replacementEnd++
            val replacementLength = replacementEnd - replacementStart
            if (replacementLength == 0) continue

            // What gets replaced stays within the clause before the cue
            var clauseStart = i - 2
            while (clauseStart >= sentenceStart && !tokens[clauseStart].endsClause) clauseStart--
            clauseStart = maxOf(clauseStart + 1, sentenceStart)

            // Prefer restarting from where the speaker repeats themselves ("to the store, no wait,
            // to the mall")
            val firstWord = tokens[replacementStart].core
            val anchor = (i - 1 downTo maxOf(clauseStart, i - replacementLength - 3))
                .firstOrNull { tokens[it].core == firstWord }
                ?: firstWord.takeIf { it.isNumber() }?.let {
                    // "at 3, no wait, 4 pm" replaces the number
                    (i - 1 downTo clauseStart).firstOrNull { tokens[it].core.isNumber() }
                }

            if (anchor == null && strongCue == null && replacementLength > MAX_WEAK_CORRECTION_WORDS) continue

            // Otherwise replace as many words as the correction leads with, before any phrase it
            // adds ("tomorrow, no sorry, Friday at noon" only replaces "tomorrow")
            val leadLength = (1 until replacementLength)
                .firstOrNull { tokens[replacementStart + it].core in phraseStarters } ?: replacementLength
            val replaceFrom = anchor ?: maxOf(clauseStart, i - leadLength)

            tokens.subList(replaceFrom, replacementStart).clear()
            return true
        }
        return false
    }

    private fun removeRepeats(tokens: MutableList<Token>) {
        for (length in 3 downTo 1) {
            var i = 0
            while (i + 2 * length <= tokens.size) {
                val first = tokens.subList(i, i + length)
                val second = tokens.subList(i + length, i + 2 * length)

                val isRepeat = first.indices.all { first[it].core == second[it].core && first[it].core.isNotEmpty() }
                        && first.none { it.endsSentence }
                        && !(length == 1 && first[0].core in intentionalRepeats)

                if (isRepeat) {
                    first.clear()
                } else {
                    i++
                }
            }
        }
    }

    private fun capitalizeSentences(tokens: MutableList<Token>) {
        for (i in tokens.indices) {
            if (i == 0 || tokens[i - 1].endsSentence) {
                val token = tokens[i]
                tokens[i] = Token(token.leading, token.word.replaceFirstChar { it.uppercaseChar() }, token.trailing)
            }
        }
    }

    @JvmStatic
    fun clean(text: String): String {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text.trim()

        removeFillers(tokens)

        var iterations = 0
        while (iterations++ < 16 && (applyDiscards(tokens) || applyCorrections(tokens))) { }

        removeRepeats(tokens)

        // A clause mark left dangling at the very end, from text removed after it
        if (tokens.isNotEmpty() && !tokens.last().endsSentence && tokens.last().endsClause) {
            tokens[tokens.size - 1] = tokens.last().withTrailing("")
        }

        capitalizeSentences(tokens)

        return tokens.joinToString(" ")
    }
}
