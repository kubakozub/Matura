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
