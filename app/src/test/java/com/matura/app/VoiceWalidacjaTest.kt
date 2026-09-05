package com.matura.app

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.matura.app.ui.PUSTE_BEZ_MOWY
import com.matura.app.ui.VoiceInput
import com.matura.app.ui.jezykDoKomunikatu
import com.matura.app.ui.uzyteczneHipotezy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Pusta transkrypcja uchodzila za rozpoznanie. SpeechRecognizer ma cztery sposoby na
 * powiedzenie "nic nie uslyszalem" — brak Bundle, brak klucza wynikow, pusta lista i lista
 * samych pustych ciagow — a kod rozpoznawal tylko dwa pierwsze. Silnik bez modelu jezyka
 * oddaje czesto wlasnie ten czwarty ksztalt: przechodzil on obie bramki w deliver(),
 * ustawial "silnik oddal wynik" i na zawsze wylaczal schodzenie drabinka. Objaw jest ten sam
 * co w zgloszeniu z telefonu: pasek swieci "SLUCHAM…", zadnego bledu nie ma, mowa bez reakcji.
 *
 * Tu pilnujemy CZYSTEJ czesci tej poprawki; przebieg z callbackami sprawdza klasa nizej.
 */
class VoiceWalidacjaTest {

    /** Cztery postacie braku transkrypcji maja dawac DOKLADNIE ten sam wynik. */
    @Test
    fun brakTranskrypcji_wKazdejPostaci_dajeTenSamWynik() {
        val brak = emptyList<String>()
        assertEquals("brak Bundle albo brak klucza wynikow", brak, uzyteczneHipotezy(null))
        assertEquals("pusta lista", brak, uzyteczneHipotezy(emptyList()))
        assertEquals("jeden pusty ciag", brak, uzyteczneHipotezy(listOf("")))
        assertEquals("same biale znaki", brak, uzyteczneHipotezy(listOf("", "   ", "\n\t")))
    }

    /**
     * Sam isNotBlank przepuscilby hipoteze "." albo ",", ktora czesc silnikow zwraca zamiast
     * pustego ciagu. Dla dopasowania odpowiedzi znaczy ona tyle samo co nic.
     */
    @Test
    fun samaInterpunkcja_toNadalBrakTresci() {
        assertEquals(emptyList<String>(), uzyteczneHipotezy(listOf(".", " , ", "…", "-")))
    }

    @Test
    fun trescPrzechodzi_iJestPrzycieta() {
        assertEquals(listOf("kolano"), uzyteczneHipotezy(listOf("  kolano ")))
        assertEquals(
            "kolejnosc od silnika zostaje — pierwsza hipoteza jest najlepsza",
            listOf("kolano", "plecy"),
            uzyteczneHipotezy(listOf("", "kolano", "   ", "plecy")),
        )
        // Odpowiedzi bywaja liczbami ("2020") i skrotami z kropka — te maja przejsc.
        assertEquals(listOf("2020", "np."), uzyteczneHipotezy(listOf("2020", "np.")))
    }

    /**
     * Lista przychodzi z uslugi spoza aplikacji, wiec Kotlin nie sprawdza jej elementow.
     * Jeden null wywracalby caly nasluch wyjatkiem — czyli gasil glos na cala rozgrywke.
     */
    @Test
    fun nullWliscie_nieWywracaFiltra() {
        assertEquals(listOf("kolano"), uzyteczneHipotezy(listOf(null, "kolano", null)))
        assertEquals(emptyList<String>(), uzyteczneHipotezy(listOf(null, null)))
    }

    /**
     * Komunikat o braku pakietu mowy ma nazywac jezyk, o ktory silnik byl PYTANY. Do 0.9.11
     * mowil "PL" na sztywno, choc gra rownie czesto slucha po angielsku — uczen instalowal
     * wtedy nie ten pakiet, co trzeba.
     */
    @Test
    fun nazwaJezyka_bierzeSieZTagu_aNieZeStalej() {
        assertEquals("angielskiego", jezykDoKomunikatu("en-US", "polskiego", "angielskiego"))
        assertEquals("polskiego", jezykDoKomunikatu("pl-PL", "polskiego", "angielskiego"))
        // Szczebel 2b drabinki zamienia "en-US" na "en" — nazwa ma sie nie zgubic.
        assertEquals("angielskiego", jezykDoKomunikatu("en", "polskiego", "angielskiego"))
        assertEquals("polskiego", jezykDoKomunikatu("pl", "polskiego", "angielskiego"))
    }

    /** Nieznany tag: pusta nazwa, bo surowe "de-DE" na pasku gry nic uczniowi nie mowi. */
    @Test
    fun nieznanyTag_nieTrafiaDoKomunikatu() {
        assertEquals("", jezykDoKomunikatu("de-DE", "polskiego", "angielskiego"))
        assertEquals("", jezykDoKomunikatu("", "polskiego", "angielskiego"))
    }
}

