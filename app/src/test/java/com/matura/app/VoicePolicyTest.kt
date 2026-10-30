package com.matura.app

import com.matura.app.ui.SilenceDetector
import com.matura.app.ui.VoiceLabel
import com.matura.app.ui.VoiceMode
import com.matura.app.ui.VoicePath
import com.matura.app.ui.VoicePolicy
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
}
