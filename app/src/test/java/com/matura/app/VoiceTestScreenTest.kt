package com.matura.app

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.speech.RecognitionService
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.matura.app.model.Options
import com.matura.app.model.UiLanguage
import com.matura.app.ui.OptionsScreen
import com.matura.app.ui.Str
import com.matura.app.ui.VOICE_TEST_SLOWO
import com.matura.app.ui.VoiceProbe
import com.matura.app.ui.VoiceTestScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Ekran testu mowy ma dzialac na telefonie, do ktorego nie mamy dostepu — a jego jedynym
 * zadaniem jest zdobyc dane z tego telefonu. Ekran, ktory sie nie sklada albo do ktorego nie
 * ma jak wejsc, nie zawiodlby glosno, tylko cicho: uzytkownik napisalby po raz trzeci
 * "nie dziala". Sprawdzamy wiec dwie rzeczy, ktorych nie widac w testach czystej logiki:
 * ekran daje sie zlozyc (bez zgody na mikrofon i bez dotykania SpeechRecognizera) i ma
 * instrukcje ze slowem do wypowiedzenia, a w OPCJACH jest pozycja, ktora do niego prowadzi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VoiceTestScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val str = Str(UiLanguage.PL)

    @Test
    fun ekranSkladaSieIMowiCoPowiedziec() {
        rule.setContent { VoiceTestScreen(str = str, onBack = {}) }
        // Instrukcja jest pierwsza i widoczna bez przewijania: bez niej uzytkownik nie wie,
        // ze ma mowic, i test konczy sie sama cisza na kazdej konfiguracji.
        rule.onNodeWithText(str.voiceTestIntro).assertIsDisplayed()
        assertTrue("instrukcja musi zawierac slowo do wypowiedzenia", str.voiceTestIntro.contains(VOICE_TEST_SLOWO))
        // Sam SpeechRecognizer nie rusza az do dotkniecia START — zlozenie ekranu ma byc bierne.
    }

    /**
     * PRZYPADEK (b): telefon bez JAKIEJKOLWIEK uslugi rozpoznawania. Matryca jest wtedy pusta,
     * wiec nie ma ani jednej proby — i to wlasnie wtedy caly dowod siedzi w NAGLOWKU (Android,
     * model telefonu, on-device/usluga/okno/zgoda, lista uslug). Do 0.9.11 naglowek i przycisk
     * KOPIUJ RAPORT staly pod warunkiem "sa jakies proby", wiec dokladnie w tym przypadku
     * uzytkownik nie mial czego skopiowac i wracal z jednym zdaniem — czyli z tym samym,
     * co przed calym ekranem.
     */
    @Test
    fun przebiegBezProb_iTakDajeNaglowekIRaportDoSkopiowania() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = VoiceProbe(context)
        rule.setContent { VoiceTestScreen(str = str, onBack = {}, probe = probe) }
        val przedStartem = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size

        rule.runOnUiThread { probe.start() }
        rule.waitForIdle()

        assertEquals("bez uslug rozpoznawania nie ma czego probowac", 0, probe.ile)
        rule.onNodeWithText(str.voiceTestNothing).assertExists()
        // Naglowek jest jedynym twardym dowodem tego przebiegu — musi byc na ekranie.
        rule.onNodeWithText("on-device:", substring = true).assertExists()
        // ...i musi dac sie skopiowac: doszedl JEDEN przycisk (KOPIUJ RAPORT), a jego dotkniecie
        // konczy sie potwierdzeniem kopiowania.
        val poPrzebiegu = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertEquals("po przebiegu ma dojsc przycisk kopiowania", przedStartem + 1, poPrzebiegu)
        rule.onAllNodes(hasClickAction())[poPrzebiegu - 1].performClick()
        rule.onNodeWithText(str.voiceTestCopied).assertExists()
    }

    /**
     * SEDNO. Przebieg trwa okolo minuty, przez ktora uczen ma TYLKO mowic — mowa nie resetuje
     * licznika wygaszania ekranu. Przerwanie (zgaszony ekran i ON_STOP, przycisk PRZERWIJ,
     * przejscie do innej aplikacji) nie jest wiec przypadkiem brzegowym. Do 0.9.11 przerwany
     * przebieg podawal sie za skonczony i konczyl zdaniem "na tym telefonie rozpoznawanie mowy
     * w aplikacji nie dziala" — przy przerwaniu przed pierwszym zapisem po ZERU prob. Z telefonu
     * wracalaby odpowiedz, tylko zla, i wyslalibysmy uzytkownika do pakietow jezyka albo do
     * przelacznika prywatnosci na podstawie niczego.
     */
    @Test
    fun przebiegPrzerwany_niePodszywaSiePodSkonczony() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Jedna usluga rozpoznawania — inaczej matryca jest pusta i nie ma czego przerywac.
        val komponent = ComponentName("com.przyklad.mowa", "com.przyklad.mowa.Rozpoznawanie")
        shadowOf(context.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(context.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val probe = VoiceProbe(context)
        rule.setContent { VoiceTestScreen(str = str, onBack = {}, probe = probe) }

        // Ekran gasnie (ON_STOP) albo uczen dotyka PRZERWIJ w polowie przebiegu.
        rule.runOnUiThread {
            probe.start()
            probe.zwolnij()
        }
        rule.waitForIdle()

        assertTrue("matryca musi miec z czego przerwac", probe.ile > 0)
        assertFalse("przerwany przebieg nie jest skonczony", probe.skonczone)
        assertTrue("i ma sie tak nazywac", probe.przerwane)

        // Kategoryczny werdykt nie ma prawa sie pojawic...
        rule.onNodeWithText(str.voiceTestNone).assertDoesNotExist()
        // ...a na jego miejscu stoi licznik prob i slowo, ktorego nie da sie przeoczyc.
        val przerwanie = str.voiceTestInterrupted(0, probe.ile)
        assertTrue(
            "ekran ma powiedziec, ze przebieg jest niepelny",
            rule.onAllNodesWithText(przerwanie, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "a raport do wklejenia ma to niesc w naglowku",
            rule.onAllNodesWithText("PRZERWANY", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "razem z licznikiem prob",
            rule.onAllNodesWithText("próby: 0/" + probe.ile, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun wOpcjachJestDrogaDoTestuMowy() {
        rule.setContent {
            OptionsScreen(
                str = str,
                options = Options(),
                onBack = {}, onChange = {},
            )
        }
        rule.onNodeWithText(str.voiceTest).assertExists()
    }
}
