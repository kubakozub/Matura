package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.game.Matching
import com.matura.app.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresja bugu z audytu (MAJOR): przy zestawie z hasłami wieloznacznymi
 * (dwa różne słowa o tym samym tłumaczeniu) poprawna odpowiedź bywała
 * oznaczana jako "fałsz" / trafiała jako dystraktor. answersForPrompt zbiera
 * WSZYSTKIE poprawne odpowiedzi dla danej strony pytania, więc żaden poprawny
 * wariant nie zostanie potraktowany jako błędny.
 */
class AnswersForPromptTest {

    private val body = listOf(
        Entry("arm", "ręka"),
        Entry("hand", "ręka"),
        Entry("leg", "noga"),
    )

    @Test
    fun `def to term - reka akceptuje arm i hand`() {
        // Kierunek DEFINICJA -> HASŁO: prompt = "ręka", oczekiwane odpowiedzi to arm ORAZ hand.
        val answers = Matching.answersForPrompt(body, prompt = "ręka", termToDef = false)
        assertTrue("arm powinno być akceptowane", answers.contains(Matching.normalize("arm")))
        assertTrue("hand powinno być akceptowane", answers.contains(Matching.normalize("hand")))
    }

    @Test
    fun `hand nie moze byc falszem dla reka`() {
        // Symulacja generatora fałszu: kandydat "hand" dla promptu "ręka".
        val correct = Matching.answersForPrompt(body, prompt = "ręka", termToDef = false)
        val candidate = Matching.acceptedAnswers("hand")
        assertFalse(
            "hand jest poprawną odpowiedzią na 'ręka' — nie wolno użyć go jako fałszu",
            candidate.none { it in correct },
        )
    }

    @Test
    fun `leg jest poprawnym falszem dla reka`() {
        val correct = Matching.answersForPrompt(body, prompt = "ręka", termToDef = false)
        val candidate = Matching.acceptedAnswers("leg")
        assertTrue(
            "leg NIE jest odpowiedzią na 'ręka' — może być fałszem",
            candidate.none { it in correct },
        )
    }

    @Test
    fun ` diacritics i wielkosc liter nie maja znaczenia`() {
        val answers = Matching.answersForPrompt(body, prompt = "RĘKA", termToDef = false)
        assertTrue(answers.contains(Matching.normalize("arm")))
    }

    // ---- to samo pytanie w różnie zapisanych komórkach ----
    //
    // Ciąg dalszy tego samego błędu: do 0.9.18 wiersze łączyły się tylko wtedy, gdy CAŁE komórki
    // pytania były identyczne. Zestaw "Edukacja · Ludzie w szkole" ma pupil i schoolboy = "uczeń"
    // oraz student = "uczeń, student", więc prawda/fałsz pokazywało "uczeń = student" jako FAŁSZ,
    // a uczeń, który odpowiedział zgodnie z zestawem, dostawał pomyłkę.

    private val school = listOf(
        Entry("pupil", "uczeń"),
        Entry("schoolboy", "uczeń"),
        Entry("student", "uczeń, student"),
        Entry("teacher", "nauczyciel"),
    )

    @Test
    fun `uczen akceptuje tez odpowiedz z wiersza uczen przecinek student`() {
        val answers = Matching.answersForPrompt(school, prompt = "uczeń", termToDef = false)
        assertEquals(setOf("pupil", "schoolboy", "student"), answers)
    }

    @Test
    fun `student nie moze byc falszem dla uczen`() {
        // Symulacja generatora fałszu, jak wyżej dla "ręka": kandydat "student" przy pytaniu "uczeń".
        val correct = Matching.answersForPrompt(school, prompt = "uczeń", termToDef = false)
        val candidate = Matching.acceptedAnswers("student")
        assertFalse(
            "student jest poprawną odpowiedzią na 'uczeń': nie wolno użyć go jako fałszu",
            candidate.none { it in correct },
        )
        assertTrue(
            "nauczyciel dalej może być fałszem",
            Matching.acceptedAnswers("teacher").none { it in correct },
        )
    }

    @Test
    fun `pytanie z dwoma znaczeniami zbiera odpowiedzi obu`() {
        // Kierunek odwrotny tej samej pary: pytanie "uczeń, student" przyjmuje też pupil i schoolboy.
        val answers = Matching.answersForPrompt(school, prompt = "uczeń, student", termToDef = false)
        assertEquals(setOf("student", "pupil", "schoolboy"), answers)
    }

    @Test
    fun `dluzsza fraza to inne pytanie`() {
        val entries = listOf(Entry("kind", "uprzejmy"), Entry("kind of", "w pewnym sensie"))
        val answers = Matching.answersForPrompt(entries, prompt = "kind", termToDef = true)
        assertEquals(setOf("uprzejmy"), answers)
    }

