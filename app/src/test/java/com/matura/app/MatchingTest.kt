package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.game.Matching
import com.matura.app.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * WZORZEC ZGODNOSCI: Matching.acceptedAnswers i Matching.answersForPrompt przepisane w postaci
 * z wydania 0.9.18 (commit 2b3377e), sprzed rozszerzenia regul o apostrofy, ukosniki w srodku
 * frazy i wspolne pytania.
 *
 * Nie poprawiac i nie upraszczac. Ten kod ma zostac dokladnie taki, jaki gral u uczniow, bo
 * testy sprawdzaja wlasnie to: nic, co wtedy bylo zaliczane, nie moze przestac byc zaliczane.
 * Normalizacja jest wspolna z produkcyjna, bo zmiana regul jej nie dotyka.
 */
internal object LegacyMatching {

    fun acceptedAnswers(definition: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            val n = Matching.normalize(s)
            if (n.isNotEmpty()) out.add(n)
        }
        add(definition)
        for (part in definition.split(',', ';', '/')) {
            add(part)
            val withoutGloss = part.replace(Regex("\\([^)]*\\)"), " ")
            add(withoutGloss)
        }
        for (variant in out.toList()) {
            if (variant.contains(' ')) out.add(variant.replace(" ", ""))
        }
        return out.toList()
    }

    fun answersForPrompt(entries: List<Entry>, prompt: String, termToDef: Boolean): Set<String> {
        val key = Matching.normalize(prompt)
        if (key.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (e in entries) {
            val promptSide = if (termToDef) e.term else e.definition
            if (Matching.normalize(promptSide) == key) {
                out += acceptedAnswers(if (termToDef) e.definition else e.term)
            }
        }
        return out
    }
}

/** Reguly rozbijania jednej komorki na przyjmowane odpowiedzi. */
class MatchingVariantsTest {

    private fun accepts(cell: String, typed: String): Boolean =
        Matching.isExactMatch(typed, Matching.acceptedAnswers(cell))

    // ---- wariant bez odstepu: dwa testy z galezi master, zgubione przy scalaniu drzew ----

    @Test
    fun `a hyphenated answer counts with or without the gap`() {
        // Tester wylosowal "otwarty" i nie mial jak odpowiedziec: klawiatura A-Z nie ma
        // lacznika, wiec "open-minded" bylo nie do wpisania.
        val ans = Matching.acceptedAnswers("open-minded")
        assertTrue(Matching.isExactMatch("open minded", ans))
        assertTrue(Matching.isExactMatch("openminded", ans))
        assertTrue(Matching.isExactMatch("OPEN-MINDED", ans))
        assertTrue("prefiks bez spacji tez podswietla", Matching.isPrefixOfAny("openm", ans))
        assertFalse("polowa hasla to nadal nie odpowiedz", Matching.isExactMatch("open", ans))
    }

    @Test
    fun `a two word answer counts written as one word`() {
        val ans = Matching.acceptedAnswers("stan cywilny")
        assertTrue(Matching.isExactMatch("stan cywilny", ans))
        assertTrue(Matching.isExactMatch("stancywilny", ans))
        assertTrue(Matching.isPrefixOfAny("stanc", ans))
    }

    // ---- apostrof ----

    @Test
    fun `an apostrophe can be left out`() {
        // Klawiatura A-Z nie ma apostrofu, a pole klawiatury systemowej go wycina. Do 0.9.18
        // "can't stand" dalo sie zaliczyc tylko jako "can t stand" albo "cantstand",
        // a naturalne "cant stand" bylo pomylka zapisana uczniowi.
        val ans = Matching.acceptedAnswers("can't stand")
        assertTrue(Matching.isExactMatch("cant stand", ans))
        assertTrue(Matching.isExactMatch("can't stand", ans))
        assertTrue("stare warianty zostaja", "can t stand" in ans && "cantstand" in ans)
        assertTrue("podswietlenie prefiksu idzie za nowym wariantem", Matching.isPrefixOfAny("cant s", ans))
        assertFalse("polowa hasla to nadal nie odpowiedz", Matching.isExactMatch("cant", ans))
        assertFalse(Matching.isExactMatch("stand", ans))

        assertTrue(accepts("dry cleaner's", "dry cleaners"))
        assertTrue("jedno slowo dzialalo juz wczesniej", accepts("butcher's", "butchers"))
    }

