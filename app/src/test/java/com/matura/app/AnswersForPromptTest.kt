package com.matura.app

import com.matura.app.game.Matching
import com.matura.app.model.Entry
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
}
