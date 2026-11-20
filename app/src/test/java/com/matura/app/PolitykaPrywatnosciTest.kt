package com.matura.app

import com.matura.app.ui.politykaEn
import com.matura.app.ui.politykaPl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polityka prywatnosci to jedyny tekst w aplikacji, ktory ma byc prawdziwy CO DO SLOWA —
 * Google Play wymaga go wewnatrz aplikacji, a czytaja go uczniowie i ich rodzice.
 *
 * Ten zestaw powstal po wydaniu 0.9.18, w ktorym polityka opisywala rzeczy, ktorych paczka
 * ze sklepu nie robila:
 *  1. odsylala do "OPCJE -> Test mikrofonu" i do kilkusekundowego nagrania, podczas gdy
 *     ekrany diagnostyczne schowano za BuildConfig.DEBUG — czyli w wydaniu zaden plik audio
 *     nie powstawal, a takiej pozycji w menu w ogole nie bylo;
 *  2. obiecywala, ze "najpierw prosimy o rozpoznawanie bez internetu" i ze "dzwiek nie
 *     opuszcza urzadzenia", podczas gdy od 0.9.17 sesja zaczyna od silnika domyslnego,
 *     a EXTRA_PREFER_OFFLINE leci tylko z potwierdzonym modelem (API 33+).
 *
 * Test bierze flage jako PARAMETR funkcji, a nie z BuildConfig: w testach jednostkowych
 * BuildConfig.DEBUG jest zawsze true, wiec wariant sklepowy nie mialby kto sprawdzic.
 */
class PolitykaPrywatnosciTest {

    private companion object {
        /** Build sklepowy: ekranu Test mikrofonu nie da sie otworzyc. */
        const val BEZ_EKRANU = false

        /** Build debug: pozycja w OPCJACH istnieje, wiec plik audio moze powstac. */
        const val Z_EKRANEM = true
    }

    // --- wariant sklepowy: zero obietnic o nagrywaniu -----------------------------------

    @Test fun bez_ekranu_testu_polityka_nie_wspomina_o_nagrywaniu() {
        for (tekst in listOf(politykaPl(BEZ_EKRANU), politykaEn(BEZ_EKRANU))) {
            assertFalse("polityka odsyla do pozycji menu, ktorej w wydaniu nie ma",
                tekst.contains("Test mikrofonu") || tekst.contains("Microphone test"))
            assertFalse("polityka opisuje nagranie, ktorego wydanie nie tworzy",
                tekst.contains("nagranie") || tekst.contains("saves a few seconds of audio"))
            assertFalse("polityka mowi o wyjatku od zakazu zapisu audio",
                tekst.contains("JEDYNY wyjątek") || tekst.contains("ONLY exception"))
        }
    }

    @Test fun bez_ekranu_testu_polityka_obiecuje_zero_plikow_audio() {
        assertTrue(
            "wersja sklepowa ma powiedziec wprost, ze nie zapisuje zadnych plikow audio",
            politykaPl(BEZ_EKRANU).contains("nie zapisujemy żadnych plików audio"),
        )
        assertTrue(
            politykaEn(BEZ_EKRANU).contains("do not record or store any audio at all"),
        )
    }

    // --- wariant debug: tekst wraca razem z ekranem -------------------------------------

    @Test fun z_ekranem_testu_polityka_opisuje_nagranie() {
        assertTrue(
            "gdy ekran jest osiagalny, polityka MUSI opisac zapisywany plik",
            politykaPl(Z_EKRANEM).contains("OPCJE → Test mikrofonu"),
        )
        assertTrue(politykaEn(Z_EKRANEM).contains("OPTIONS → Microphone test"))
    }

    @Test fun oba_warianty_roznia_sie_TYLKO_punktem_o_audio() {
        // Gdyby warianty rozjechaly sie gdzie indziej, jeden z nich przestalby byc pilnowany
        // przez reszte tego zestawu — a niepilnowany wariant to dokladnie ta sama awaria.
        for (para in listOf(
            politykaPl(BEZ_EKRANU) to politykaPl(Z_EKRANEM),
            politykaEn(BEZ_EKRANU) to politykaEn(Z_EKRANEM),
        )) {
            val (sklep, debug) = para
            val wspolne = sklep.lines().filterNot { it.startsWith("•") }
            assertTrue(
                "poza punktami wypunktowania warianty musza byc identyczne",
                debug.lines().filterNot { it.startsWith("•") } == wspolne,
            )
        }
    }

    // --- rozpoznawanie mowy: zadnych gwarancji, ktorych kod nie daje --------------------

    @Test fun polityka_nie_obiecuje_rozpoznawania_offline_w_pierwszej_kolejnosci() {
        for (wariant in listOf(BEZ_EKRANU, Z_EKRANEM)) {
            val pl = politykaPl(wariant)
            assertFalse(
                "kod zaczyna od silnika DOMYSLNEGO, wiec nie wolno pisac o 'najpierw'",
                pl.contains("Najpierw prosimy") || pl.contains("najpierw prosimy"),
            )
            assertFalse(
                "to gwarancja, ktorej kod nie daje: EXTRA_PREFER_OFFLINE jest tylko prosba",
                pl.contains("dźwięk nie opuszcza urządzenia"),
            )
            assertFalse(
                politykaEn(wariant).contains("the audio never leaves the device"),
            )
        }
    }

    @Test fun polityka_mowi_ze_o_offline_prosimy_dopiero_od_androida_13() {
        // VoiceInput.sprawdzModel ponizej API 33 wpisuje "brak modelu", wiec na Androidzie 7-12
        // EXTRA_PREFER_OFFLINE nie leci NIGDY. Uczen ma to przeczytac, a nie sie domyslic.
        assertTrue(politykaPl(BEZ_EKRANU).contains("Androidzie 13 i nowszym"))
        assertTrue(politykaEn(BEZ_EKRANU).contains("Android 13 and newer"))
    }

    // --- lista "co zapisujemy" zgodna z tym, co leci do matura.json ---------------------

    @Test fun lista_zapisu_wymienia_nazwe_gracza_i_przerwana_sesje() {
        // Profile.name i ScoreRecord.playerName ida do zapisu przy KAZDYM wyniku,
        // a SaveData.sesja trzyma przerwana sesje "Dzisiejsze 5 minut".
        val pl = politykaPl(BEZ_EKRANU)
        assertTrue(pl.contains("nazwę gracza"))
        assertTrue(pl.contains("przerwaną sesję"))
        val en = politykaEn(BEZ_EKRANU)
        assertTrue(en.contains("player name"))
        assertTrue(en.contains("interrupted"))
    }

    @Test fun sekcja_czego_nie_zbieramy_nie_kloci_sie_z_nazwa_gracza() {
        // Do 0.9.18 lista zaczynala sie od slowa "Imienia", a uczen wpisuje swoje imie
        // na pierwszym ekranie i widzi je potem przy wynikach.
        val pl = politykaPl(BEZ_EKRANU)
        val ogon = pl.substringAfter("CZEGO NIE ZBIERAMY")
        assertFalse("sprzecznosc: imie jest zapisywane jako nazwa gracza", ogon.contains("Imienia,"))
        assertTrue(ogon.contains("zostaje na telefonie"))
    }
}