    @Test
    fun `typographic apostrophes and the backtick count as apostrophes`() {
        // Wlasny zestaw wklejony z sieci ma zwykle U+2019 zamiast prostego apostrofu. U+02BC
        // jest dla Javy litera, wiec bez tej reguly takiego hasla nie daloby sie wpisac wcale.
        val apostrofy = listOf('\'', Char(0x2019), Char(0x2018), Char(0x02BC), '`')
        for (znak in apostrofy) {
            val kod = "U+%04X".format(znak.code)
            assertTrue(kod, accepts("can${znak}t stand", "cant stand"))
            assertTrue(kod, accepts("dry cleaner${znak}s", "dry cleaners"))
        }
    }

    @Test
    fun `every listed answer gets its apostrophe free form`() {
        val ans = Matching.acceptedAnswers("don't know, can't say (I'm not sure)")
        assertTrue("dont know" in ans)
        assertTrue("cant say" in ans)
        assertTrue("cant say im not sure" in ans)
        assertTrue("wariant bez odstepu tez", "dontknow" in ans)
    }

    // ---- ukosnik ----

    @Test
    fun `a slash inside a phrase lists alternatives for one word`() {
        // "single-sex school" = "szkoła żeńska/męska": do 0.9.18 gra przyjmowala
        // "szkola zenska" i samo "meska", a "szkola meska" liczyla jako pomylke.
        val school = Matching.acceptedAnswers("szkoła żeńska/męska")
        assertTrue(Matching.isExactMatch("szkola zenska", school))
        assertTrue(Matching.isExactMatch("szkola meska", school))
        assertTrue("wariant bez odstepu", Matching.isExactMatch("szkolameska", school))
        assertTrue("podswietlenie prefiksu", Matching.isPrefixOfAny("szkola me", school))
        assertTrue("takze w dalszej czesci komorki", accepts("internat, szkoła żeńska/męska", "szkola meska"))

        val married = Matching.acceptedAnswers("żonaty/zamężna")
        assertTrue(Matching.isExactMatch("zonaty", married))
        assertTrue(Matching.isExactMatch("zamezna", married))
    }

    @Test
    fun `every combination of slashed words is accepted`() {
        val ans = Matching.acceptedAnswers("big/large house/home")
        for (typed in listOf("big house", "big home", "large house", "large home")) {
            assertTrue(typed, Matching.isExactMatch(typed, ans))
        }
        val three = Matching.acceptedAnswers("he/she/it is")
        for (typed in listOf("he is", "she is", "it is")) {
            assertTrue(typed, Matching.isExactMatch(typed, three))
        }
    }

    @Test
    fun `answers accepted before slash expansion stay accepted`() {
        // Stara regula ciela komorke na kazdym ukosniku, wiec przyjmowala calosc i sam ogon.
        // Ogon nie jest dobra odpowiedzia, ale zostaje: uczen, ktoremu gra to zaliczala,
        // nie moze po aktualizacji dostac za to samo pomylki.
        val ans = Matching.acceptedAnswers("szkoła żeńska/męska")
        assertTrue("szkola zenska meska" in ans)
        assertTrue("meska" in ans)
    }

