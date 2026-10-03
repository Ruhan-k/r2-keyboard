package com.example.furryreply

object StyleLearning {
    private val slang = listOf("lol", "lmao", "fr", "ngl", "idk", "rn", "bro", "nah", "wait", "yooo")
    private val emoji = Regex("[\\p{So}\\p{Sk}]")
    private val stretched = Regex("(?i)(.)\\1\\1+")

    fun summarize(feedback: List<ReplyFeedback>): String {
        if (feedback.isEmpty()) return "No replies learned yet."
        val replies = feedback.map { it.finalSentMessage.trim() }.filter { it.isNotEmpty() }
        if (replies.isEmpty()) return "No replies learned yet."
        val words = replies.map { it.split(Regex("\\s+")).filter(String::isNotBlank).size }
        val average = words.average()
        val lowercase = replies.count { reply ->
            val letters = reply.filter(Char::isLetter)
            letters.isNotEmpty() && letters.count(Char::isLowerCase).toDouble() / letters.length >= 0.8
        }
        val periods = replies.count { it.trimEnd().endsWith('.') }
        val punctuation = replies.count { it.contains(Regex("[!?]")) }
        val slangCounts = slang.mapNotNull { word ->
            val count = replies.count { Regex("(?i)\\b${Regex.escape(word)}\\b").containsMatchIn(it) }
            word.takeIf { count > 0 }?.let { it to count }
        }.sortedByDescending { it.second }.take(4).map { it.first }
        val emojiCounts = replies.flatMap { emoji.findAll(it).map { match -> match.value }.toList() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
        val stretchCount = replies.count { stretched.containsMatchIn(it) }
        val playfulSignals = replies.count { it.contains(Regex("(?i)lol|lmao|bro|😭|💀|waittt|yooo")) }
        val energy = when {
            replies.count { it.contains(Regex("[!?]|😭|💀")) } * 2 >= replies.size -> "playful or energetic"
            punctuation * 3 <= replies.size -> "calm or dry"
            else -> "casual"
        }
        val tone = when {
            playfulSignals * 2 >= replies.size -> "often makes quick playful jokes"
            average <= 5 -> "usually keeps replies dry and concise"
            else -> "usually keeps a calm casual tone"
        }
        return buildList {
            add("Usually writes ${maxOf(1, (average - 3).toInt())}-${(average + 4).toInt()} words.")
            add(if (lowercase * 2 >= replies.size) "Mostly lowercase." else "Uses mixed capitalization.")
            add(if (periods * 4 <= replies.size) "Rarely uses periods." else "Sometimes ends messages with periods.")
            if (slangCounts.isNotEmpty()) add("Often uses ${slangCounts.joinToString(", ") }.")
            if (emojiCounts.isNotEmpty()) add("Common emoji: ${emojiCounts.joinToString(", ")}.")
            if (stretchCount > 0) add("Sometimes stretches words for emphasis.")
            add("$tone.")
            add("Typical energy: $energy.")
        }.joinToString(" ")
    }
}
