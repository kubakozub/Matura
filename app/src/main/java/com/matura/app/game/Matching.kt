package com.matura.app.game

/**
 * Answer normalisation and prefix matching.
 *
 * The in-game keyboard drawn in the design document has only A-Z, space, backspace
 * and cursor keys — no Polish diacritics. A player therefore cannot literally type
 * "zręczny", so matching has to be diacritic-insensitive or the game is unplayable
 * with its own keyboard. Comparison is also case-insensitive, tolerant of repeated
 * whitespace, and — because the keyboard has no hyphen either — accepts a hyphenated
 * or multi-word answer written with or without the gap.
 */
object Matching {

    private val FOLD: Map<Char, Char> = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a',
        'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
        'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o', 'ø' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
        'ý' to 'y', 'ÿ' to 'y', 'ñ' to 'n', 'ç' to 'c', 'š' to 's', 'ž' to 'z',
        'đ' to 'd', 'ð' to 'd', 'þ' to 't', 'ı' to 'i',
    )

    /** Lowercase, strip diacritics, drop non-alphanumerics to single spaces, trim. */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastWasSpace = true
        for (ch in raw.lowercase()) {
            val folded = FOLD[ch] ?: ch
            when {
                folded.isLetterOrDigit() -> {
                    sb.append(folded); lastWasSpace = false
                }
                else -> if (!lastWasSpace) {
                    sb.append(' '); lastWasSpace = true
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Splits a definition cell into every answer that should be accepted.
     *
     * "konsorcjum, syndykat (związek przedsiębiorstw)" accepts:
     *   - the whole cell,
     *   - "konsorcjum",
     *   - "syndykat (związek przedsiębiorstw)" and "syndykat" without the gloss.
     *
     * A parenthetical is treated as an optional gloss rather than part of the answer,
     * because a player typing on the restricted keyboard will not type brackets.
     */
    fun acceptedAnswers(definition: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            val n = normalize(s)
            if (n.isEmpty()) return
            out.add(n)
            // Tester nie mial jak wpisac "open-minded": lacznik nie miesci sie na
            // klawiaturze A-Z, a normalizacja zamienia go na spacje. Wersja bez spacji
            // jest przyjmowana obok tej ze spacja, wiec i "open minded", i "openminded"
            // sa poprawne — a podpowiedz prefiksu dziala w obu przypadkach.
            if (' ' in n) out.add(n.replace(" ", ""))
        }
        add(definition)
        for (part in definition.split(',', ';', '/')) {
            add(part)
            val withoutGloss = part.replace(Regex("\\([^)]*\\)"), " ")
            add(withoutGloss)
        }
        return out.toList()
    }

    /** True when [typed] is a prefix of any accepted answer. Empty input matches nothing. */
    fun isPrefixOfAny(typed: String, answers: List<String>): Boolean {
        val t = normalize(typed)
        if (t.isEmpty()) return false
        return answers.any { it.startsWith(t) }
    }

    /** True when [typed] equals one of the accepted answers. */
    fun isExactMatch(typed: String, answers: List<String>): Boolean {
        val t = normalize(typed)
        if (t.isEmpty()) return false
        return answers.any { it == t }
    }
}