    @Test
    fun `a slash next to a space separates two whole answers`() {
        val ans = Matching.acceptedAnswers("his / her")
        assertTrue(Matching.isExactMatch("his", ans))
        assertTrue(Matching.isExactMatch("her", ans))

        // Odstep przy ukosniku znaczy "albo cala ta odpowiedz, albo cala tamta", wiec slowa
        // z jednej strony nie lacza sie ze slowami z drugiej.
        val sides = Matching.acceptedAnswers("big/large / huge/enormous")
        for (typed in listOf("big", "large", "huge", "enormous")) {
            assertTrue(typed, Matching.isExactMatch(typed, sides))
        }
        assertFalse(Matching.isExactMatch("big huge", sides))
        assertFalse(Matching.isExactMatch("large enormous", sides))
        assertFalse(Matching.isExactMatch("szkola meska", Matching.acceptedAnswers("szkoła żeńska / męska")))
    }

    @Test
    fun `a stray slash does not make a word optional`() {
        // Ukosnik na brzegu slowa albo podwojny nie ma po jednej stronie zadnego zamiennika,
        // wiec nie moze z niego powstac odpowiedz "bez tego slowa".
        assertTrue(accepts("szkoła żeńska/", "szkola zenska"))
        assertFalse(accepts("szkoła żeńska/", "szkola"))

        val doubled = Matching.acceptedAnswers("big//large house")
        assertTrue(Matching.isExactMatch("big house", doubled))
        assertTrue(Matching.isExactMatch("large house", doubled))
        assertFalse(Matching.isExactMatch("house", doubled))
    }

    @Test
    fun `a hard space separates words like an ordinary one`() {
        // Tekst wklejony z edytora albo ze strony miewa twarda spacje (U+00A0) zamiast zwyklej.
        val nbsp = Char(0x00A0)
        assertTrue(accepts("szkoła${nbsp}żeńska/męska", "szkola meska"))
    }

    @Test
    fun `slash combinations are capped`() {
        // Szesc slow z ukosnikiem to 64 kombinacje. Sufit zostawia 16 pierwszych: ostatnie
        // slowa zmieniaja sie najszybciej, wiec dwa pierwsze stoja w miejscu.
        val ans = Matching.acceptedAnswers("a/b c/d e/f g/h i/j k/l")
        val combinations = ans.filter { Regex("[a-l]( [a-l]){5}").matches(it) }
        assertEquals(combinations.toString(), 16, combinations.size)
        assertTrue("a c e g i k" in ans)
        assertTrue("a c f h j l" in ans)
        assertFalse("siedemnasta kombinacja juz nie powstaje", "a d e g i k" in ans)
        assertFalse("b d f h j l" in ans)
    }

    @Test(timeout = 10_000L)
    fun `a pathological cell does not explode`() {
        // Edytor zestawow nie ma limitow. 24 slowa z ukosnikiem to bez sufitu ponad 16 milionow
        // kombinacji, liczonych przy kazdym pytaniu na watku rysujacym ekran.
        val cell = (1..24).joinToString(" ") { "a$it/b$it" }
        val ans = Matching.acceptedAnswers(cell)
        assertTrue("${ans.size} wariantow", ans.size < 200)
        assertTrue("calosc nadal sie liczy", Matching.isExactMatch(cell, ans))
    }

    // ---- dopowiedzenie w nawiasie ----

    @Test
    fun `a comma inside brackets does not split the answer`() {
        // Do 0.9.18 przecinek cial komorke PRZED zdjeciem nawiasu: "syndykat" nie byl
        // przyjmowany, a przyjmowane bylo samo "grupa", czyli kawalek objasnienia.
        val ans = Matching.acceptedAnswers("syndykat (związek, grupa)")
        assertTrue(Matching.isExactMatch("syndykat", ans))
        assertTrue("calosc z dopowiedzeniem", Matching.isExactMatch("syndykat zwiazek grupa", ans))
        assertFalse(Matching.isExactMatch("grupa", ans))
        assertFalse(Matching.isExactMatch("syndykat zwiazek", ans))
    }

