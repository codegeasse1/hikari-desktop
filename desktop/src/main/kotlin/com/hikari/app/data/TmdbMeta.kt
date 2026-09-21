package com.hikari.app.data

/**
 * Title-matching helpers shared by the TMDB-backed engines (nuvio, and the
 * episode-title enricher they use).
 *
 * Desktop port note: only this text/matching surface is carried over. The
 * Android version of this object also owns the detail page's artwork, cast,
 * trailer, certification and Related/Similar lookups — those depend on the
 * TMDB detail models the desktop app has not ported yet, and nothing here
 * needs them.
 */
object TmdbMeta {

    private val NUMERALS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    /** Lower-cased, punctuation-stripped, numeral-normalised title — the form
     *  used to match a title across sources that punctuate it differently
     *  ("Ramayana: Part Two" vs "Ramayana Part 2"). Also used by the episode
     *  fallback to pick the right hit out of an extension's search results. */
    fun normalizeTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { NUMERALS[it] ?: it }

    /**
     * How well [candidate] names the same title as [query]: 60 for a match, 40
     * for one being the other's prefix ("Sword of Coming" / "Sword of Coming
     * Season 2"), 25 for a containment, 0 otherwise. Both the raw lower-cased
     * forms and the punctuation/numeral-normalised forms are tried, because
     * [normalizeTitle] drops CJK entirely — a Chinese title has to be compared
     * as-is while a decorated Latin one needs normalising.
     */
    fun titleScore(query: String, candidate: String): Int {
        val q = query.trim().lowercase()
        val n = candidate.trim().lowercase()
        if (q.isBlank() || n.isBlank()) return 0
        var best = scorePair(q, n)
        val qn = normalizeTitle(q)
        val nn = normalizeTitle(n)
        if (qn.isNotBlank() && nn.isNotBlank()) best = maxOf(best, scorePair(qn, nn))
        return best
    }

    private fun scorePair(q: String, n: String): Int = when {
        n == q -> 60
        (n.length >= 5 && q.startsWith(n)) || (q.length >= 5 && n.startsWith(q)) -> 40
        (q.length >= 6 && n.contains(q)) || (n.length >= 6 && q.contains(n)) -> 25
        else -> 0
    }

    /** The season/part ordinal a site title names ("Sword of Coming Season 2",
     *  "斗破苍穹 第二季" → 2), or null. Extensions commonly split a donghua's run
     *  into per-season items, which restart their episode numbering at 1 — the
     *  episode enricher needs to know so it does not map season 2's first
     *  episode onto the season-1 title. */
    fun seasonHint(raw: String): Int? {
        SEASON_NUM.find(raw)?.let { m ->
            val digits = m.groupValues[1].ifBlank { m.groupValues[2] }
            digits.toIntOrNull()?.let { return it }
        }
        SEASON_CN.find(raw)?.let { return cnNumber(it.groupValues[1]) }
        return null
    }

    /** Strings that mark season/part [n] inside a name, for matching against a
     *  database entry that titles its seasons its own way ("第二季", "Season 2",
     *  "Part 2"). */
    fun seasonMarkers(n: Int): List<String> {
        val cn = CN_NUMERALS[n] ?: n.toString()
        val ordinal = when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
        return listOf(
            "season $n", "season$n", "$ordinal season",
            "part $n", "part$n",
            "第${cn}季", "第${n}季", "第${cn}部", "第${n}部", "第${cn}篇", "第${n}篇",
        )
    }

    /** Chinese numeral ("二", "十二", "21") to Int. */
    fun cnNumber(raw: String): Int? {
        val t = raw.trim()
        t.toIntOrNull()?.let { return it }
        if (t.isEmpty()) return null
        if (t == "十") return 10
        if (t.length == 1) return CN_DIGITS[t[0]]
        if (t[0] == '十' && t.length == 2) return CN_DIGITS[t[1]]?.let { 10 + it }
        if (t.length >= 2 && t[1] == '十') {
            val tens = CN_DIGITS[t[0]] ?: return null
            if (t.length == 2) return tens * 10
            if (t.length == 3) return CN_DIGITS[t[2]]?.let { tens * 10 + it }
        }
        return null
    }

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val CN_NUMERALS = mapOf(
        1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "七",
        8 to "八", 9 to "九", 10 to "十", 11 to "十一", 12 to "十二", 13 to "十三",
        14 to "十四", 15 to "十五", 16 to "十六", 17 to "十七", 18 to "十八",
        19 to "十九", 20 to "二十", 21 to "二十一", 22 to "二十二", 23 to "二十三",
    )

