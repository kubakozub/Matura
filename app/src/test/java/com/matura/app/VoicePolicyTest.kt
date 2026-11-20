package com.matura.app

import com.matura.app.game.Matching
import com.matura.app.model.Entry
import com.matura.app.model.WordSet
import com.matura.app.ui.SilenceDetector
import com.matura.app.ui.VoiceLabel
import com.matura.app.ui.VoiceMode
import com.matura.app.ui.VoicePath
import com.matura.app.ui.VoicePolicy
import com.matura.app.ui.odpowiedziPoPolsku
import com.matura.app.ui.polskiZDeklaracji
import com.matura.app.ui.stronaPolska
import com.matura.app.ui.zaliczycWypowiedz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Czysta logika decyzji — bez Androida, bez Robolectric. */
class VoicePolicyTest {

    @Test
    fun brakZgody_najpierwProsba() {
        assertEquals(
            VoicePath.REQUEST_PERMISSION,
            VoicePolicy.path(permissionGranted = false, settingsPending = false, hasEngine = true, dialogAvailable = true),
        )
    }

    @Test
    fun odmowaTrwala_otwieraUstawienia() {
        assertEquals(
            VoicePath.OPEN_SETTINGS,
            VoicePolicy.path(permissionGranted = false, settingsPending = true, hasEngine = true, dialogAvailable = true),
        )
    }

    @Test
    fun zgodaISilnik_nasluch() {
        assertEquals(
            VoicePath.ENGINE,
            VoicePolicy.path(permissionGranted = true, settingsPending = false, hasEngine = true, dialogAvailable = true),
        )
    }

    @Test
    fun bezSilnikaZOknem_okno() {
        assertEquals(
            VoicePath.SYSTEM_DIALOG,
            VoicePolicy.path(permissionGranted = true, settingsPending = false, hasEngine = false, dialogAvailable = true),
        )
    }

    @Test
    fun bezNiczego_klawiatura() {
        assertEquals(
            VoicePath.KEYBOARD,
            VoicePolicy.path(permissionGranted = true, settingsPending = false, hasEngine = false, dialogAvailable = false),
        )
    }

    // ---- zgoda dotyczy TYLKO silnika ----------------------------------------
    // Okno systemowe nagrywa aplikacja Google (wlasna zgoda), klawiatura to zwykly tekst.
    // Bez silnika prosba o RECORD_AUDIO bylaby prosba o cos, czego nigdy nie uzyjemy.

    @Test
    fun bezSilnika_bezZgody_odRazuOkno() {
        assertEquals(
            VoicePath.SYSTEM_DIALOG,
            VoicePolicy.path(permissionGranted = false, settingsPending = false, hasEngine = false, dialogAvailable = true),
        )
    }

    @Test
    fun bezSilnika_odmowaTrwala_odRazuKlawiatura() {
        assertEquals(
            VoicePath.KEYBOARD,
            VoicePolicy.path(permissionGranted = false, settingsPending = true, hasEngine = false, dialogAvailable = false),
        )
    }

    /**
     * Petla, ktora byla w 0.9.7: ustawienia -> (bez zmiany) -> prosba (Android 11+ odpowiada
     * natychmiast "nie") -> odmowa trwala -> ustawienia... Po JEDNYM otwarciu ustawien bez
     * zmiany zgody dotkniecie ma zejsc do okna / klawiatury.
     */
    @Test
    fun ustawieniaJuzOtwarte_bezZmianyZgody_oknoAlboKlawiatura() {
        assertEquals(
            VoicePath.SYSTEM_DIALOG,
            VoicePolicy.path(permissionGranted = false, settingsPending = true, hasEngine = true, dialogAvailable = true, settingsTried = true),
        )
        assertEquals(
            VoicePath.KEYBOARD,
            VoicePolicy.path(permissionGranted = false, settingsPending = true, hasEngine = true, dialogAvailable = false, settingsTried = true),
        )
    }

