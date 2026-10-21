package com.novelassist.voice

enum class VoiceCommand(val help: String) {
    UNDO("“undo” or “scratch that”: remove the last thing added"),
    DELETE_LAST_WORD("“delete last word”"),
    DELETE_LAST_SENTENCE("“delete last sentence”"),
    NEW_PARAGRAPH("“new paragraph”"),
    NEW_CHAPTER("“new chapter”"),
    NEXT_CHAPTER("“next chapter” / “previous chapter”"),
    PREVIOUS_CHAPTER(""),
    READ_BACK("“read that back”: hear the last paragraph"),
    READ_CHAPTER("“read the chapter”"),
    STOP_READING("“stop reading”"),
    SUGGEST("“continue” or “suggest”: the AI writes the next lines in your voice"),
    ACCEPT("“accept” or “use that”: keep the suggestion"),
    REJECT("“discard”: throw the suggestion away"),
    RETRY("“try again”: a different suggestion"),
    READ_SUGGESTION("“read suggestion”"),
    FIX("“fix that”: correct dictation mistakes in the current paragraph"),
    BRAINSTORM("“I'm stuck” or “give me ideas”"),
    ASK_BIBLE("“ask the bible …”: e.g. “ask the bible what colour are Mara's eyes”"),
    BIBLE_NOTE("“bible note …”: save a fact to the story bible"),
    LEARN("“learn this chapter”: update the bible and your voice profile"),
    SAVE("“save”"),
    STOP_LISTENING("“stop listening”"),
    PICK_WORD("“pick one” … “pick five”: insert a suggested word"),
    HELP("“what can I say”: hear these commands"),
}

sealed interface VoiceInput {
    data class Dictation(val text: String) : VoiceInput
    data class Command(val command: VoiceCommand, val argument: String = "") : VoiceInput
}

/**
 * Decides whether an utterance is a command or prose. A whole utterance must
 * match a command phrase, so ordinary sentences are never mistaken for
 * commands. In strict mode the author says "command" first ("command undo").
 */
object VoiceCommandParser {

    private val exact: Map<String, VoiceCommand> = buildMap {
        fun put(cmd: VoiceCommand, vararg phrases: String) = phrases.forEach { put(it, cmd) }
        put(VoiceCommand.UNDO, "undo", "undo that", "scratch that", "delete that", "remove that", "take that back", "undo undo")
        put(VoiceCommand.DELETE_LAST_WORD, "delete last word", "delete the last word", "remove last word", "remove the last word", "backspace")
        put(VoiceCommand.DELETE_LAST_SENTENCE, "delete last sentence", "delete the last sentence", "remove last sentence", "remove the last sentence")
        put(VoiceCommand.NEW_PARAGRAPH, "new paragraph", "next paragraph")
        put(VoiceCommand.NEW_CHAPTER, "new chapter", "start new chapter", "start a new chapter", "add a chapter", "add chapter")
        put(VoiceCommand.NEXT_CHAPTER, "next chapter", "go to next chapter")
        put(VoiceCommand.PREVIOUS_CHAPTER, "previous chapter", "last chapter", "go back a chapter", "go to previous chapter")
        put(VoiceCommand.READ_BACK, "read back", "read that back", "read it back", "read that", "read last paragraph", "read the last paragraph", "what did i say", "what did i write", "read paragraph", "read the paragraph")
        put(VoiceCommand.READ_CHAPTER, "read chapter", "read the chapter", "read whole chapter", "read the whole chapter", "read me the chapter")
        put(VoiceCommand.STOP_READING, "stop reading", "stop talking", "be quiet", "quiet", "shush", "stop", "silence")
        put(VoiceCommand.SUGGEST, "suggest", "suggestion", "suggest something", "continue", "keep going", "write more", "write the next bit", "help me continue", "give me a suggestion", "next lines", "carry on")
        put(VoiceCommand.ACCEPT, "accept", "accept that", "accept it", "use that", "use it", "insert", "insert that", "insert it", "keep that", "keep it", "add that", "add it", "yes use that", "i like it", "i like that")
        put(VoiceCommand.REJECT, "reject", "reject that", "discard", "discard that", "discard it", "no thanks", "dismiss", "throw it away", "cancel", "never mind")
        put(VoiceCommand.RETRY, "try again", "another", "another one", "again", "give me another", "different one", "a different one", "regenerate", "something else")
        put(VoiceCommand.READ_SUGGESTION, "read suggestion", "read the suggestion", "read it", "read it to me", "what did you write", "read the ideas", "read ideas")
        put(VoiceCommand.FIX, "fix that", "fix it", "fix dictation", "fix the dictation", "clean up", "clean that up", "clean it up", "fix punctuation", "fix the punctuation", "polish that", "tidy up", "tidy that up")
        put(VoiceCommand.BRAINSTORM, "brainstorm", "give me ideas", "give me some ideas", "ideas", "what could happen next", "what happens next", "im stuck", "i am stuck", "help im stuck", "i'm stuck")
        put(VoiceCommand.LEARN, "learn", "learn this chapter", "learn the chapter", "learn chapter", "study this chapter", "update the bible", "update bible", "learn my style")
        put(VoiceCommand.SAVE, "save", "save chapter", "save it", "save that", "save the chapter")
        put(VoiceCommand.STOP_LISTENING, "stop listening", "pause listening", "stop dictation", "stop dictating", "microphone off", "mic off", "go to sleep")
        put(VoiceCommand.HELP, "help", "what can i say", "list commands", "commands", "voice commands")
    }

