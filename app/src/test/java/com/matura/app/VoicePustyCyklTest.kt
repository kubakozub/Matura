package com.matura.app

import com.matura.app.ui.PUSTE_BEZ_MOWY
import com.matura.app.ui.PUSTE_Z_MOWA
import com.matura.app.ui.SilenceDetector
import com.matura.app.ui.VoiceLog
import com.matura.app.ui.eskalowacPoPustym
import com.matura.app.ui.PUSTE_PO_SUKCESIE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zgloszenie z Motoroli: przycisk glosu dziala, nie ma zadnego bledu, a wypowiedziane slowa nie
 * wywoluja reakcji (wpisane dzialaja). Tak zachowuje sie silnik bez modelu jezyka: przyjmuje
 * nagranie i w kolko zwraca ERROR_NO_MATCH. Poprzednia wersja traktowala to jako normalna cisze
 * i restartowala TEN SAM silnik w nieskonczonosc — drabinka nigdy nie ruszala.
 */
class VoicePustyCyklTest {

    @Test
    fun silnikKtoryJuzRozpoznal_ciszaGoNieDegraduje_aleAwariaTak() {
        // Cisza przy sprawnym silniku to po prostu cisza — uzytkownik milczy, nie ruszamy go.
        assertFalse(eskalowacPoPustym(oddalWynik = true, pusteCykle = 99, slyszalMowe = false))
        // Ale gdy mikrofon slyszy mowe, a silnik przestal zwracac cokolwiek, po PUSTE_PO_SUKCESIE
        // cyklach schodzimy nizej: wczesniejszy sukces nie moze blokowac odzyskania dzialania.
        assertFalse(eskalowacPoPustym(oddalWynik = true, pusteCykle = PUSTE_PO_SUKCESIE - 1, slyszalMowe = true))
        assertTrue(eskalowacPoPustym(oddalWynik = true, pusteCykle = PUSTE_PO_SUKCESIE, slyszalMowe = true))
    }

    @Test
    fun mowilAsilnikNicNieZwrocil_eskalacjaPoDwochCyklach() {
        assertFalse(eskalowacPoPustym(oddalWynik = false, pusteCykle = 1, slyszalMowe = true))
        assertTrue(eskalowacPoPustym(oddalWynik = false, pusteCykle = PUSTE_Z_MOWA, slyszalMowe = true))
    }

    @Test
    fun samaCisza_dajemySilnikowiWiecejProb() {
        assertFalse(eskalowacPoPustym(oddalWynik = false, pusteCykle = PUSTE_Z_MOWA, slyszalMowe = false))
        assertTrue(eskalowacPoPustym(oddalWynik = false, pusteCykle = PUSTE_BEZ_MOWY, slyszalMowe = false))
    }

    @Test
    fun progiSaSensowne() {
        // Mowa musi eskalowac szybciej niz cisza, inaczej rozroznienie nic nie daje.
        assertTrue(PUSTE_Z_MOWA < PUSTE_BEZ_MOWY)
        assertTrue(PUSTE_Z_MOWA >= 2)   // jeden cykl to za malo: silnik ma prawo raz nie trafic
    }

    @Test
    fun detektorCiszy_rozpoznajeMoweIJejBrak() {
        val d = SilenceDetector()
        d.onCycleStart()
        repeat(6) { d.onRms(-2.0f) }          // dno skali: mikrofon nic nie slyszy
        assertFalse(d.onEmptyCycle())          // pierwszy cichy cykl to jeszcze nie wniosek
        repeat(6) { d.onRms(-2.0f) }
        assertTrue(d.onEmptyCycle())           // drugi z rzedu — podpowiedz uzasadniona

        val g = SilenceDetector()
        g.onCycleStart()
        repeat(6) { g.onRms(4.0f) }            // wyraznie slychac mowe
        // Kolejnosc ma znaczenie i tak samo jest w kodzie: maxRms czytamy PRZED onEmptyCycle(),
        // bo ta metoda konczy cykl i zeruje pomiar.
        assertTrue(g.maxRms > SilenceDetector.SILENCE_DB)
        assertFalse(g.onEmptyCycle())          // to nie jest gluchy mikrofon
    }

    @Test
    fun dziennik_trzymaOstatnieZdarzeniaIczasWzgledny() {
        val log = VoiceLog(limit = 3)
        log.reset(1_000L)
        log.dodaj(1_000L, "start")
        log.dodaj(2_500L, "gotowy")
        log.dodaj(4_000L, "pusto (7)")
        log.dodaj(5_000L, "silnik -> sieciowy")
        val t = log.tekst()
        assertFalse("najstarszy wpis wypada poza limit", t.contains("start"))
        assertTrue(t.contains("silnik -> sieciowy"))
        assertEquals(3, t.lines().size)
        assertTrue("czas wzgledny od resetu", t.contains("1.5s gotowy"))
    }

    @Test
    fun dziennikPusty_niePsujeDiagnostyki() {
        assertEquals("—", VoiceLog().tekst())
    }
}