    /** Uczen dal zgode w ustawieniach: szczebel zgody odpada, silnik wraca. */
    @Test
    fun ustawieniaOtwarte_zgodaDana_nasluch() {
        assertEquals(
            VoicePath.ENGINE,
            VoicePolicy.path(permissionGranted = true, settingsPending = true, hasEngine = true, dialogAvailable = true, settingsTried = true),
        )
    }

    /** Zadna kombinacja nie moze skonczyc sie "niczym" — kazda daje jakas droge. */
    @Test
    fun kazdaKombinacja_dajeDroge() {
        val flagi = listOf(true, false)
        for (z in flagi) for (u in flagi) for (s in flagi) for (o in flagi) for (t in flagi) {
            val p = VoicePolicy.path(z, u, s, o, t)
            assertTrue("$z/$u/$s/$o/$t -> $p", VoicePath.entries.contains(p))
            // Zgoda i ustawienia to sprawa silnika: bez silnika nigdy nie prosimy i nie otwieramy ustawien.
            if (!s) assertTrue("$z/$u/$s/$o/$t -> $p", p == VoicePath.SYSTEM_DIALOG || p == VoicePath.KEYBOARD)
        }
    }

    @Test
    fun podpis_kolejnosc() {
        assertEquals(VoiceLabel.MESSAGE, VoicePolicy.label(false, VoiceMode.UNAVAILABLE, hasMessage = true, dialogAvailable = true, hasEngine = false))
        assertEquals(VoiceLabel.ON_DEVICE, VoicePolicy.label(false, VoiceMode.ON_DEVICE, hasMessage = false, dialogAvailable = true, hasEngine = true))
        assertEquals(VoiceLabel.ONLINE, VoicePolicy.label(false, VoiceMode.ONLINE, hasMessage = false, dialogAvailable = true, hasEngine = true))
        assertEquals(VoiceLabel.ONLINE, VoicePolicy.label(true, VoiceMode.UNAVAILABLE, hasMessage = false, dialogAvailable = true, hasEngine = true))
        assertEquals(VoiceLabel.DIALOG, VoicePolicy.label(false, VoiceMode.UNAVAILABLE, hasMessage = false, dialogAvailable = true, hasEngine = false))
        assertEquals(VoiceLabel.KEYBOARD, VoicePolicy.label(false, VoiceMode.UNAVAILABLE, hasMessage = false, dialogAvailable = false, hasEngine = false))
        // O drodze decyduje hasEngine, nie sam tryb: UNAVAILABLE z lista uslug zwiaze sie
        // z pierwsza (online), a wyczerpana drabinka przy ONLINE pojdzie do okna / klawiatury.
        assertEquals(VoiceLabel.ONLINE, VoicePolicy.label(false, VoiceMode.UNAVAILABLE, hasMessage = false, dialogAvailable = true, hasEngine = true))
        assertEquals(VoiceLabel.DIALOG, VoicePolicy.label(false, VoiceMode.ONLINE, hasMessage = false, dialogAvailable = true, hasEngine = false))
        assertEquals(VoiceLabel.KEYBOARD, VoicePolicy.label(false, VoiceMode.ON_DEVICE, hasMessage = false, dialogAvailable = false, hasEngine = false))
    }

    // ---- gluchy mikrofon -----------------------------------------------------

    /** Cykl "na dnie": kilkanascie probek RMS na poziomie ciszy silnika Google (-2,12 dB). */
    private fun SilenceDetector.cichyCykl() {
        repeat(12) { onRms(-2.12f) }
    }

    @Test
    fun dwaCicheCykle_podpowiedz() {
        val d = SilenceDetector()
        d.cichyCykl()
        assertFalse("po jednym pustym cyklu jeszcze nie straszymy", d.onEmptyCycle())
        d.cichyCykl()
        assertTrue("dwa puste cykle z RMS na dnie = mikrofon gluchy", d.onEmptyCycle())
        assertTrue(d.active)
    }

