package com.matura.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import com.matura.app.model.UiLanguage
import com.matura.app.ui.SilenceDetector
import com.matura.app.ui.Str
import com.matura.app.ui.VoiceBar
import com.matura.app.ui.VoiceLabel
import com.matura.app.ui.VoiceMode
import com.matura.app.ui.VoicePath
import com.matura.app.ui.VoicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Do 0.9.6 pasek glosu ZNIKAL, gdy sonda nie znalazla silnika mowy (VoiceMode.UNAVAILABLE)
 * — na telefonie bez uslugi rozpoznawania uczen nie mial nawet czego dotknac, choc zostawaly
 * jeszcze dwie drogi: okno systemowe i klawiatura telefonu z wlasnym dyktowaniem.
 * Od 0.9.7 pasek jest zawsze, a droga glosu wybierana jest przy dotknieciu.
 *
 * Tu sprawdzamy warunek konieczny: przycisk istnieje i klika sie TAKZE bez silnika,
 * a podpis pod nim mowi, ktora droga pojdzie glos. Sam Android (SpeechRecognizer, okno,
 * uprawnienia) nie da sie odtworzyc na JVM — dlatego logika decyzji jest wyciagnieta do
 * VoicePolicy i testowana osobno ponizej, bez Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VoiceAlwaysAvailableTest {

    @get:Rule
    val rule = createComposeRule()

    private val str = Str(UiLanguage.PL)

    private fun pokaz(
        mode: VoiceMode,
        message: String = "",
        okno: Boolean = false,
        onToggle: () -> Unit = {},
    ) {
        rule.setContent {
            VoiceBar(
                listening = false,
                mode = mode,
                message = message,
                str = str,
                onToggle = onToggle,
                dialogAvailable = okno,
            )
        }
    }

    /** Sedno: bez silnika i bez komunikatu przycisk JEST i reaguje na dotkniecie. */
    @Test
    fun bezSilnika_przyciskJestIKlika() {
        var dotkniec = 0
        pokaz(VoiceMode.UNAVAILABLE) { dotkniec++ }
        rule.onNodeWithText(str.speakAnswer)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals("dotkniecie paska musi dojsc do ekranu", 1, dotkniec)
    }

    /** Bez silnika i bez okna podpis zapowiada klawiature telefonu. */
    @Test
    fun bezSilnikaBezOkna_podpisKlawiatura() {
        pokaz(VoiceMode.UNAVAILABLE, okno = false)
        rule.onNodeWithText(str.voiceKeyboardHint).assertIsDisplayed()
    }

    /** Bez silnika, ale z oknem systemowym — podpis zapowiada okno. */
    @Test
    fun bezSilnikaZOknem_podpisOkno() {
        pokaz(VoiceMode.UNAVAILABLE, okno = true)
        rule.onNodeWithText(str.voiceDialog).assertIsDisplayed()
    }

    /** Komunikat ma pierwszenstwo przed podpisem trybu. */
    @Test
    fun komunikat_wypieraPodpis() {
        pokaz(VoiceMode.UNAVAILABLE, message = str.micDeniedSettings)
        rule.onNodeWithText(str.micDeniedSettings).assertIsDisplayed()
    }

    /** Kontrola: z silnikiem podpis to tryb (online), jak dotad. */
    @Test
    fun zSilnikiem_podpisTrybu() {
        pokaz(VoiceMode.ONLINE)
        rule.onNodeWithText(str.voiceOnline).assertIsDisplayed()
    }

    /** Wyczerpana drabinka: tryb dalej ONLINE, ale dotkniecie pojdzie do okna — podpis tez. */
    @Test
    fun wyczerpanaDrabinka_podpisOknoMimoTrybuOnline() {
        rule.setContent {
            VoiceBar(
                listening = false,
                mode = VoiceMode.ONLINE,
                message = "",
                str = str,
                onToggle = {},
                dialogAvailable = true,
                hasEngine = false,
            )
        }
        rule.onNodeWithText(str.voiceDialog).assertIsDisplayed()
    }

    /**
     * Wysokosc paska NIE zalezy od komunikatu. Pod paskiem siedzi klawiatura gry — gdyby
     * dluga podpowiedz rozciagala podpis na dwie linie, klawisze skakalyby w trakcie rundy.
     */
    @Test
    fun wysokoscPaska_niezaleznaOdKomunikatu() {
        val komunikaty = listOf(
            "",
            str.micSilentHint + " " + str.micDeniedSettings + " " + str.voiceKeyboardAuto,
            "Android 34 · X\non-device: nie\nsilniki (0): brak\npróbowano: —\nostatni błąd: 0",
        )
        val wysokosci = mutableListOf<Dp>()
        var komunikat by mutableStateOf("")
        rule.setContent {
            Box(Modifier.testTag("pasek")) {
                VoiceBar(
                    listening = false,
                    mode = VoiceMode.ONLINE,
                    message = komunikat,
                    str = str,
                    onToggle = {},
                )
            }
        }
        for (k in komunikaty) {
            komunikat = k
            rule.waitForIdle()
            wysokosci += rule.onNodeWithTag("pasek").getUnclippedBoundsInRoot().height
        }
        assertEquals("dluga podpowiedz nie moze podnosic paska", wysokosci[0], wysokosci[1])
        assertEquals("tekst wieloliniowy nie moze podnosic paska", wysokosci[0], wysokosci[2])
    }
}

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
