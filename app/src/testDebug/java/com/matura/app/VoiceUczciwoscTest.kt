package com.matura.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matura.app.data.BuiltInSets
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.model.Options
import com.matura.app.model.Speed
import com.matura.app.model.UiLanguage
import com.matura.app.ui.PlayScreen
import com.matura.app.ui.Str
import com.matura.app.ui.VoiceStan
import com.matura.app.ui.opisStanuMikrofonu
import com.matura.app.ui.podpisAwariiMikrofonu
import com.matura.app.ui.podpisBrakuMikrofonu
import com.matura.app.ui.wstrzymacRunde
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Mikrofon, ktory zawiodl, byl do tej pory nieodroznialny od ucznia, ktory nie zna slowa.
 * Gdy drabinka silnikow konczyla sie porazka albo zgoda na mikrofon nie przychodzila, plansza
 * szla dalej: potworek dochodzil do dziala, zabieral zycie i zerowal serie — kara za usterke
 * uslugi rozpoznawania mowy, a nie za bledna odpowiedz. Do tego nic nie zatrzymywalo nasluchu
 * przy zejsciu aplikacji w tlo: HOME ani zgaszenie ekranu nie wyprowadzaja ekranu gry
 * z kompozycji, wiec onDispose nie leci, pauza sie nie wlacza, a mikrofon zostaje otwarty.
 *
 * CZEGO TU NIE MA, I DLACZEGO
 *
 * Ze samo oddanie mikrofonu (VoiceInput.stop) przy ON_STOP naprawde nastapilo, nie da sie
 * sprawdzic bez urzadzenia: w Robolectriku nie ma zadnej uslugi RecognitionService, wiec
 * nasluch nigdy sie nie zaczyna i `listening` zostaje false. Sprawdzalny bez telefonu jest
 * DRUGI skutek tego samego zdarzenia — zatrzymanie rundy — i to mierzymy nizej.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VoiceUczciwoscTest {

    @get:Rule
    val rule = createComposeRule()

    private val zestaw = BuiltInSets.all().first { it.entries.size >= 5 }

    /** Wlasciciel cyklu zycia pod kontrola testu — zeby dalo sie wywolac ON_STOP bez telefonu. */
    private class SterowanyWlasciciel : LifecycleOwner {
        // createUnsafe, bo rejestr powstaje poza petla glownego watku aplikacji.
        val rejestr: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = rejestr
    }

    /**
     * Ekran gry z klawiatura SYSTEMOWA. Pole tekstowe jest tu jedynym elementem gry widocznym
     * w drzewie semantyki (plansza i klawiatura pikselowa to rysunki na Canvasie), wiec to przez
     * nie sprawdzamy, czy runda idzie, czy stoi.
     */
    private fun pokazGre(wlasciciel: SterowanyWlasciciel) {
        // Ekran gry ma nieprzerwana petle klatek, wiec Compose NIGDY nie zglosi bezczynnosci
        // i domyslne czekanie testu wisialoby 60 s. Zegar prowadzimy recznie (tak samo jak
        // SystemKeyboardTest).
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides wlasciciel) {
                PlayScreen(
                    set = zestaw,
                    options = Options(systemKeyboard = true, soundEnabled = false),
                    str = Str(UiLanguage.PL),
                    playerName = "Test",
                    onExit = {},
                    onFinished = {},
                onAttempt = {},
                )
            }
        }
    }

    private fun semantykaPol(): String =
        rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            .joinToString(" ") { it.config.toString() }

    // ---- A. cykl zycia -----------------------------------------------------

    /**
     * ON_STOP (HOME, przelaczenie aplikacji, zgaszony ekran) ma zatrzymac runde.
     *
     * Mierzymy to po wejsciu: dopoki gra idzie, tekst z klawiatury systemowej dociera do silnika;
     * po ON_STOP juz nie, bo plansza stoi. Kontrola pozytywna jest tu konieczna — bez niej test
     * przechodzilby takze wtedy, gdyby pole w ogole nie przyjmowalo tekstu.
     */
    @Test
    fun zejscieWTlo_zatrzymujeRunde() {
        val wlasciciel = SterowanyWlasciciel()
        wlasciciel.rejestr.currentState = Lifecycle.State.RESUMED
        pokazGre(wlasciciel)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("kot")
        rule.mainClock.advanceTimeByFrame()
        assertTrue(
            "Kontrola pozytywna: w biegnacej rundzie wpisany tekst musi dotrzec do gry. " +
                "Semantyka: " + semantykaPol().take(300),
            semantykaPol().contains("kot", ignoreCase = true),
        )

        // Uczen nacisnal HOME albo zgasil ekran.
        wlasciciel.rejestr.currentState = Lifecycle.State.CREATED
        rule.mainClock.advanceTimeByFrame()

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("x")
        rule.mainClock.advanceTimeByFrame()
        assertFalse(
            "Po ON_STOP runda ma stac: wpis nie moze isc do gry, dopoki uczen swiadomie " +
                "nie wznowi. Semantyka: " + semantykaPol().take(300),
            semantykaPol().contains("kotx", ignoreCase = true),
        )
    }

    // ---- B. uczciwosc oceniania -------------------------------------------

    /** Awaria drogi glosowej i odmowa zgody wstrzymuja biegnaca runde. */
    @Test
    fun awariaMikrofonu_wstrzymujeBiegnacaRunde() {
        assertTrue(
            "Brak transkrypcji ani odmowa uprawnienia nie sa odpowiedzia ucznia — gra ma stanac " +
                "na czas przesiadki na klawiature",
            wstrzymacRunde(paused = false, gameOver = false),
        )
    }

    /**
     * Ile kosztuje awaria mikrofonu przy planszy, ktora idzie dalej — i ile przy wstrzymanej.
     *
     * Dwie identyczne rundy (to samo ziarno losowania) dochodza do tej samej chwili awarii.
     * Potem jedna stoi (petla klatek nie wola tick(), bo paused == true), a druga dostaje
     * czas, ktorego uczen potrzebuje na przeczytanie komunikatu i przesiadke na klawiature.
     * Roznica w zyciach jest dokladnie ta kara za usterke silnika mowy, ktorej brief zakazuje.
     */
    @Test
    fun wstrzymanaPlansza_nieOdbieraAniZyciaAniPunktow() {
        val konfiguracja = GameConfig(lives = 3, speed = Speed.FAST)
        val stojaca = GameEngine(zestaw, konfiguracja, Random(7))
        val idaca = GameEngine(zestaw, konfiguracja, Random(7))
        // 10 s: pierwszy potworek wchodzi po 0,9 s i potrzebuje ponad 13 s marszu, wiec do
        // chwili awarii nikt jeszcze nie doszedl do dziala.
        repeat(200) { stojaca.tick(0.05f); idaca.tick(0.05f) }
        val wChwiliAwarii = stojaca.snapshot()
        assertEquals("test ma sens tylko, gdy przed awaria nic sie jeszcze nie stalo", 3, wChwiliAwarii.lives)

        // Uczen czyta komunikat i przechodzi na klawiature — 60 s z zapasem.
        repeat(1200) { idaca.tick(0.05f) }

        assertEquals("wstrzymana runda nie moze zabrac zycia", 3, stojaca.snapshot().lives)
        assertEquals("wstrzymana runda nie moze ruszyc wyniku", wChwiliAwarii.score, stojaca.snapshot().score)
        assertEquals("wstrzymana runda nie moze zerwac serii", wChwiliAwarii.streak, stojaca.snapshot().streak)
        assertTrue(
            "kontrola negatywna: bez pauzy ta sama przerwa kosztuje zycie (zostalo: " +
                idaca.snapshot().lives + ")",
            idaca.snapshot().lives < 3,
        )
    }

    /** Wlasnej pauzy ucznia i ekranu wyniku awaria nie rusza. */
    @Test
    fun awariaNieNadpisujePauzyUczniaAniEkranuWyniku() {
        assertFalse(
            "uczen juz wstrzymal gre sam — nie ma czego zmieniac",
            wstrzymacRunde(paused = true, gameOver = false),
        )
        assertFalse(
            "po koncu rundy pauza tylko zablokowalaby ekran wyniku",
            wstrzymacRunde(paused = false, gameOver = true),
        )
    }

    /** Podpis drogi awaryjnej musi mowic, co robic dalej — w obu jezykach i w jednej linii. */
    @Test
    fun podpisyDrogAwaryjnych_saWObuJezykach() {
        val podpisy: List<(UiLanguage) -> String> =
            listOf(::podpisAwariiMikrofonu, ::podpisBrakuMikrofonu)
        for (podpis in podpisy) {
            val pl = podpis(UiLanguage.PL)
            val en = podpis(UiLanguage.EN)
            assertTrue("pusty podpis PL", pl.isNotBlank())
            assertTrue("pusty podpis EN", en.isNotBlank())
            assertNotEquals("wersja angielska nie zostala napisana: " + pl, pl, en)
            // Pasek glosu ma STALA wysokosc jednej linii (pod nim siedzi klawiatura gry),
            // a przy 12 sp na ekranie 360 dp miesci sie okolo 45 znakow.
            assertTrue("podpis nie miesci sie w jednej linii paska: " + pl, pl.length <= 45)
            assertTrue("podpis nie miesci sie w jednej linii paska: " + en, en.length <= 45)
        }
    }

    // ---- C. stany mikrofonu jezykiem gracza -------------------------------

    /** Kazdy stan nasluchu ma opis — po polsku i po angielsku. */
    @Test
    fun kazdyStanMikrofonu_maOpisWObuJezykach() {
        for (stan in VoiceStan.values()) {
            val pl = opisStanuMikrofonu(stan, UiLanguage.PL)
            val en = opisStanuMikrofonu(stan, UiLanguage.EN)
            assertTrue("brak opisu PL dla " + stan, pl.isNotBlank())
            assertTrue("brak opisu EN dla " + stan, en.isNotBlank())
            assertNotEquals("opis " + stan + " nie zostal przetlumaczony", pl, en)
        }
    }

    /** Opisy musza sie roznic — inaczej pasek nie odroznia "slucham" od "ponawiam". */
    @Test
    fun opisyStanow_saRozroznialne() {
        for (lang in UiLanguage.values()) {
            val opisy = VoiceStan.values().map { opisStanuMikrofonu(it, lang) }
            assertEquals(
                "kazdy stan ma miec wlasny opis (" + lang + "): " + opisy,
                opisy.size, opisy.toSet().size,
            )
        }
    }

    /**
     * Brief: nazwy silnikow i kody bledow siedza w diagnostyce, nie na pasku. Cyfra w opisie
     * to prawie zawsze przemycony kod bledu ("brak zgody na mikrofon (9)"), a "com." — nazwa
     * pakietu uslugi rozpoznawania.
     */
    @Test
    fun opisStanu_niePrzemycaKodowAniNazwSilnikow() {
        for (stan in VoiceStan.values()) {
            for (lang in UiLanguage.values()) {
                val opis = opisStanuMikrofonu(stan, lang)
                assertFalse("kod bledu w podpisie paska: " + opis, opis.any { it.isDigit() })
                assertFalse(
                    "nazwa silnika w podpisie paska: " + opis,
                    opis.contains("com.", ignoreCase = true) ||
                        opis.contains("ERROR", ignoreCase = true) ||
                        opis.contains("google", ignoreCase = true),
                )
                assertTrue("opis nie miesci sie w jednej linii paska: " + opis, opis.length <= 45)
            }
        }
    }
}
