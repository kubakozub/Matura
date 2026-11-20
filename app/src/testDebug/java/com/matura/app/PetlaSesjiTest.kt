package com.matura.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matura.app.data.Repository
import com.matura.app.game.Matching
import com.matura.app.learn.Attempt
import com.matura.app.learn.Clock
import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.TaskKind
import com.matura.app.model.UiLanguage
import com.matura.app.ui.MaturaApp
import com.matura.app.ui.SesjaStr
import com.matura.app.ui.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Dzienna sesja byla zlozona z czesci, z ktorych kazda miala testy, a razem nie dzialaly:
 *
 *  - runda z sesji konczyla sie w MENU i uczen musial sam wrocic na ekran sesji, zeby nacisnac
 *    DALEJ;
 *  - po powrocie plan byl ukladany od nowa i krok pierwszy mial juz INNE slowa (licznik szedl
 *    5 / 10, 10 / 15 i nigdy sie nie domykal);
 *  - przy ustawieniu "losowo" runda losowala kierunek dla kazdego slowa, a sesja liczyla tylko
 *    jeden, wiec polowy odpowiedzi nie widziala;
 *  - dzien harmonogramu byl liczony raz na zycie procesu, wiec rano aplikacja wznawiala
 *    wczorajsza sesje.
 *
 * Tego nie widac w testach czystej logiki, bo blad siedzial w tym, CO aplikacja jej podaje.
 * Dlatego idziemy tu przez prawdziwa aplikacje: ekran startowy, menu, sesja, runda grana
 * klawiatura gry, ekran wyniku, powrot. Zapis jest prawdziwy (katalog tymczasowy), zegar
 * podstawiony, zeby dalo sie przejsc przez polnoc.
 */