    @Test
    fun cykl_zSygnalem_zerujeLicznik() {
        val d = SilenceDetector()
        d.cichyCykl()
        d.onEmptyCycle()
        repeat(12) { d.onRms(-2.12f) }
        d.onRms(3.5f) // ktos szurnal krzeslem — mikrofon zyje
        assertFalse(d.onEmptyCycle())
        d.cichyCykl()
        assertFalse("licznik liczy od nowa po cyklu z sygnalem", d.onEmptyCycle())
    }

    /** Silnik, ktory nie raportuje RMS w ogole, nie moze uchodzic za wyciszony mikrofon. */
    @Test
    fun bezProbekRms_nigdyPodpowiedzi() {
        val d = SilenceDetector()
        repeat(5) { assertFalse(d.onEmptyCycle()) }
    }

    @Test
    fun rozpoznanaMowa_kasujePodpowiedz() {
        val d = SilenceDetector()
        d.cichyCykl(); d.onEmptyCycle()
        d.cichyCykl(); assertTrue(d.onEmptyCycle())
        d.onSpeech()
        assertFalse(d.active)
    }

    /** Prog: -1,5 dB to jeszcze cisza, odrobine glosniej juz nie. */
    @Test
    fun prog_minusPoltoraDecybela() {
        val naProgu = SilenceDetector()
        repeat(6) { naProgu.onRms(-1.5f) }
        naProgu.onEmptyCycle()
        repeat(6) { naProgu.onRms(-1.5f) }
        assertTrue(naProgu.onEmptyCycle())

        val nadProgiem = SilenceDetector()
        repeat(6) { nadProgiem.onRms(-1.4f) }
        nadProgiem.onEmptyCycle()
        repeat(6) { nadProgiem.onRms(-1.4f) }
        assertFalse(nadProgiem.onEmptyCycle())
    }

    // ---- jedna wypowiedz, jedno zaliczenie -----------------------------------

    @Test
    fun wypowiedzKtoraZaliczyla_nieZaliczaNicWiecej() {
        assertFalse("ta sama odpowiedz", zaliczycWypowiedz("bluza", "bluza", 4, 4))
        assertFalse("inna odpowiedz", zaliczycWypowiedz("bluza sportowa", "bluza", 4, 4))
        assertTrue("nastepna wypowiedz, inna odpowiedz", zaliczycWypowiedz("bluza sportowa", "bluza", 5, 4))
        assertTrue("nastepna wypowiedz, ta sama odpowiedz", zaliczycWypowiedz("bluza", "bluza", 5, 4))
        // okno systemowe liczy swoje wypowiedzi na minusie
        assertFalse(zaliczycWypowiedz("plecy", "kolano", -2, -2))
        assertTrue(zaliczycWypowiedz("plecy", "kolano", -3, -2))
        assertTrue(zaliczycWypowiedz("plecy", "kolano", -1, 1))
    }

    /** Ekran zaczyna od "nic nie zaliczono": pusta odpowiedz i numer 0. To nie jest wypowiedz. */
    @Test
    fun pierwszaOdpowiedzRundy_zaliczaPrzyKazdymNumerze() {
        assertTrue(zaliczycWypowiedz("kolano", "", 1, 0))
        assertTrue("silnik, ktory numeruje od zera", zaliczycWypowiedz("kolano", "", 0, 0))
        assertTrue(zaliczycWypowiedz("kolano", "", -1, 0))
    }

    // ---- jezyk odpowiedzi z deklaracji zestawu -------------------------------

    @Test
    fun stronaPolska_zestawWbudowany() {
        assertFalse("odpowiedzia jest haslo", stronaPolska(haslo = true, "English", "Polski"))
        assertTrue("odpowiedzia jest tlumaczenie", stronaPolska(haslo = false, "English", "Polski"))
    }

    /** Przycisk zamiany w edytorze przestawia kolumny razem z podpisami. */
    @Test
    fun stronaPolska_poZamianieKolumn() {
        assertTrue(stronaPolska(haslo = true, "Polski", "English"))
        assertFalse(stronaPolska(haslo = false, "Polski", "English"))
    }

