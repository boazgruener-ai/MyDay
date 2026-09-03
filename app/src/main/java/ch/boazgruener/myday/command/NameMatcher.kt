package ch.boazgruener.myday.command

/** [emails] holds every distinct address found for this exact name, not just one - a contact
 * accumulated over many years (job changes, re-added with a new address, etc.) can genuinely have
 * several, and silently picking one is exactly how a stale, no-longer-valid address got used
 * instead of the real one (confirmed via live testing: a 15-year-old contact with 5 emails on
 * file sent to a defunct one instead of the current address). */
data class ContactMatch(val name: String, val emails: List<String>, val score: Int)

/**
 * Scores how well [candidate] matches [query] for correcting speech-to-text errors in names -
 * lower is better, 0 is an exact case-insensitive match. Weighted specifically for the failure
 * mode STT actually produces: it usually gets the beginning of a name right and drops or
 * mangles the end (e.g. "Jamie Carte" heard for "Jamie Carter"), so a shared prefix
 * scores far better than a same-length edit distance would give it.
 */
fun matchScore(query: String, candidate: String): Int {
    val q = query.trim().lowercase()
    val c = candidate.trim().lowercase()
    if (q.isEmpty() || c.isEmpty()) return Int.MAX_VALUE
    if (q == c) return 0
    if (c.startsWith(q) || q.startsWith(c)) return 1 + kotlin.math.abs(c.length - q.length)
    if (c.contains(q) || q.contains(c)) return 4 + kotlin.math.abs(c.length - q.length)
    return 8 + levenshtein(q, c)
}

private fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
    }
    return dp[a.length][b.length]
}

/**
 * Best-matching candidates for [query], best first, capped to [limit]. Groups candidates by
 * exact name first (case-insensitive) and collects every distinct email seen for that name,
 * rather than discarding all but one - see [ContactMatch].
 */
fun findBestContactMatches(
    query: String,
    candidates: List<Pair<String, String?>>,
    limit: Int = 3
): List<ContactMatch> {
    return candidates
        .groupBy({ it.first.lowercase() }, { it.second })
        .map { (lowerName, emails) ->
            val name = candidates.first { it.first.lowercase() == lowerName }.first
            val distinctEmails = emails.filterNotNull().distinctBy { it.lowercase() }
            ContactMatch(name, distinctEmails, matchScore(query, name))
        }
        .sortedBy { it.score }
        .take(limit)
}