    private val SEASON_NUM = Regex(
        "(?i)(?:\\bseason|\\bseries|\\bpart|\\bcour|\\bbook|\\bvolume)\\s*(\\d+)\\b|\\b(\\d+)\\s*(?:st|nd|rd|th)\\s+season\\b"
    )
    private val SEASON_CN = Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*[季部篇]")
    private val SEASON_MARKER = Regex(
        "(?i)\\b(?:season|series|part|cour|book|volume)\\s*\\d+\\b" +
            "|\\b\\d+\\s*(?:st|nd|rd|th)\\s+season\\b" +
            "|第\\s*[0-9一二三四五六七八九十]+\\s*[季部篇]"
    )
    private val BRACKETED = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|（[^）]*）|【[^】]*】")
    private val SPACES = Regex("\\s+")

    /**
     * The decorations a SITE title carries that a database title never does: the
     * episode it is ("… Episode 172", "Ep. 12", "E12", "S01E12") plus the
     * release noise that habitually rides with it ("English Subtitles", "Hindi
     * Dubbed", "1080p", "WEB-DL", "x265"). They are what makes TMDB's index
     * return nothing at all for a title that is otherwise plain — and a title
     * that does not resolve loses its rating strip, its cast, its trailers and
     * its Related/Similar rows with it.
     *
     * Deliberately only used to build ADDITIONAL query variants; the full title
     * is always tried first, so an exact hit still wins.
     */
    private val EPISODE_MARKER = Regex(
        "(?i)\\b(?:episode|epis|ep)\\.?\\s*[-–—]?\\s*\\d{1,4}\\b" +
            "|\\bS\\d{1,2}\\s*[Ee]\\d{1,4}\\b" +
            "|\\b(?:english|eng|hindi|tamil|telugu|malayalam|urdu|bangla|bengali|spanish|arabic|korean)" +
            "\\s+(?:sub(?:title)?s?|subs|dubbed|dub|audio)\\b" +
            "|\\b(?:multi|dual)\\s+audio\\b|(?<!\\d)\\b\\d{3,4}p\\b" +
            "|\\b(?:web[- ]?dl|blu-?ray|hdtv|dvdrip|webrip|hdrip|hdts|x264|x265|h264|h265|hevc|avc|aac|ac3|dts|10bit|8bit|esubs?)\\b",
    )

    /**
     * Progressively simpler TMDB search queries for a title that carries
     * decorations the TMDB index does not match — site metas are full of them:
     *
     *   "Sword of Coming Season 2"        → "Sword of Coming"
     *   "Battle Through The Heavens: Origin" → "Battle Through The Heavens"
     *   "One Piece (2023)"                → "One Piece"
     *   "剑来 第二季"                        → "剑来"
     *
     * The full title is always first, so an exact hit still wins; the stripped
     * forms only get used when it returns nothing.
     *
     * The minimum length for a stripped form is TWO characters, not three:
     * Chinese titles are routinely that short, and a threshold of three
     * silently dropped "剑来" — so a site item called "剑来 第二季" never resolved
     * to anything at all, losing its cast/related/similar rows along with its
     * episode titles. A rejected query merely costs one wasted search.
     */
    fun queryVariants(raw: String): List<String> {
        val t = raw.trim()
        if (t.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(t)
        val flat = t.replace(BRACKETED, " ").replace(SPACES, " ").trim()
        if (flat.isNotBlank()) out.add(flat)
        for (base in listOf(t, flat)) {
            // Episode/release decorations go first: "Soul Land 2: The Peerless
            // Tang Sect Episode 172 English Subtitles" is the shape a search row
            // for an episode has, and while the long form usually misses TMDB's
            // index entirely, the plain series name resolves.
            val noEp = base.replace(EPISODE_MARKER, " ").replace(SPACES, " ")
                .trim().trim('-', '–', '—', ':', '：', '|', '.', ',', '_').trim()
            if (noEp.length >= 2) out.add(noEp)
            val noSeason = base.replace(SEASON_MARKER, " ").replace(SPACES, " ")
                .trim().trim('-', '–', '—', ':', '：', '|', '.').trim()
            if (noSeason.length >= 2) out.add(noSeason)
            // …and the combination of the two (a season title that also names
            // the episode): the plainest form of all.
            val plain = noEp.replace(SEASON_MARKER, " ").replace(SPACES, " ")
                .trim().trim('-', '–', '—', ':', '：', '|', '.').trim()
            if (plain.length >= 2) out.add(plain)
            val head = base.substringBefore("：").substringBefore(":")
                .substringBefore(" - ").trim()
            if (head.length >= 2 && head.length < base.length) out.add(head)
        }
        return out.filter { it.length >= 2 }
    }
}