    private val prefixes: List<Pair<String, VoiceCommand>> = listOf(
        "ask the bible" to VoiceCommand.ASK_BIBLE,
        "ask bible" to VoiceCommand.ASK_BIBLE,
        "question for the bible" to VoiceCommand.ASK_BIBLE,
        "bible question" to VoiceCommand.ASK_BIBLE,
        "bible note" to VoiceCommand.BIBLE_NOTE,
        "note for the bible" to VoiceCommand.BIBLE_NOTE,
        "add to the bible" to VoiceCommand.BIBLE_NOTE,
        "add to bible" to VoiceCommand.BIBLE_NOTE,
    )

    private val numbers = mapOf(
        "one" to 1, "1" to 1, "won" to 1, "two" to 2, "2" to 2, "to" to 2, "too" to 2, "three" to 3, "3" to 3,
        "four" to 4, "4" to 4, "for" to 4, "five" to 5, "5" to 5,
    )

    fun normalize(text: String): String =
        text.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun parse(utterance: String, strict: Boolean = false): VoiceInput {
        val raw = utterance.trim()
        var n = normalize(raw)
        if (n.isEmpty()) return VoiceInput.Dictation("")
        val hadPrefix = n.startsWith("command ") || n == "command"
        if (hadPrefix) n = n.removePrefix("command").trim()
        if (strict && !hadPrefix) return VoiceInput.Dictation(raw)

        exact[n]?.let { return VoiceInput.Command(it) }
        exact[n.replace("'", "")]?.let { return VoiceInput.Command(it) }

        Regex("^(?:pick|choose|word|option|number) (\\w+)$").find(n)?.let { m ->
            numbers[m.groupValues[1]]?.let { return VoiceInput.Command(VoiceCommand.PICK_WORD, it.toString()) }
        }
        for ((prefix, cmd) in prefixes) {
            if (n.startsWith("$prefix ")) {
                // Keep the original wording (and capitals) of the argument.
                val words = prefix.split(" ").size
                val argument = raw.split(Regex("\\s+")).drop(words + if (hadPrefix) 1 else 0).joinToString(" ")
                    .trim().trimStart(',', ':', ' ')
                if (argument.isNotBlank()) return VoiceInput.Command(cmd, argument)
            }
        }
        return VoiceInput.Dictation(raw)
    }

    val helpText: String = VoiceCommand.entries.filter { it.help.isNotEmpty() }.joinToString("\n") { "• ${it.help}" }
}
