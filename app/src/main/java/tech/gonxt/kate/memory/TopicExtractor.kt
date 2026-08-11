package tech.gonxt.kate.memory

/**
 * Heuristic on-device topic/entity extraction (spec Iteration 2). Cheap enough
 * to run on every turn; the portal's nightly consolidation redoes it at full
 * quality in Iteration 5.
 */
object TopicExtractor {

    data class Extraction(val topics: List<String>, val entities: List<String>)

    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at",
        "for", "with", "about", "into", "over", "after", "before", "is", "are", "was", "were",
        "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would",
        "can", "could", "should", "shall", "may", "might", "must", "i", "you", "he", "she",
        "it", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its",
        "our", "their", "this", "that", "these", "those", "what", "which", "who", "whom",
        "when", "where", "why", "how", "not", "no", "yes", "please", "kate", "hey", "ok",
        "okay", "just", "really", "very", "some", "any", "tell", "say", "said", "know",
        "think", "want", "like", "get", "got", "make", "go", "going", "there", "here",
        "up", "down", "out", "as", "by", "from", "am", "pm", "dont", "im", "its", "thats",
    )

    fun extract(text: String): Extraction {
        val entities = Regex("\\b[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,})*\\b")
            .findAll(text)
            .map { it.value }
            .filter { it.lowercase() !in STOPWORDS }
            .distinct()
            .take(5)
            .toList()

        val entityWords = entities.flatMap { it.lowercase().split(" ") }.toSet()

        val topics = Regex("[a-zA-Z]{4,}")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOPWORDS && it !in entityWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)

        return Extraction(topics, entities)
    }
}