    @Test
    fun `no separator splits inside brackets`() {
        val semicolon = Matching.acceptedAnswers("syndykat (związek; grupa)")
        assertTrue(Matching.isExactMatch("syndykat", semicolon))
        assertFalse(Matching.isExactMatch("grupa", semicolon))

        val slash = Matching.acceptedAnswers("przyjaciel (kolega/koleżanka)")
        assertTrue(Matching.isExactMatch("przyjaciel", slash))
        assertFalse(Matching.isExactMatch("kolezanka", slash))
        assertFalse(Matching.isExactMatch("przyjaciel kolega", slash))
    }

    @Test
    fun `parts outside brackets still split`() {
        val ans = Matching.acceptedAnswers("konsorcjum (grupa firm, spółek), syndykat (związek); kartel")
        for (typed in listOf("konsorcjum", "syndykat", "kartel", "syndykat zwiazek")) {
            assertTrue(typed, Matching.isExactMatch(typed, ans))
        }
        assertFalse(Matching.isExactMatch("spolek", ans))

        // Ukosnik poza nawiasem dziala jak zawsze, dopowiedzenie przy nim zostaje opcjonalne.
        val married = Matching.acceptedAnswers("żonaty/zamężna (o osobie)")
        for (typed in listOf("zonaty", "zamezna", "zamezna o osobie")) {
            assertTrue(typed, Matching.isExactMatch(typed, married))
        }
    }

    @Test
    fun `a bracket that never closes is not a gloss`() {
        // Zgubiony nawias zamykajacy nie moze polknac reszty komorki: taka komorka dzieli sie
        // dokladnie jak dawniej, razem z kolejnoscia wariantow.
        for (cell in listOf("syndykat (związek, grupa", ":( smutny, zły", "a) kot, b) pies")) {
            assertEquals(cell, LegacyMatching.acceptedAnswers(cell), Matching.acceptedAnswers(cell))
        }
        assertTrue(accepts("syndykat (związek, grupa", "grupa"))
    }

    // ---- ksztalt wyniku ----

    @Test
    fun `the list starts with the whole cell and has no duplicates`() {
        val cells = listOf(
            "can't stand", "szkoła żeńska/męska", "żonaty/zamężna", "his / her",
            "konsorcjum, syndykat (związek przedsiębiorstw)", "syndykat (związek, grupa)",
            "a/a b/b", "don't / don't", "open-minded",
        ) + BuiltInSets.all().flatMap { set -> set.entries.flatMap { listOf(it.term, it.definition) } }
        for (cell in cells) {
            val ans = Matching.acceptedAnswers(cell)
            assertEquals("calosc na poczatku: '$cell'", Matching.normalize(cell), ans.first())
            assertEquals("duplikaty: '$cell' -> $ans", ans.size, ans.toSet().size)
            assertEquals("kolejnosc ma byc stala: '$cell'", ans, Matching.acceptedAnswers(cell))
        }
    }

    @Test
    fun `a cell with nothing to type has no answers`() {
        for (cell in listOf("", "   ", "?!", "/", " / ", "(,)", "'")) {
            assertEquals("'$cell'", emptyList<String>(), Matching.acceptedAnswers(cell))
        }
    }

    /**
     * To samo co przeglad katalogu, ale na ksztaltach, ktorych katalog nie ma, a wlasny zestaw
     * moze miec: zlepki separatorow, puste czesci, apostrofy i ukosniki obok siebie. Komorki sa
     * losowane ze stalego ziarna, wiec test jest powtarzalny.
     */
    @Test
    fun `random cells without separators in brackets never lose an answer`() {
        val rnd = Random(20261120)
        val plain = "ab c,;/'-"
        fun text(alphabet: String, max: Int) =
            String(CharArray(rnd.nextInt(max + 1)) { alphabet[rnd.nextInt(alphabet.length)] })
        repeat(4000) { nr ->
            // Co drugi przypadek dostaje dopowiedzenia w nawiasach, ale bez separatora w srodku:
            // tylko taki nawias stara regula czytala poprawnie.
            val cell = if (nr % 2 == 0) {
                text(plain, 14)
            } else {
                (0..rnd.nextInt(3)).joinToString("") { text(plain, 6) + "(" + text("ab ", 4) + ")" } + text(plain, 4)
            }
            val before = LegacyMatching.acceptedAnswers(cell)
            val now = Matching.acceptedAnswers(cell)
            val lost = before.filter { it !in now }
            assertTrue("'$cell' stracilo $lost", lost.isEmpty())
            // Bez ukosnika i apostrofu nowe reguly nie maja nic do dodania.
            if ('/' !in cell && '\'' !in cell) assertEquals("'$cell'", before, now)
        }
    }
}

