package com.matura.app

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import android.speech.RecognitionService
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.matura.app.model.Options
import com.matura.app.model.UiLanguage
import com.matura.app.ui.KOD_PRZERWANE
import com.matura.app.ui.OptionsScreen
import com.matura.app.ui.PowodPrzerwania
import com.matura.app.ui.Str
import com.matura.app.ui.TAG_TEST_KOPIUJ_OSTATNI
import com.matura.app.ui.TAG_TEST_START
import com.matura.app.ui.TAG_TEST_STOP
import com.matura.app.ui.VOICE_TEST_PAMIEC
import com.matura.app.ui.VOICE_TEST_SLOWO
import com.matura.app.ui.VoiceProbe
import com.matura.app.ui.VoiceTestScreen
import com.matura.app.ui.raportPrzebiegu
import com.matura.app.ui.voiceTestTeksty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * Kazdy test zaczyna od telefonu, na ktorym testu mowy jeszcze nie bylo.
     *
     * Ekran od 0.9.16 zapisuje raport na dysk i przy wejsciu pokazuje przycisk "KOPIUJ OSTATNI
     * RAPORT", wiec zapis z poprzedniego testu zmienialby liczbe przyciskow na ekranie —
     * i testy przestalyby miec jeden ustalony stan poczatkowy.
     */
    @Before
    fun czystyTelefon() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(VOICE_TEST_PAMIEC, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

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

        // ...a od 0.9.16 takze przezyc zamkniecie aplikacji. PELNY przebieg musi wrocic z dysku
        // jako pelny: zapis po ostatniej probie ma jeszcze skonczone = false, wiec bez zapisu
        // na koniec raport odtworzony podawalby sie za przerwany.
        val zapisany = VoiceProbe(context).ostatniRaport()
        assertNotNull("raport ma byc na dysku", zapisany)
        assertTrue("i wiedziec, ze przebieg doszedl do konca", zapisany!!.skonczone)
        assertFalse(
            "wiec nie ma prawa nazywac sie przerwanym",
            raportPrzebiegu(zapisany, str, voiceTestTeksty(str)).contains("PRZERWANY"),
        )
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

        // Uczen dotyka PRZERWIJ w polowie przebiegu.
        rule.runOnUiThread {
            probe.start()
            probe.zwolnij(PowodPrzerwania.PRZYCISK)
        }
        rule.waitForIdle()

        assertTrue("matryca musi miec z czego przerwac", probe.ile > 0)
        assertFalse("przerwany przebieg nie jest skonczony", probe.skonczone)
        assertTrue("i ma sie tak nazywac", probe.przerwane)

        // SEDNO poprawki 0.9.16: proba, ktora wlasnie leciala, JEST wynikiem i zostaje.
        assertEquals("przerwana proba trafia do wynikow", 1, probe.wyniki.size)
        assertEquals("z wlasnym kodem, a nie jako blad silnika", KOD_PRZERWANE, probe.wyniki[0].blad)

        // Kategoryczny werdykt nie ma prawa sie pojawic...
        rule.onNodeWithText(str.voiceTestNone).assertDoesNotExist()
        // ...a na jego miejscu stoi licznik prob i slowo, ktorego nie da sie przeoczyc.
        val przerwanie = str.voiceTestInterrupted(1, probe.ile)
        assertTrue(
            "ekran ma powiedziec, ze przebieg jest niepelny",
            rule.onAllNodesWithText(przerwanie, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "a raport do wklejenia ma to niesc w naglowku",
            rule.onAllNodesWithText("PRZERWANY", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "razem z licznikiem prob, w ktorym przerwana proba juz sie liczy",
            rule.onAllNodesWithText("próby: 1/" + probe.ile, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // ...i z POWODEM, bez ktorego "PRZERWANY" nie mowi, kto przerwal: uczen, tlo czy wyjscie.
        assertTrue(
            "raport ma niesc powod przerwania",
            rule.onAllNodesWithText("PRZERWANY (przycisk", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "i chwile przerwania liczona od startu przebiegu",
            rule.onAllNodesWithText("w próbie 1", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * SEDNO poprawki 0.9.16 numer dwa: przebieg trwa okolo minuty, a wystarczy powiadomienie
     * albo przypadkowe przelaczenie aplikacji, zeby do 0.9.16 przepadl w calosci — ON_STOP wolal
     * zwolnij(). Mikrofonu w tle i tak nie ma, wiec rozpoznawacz trzeba oddac, ale wyniki,
     * matryca i naglowek nie maja powodu ginac.
     */
    @Test
    fun zejscieWTlo_iPowrot_nieGubiWynikow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val komponent = ComponentName("com.przyklad.mowa", "com.przyklad.mowa.Rozpoznawanie")
        shadowOf(context.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(context.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val probe = VoiceProbe(context)
        rule.setContent { VoiceTestScreen(str = str, onBack = {}, probe = probe) }

        rule.runOnUiThread { probe.start() }
        rule.waitForIdle()
        val naglowek = probe.naglowekRaportu()
        assertTrue("przebieg musi miec z czego schodzic w tlo", probe.ile > 0)

        rule.runOnUiThread { probe.wstrzymaj() }
        rule.waitForIdle()
        assertTrue("wstrzymany przebieg czeka", probe.wstrzymany)
        assertFalse("ale NIE jest przerwany", probe.przerwane)
        assertFalse("ani skonczony", probe.skonczone)
        assertEquals("proba z chwili zejscia w tlo zostaje wynikiem", 1, probe.wyniki.size)
        assertEquals(KOD_PRZERWANE, probe.wyniki[0].blad)
        assertEquals("i liczymy, ile razy przebieg schodzil w tlo", 1, probe.zejsciaWTlo)
        assertTrue("naglowek ma to niesc", probe.naglowekRaportu().contains("tło: 1x"))

        rule.runOnUiThread { probe.wznow() }
        rule.waitForIdle()
        assertFalse("powrot wznawia przebieg", probe.wstrzymany)
        assertTrue(probe.trwa)
        assertEquals("wyniki z przed tla sa nietkniete", 1, probe.wyniki.size)
        assertTrue("i naglowek tez", probe.naglowekRaportu().startsWith(naglowek.substringBefore("tło:")))
        assertFalse("wznowiony przebieg nie jest przerwany", probe.przerwane)
    }

    /**
     * Naglowek ma niesc DWIE nazwy, ktorych nie widac nigdzie indziej: kogo system uwaza za
     * domyslny rozpoznawacz i czyje jest systemowe okno mowy. Na telefonie bez aplikacji Google
     * to one rozstrzygaja, czyj silnik odpowiada za proby "default / *" — a android.speech
     * .RecognitionService rejestruje tam takze aplikacje, ktorych nikt by sie nie spodziewal.
     */
    @Test
    fun naglowekNiesieDomyslnaUslugeIPakietOknaMowy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putString(
            context.contentResolver,
            "voice_recognition_service",
            "com.anthropic.claude/.VoiceService",
        )
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val probe = VoiceProbe(context)
        rule.setContent { VoiceTestScreen(str = str, onBack = {}, probe = probe) }

        rule.runOnUiThread { probe.start() }
        rule.waitForIdle()

        val naglowek = probe.naglowekRaportu()
        assertTrue("domyslny rozpoznawacz musi byc nazwany", naglowek.contains("domyślny rozpoznawacz: "))
        assertTrue("i to nazwa z systemu", naglowek.contains("com.anthropic.claude"))
        assertTrue("okno mowy tez ma swoja linie", naglowek.contains("okno mowy: "))
        // Naglowek jest calym dowodem takiego przebiegu — musi byc na ekranie do skopiowania.
        assertTrue(
            rule.onAllNodesWithText("domyślny rozpoznawacz:", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * Raport ma przezyc aplikacje. Przebieg trwa okolo minuty i najczesciej konczy go nie
     * przycisk, tylko system ubijajacy aplikacje w tle — a wtedy do 0.9.16 nie zostawalo NIC.
     */
    @Test
    fun raportPoprzedniegoPrzebiegu_dajeSieSkopiowacPoWejsciuNaEkran() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val komponent = ComponentName("com.przyklad.mowa", "com.przyklad.mowa.Rozpoznawanie")
        shadowOf(context.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(context.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)

        // Przebieg z poprzedniego uruchomienia aplikacji: wystartowal i zostal przerwany.
        val poprzedniaSonda = VoiceProbe(context)
        poprzedniaSonda.start()
        poprzedniaSonda.zwolnij(PowodPrzerwania.TLO)
        val zapisany = VoiceProbe(context).ostatniRaport()
        assertNotNull("raport ma byc na dysku", zapisany)
        assertEquals("razem z przerwana proba", 1, zapisany!!.wyniki.size)
        assertEquals(PowodPrzerwania.TLO, zapisany.przerwanie?.powod)

        // Nowe wejscie na ekran: zadnego wlasnego wyniku, ale poprzedni da sie skopiowac.
        val probe = VoiceProbe(context)
        rule.setContent { VoiceTestScreen(str = str, onBack = {}, probe = probe) }
        rule.onNodeWithTag(TAG_TEST_KOPIUJ_OSTATNI).performClick()
        rule.onNodeWithText(str.voiceTestCopied).assertExists()

        val schowek = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val tresc = schowek.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("skopiowany raport niesie naglowek", tresc.contains("on-device:"))
        assertTrue("licznik z przerwana proba", tresc.contains("próby: 1/"))
        assertTrue("i powod przerwania", tresc.contains("PRZERWANY (tło"))
    }

    /**
     * Drugie dotkniecie glownego przycisku nie moze zabic wlasnie zaczetego przebiegu: silnik
     * z tego zgloszenia milczy, wiec uczen, ktory nie widzi reakcji, dotyka go ponownie.
     * PRZERWIJ jest od 0.9.16 osobnym, mniejszym przyciskiem.
     */
    @Test
    fun drugieDotknieciePrzyciskuSTART_nieZabijaPrzebiegu() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val komponent = ComponentName("com.przyklad.mowa", "com.przyklad.mowa.Rozpoznawanie")
        shadowOf(context.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(context.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val probe = VoiceProbe(context)
        rule.setContent { VoiceTestScreen(str = str, onBack = {}, probe = probe) }

        rule.runOnUiThread { probe.start() }
        rule.waitForIdle()
        assertTrue("przebieg ma trwac", probe.trwa)

        // Glowny przycisk w trakcie przebiegu nie przyjmuje dotkniecia — nie ma jak nim
        // przypadkiem zabic przebiegu.
        rule.onNodeWithTag(TAG_TEST_START).assertHasNoClickAction()
        assertTrue("przebieg dalej trwa", probe.trwa)
        assertFalse(probe.przerwane)

        // Przerwac sie da, ale osobnym przyciskiem — i z wlasnym powodem w raporcie.
        rule.onNodeWithTag(TAG_TEST_STOP).performClick()
        rule.waitForIdle()
        assertFalse("PRZERWIJ konczy przebieg", probe.trwa)
        assertTrue(probe.przerwane)
        assertEquals(PowodPrzerwania.PRZYCISK, probe.przerwanie?.powod)
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
