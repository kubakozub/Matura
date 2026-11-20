package com.matura.app.game

import com.matura.app.model.Entry

/**
 * Answer normalisation and prefix matching.
 *
 * The in-game keyboard drawn in the design document has only A-Z, space, backspace
 * and cursor keys: no Polish diacritics. A player therefore cannot literally type
 * "zręczny", so matching has to be diacritic-insensitive or the game is unplayable
 * with its own keyboard. Comparison is also case-insensitive and tolerant of
 * repeated whitespace.
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
     * Znaki pisane w roli apostrofu: prosty, oba typograficzne (U+2019, U+2018), litera
     * modyfikujaca U+02BC i grawis. U+02BC jest dla Javy LITERA, wiec normalizacja go nie
     * rusza i haslo z takim apostrofem nie daloby sie wpisac w ogole.
     */
    private const val APOSTROPHES = "'\u2019\u2018\u02BC`"

    /**
     * Dopowiedzenie w nawiasie: od "(" do najblizszego ")". Nawias, ktorego nikt nie zamknal,
     * dopowiedzeniem nie jest i komorka z nim dzieli sie jak zwykly tekst.
     */
    private val GLOSS = Regex("\\([^)]*\\)")

    /** Odstep miedzy slowami, razem z twarda spacja i jej krewnymi z wklejonego tekstu. */
    private const val GAP = "[\\s\\p{Z}]"
    private val GAPS = Regex("$GAP+")

    /** Ukosnik, ktory z ktorejkolwiek strony dotyka odstepu ("his / her", "his/ her"). */
    private val SPACED_SLASH = Regex("$GAP+/$GAP*|/$GAP+")

    /**
     * Ile kombinacji wolno rozwinac z jednej frazy z ukosnikami. Kazde slowo z ukosnikiem
     * mnozy ich liczbe, a edytor zestawow nie ma limitow, wiec "a/b c/d e/f ..." we wlasnym
     * zestawie roslaby wykladniczo. Katalog potrzebuje najwyzej dwoch.
     */
    private const val MAX_SLASH_COMBINATIONS = 16

    private fun withoutGlosses(text: String): String =
        if ('(' in text) GLOSS.replace(text, " ") else text

    /**
     * Tnie [text] na znakach z [separators], ale nigdy w srodku dopowiedzenia w nawiasie:
     * "syndykat (zwiazek, grupa)" to jedna odpowiedz z jednym dopowiedzeniem, a nie
     * "syndykat (zwiazek" i "grupa)". Granice dopowiedzen sa te same co w [GLOSS].
     */
    private fun splitOutsideGlosses(text: String, separators: String): List<String> {
        val parts = ArrayList<String>()
        val lastClose = text.lastIndexOf(')')
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '(' && i < lastClose) {
                i = text.indexOf(')', i)
            } else if (ch in separators) {
                parts.add(text.substring(start, i))
                start = i + 1
            }
            i++
        }
        parts.add(text.substring(start))
        return parts
    }

    /**
     * Alternatywy, ktore komorka WYMIENIA: czesci po przecinkach i srednikach, bez dopowiedzen
     * w nawiasach, z rozwinietymi ukosnikami. Teksty sa surowe, normalizuje je wolajacy.
     *
     * Ukosnik w srodku frazy podaje zamienniki JEDNEGO slowa, wiec "szkola zenska/meska"
     * to "szkola zenska" albo "szkola meska", a nie "szkola zenska" albo samo "meska".
     * Ukosnik dotykajacy odstepu rozdziela dwie cale odpowiedzi, tak jak przecinek.
     *
     * Wspolne dla odpowiedzi ([acceptedAnswers]) i dla pytan ([samePrompt]), zeby obie
     * strony wiersza byly czytane tak samo.
     */
    private fun alternatives(cell: String): List<String> {
        val out = ArrayList<String>()
        for (part in splitOutsideGlosses(cell, ",;")) {
            val bare = withoutGlosses(part)
            if ('/' !in bare) {
                out.add(bare)
                continue
            }
            for (phrase in bare.split(SPACED_SLASH)) {
                // Kazde slowo frazy to lista zamiennikow, zwykle jednoelementowa.
                val slots = phrase.split(GAPS)
                    .map { word -> word.split('/').filter { normalize(it).isNotEmpty() } }
                    .filter { it.isNotEmpty() }
                var count = 1
                for (slot in slots) count = minOf(count * slot.size, MAX_SLASH_COMBINATIONS)
                // Kombinacje po kolei jak liczby w systemie o mieszanej podstawie: ostatnie
                // slowo zmienia sie najszybciej, wiec przy suficie zostaja te z poczatku listy.
                for (nr in 0 until count) {
                    var rest = nr
                    val words = arrayOfNulls<String>(slots.size)
                    for (i in slots.indices.reversed()) {
                        words[i] = slots[i][rest % slots[i].size]
                        rest /= slots[i].size
                    }
                    out.add(words.joinToString(" "))
                }
            }
        }
        return out
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
     * because a player typing on the restricted keyboard will not type brackets. The
     * gloss is one unit: a comma, semicolon or slash inside the brackets separates
     * nothing, so "syndykat (związek, grupa)" accepts "syndykat" but not "grupa".
     *
     * A slash inside a phrase lists alternatives for one word: "szkoła żeńska/męska"
     * accepts "szkola zenska" and "szkola meska". A slash next to a space ("his / her")
     * separates two whole answers, like a comma.
     *
     * Every variant also counts with its apostrophes left out ("can't stand" typed as
     * "cant stand") and, if it has a space, written as one word. The list has no
     * duplicates and starts with the whole cell.
     */
    fun acceptedAnswers(definition: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            val n = normalize(s)
            if (n.isNotEmpty()) out.add(n)
            // Klawiatura w grze nie ma apostrofu, a pole klawiatury systemowej go wycina,
            // wiec "can't stand" dochodzi tu jako "cant stand". Normalizacja robi z apostrofu
            // spacje ("can t stand"), dlatego obok kazdego wariantu stoi ten sam tekst
            // z apostrofami usunietymi jeszcze PRZED normalizacja.
            if (s.any { it in APOSTROPHES }) {
                val plain = normalize(s.filterNot { it in APOSTROPHES })
                if (plain.isNotEmpty()) out.add(plain)
            }
        }
        add(definition)
        // Najstarsza regula: kazdy przecinek, srednik i ukosnik tnie komorke na osobne
        // odpowiedzi. Przy ukosniku w srodku frazy daje to takze sam ogon ("meska" ze
        // "szkola zenska/meska"). Zostaje, bo gra przyjmowala go od poczatku, a odpowiedz,
        // ktora dotad byla zaliczana, nie moze nagle stac sie pomylka ucznia.
        for (part in splitOutsideGlosses(definition, ",;/")) {
            add(part)
            add(withoutGlosses(part))
        }
        if ('/' in definition) {
            for (answer in alternatives(definition)) add(answer)
        }
        // Hasła z łącznikiem i wielowyrazowe: "open-minded" zalicza się także jako
        // "open minded" i "openminded" — normalizacja zamienia łącznik na spację,
        // a tu dokładamy wariant zupełnie bez odstępów.
        for (variant in out.toList()) {
            if (variant.contains(' ')) out.add(variant.replace(" ", ""))
        }
        return out.toList()
    }

    /** Alternatywy komorki PYTANIA po normalizacji: cala komorka i to, co ona wymienia. */
    private fun promptAlternatives(cell: String): Set<String> {
        val whole = normalize(cell)
        if (whole.isEmpty()) return emptySet()
        // Zwykla komorka to jedno haslo i nie wymienia nic poza soba. Skrot ma znaczenie, bo
        // prawda/falsz i ABCD pytaja o to dla kazdej pary wierszy zestawu.
        if (cell.none { it in ",;/(" }) return setOf(whole)
        val out = LinkedHashSet<String>()
        out.add(whole)
        for (answer in alternatives(cell)) {
            val n = normalize(answer)
            if (n.isNotEmpty()) out.add(n)
        }
        return out
    }

    /**
     * Czy dwie komorki PYTANIA to to samo pytanie, czyli czy maja choc jedna wspolna
     * alternatywe. Alternatywami sa cala komorka oraz jej czesci wydzielone tak samo jak
     * odpowiedzi: przecinki i sredniki poza nawiasem, ukosnik przy odstepie, rozwiniete
     * ukosniki w srodku frazy, bez dopowiedzen w nawiasach.
     *
     * "uczen" i "uczen, student" to jedno pytanie: zestaw sam podaje "uczen" jako pelne
     * znaczenie drugiego wiersza, wiec jego odpowiedz jest poprawna takze dla pierwszego.
     * "kind" i "kind of" to dwa rozne pytania. Dwie puste komorki nigdy nie sa tym samym.
     *
     * Celowo NIE licza sie tu ulatwienia dla wpisujacego z [acceptedAnswers]: wariant bez
     * apostrofu, wariant bez spacji i sam ogon po ukosniku. Pytanie uczen CZYTA, wiec
     * "we're" i "were" albo "a head" i "ahead" to dla niego rozne hasla, a zlepienie ich
     * przyznaloby odpowiedziom jednego prawa drugiego.
     */
    fun samePrompt(a: String, b: String): Boolean = samePrompt(promptAlternatives(a), b)

    /** [samePrompt] z jedna strona policzona zawczasu, dla petli po wierszach zestawu. */
    private fun samePrompt(asked: Set<String>, cell: String): Boolean =
        promptAlternatives(cell).any { it in asked }

    /**
     * Wszystkie akceptowane odpowiedzi dla PYTANIA o tresci [prompt]: suma
     * [acceptedAnswers] po stronie odpowiedzi KAZDEGO wiersza, ktorego strona
     * pytania jest tym samym pytaniem co [prompt] w sensie [samePrompt]: wystarczy
     * jedna wspolna alternatywa, komorki nie musza byc identyczne. Przy haslach
     * wieloznacznych ("reka" -> arm/hand, "uczen" -> pupil obok "uczen, student" ->
     * student) zaden poprawny wariant nie zostanie wtedy uznany za falsz ani uzyty jako
     * dystraktor.
     */
    fun answersForPrompt(entries: List<Entry>, prompt: String, termToDef: Boolean): Set<String> {
        val asked = promptAlternatives(prompt)
        val out = LinkedHashSet<String>()
        for (e in entries) {
            val promptSide = if (termToDef) e.term else e.definition
            if (samePrompt(asked, promptSide)) {
                out += acceptedAnswers(if (termToDef) e.definition else e.term)
            }
        }
        return out
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