/** [Matching.samePrompt]: kiedy dwa wiersze pytaja o to samo. */
class MatchingSamePromptTest {

    private fun same(a: String, b: String): Boolean {
        val result = Matching.samePrompt(a, b)
        assertEquals("relacja ma byc symetryczna: '$a' / '$b'", result, Matching.samePrompt(b, a))
        return result
    }

    @Test
    fun `cells that share one alternative are the same question`() {
        assertTrue(same("uczeń", "uczeń, student"))
        assertTrue(same("towarzyski, kontaktowy", "towarzyski"))
        assertTrue(same("sweter, bluza", "bluza; kurtka"))
        assertFalse(same("uczeń, student", "nauczyciel, wykładowca"))
    }

    @Test
    fun `a longer phrase is a different question`() {
        assertFalse(same("kind", "kind of"))
        assertFalse(same("uczeń", "uczennica"))
        assertFalse(same("szkoła", "szkoła średnia"))
    }

    @Test
    fun `identical cells are the same question whatever the case and diacritics`() {
        assertTrue(same("ręka", "RĘKA"))
        assertTrue(same("reka", " ręka "))
        assertTrue("cala komorka tez jest wariantem", same("uczeń, student", "uczen student"))
    }

    @Test
    fun `two blank cells are never the same question`() {
        assertFalse(same("", ""))
        assertFalse(same("   ", ""))
        assertFalse(same("?!", "..."))
        assertFalse(same("", "uczeń"))
        assertFalse(same(",", ";"))
        // Pusta czesc po zbednym przecinku albo ukosniku to tez nie jest nic wspolnego.
        assertFalse(same("uczeń,", "nauczyciel,"))
        assertFalse(same("his /", "her /"))
    }

    @Test
    fun `glosses in brackets are dropped`() {
        assertTrue(same("uczeń (w szkole)", "uczeń"))
        assertTrue(same("syndykat (związek, grupa)", "syndykat"))
        assertFalse("kawalek dopowiedzenia to nie znaczenie", same("syndykat (związek, grupa)", "grupa"))
        assertFalse(same("uczeń (w szkole)", "w szkole"))
    }

    @Test
    fun `slashes are read the same way as in answers`() {
        assertTrue(same("his / her", "her"))
        assertTrue(same("his/ her", "his"))
        assertTrue(same("szkoła żeńska/męska", "szkoła męska"))
        assertTrue(same("szkoła żeńska/męska", "szkoła żeńska"))
        assertTrue(same("żonaty/zamężna", "zamężna"))
        // Sam ogon po ukosniku gra przyjmuje przy WPISYWANIU ze wzgledu na zgodnosc,
        // ale znaczeniem pytania on nie jest.
        assertFalse(same("szkoła żeńska/męska", "męska"))
    }

    @Test
    fun `typing shortcuts do not merge questions`() {
        // Pytanie uczen czyta, a nie wpisuje: te pary to rozne hasla.
        assertFalse(same("we're", "were"))
        assertFalse(same("can't", "cant"))
        assertFalse(same("a head", "ahead"))
        assertFalse(same("every day", "everyday"))
    }
}