    @Test
    fun polskiZDeklaracji_znaNazwyObuJezykow() {
        for (nazwa in listOf("Polski", "polski", "Polish", "polish", "PL", "pl", " Polski ", "POLSKI", "pl-PL")) {
            assertEquals(nazwa, true, polskiZDeklaracji(nazwa))
        }
        for (nazwa in listOf("English", "english", "Angielski", "angielski", "EN", "en", " English ", "ENGLISH", "en-GB")) {
            assertEquals(nazwa, false, polskiZDeklaracji(nazwa))
        }
        for (nazwa in listOf("", "?", "Deutsch", "niemiecki", "polski i angielski", "plus")) {
            assertEquals(nazwa, null, polskiZDeklaracji(nazwa))
        }
    }

    @Test
    fun stronaPolska_jednaStronaBezDeklaracji_jestDrugimJezykiemGry() {
        // edytor zapisuje "?" w miejsce pustego podpisu
        assertFalse(stronaPolska(haslo = false, "Polski", "?"))
        assertTrue(stronaPolska(haslo = true, "Polski", "?"))
        assertTrue(stronaPolska(haslo = true, "?", "English"))
        assertFalse(stronaPolska(haslo = false, "?", "English"))
        assertTrue(stronaPolska(haslo = false, "English", "moje slowka"))
    }

    @Test
    fun stronaPolska_zadnaDeklaracja_zostajeRegulaKierunku() {
        assertFalse("haslo liczy sie za angielskie", stronaPolska(haslo = true, "?", "?"))
        assertTrue("tlumaczenie liczy sie za polskie", stronaPolska(haslo = false, "?", "?"))
        assertFalse(stronaPolska(haslo = true, "Deutsch", "Latina"))
        assertTrue(stronaPolska(haslo = false, "Deutsch", "Latina"))
    }

    @Test
    fun stronaPolska_obieStronyWTymSamymJezyku() {
        assertTrue(stronaPolska(haslo = true, "Polski", "polski"))
        assertTrue(stronaPolska(haslo = false, "Polski", "polski"))
        assertFalse(stronaPolska(haslo = true, "English", "EN"))
        assertFalse(stronaPolska(haslo = false, "English", "EN"))
    }

    /**
     * Zestaw wlasny po zamianie kolumn, tak jak zlozy go ekran gry. Do 0.9.18 przy kierunku
     * domyslnym silnik sluchal tu po polsku angielskich odpowiedzi.
     */
    @Test
    fun zestawPoZamianieKolumn_sluchaWJezykuOdpowiedzi() {
        val zestaw = WordSet(
            id = "wlasny",
            title = "Cialo",
            termLanguage = "Polski",
            definitionLanguage = "English",
            entries = listOf(Entry("kolano", "knee"), Entry("plecy", "back"), Entry("głowa", "head")),
        )
        val haslaPolskie = stronaPolska(haslo = true, zestaw.termLanguage, zestaw.definitionLanguage)
        val tlumaczeniaPolskie = stronaPolska(haslo = false, zestaw.termLanguage, zestaw.definitionLanguage)
        // kierunek domyslny (haslo -> tlumaczenie): uczen mowi "knee", silnik ma sluchac en-US
        assertFalse(tlumaczeniaPolskie)
        // kierunek odwrotny: uczen mowi "kolano"
        assertTrue(haslaPolskie)

        // kierunek losowy: strony zestawu zbierane wedlug deklaracji, nie wedlug kolumny
        val hasla = zestaw.entries.flatMap { Matching.acceptedAnswers(it.term) }.toSet()
        val tlumaczenia = zestaw.entries.flatMap { Matching.acceptedAnswers(it.definition) }.toSet()
        val polskie = (if (haslaPolskie) hasla else emptySet()) + (if (tlumaczeniaPolskie) tlumaczenia else emptySet())
        val angielskie = (if (haslaPolskie) emptySet() else hasla) + (if (tlumaczeniaPolskie) emptySet() else tlumaczenia)
        assertEquals(true, odpowiedziPoPolsku(listOf("kolano", "plecy", "head"), polskie, angielskie))
        assertEquals(false, odpowiedziPoPolsku(listOf("knee", "back", "glowa"), polskie, angielskie))
    }
}
