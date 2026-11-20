package com.matura.app

import com.matura.app.ui.PUSTE_BEZ_MOWY
import com.matura.app.ui.PUSTE_PO_SUKCESIE
import com.matura.app.ui.PUSTE_Z_MOWA
import com.matura.app.ui.eskalowacPoPustym
import com.matura.app.ui.zejscPoMilczacymCyklu
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REGRESJA: gotowosc silnika nie jest rozpoznaniem.
 *
 * Do wersji 0.9.11 wlacznie decyzje o zejsciu drabinka podejmowal `dzialal` — znak zycia
 * ustawiany juz przez onReadyForSpeech. Silnik, ktory melduje gotowosc i nie rozpoznaje ani
 * jednego slowa (typowo: brak modelu jezyka), na zawsze wylaczal eskalacje po pustych cyklach.
 * Objaw zgloszony przez uzytkownika: pasek "Mow odpowiedz" swieci, zadnego bledu nie ma,
 * wypowiedziane slowa nie wywoluja reakcji, a te same wpisane z klawiatury dzialaja.
 *
 * Te testy pilnuja, ze o degradacji silnika decyduje wylacznie NIEPUSTY WYNIK TEKSTOWY.
 */
class VoiceGotowoscTest {

    @Test
    fun samaGotowosc_nieBlokujeEskalacji() {
        // Silnik zglosil gotowosc (dzialal = true w kodzie), ale nie oddal ani jednego tekstu.
        // Sygnal, ktory tu podajemy, to oddalWynik = false — i eskalacja MA nastapic.
        assertTrue(
            "gotowosc bez rozpoznania nie moze chronic silnika",
            eskalowacPoPustym(oddalWynik = false, pusteCykle = PUSTE_Z_MOWA, slyszalMowe = true),
        )
        assertTrue(
            eskalowacPoPustym(oddalWynik = false, pusteCykle = PUSTE_BEZ_MOWY, slyszalMowe = false),
        )
    }

    @Test
    fun pustyWynik_niePodszywaSiePodTranskrypcje() {
        // deliver() ustawia oddalWynik dopiero dla niepustej listy rozpoznan, wiec pusty
        // onResults zostawia oddalWynik = false. Z punktu widzenia polityki to nadal awaria.
        assertTrue(eskalowacPoPustym(oddalWynik = false, pusteCykle = PUSTE_Z_MOWA, slyszalMowe = true))
        assertTrue(zejscPoMilczacymCyklu(oddalWynik = false))
    }

    @Test
    fun poprawnyWynik_chroniPrzedCisza_aleNiePrzedAwaria() {
        // Sprawny silnik + milczacy gracz = nie ruszamy niczego, choćby cykli byly setki.
        assertFalse(eskalowacPoPustym(oddalWynik = true, pusteCykle = 999, slyszalMowe = false))
        // Sprawny silnik + slychac mowe + zero wynikow = po progu schodzimy nizej.
        assertFalse(eskalowacPoPustym(oddalWynik = true, pusteCykle = PUSTE_PO_SUKCESIE - 1, slyszalMowe = true))
        assertTrue(
            "wczesniejszy sukces nie moze blokowac odzyskania dzialania na zawsze",
            eskalowacPoPustym(oddalWynik = true, pusteCykle = PUSTE_PO_SUKCESIE, slyszalMowe = true),
        )
    }

    @Test
    fun progiZachowujaSensownaKolejnosc() {
        assertTrue("mowa eskaluje szybciej niz cisza", PUSTE_Z_MOWA < PUSTE_BEZ_MOWY)
        assertTrue("sprawdzony silnik ma najwiekszy kredyt", PUSTE_BEZ_MOWY < PUSTE_PO_SUKCESIE)
        assertTrue("jeden nietrafiony cykl to za malo", PUSTE_Z_MOWA >= 2)
    }

    @Test
    fun milczacyCykl_degradujeTylkoSilnikBezWyniku() {
        assertTrue(zejscPoMilczacymCyklu(oddalWynik = false))
        assertFalse(zejscPoMilczacymCyklu(oddalWynik = true))
    }
}