/**
 * Ta sama poprawka widziana od strony PRZEBIEGU: kolejnosc callbackow silnika. Cien
 * [SpeechRecognizer] z Robolectrica nie ma mikrofonu ani modelu jezyka i nie zastapi proby
 * na urzadzeniu — ale steruje kolejnoscia zdarzen, a to wlasnie ona byla zrodlem usterki.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicePustaTranskrypcjaTest {

    private lateinit var voice: VoiceInput
    private var wyczerpane = 0
    private val uslyszane = mutableListOf<List<String>>()

    /** Udawana usluga rozpoznawania — nazwa bez "google", zeby sortowanie nic nie mieszalo. */
    private val PAKIET_USLUGI = "com.przyklad.mowa"

    private val petla get() = shadowOf(Looper.getMainLooper())

    private fun ostatni(): SpeechRecognizer =
        requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) {
            "start() nie utworzyl zadnego rozpoznawacza"
        }

    private fun cien(r: SpeechRecognizer): ShadowSpeechRecognizer = Shadow.extract(r)

    private fun uplyw(ms: Long) = petla.idleFor(Duration.ofMillis(ms))

    @Before
    fun ustaw() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val komponent = ComponentName(PAKIET_USLUGI, PAKIET_USLUGI + ".Rozpoznawanie")
        shadowOf(ctx.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(ctx.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        // Zaczynamy od szczebla sieciowego — tak jest na wiekszosci telefonow.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        voice = VoiceInput(ctx)
        voice.onExhausted = { wyczerpane += 1 }
    }

    /** Kazdy test oddaje mikrofon i NISZCZY rozpoznawacz — patrz komentarz w VoiceDrabinkaTest. */
    @After
    fun sprzataj() {
        voice.release()
        uplyw(200)
    }

    private fun start(jezyk: String = "pl-PL") {
        voice.start(jezyk, listOf("kolano", "plecy", "głowa")) { slowa, _ -> uslyszane.add(slowa) }
        uplyw(50)
    }

    private fun wynikBundle(vararg slowa: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(slowa.toList()))
    }

    private fun blad(r: SpeechRecognizer, kod: Int) {
        cien(r).triggerOnError(kod)
        uplyw(300)
    }

    /** Drabinka do samego konca dla podanego kodu; wiecej prob niz szczebli nic nie psuje. */
    private fun doKoncaDrabinki(kod: Int) {
        repeat(8) { if (wyczerpane == 0) blad(ostatni(), kod) }
    }

    // =====================================================================
    // 1. Lista samych pustych ciagow nie jest rozpoznaniem.
    // =====================================================================
    /**
     * Silnik oddajacy liste jednego pustego ciagu (albo samych spacji) przechodzil obie bramki
     * w deliver() i ustawial oddalWynik = true. Od tej chwili polityka pustych cykli uznawala go
     * za sprawny
     * i NIC juz nie moglo go zdegradowac. Sprawdzamy to przez zachowanie: po takim "wyniku"
     * seria pustych cykli ma nadal zejsc drabinka.
     */
    @Test
    fun pusteHipotezy_nieUchodzaZaRozpoznanie() {
        start()
        val pierwszy = ostatni()
        cien(pierwszy).triggerOnResults(wynikBundle("", "   "))
        uplyw(300)

        assertTrue("puste ciagi nie sa wypowiedzia gracza", uslyszane.isEmpty())
        assertTrue(
            "diagnostyka ma pokazywac, ze tekstu nie bylo",
            voice.diagnostyka().contains("rozpoznał tekst: NIE"),
        )

        repeat(PUSTE_BEZ_MOWY) { blad(pierwszy, SpeechRecognizer.ERROR_NO_MATCH) }
        assertNotSame("pusta transkrypcja nie moze chronic silnika", pierwszy, ostatni())
    }

    // =====================================================================
    // 2. Pusty wynik czesciowy nie kasuje historii niepowodzen.
    // =====================================================================
    /**
     * Silnik bez modelu jezyka potrafi na KAZDY cykl oddac jeden pusty wynik czesciowy,
     * a zaraz po nim NO_MATCH. onPartialResults zerowalo pusteCykle ZANIM cokolwiek sprawdzilo,
     * wiec licznik wracal do zera przy kazdym obrocie i prog eskalacji nie byl osiagalny NIGDY.
     */
    @Test
    fun pustyWynikCzesciowy_nieKasujeLicznikaPustychCykli() {
        start()
        val pierwszy = ostatni()
        repeat(PUSTE_BEZ_MOWY) {
            cien(pierwszy).triggerOnPartialResults(wynikBundle("   "))
            uplyw(10)
            blad(pierwszy, SpeechRecognizer.ERROR_NO_MATCH)
        }
        assertNotSame(
            "pusty partial nie moze kasowac historii pustych cykli",
            pierwszy,
            ostatni(),
        )
    }

    /**
     * KONTROLA UJEMNA do testu wyzej: partial Z TRESCIA nadal jest dowodem pracy. Poprawka ma
     * odciac puste hipotezy, a nie wylaczyc wyniki czesciowe — silnik, ktory sypie tekstem
     * i tylko nie trafia w slowo z planszy, nie moze przez to tracic szczebla.
     */
    @Test
    fun wynikCzesciowyZTrescia_jestDowodemPracy() {
        start()
        val pierwszy = ostatni()
        repeat(PUSTE_BEZ_MOWY * 2) {
            cien(pierwszy).triggerOnPartialResults(wynikBundle("kola"))
            uplyw(10)
            blad(pierwszy, SpeechRecognizer.ERROR_NO_MATCH)
        }
        assertSame("silnik, ktory oddaje tekst, zostaje", pierwszy, ostatni())
    }

    // =====================================================================
    // 3. Pusty wynik koncowy to to samo co NO_MATCH.
    // =====================================================================
    /**
     * Czesc silnikow zglasza brak dopasowania pustym onResults, bez zadnego kodu bledu.
     * Do 0.9.11 taki cykl zerowal licznik, zapisywal sie w dzienniku jako "wynik" i konczyl
     * zwyklym restartem: nasluch krecil sie bez konca z zapalonym paskiem i bez sladu w logu.
     */
    @Test
    fun pustyWynikKoncowy_zachowujeSieJakNoMatch() {
        start()
        val pierwszy = ostatni()
        repeat(PUSTE_BEZ_MOWY) {
            cien(ostatni()).triggerOnResults(wynikBundle())
            uplyw(300)
        }
        assertNotSame("silnik oddajacy same puste wyniki musi ustapic miejsca", pierwszy, ostatni())
        assertEquals("to zejscie drabinka, a nie koniec glosu", 0, wyczerpane)
        assertTrue(
            "dziennik ma nazwac rzecz po imieniu — 'wynik' bylby nieprawda",
            voice.diagnostyka().contains("pusty wynik"),
        )
    }

    // =====================================================================
    // 4. Do ekranu nie ida puste ciagi.
    // =====================================================================
    /** Bez filtra PlayScreen wypisywal pod paskiem puste cudzyslowy, a VoiceMatch dopasowywal nic. */
    @Test
    fun onHeard_dostajeSameNiepusteHipotezy() {
        start()
        cien(ostatni()).triggerOnResults(wynikBundle("", "  kolano  ", "   ", "plecy"))
        uplyw(300)
        assertEquals(listOf(listOf("kolano", "plecy")), uslyszane)
    }

    // =====================================================================
    // 5. Komunikat o braku pakietu mowy nazywa jezyk, o ktory silnik byl pytany.
    // =====================================================================
    /**
     * Znaczniki zamiast prawdziwych nazw: test ma pilnowac WYBORU jezyka, a nie brzmienia zdania,
     * ktore moze sie jeszcze zmienic w tlumaczeniach.
     */
    @Test
    fun brakPakietuMowy_nazywaJezykNasluchu_aNieStalyZKodu() {
        voice.tekstJezykPolski = "POLSKIEGO"
        voice.tekstJezykAngielski = "ANGIELSKIEGO"
        start("en-US")
        doKoncaDrabinki(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        assertEquals("po koncu drabinki decyzja idzie do ekranu", 1, wyczerpane)
        assertTrue(
            "komunikat ma nazwac jezyk, o ktory silnik byl pytany: " + voice.status,
            voice.status.contains("ANGIELSKIEGO"),
        )
        assertFalse("i nie ten drugi", voice.status.contains("POLSKIEGO"))
    }

    /** To samo na domyslnych tekstach — takze zanim ekran cokolwiek podstawi. */
    @Test
    fun brakPakietuMowy_przyAngielskim_nieMowiOPolskimPakiecie() {
        start("en-US")
        doKoncaDrabinki(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        assertEquals(1, wyczerpane)
        assertFalse("nie moze mowic o polskim pakiecie: " + voice.status, voice.status.contains("polsk"))
        assertFalse("ani o pakiecie 'PL'", voice.status.contains("PL"))
        assertFalse("ani wypisywac surowego tagu BCP 47", voice.status.contains("en-"))
    }

    /**
     * Tresc komunikatow konca drabinki idzie z pol podstawianych przez ekran — tak samo jak
     * tekstCiszy. VoiceInput nie ma dostepu do Str, a interfejs ma wersje angielska.
     */
    @Test
    fun komunikatyKoncaDrabinki_bieraTrescZPolEkranu() {
        voice.tekstBrakPakietuJezyka = "no %s speech pack"
        voice.tekstJezykPolski = "Polish"
        start("pl-PL")
        doKoncaDrabinki(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertEquals(1, wyczerpane)
        assertEquals("no Polish speech pack", voice.status)
    }
}
