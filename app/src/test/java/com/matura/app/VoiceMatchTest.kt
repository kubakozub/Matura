package com.matura.app

import com.matura.app.game.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ten sam zestaw przypadkow, ktory dostroil wersje przegladarkowa (matura.uk/demo).
 * Chodzi o to, zeby port do Kotlina zachowywal sie identycznie, a nie "podobnie".
 *
 * Najwazniejsza asercja jest na koncu: ZERO falszywych strzalow. Pudlo kaze
 * graczowi powtorzyc, falszywy strzal zabija zly cel i uczy zlego skojarzenia.
 */
class VoiceMatchTest {

    private val pl = listOf(
        "pies", "kot", "dom", "ksiazka", "okno", "samochod", "nauczyciel", "zdrowie", "podroz"
    )
    private val en = listOf(
        "dog", "cat", "house", "book", "window", "car", "teacher", "health", "journey"
    )
    // plansze celowo pelne slow mylacych sie ze soba
    private val ambPl = listOf("kot", "kod", "kos", "kok", "brat", "brak", "rok")
    private val ambEn = listOf("ship", "sheep", "chip", "cheap", "bat", "bad", "bed")

    private fun heard(transcripts: List<String>, cands: List<String>, polish: Boolean): String? {
        val r = VoiceMatch.match(transcripts, cands, polish)
        return if (r.accepted) r.best.answer else null
    }

    private data class Case(
        val said: List<String>,
        val want: String?,
        val board: List<String>,
        val polish: Boolean,
    )

    private val cases = listOf(
        // --- powinno trafic, polski ---
        Case(listOf("pies"), "pies", pl, true),
        Case(listOf("Pies."), "pies", pl, true),
        Case(listOf("piesk"), "pies", pl, true),
        Case(listOf("pies to"), "pies", pl, true),
        Case(listOf("ksiazka"), "ksiazka", pl, true),
        Case(listOf("ksionszka"), "ksiazka", pl, true),
        Case(listOf("samochut"), "samochod", pl, true),
        Case(listOf("samo chod"), "samochod", pl, true),
        Case(listOf("nauczyciel"), "nauczyciel", pl, true),
        Case(listOf("na uczyciel"), "nauczyciel", pl, true),
        Case(listOf("zdrovie"), "zdrowie", pl, true),
        Case(listOf("podruz"), "podroz", pl, true),
        Case(listOf("okno"), "okno", pl, true),
        Case(listOf("dom"), "dom", pl, true),
        // --- powinno trafic, angielski ---
        Case(listOf("dog"), "dog", en, false),
        Case(listOf("the dog"), "dog", en, false),
        Case(listOf("dogg"), "dog", en, false),
        Case(listOf("house"), "house", en, false),
        Case(listOf("hows"), "house", en, false),
        Case(listOf("ows", "house"), "house", en, false),
        Case(listOf("teacher"), "teacher", en, false),
        Case(listOf("teecher"), "teacher", en, false),
        Case(listOf("tea cher"), "teacher", en, false),
        Case(listOf("journey"), "journey", en, false),
        Case(listOf("jurney"), "journey", en, false),
        Case(listOf("window"), "window", en, false),
        Case(listOf("windows"), "window", en, false),
        Case(listOf("health"), "health", en, false),
        Case(listOf("helth"), "health", en, false),
        Case(listOf("car"), "car", en, false),
        Case(listOf("cat"), "cat", en, false),
        Case(listOf("kar"), "car", en, false),
        Case(listOf("book"), "book", en, false),
        Case(listOf("boook"), "book", en, false),
        Case(listOf("a book please"), "book", en, false),
        // --- nie strzelac: slowo spoza planszy ---
        Case(listOf("telefon"), null, pl, true),
        Case(listOf("elephant"), null, en, false),
        Case(listOf("komputer"), null, pl, true),
        Case(listOf("bicycle"), null, en, false),
        Case(listOf("uniwersytet"), null, pl, true),
        Case(listOf(""), null, pl, true),
        Case(listOf("yyy"), null, pl, true),
        Case(listOf("no dobra czekaj"), null, pl, true),
        // --- nie strzelac: urwane albo dwuznaczne, plansza mylaca ---
        Case(listOf("kop"), null, ambPl, true),
        Case(listOf("bra"), null, ambPl, true),
        Case(listOf("ko"), null, ambPl, true),
        Case(listOf("shi"), null, ambEn, false),
        Case(listOf("bap"), null, ambEn, false),
        Case(listOf("chep"), null, ambEn, false),
        // --- na mylacej planszy dokladne trafienia MAJA dzialac ---
        Case(listOf("kot"), "kot", ambPl, true),
        Case(listOf("brat"), "brat", ambPl, true),
        Case(listOf("sheep"), "sheep", ambEn, false),
        Case(listOf("cheap"), "cheap", ambEn, false),
    )

    @Test fun noFalseShots() {
        val wrong = cases.filter {
            val got = heard(it.said, it.board, it.polish)
            got != it.want && got != null
        }
        assertTrue(
            "falszywe strzaly: " + wrong.map { "${it.said.first()} -> ${heard(it.said, it.board, it.polish)}" },
            wrong.isEmpty()
        )
    }

    @Test fun everyCaseResolvesAsExpected() {
        val bad = cases.filter { heard(it.said, it.board, it.polish) != it.want }
        assertTrue(
            "niezgodne: " + bad.map { "${it.said.first()} chcialem=${it.want} dostalem=${heard(it.said, it.board, it.polish)}" },
            bad.isEmpty()
        )
    }

    @Test fun emptyBoardNeverFires() {
        assertEquals(null, heard(listOf("dog"), emptyList(), false))
    }

    @Test fun phoneticsFoldPolishHomophones() {
        // "morze" i "moze" brzmia identycznie - obie formy maja trafiac w ten sam cel
        assertEquals("morze", heard(listOf("moze"), listOf("morze", "samochod"), true))
    }

    @Test fun phoneticsFoldEnglishSpelling() {
        assertEquals("night", heard(listOf("nite"), listOf("night", "teacher"), false))
    }

    @Test fun longerWordsToleratePartialErrors() {
        // dlugie slowa moga sobie pozwolic na luzniejszy prog - jest z czego wnioskowac
        assertEquals("nauczyciel", heard(listOf("nauczycel"), pl, true))
    }
}