    @Test
    fun `term to def - identyczne hasla z dwoch wierszy dalej sie lacza`() {
        // Tak wygląda losowy zestaw z całego katalogu: to samo hasło z dwóch działów.
        val mix = listOf(Entry("patient", "cierpliwy"), Entry("patient", "pacjent"), Entry("cold", "zimny"))
        val answers = Matching.answersForPrompt(mix, prompt = "patient", termToDef = true)
        assertEquals(setOf("cierpliwy", "pacjent"), answers)
    }

    @Test
    fun `suma bierze kazdy wariant odpowiedzi a nie tylko cala komorke`() {
        // Hasło z zestawu "Zakupy · Usługi": wariant bez apostrofu musi dojść także tędy,
        // inaczej prawda/fałsz i ABCD liczyłyby poprawność inaczej niż tryby z wpisywaniem.
        val shops = listOf(Entry("dry cleaner's", "pralnia chemiczna"), Entry("florist's", "kwiaciarnia"))
        val answers = Matching.answersForPrompt(shops, prompt = "pralnia chemiczna", termToDef = false)
        assertEquals(Matching.acceptedAnswers("dry cleaner's").toSet(), answers)
        assertTrue("dry cleaners" in answers)
    }

    @Test
    fun `puste pytanie nie ma odpowiedzi`() {
        val entries = listOf(Entry("", "ręka"), Entry("?!", "noga"), Entry("arm", "ręka"))
        assertTrue(Matching.answersForPrompt(entries, prompt = "", termToDef = true).isEmpty())
        assertTrue(Matching.answersForPrompt(entries, prompt = "?!", termToDef = true).isEmpty())
    }

    // ---- cały wbudowany katalog ----

    private val kierunki = listOf(true to "hasło -> tłumaczenie", false to "tłumaczenie -> hasło")

    @Test
    fun `w kazdym zestawie wbudowanym wynik zawiera wszystko co dawal dotad`() {
        // Wzorcem jest reguła z 0.9.18 w całości: identyczne komórki pytania i stare
        // acceptedAnswers (LegacyMatching w MatchingTest.kt).
        val braki = ArrayList<String>()
        for (set in BuiltInSets.all()) {
            for ((termToDef, kierunek) in kierunki) {
                for (e in set.entries) {
                    val prompt = if (termToDef) e.term else e.definition
                    val teraz = Matching.answersForPrompt(set.entries, prompt, termToDef)
                    val dawniej = LegacyMatching.answersForPrompt(set.entries, prompt, termToDef)
                    val brak = dawniej - teraz
                    if (brak.isNotEmpty()) braki += "${set.id} ($kierunek) '$prompt': $brak"
                }
            }
        }
        assertTrue("odpowiedzi, które wypadły z wyniku: $braki", braki.isEmpty())
    }

    /**
     * Ile wierszy katalogu dostało odpowiedzi z INNEGO wiersza tylko dzięki temu, że pytania
     * porównujemy po wspólnym znaczeniu, a nie po całej komórce. Liczby są dokładne celowo:
     * poprawka słownictwa, która doda albo usunie taką parę, ma zostać zauważona. Gdy test
     * padnie po zmianie katalogu, komunikat wypisuje całą listę: wystarczy ją przeczytać
     * i poprawić liczby.
     */
    @Test
    fun `wiersze katalogu ktore dostaly odpowiedzi z innego wiersza`() {
        fun poCalejKomorce(entries: List<Entry>, prompt: String, termToDef: Boolean): Set<String> {
            val key = Matching.normalize(prompt)
            return entries
                .filter { Matching.normalize(if (termToDef) it.term else it.definition) == key }
                .flatMap { Matching.acceptedAnswers(if (termToDef) it.definition else it.term) }
                .toSet()
        }
        val zyski = kierunki.associate { (termToDef, kierunek) ->
            kierunek to BuiltInSets.all().flatMap { set ->
                set.entries.mapNotNull { e ->
                    val prompt = if (termToDef) e.term else e.definition
                    val doszlo = Matching.answersForPrompt(set.entries, prompt, termToDef) -
                        poCalejKomorce(set.entries, prompt, termToDef)
                    if (doszlo.isEmpty()) null else "${set.id} '$prompt' (${e.term}): +$doszlo"
                }
            }
        }
        val lista = zyski.entries.joinToString("\n", prefix = "\n") { "${it.key}: ${it.value}" }
        assertEquals(lista, 0, zyski.getValue("hasło -> tłumaczenie").size)
        assertEquals(lista, 7, zyski.getValue("tłumaczenie -> hasło").size)
    }
}