/** Przeglad calego wbudowanego katalogu: zgodnosc ze starymi regulami i to, co doszlo. */
class MatchingBuiltInTest {

    private data class Komorka(val zestaw: String, val kolumna: String, val tresc: String)

    private fun komorki(): List<Komorka> = BuiltInSets.all().flatMap { set ->
        set.entries.flatMap { e ->
            listOf(Komorka(set.id, "haslo", e.term), Komorka(set.id, "tlumaczenie", e.definition))
        }
    }

    @Test
    fun `everything accepted before is still accepted in every built-in row`() {
        val stracone = komorki().mapNotNull { k ->
            val teraz = Matching.acceptedAnswers(k.tresc).toSet()
            val brak = LegacyMatching.acceptedAnswers(k.tresc).filter { it !in teraz }
            if (brak.isEmpty()) null else "${k.zestaw} ${k.kolumna} '${k.tresc}': $brak"
        }
        assertTrue("odpowiedzi, ktore przestaly byc przyjmowane: $stracone", stracone.isEmpty())
    }

    /**
     * Ile wierszy katalogu NAPRAWDE zyskalo na nowych regulach. Liczby sa dokladne celowo:
     * poprawka slownictwa, ktora doda albo zabierze taki wiersz, ma byc zauwazona, a nie
     * przejsc bokiem. Gdy test padnie po zmianie katalogu, komunikat wypisuje cala liste:
     * wystarczy ja przeczytac i poprawic liczby. Wiekszosc hasel z apostrofem ("butcher's")
     * nic nie zyskuje, bo jako jedno slowo byly przyjmowane juz przez wariant bez odstepu.
     */
    @Test
    fun `built-in rows that gained answers from the new rules`() {
        val zyski = komorki().mapNotNull { k ->
            val dawniej = LegacyMatching.acceptedAnswers(k.tresc).toSet()
            val doszlo = Matching.acceptedAnswers(k.tresc).filter { it !in dawniej }
            if (doszlo.isEmpty()) null else k to doszlo
        }
        val lista = zyski.joinToString("\n", prefix = "\n") { (k, doszlo) ->
            "${k.zestaw} ${k.kolumna} '${k.tresc}': +$doszlo"
        }
        val zApostrofem = zyski.count { (k, _) -> '\'' in k.tresc }
        val zUkosnikiem = zyski.count { (k, _) -> '/' in k.tresc }
        assertEquals("wiersze z nowym wariantem bez apostrofu:$lista", 2, zApostrofem)
        assertEquals("wiersze z rozwinietym ukosnikiem:$lista", 1, zUkosnikiem)
        assertEquals("zaden inny wiersz nie mial sie zmienic:$lista", 3, zyski.size)
    }

    /**
     * Pary wierszy jednego zestawu, ktore sa tym samym pytaniem, choc ich komorki sie roznia.
     * To dokladnie te pary, w ktorych do 0.9.18 prawda/falsz i ABCD potrafily podac poprawne
     * tlumaczenie jako bledne ("uczen = student" z odpowiedzia FALSZ).
     */
    @Test
    fun `built-in rows that ask the same question in different cells`() {
        fun pary(strona: (Entry) -> String): List<String> = BuiltInSets.all().flatMap { set ->
            val wiersze = set.entries
            wiersze.indices.flatMap { i ->
                (i + 1 until wiersze.size).mapNotNull { j ->
                    val a = strona(wiersze[i])
                    val b = strona(wiersze[j])
                    val teSame = Matching.samePrompt(a, b) && Matching.normalize(a) != Matching.normalize(b)
                    if (teSame) "${set.id} '$a' / '$b'" else null
                }
            }
        }
        val poHasle = pary { it.term }
        val poTlumaczeniu = pary { it.definition }
        assertEquals("pytanie haslem: $poHasle", 0, poHasle.size)
        assertEquals("pytanie tlumaczeniem: $poTlumaczeniu", 4, poTlumaczeniu.size)
    }
}