@RunWith(RobolectricTestRunner::class)
// Ekran wyzszy niz jakikolwiek telefon, celowo: lista ekranu sesji miesci sie wtedy cala i do
// przyciskow na jej koncu nie trzeba dojezdzac. Przewijanie z poziomu testu (performScrollToNode)
// przy zatrzymanym zegarze nie konczy sie nigdy, a zegar musi stac, bo gra ma petle klatek.
// Malym telefonem zajmuje sie EkranWynikuTest.
@Config(sdk = [34], qualifiers = "w411dp-h1200dp-xhdpi")
class PetlaSesjiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val katalog = TemporaryFolder()

    private val str = Str(UiLanguage.PL)
    private val sesja = SesjaStr(UiLanguage.PL)

    private companion object {
        const val DZIS = 19_875
    }

    /** Poludnie danego dnia UTC: godzina nie ma tu znaczenia, liczy sie sam dzien. */
    private fun poludnie(dzien: Int) = dzien.toLong() * Srs.MS_PER_DAY + 12 * 3_600_000L

    private var teraz = poludnie(DZIS)
    private val zegar = Clock { teraz }

    /** Wlasciciel cyklu zycia pod kontrola testu, zeby dalo sie wrocic "z tla" bez telefonu. */
    private class SterowanyWlasciciel : LifecycleOwner {
        val rejestr: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = rejestr
    }

    private val wlasciciel = SterowanyWlasciciel()
    private lateinit var repo: Repository

    private fun uruchom(przygotuj: (Repository) -> Unit = {}) {
        repo = Repository(katalog.root)
        przygotuj(repo)
        wlasciciel.rejestr.currentState = Lifecycle.State.RESUMED
        // Ekran gry ma nieprzerwana petle klatek, wiec zegar testu prowadzimy recznie od poczatku.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides wlasciciel) {
                MaturaApp(repo = repo, onExitApp = {}, zegar = zegar)
            }
        }
        klatki()
        dotknij(str.playAnonymously)
    }

    private fun klatki(ile: Int = 4) = repeat(ile) { rule.mainClock.advanceTimeByFrame() }

    private fun jest(opis: String): Boolean =
        rule.onAllNodesWithContentDescription(opis).fetchSemanticsNodes().isNotEmpty()

    private fun dotknij(opis: String) {
        rule.onNodeWithContentDescription(opis).performClick()
        klatki()
    }

    /**
     * Po zmianie stanu, ktora przyszla SPOZA Compose (systemowe "wstecz", obserwator cyklu zycia).
     * Taki zapis dociera do rekompozycji przez petle glowna watku: na telefonie kreci sie ona
     * sama, tutaj trzeba ja oproznic, a dopiero potem dac klatki. Dotkniecia tego nie potrzebuja,
     * bo ich obsluga biegnie w korutynie Compose.
     */
    private fun poZmianieSpozaCompose() {
        rule.waitForIdle()
        klatki()
    }

    private fun wstecz() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        poZmianieSpozaCompose()
    }

    private fun grajAz(co: String, warunek: () -> Boolean) {
        repeat(240) {
            if (warunek()) {
                klatki()
                return
            }
            rule.mainClock.advanceTimeBy(250)
        }
        fail("po 60 s gry dalej nie ma: $co")
    }

    private fun wpisz(odpowiedz: String) {
        for (znak in odpowiedz) {
            rule.onNodeWithContentDescription(if (znak == ' ') str.keySpace else znak.uppercase()).performClick()
        }
        rule.onNodeWithContentDescription(str.keyEnter).performClick()
    }

    /** Wpisy pierwszej trasy na swiezej instalacji: piec pierwszych slow katalogu. */
    private fun pierwszaPiatka(): List<Entry> =
        repo.load().sets.flatMap { it.entries }.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(5)

    /** Najkrotsza odpowiedz, jaka gra przyjmie dla tej strony wpisu: mniej klawiszy w tescie. */
    private fun odpowiedz(strona: String): String = Matching.acceptedAnswers(strona).minBy { it.length }

    /**
     * Przechodzi cala runde kroku sesji: czeka, az wszystkie slowa stana na planszy, i odpowiada
     * na kazde. Potworki wychodza co 3,8 s (0,9 s, 4,7 s, ... 16,1 s), a najszybszy potrzebuje
     * ponad 17 s marszu, wiec w 16,5 sekundzie stoi piec i zaden jeszcze nie doszedl do dziala.
     */
    private fun przejdzRunde(odpowiedzi: List<String>) {
        assertTrue("runda nie ruszyla: nie ma klawiatury gry", jest("Q"))
        rule.mainClock.advanceTimeBy(16_500)
        for (o in odpowiedzi) wpisz(o)
        grajAz("ekran RUNDA UKONCZONA") { jest(str.roundComplete) }
        rule.mainClock.advanceTimeBy(500)
    }

    private fun naEkranieSesji(): Boolean = jest(sesja.sessionTitle) && !jest(str.play)

    // ---- cala trasa -------------------------------------------------------

    @Test
    fun calaTrasaDnia_dwieRundyTychSamychSlow_iLicznikKtorySieDomyka() {
        // "Losowo" to ustawienie, przy ktorym runda sesji grala polowe slow w odwrotna strone.
        uruchom { it.setOptions(it.load().options.copy(direction = Direction.RANDOM)) }
        val piatka = pierwszaPiatka()
        val poPolsku = piatka.map { odpowiedz(it.definition) }

        dotknij(str.dailyMenu)
        assertTrue(naEkranieSesji())
        assertTrue(jest("0 / 5"))
        assertFalse("plakietka WZNOWIONA nad sesja, ktorej nikt jeszcze nie zaczal", jest(sesja.sessionResumed))

        // KROK 1: runda spokojna, odpowiedzi po polsku, bo plan jest w kierunku haslo -> tlumaczenie.
        dotknij(sesja.sessionStart)
        przejdzRunde(poPolsku)

        assertTrue("z rundy sesji idzie sie DALEJ", jest(str.next))
        assertFalse("runda sesji nie konczy sie w menu", jest(str.toMenu))
        dotknij(str.next)

        assertTrue("DALEJ po rundzie sesji ma prowadzic na ekran sesji", naEkranieSesji())
        assertTrue("ukonczona runda nie przesunela trasy", jest(krok(2)))
        // Te same piec slow, a nie piec zrobionych z dziesieciu.
        assertTrue("licznik sesji sie nie domknal", jest("5 / 5"))
        assertTrue(jest(sesja.sessionResumed))

        val poPierwszej = repo.load()
        val stan = requireNotNull(poPierwszej.sesja) { "sesja zniknela z zapisu" }
        assertEquals(1, stan.krok)
        assertEquals(piatka.map { it.id }, stan.zrobioneNowe)
        assertEquals(piatka.map { it.id }, stan.trasaNowe)
        // Wszystkie odpowiedzi poszly pod kierunek planu, zadna pod odwrotny.
        for (wpis in piatka) {
            assertEquals(Srs.FIRST_BOX, Srs.progressOf(poPierwszej.progress, wpis.id, Direction.TERM_TO_DEF).box)
        }
        assertEquals(5, poPierwszej.progress.size)
        // Rundy spokojnej nie da sie przegrac, wiec nie ma jej w rankingu ani w rekordach.
        assertTrue(poPierwszej.scores.isEmpty())
        assertEquals(0, poPierwszej.profile.bestScore)
        assertEquals(0, poPierwszej.profile.bestStreak)
        assertEquals(1, poPierwszej.profile.gamesPlayed)
        assertEquals(5, poPierwszej.profile.totalKills)

        // KROK 2: "to samo slownictwo jeszcze raz". Gdyby runda dostala inne slowa, te same piec
        // odpowiedzi by jej nie skonczylo.
        dotknij(sesja.sessionStart)
        przejdzRunde(poPolsku)
        dotknij(str.next)

        assertTrue(naEkranieSesji())
        assertTrue(jest(krok(3)))
        assertTrue(jest("5 / 5"))
        val poDrugiej = requireNotNull(repo.load().sesja)
        assertEquals(2, poDrugiej.krok)
        assertFalse("sesja zamknela sie sama, bez podsumowania", poDrugiej.zakonczona)

        // Podsumowanie zamyka uczen.
        dotknij(sesja.sessionFinish)
        assertTrue("po KONIEC NA DZIS wraca sie do menu", jest(str.play))
        assertNull(repo.load().sesja)
    }

    private fun krok(nr: Int) = sesja.sessionStepLabel.replace("%1", nr.toString()).replace("%2", "3")

    // ---- wyjscie w polowie -------------------------------------------------

    @Test
    fun wyjscieZRundySesji_wracaNaSesje_iNiczegoNiePrzesuwa() {
        uruchom()
        val piatka = pierwszaPiatka()

        dotknij(str.dailyMenu)
        dotknij(sesja.sessionStart)
        assertTrue(jest("Q"))
        // Jedno slowo zaliczone, potem systemowe "wstecz".
        rule.mainClock.advanceTimeBy(16_500)
        wpisz(odpowiedz(piatka[2].definition))
        rule.mainClock.advanceTimeBy(1_000)
        wstecz()

        assertTrue("wyjscie z rundy sesji ma prowadzic na ekran sesji, nie do menu", naEkranieSesji())
        // Runda porzucona nie jest ukonczona: krok zostaje biezacy, a odpowiedz i tak sie liczy.
        assertTrue(jest(krok(1)))
        assertTrue(jest("1 / 5"))
        val stan = requireNotNull(repo.load().sesja)
        assertEquals(0, stan.krok)
        assertEquals(listOf(piatka[2].id), stan.zrobioneNowe)

        // Drugie "wstecz" jest juz zwyklym wyjsciem z ekranu sesji do menu.
        wstecz()
        assertTrue(jest(str.play))
    }

    @Test
    fun sesjaTylkoObejrzana_nieZostajeWZapisie() {
        uruchom()

        dotknij(str.dailyMenu)
        assertTrue(naEkranieSesji())
        dotknij(str.back)

        assertTrue(jest(str.play))
        assertNull("sesja, w ktorej nic sie nie wydarzylo, trafila do zapisu", repo.load().sesja)
    }

    // ---- dzien harmonogramu ------------------------------------------------

    @Test
    fun poNocyWTle_aplikacjaPracujeNaDzisiejszymDniu() {
        uruchom()
        dotknij(str.dailyMenu)
        // Uczen pomija pierwszy krok: sesja jest ruszona i zapisana.
        dotknij(sesja.sessionContinue)
        assertTrue(jest(krok(2)))
        assertEquals(DZIS, requireNotNull(repo.load().sesja).dzien)

        // Telefon lezy cala noc z aplikacja w tle, proces zyje. Rano uczen do niej wraca.
        wlasciciel.rejestr.currentState = Lifecycle.State.CREATED
        teraz = poludnie(DZIS + 1)
        wlasciciel.rejestr.currentState = Lifecycle.State.RESUMED
        poZmianieSpozaCompose()

        // Ekran sesji stoi otwarty i ma pokazac DZISIEJSZA trase, a nie wczorajszy krok drugi.
        assertTrue("po nocy w tle wznowila sie wczorajsza sesja", jest(krok(1)))
        assertFalse(jest(sesja.sessionResumed))
    }

    @Test
    fun wejscieNaSesjeZMenu_tezOdswiezaDzien() {
        uruchom()
        dotknij(str.dailyMenu)
        dotknij(sesja.sessionContinue)
        dotknij(str.back)
        assertTrue(jest(str.play))

        // Polnoc mija, gdy uczen patrzy na menu: zadnego powrotu z tla nie ma.
        teraz = poludnie(DZIS + 1)
        dotknij(str.dailyMenu)

        assertTrue("sesja otwarta z menu pracuje na wczorajszym dniu", jest(krok(1)))
        assertFalse(jest(sesja.sessionResumed))
    }

    // ---- trudne slowa ------------------------------------------------------

    @Test
    fun cwiczenieTrudnegoSlowa_idzieWKierunkuListy_iWracaNaTeSamaListe() {
        lateinit var trudne: Entry
        uruchom { r ->
            // Pomylka sprzed trzech dni w kierunku tlumaczenie -> haslo: slowo jest trudne tylko
            // na tej liscie, a ustawienie ucznia ("losowo") otwiera liste w kierunku przeciwnym.
            trudne = r.load().sets.first().entries.first()
            r.setOptions(r.load().options.copy(direction = Direction.RANDOM))
            r.recordAttempt(
                Attempt(trudne.id, Direction.DEF_TO_TERM, TaskKind.PRZYPOMNIENIE, Outcome.BLEDNA, "dawno"),
                poludnie(DZIS - 3),
            )
        }

        dotknij(str.hardWordsMenu)
        assertFalse("lista w kierunku ustawien nie ma tego slowa", jest(str.hardPractice))
        rule.onNodeWithText(str.dirDefToTerm).performClick()
        klatki()
        assertTrue(jest(str.hardPractice))

        dotknij(str.hardPractice)
        assertTrue("cwiczenie nie ruszylo", jest("Q"))
        // Trudne slowo jest zalegle, wiec wychodzi pierwsze (0,9 s). Odpowiedz po ANGIELSKU:
        // na tej liscie haslem jest polskie tlumaczenie.
        rule.mainClock.advanceTimeBy(1_500)
        wpisz(odpowiedz(trudne.term))
        rule.mainClock.advanceTimeBy(1_000)

        val poOdpowiedzi = repo.load().progress
        assertEquals(
            "odpowiedz nie trafila pod kierunek listy",
            Srs.FIRST_BOX,
            Srs.progressOf(poOdpowiedzi, trudne.id, Direction.DEF_TO_TERM).box,
        )
        assertFalse(
            "runda zagrala slowo w kierunku, ktorego uczen nie cwiczyl",
            poOdpowiedzi.containsKey(Srs.key(trudne.id, Direction.TERM_TO_DEF)),
        )

        wstecz()
        assertFalse("wyjscie z cwiczenia zaprowadzilo do menu", jest(str.play))
        // Ta sama lista co przed runda. Slowo ma historie tylko w kierunku tlumaczenie -> haslo,
        // wiec jego karta z przyciskiem CWICZ istnieje wylacznie na tej liscie.
        assertTrue("po cwiczeniu lista wrocila do kierunku z ustawien", jest(str.hardPractice))
        assertTrue(jest(trudne.definition))
        // Ustawienie ucznia nie zmienilo sie od tego, ze runda szla w innym kierunku.
        assertEquals(Direction.RANDOM, repo.load().options.direction)
    }
}
