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
import com.matura.app.ui.PUSTE_PO_SUKCESIE
import com.matura.app.ui.SilenceDetector
import com.matura.app.ui.VoiceInput
import com.matura.app.ui.VoiceStan
import com.matura.app.ui.odpowiedziPoPolsku
import com.matura.app.ui.wznowicPoZgodzie
import com.matura.app.ui.zaliczycWypowiedz
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
 * SZESC KRYTERIOW ODBIORU sciezki glosowej — po jednym tescie na punkt.
 *
 * Kazdy opisuje sytuacje, ktora naprawde zdarzyla sie w tym kodzie albo w zgloszeniu z telefonu,
 * i pilnuje, zeby nie wrocila. Cien [SpeechRecognizer] z Robolectrica nie ma mikrofonu ani modelu
 * jezyka, wiec NIE zastapi proby na urzadzeniu — ale steruje callbackami co do kolejnosci, a to
 * wlasnie kolejnosc zdarzen byla zrodlem wiekszosci tych usterek.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRegresjaTest {

    private lateinit var voice: VoiceInput
    private var wyczerpane = 0
    private var uslyszane = mutableListOf<List<String>>()

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
        // Jedna usluga rozpoznawania na "urzadzeniu": drabinka ma wtedy trzy szczeble
        // (zdejmij offline, jezyk bez regionu, zwiaz sie po nazwie).
        val komponent = ComponentName(PAKIET_USLUGI, PAKIET_USLUGI + ".Rozpoznawanie")
        shadowOf(ctx.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(ctx.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
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

    private fun start() {
        voice.start("pl-PL", listOf("kolano", "plecy", "głowa")) { slowa, _ -> uslyszane.add(slowa) }
        uplyw(50)
    }

    private fun wynikBundle(vararg slowa: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(slowa.toList()))
    }

    private fun blad(r: SpeechRecognizer, kod: Int) {
        cien(r).triggerOnError(kod)
        uplyw(300)
    }

    /** Pusty cykl, w ktorym mikrofon SLYSZAL mowe — inny prog eskalacji niz przy ciszy. */
    private fun pustyCyklZMowa(r: SpeechRecognizer) {
        cien(r).triggerOnRmsChanged(SilenceDetector.SILENCE_DB + 10f)
        uplyw(10)
        blad(r, SpeechRecognizer.ERROR_NO_MATCH)
    }

    // =====================================================================
    // 1. Gotowosc silnika nie jest sukcesem i nie blokuje odzyskiwania.
    // =====================================================================
    /**
     * ZGLOSZENIE Z TELEFONU: pasek "Mow odpowiedz" swieci, zadnego bledu nie ma, wypowiedziane
     * slowa nie wywoluja reakcji, a te same wpisane z klawiatury dzialaja.
     *
     * Przyczyna: onReadyForSpeech ustawialo `dzialal = true`, a polityka pustych cykli traktowala
     * to jak dowod skutecznosci i wylaczala eskalacje NA ZAWSZE. Silnik, ktory melduje gotowosc
     * i nie rozpoznaje ani slowa (typowo: brak pobranego modelu jezyka), krecil sie w kolko.
     */
    @Test
    fun kryterium1_gotowoscIPowtarzaneNoMatch_nieUdajaSukcesu() {
        start()
        val pierwszy = ostatni()
        cien(pierwszy).triggerOnReadyForSpeech(Bundle())
        uplyw(10)

        repeat(PUSTE_BEZ_MOWY) { blad(pierwszy, SpeechRecognizer.ERROR_NO_MATCH) }

        assertNotSame(
            "sama gotowosc nie moze chronic silnika, ktory nic nie rozpoznaje",
            pierwszy,
            ostatni(),
        )
        assertEquals("drabinka ma zejsc, a nie oddac decyzje ekranowi", 0, wyczerpane)
    }

    // =====================================================================
    // 2. Pusty wynik nie ustawia flagi transkrypcji; po sukcesie da sie odzyskac dzialanie.
    // =====================================================================
    @Test
    fun kryterium2_pustyWynikNieJestTranskrypcja_aPoSukcesieDaSieOdzyskac() {
        start()
        val pierwszy = ostatni()
        // onResults BEZ listy rozpoznan i onResults z PUSTA lista. Oba to brak transkrypcji:
        // deliver() ich nie przepuszcza, a sam cykl idzie odtad ta sama droga co NO_MATCH
        // (patrz VoicePustaTranskrypcjaTest) — czyli tez zbliza silnik do zejscia drabinka.
        cien(pierwszy).triggerOnResults(Bundle())
        uplyw(10)
        cien(pierwszy).triggerOnResults(wynikBundle())
        uplyw(300)
        assertTrue("pusty wynik nie jest wypowiedzia gracza", uslyszane.isEmpty())
        assertTrue(
            "diagnostyka ma pokazywac, ze tekstu nie bylo",
            voice.diagnostyka().contains("rozpoznał tekst: NIE"),
        )

        // Skoro tekstu nie bylo, puste cykle maja zejsc drabinka tak jak przy silniku bez modelu.
        repeat(PUSTE_BEZ_MOWY) { blad(pierwszy, SpeechRecognizer.ERROR_NO_MATCH) }
        val drugi = ostatni()
        assertNotSame("pusty wynik nie moze blokowac eskalacji", pierwszy, drugi)

        // A teraz odwrotnie: silnik NAPRAWDE cos oddaje...
        cien(drugi).triggerOnResults(wynikBundle("kolano"))
        uplyw(300)
        assertEquals(listOf(listOf("kolano")), uslyszane)
        // ...i dopiero dluga seria pustych cykli Z MOWA odbiera mu kredyt zaufania. Wczesniejszy
        // sukces nie moze blokowac odzyskania dzialania na zawsze.
        repeat(PUSTE_PO_SUKCESIE) { pustyCyklZMowa(ostatni()) }
        assertTrue(
            "po serii cykli z mowa i bez wynikow sprawny silnik tez schodzi nizej",
            drugi !== ostatni() || wyczerpane == 1,
        )
    }

    // =====================================================================
    // 3. Zgoda uruchamia jedna aktualna probe; odmowa i wyjscie z ekranu nie uruchamiaja nic.
    // =====================================================================
    @Test
    fun kryterium3_zgodaOdmowaIWyjscieZEkranu_niePodnoszaNasluchu() {
        // Okno zgody potrafi wrocic dopiero po chwili — do rundy, ktorej juz nie ma.
        assertTrue(wznowicPoZgodzie(zgoda = true, pauza = false, koniecGry = false))
        assertFalse("odmowa nie wlacza mikrofonu", wznowicPoZgodzie(false, pauza = false, koniecGry = false))
        assertFalse("pauza nie wlacza mikrofonu", wznowicPoZgodzie(true, pauza = true, koniecGry = false))
        assertFalse("koniec gry nie wlacza mikrofonu", wznowicPoZgodzie(true, pauza = false, koniecGry = true))

        start()
        val pierwszy = ostatni()
        voice.stop()
        uplyw(2_000)
        assertSame("po wylaczeniu zaden restart nie ma prawa wystartowac", pierwszy, ostatni())
        assertFalse(voice.listening)
        assertEquals(VoiceStan.BEZCZYNNY, voice.stan)

        voice.release()
        assertTrue("wyjscie z ekranu zwalnia rozpoznawacz", cien(pierwszy).isDestroyed)
        uplyw(2_000)
        assertFalse("po wyjsciu z ekranu nasluch nie wraca", voice.listening)
    }

    // =====================================================================
    // 4. Busy, spozniony callback i brak zakonczenia proby nie robia petli ani zawieszenia.
    // =====================================================================
    @Test
    fun kryterium4_busySpoznionyCallbackIBrakZakonczenia_nieZawieszajaNasluchu() {
        start()
        val pierwszy = ostatni()

        // a) "silnik zajety" ponawiamy SKONCZONA liczbe razy, a potem schodzimy nizej.
        repeat(5) { blad(pierwszy, SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        val drugi = ostatni()
        assertNotSame("seria BUSY nie moze ponawiac sie bez konca", pierwszy, drugi)
        assertEquals(0, wyczerpane)

        // b) spozniony blad od silnika, ktorego juz nie ma, nie liczy sie nowemu.
        blad(pierwszy, SpeechRecognizer.ERROR_CLIENT)
        assertSame("spozniony callback nie moze przestawiac silnika", drugi, ostatni())
        assertEquals(0, wyczerpane)

        // c) silnik przyjmuje zlecenie, melduje gotowosc i milknie — bez limitu czasu pasek
        //    swiecilby w nieskonczonosc. Straznik cyklu ma to zakonczyc.
        cien(drugi).triggerOnReadyForSpeech(Bundle())
        uplyw(16_000)
        assertTrue(
            "milczacy silnik musi skonczyc cykl, a nie zawiesic nasluch",
            drugi !== ostatni() || wyczerpane == 1,
        )
    }

    // =====================================================================
    // 5. Wielokrotne wlaczanie trybu nie dubluje instancji ani sesji.
    // =====================================================================
    /**
     * Drugie dotkniecie paska (albo rekompozycja ekranu) wchodzilo w start() z ZYWA sesja:
     * startListening bez cancel() konczy sie ERROR_RECOGNIZER_BUSY, wiec nasluch zamiast sluchac
     * wchodzil w serie ponowien. Wznowienie w scheduleRestart robilo cancel() od poczatku —
     * droga z przycisku nie robila tego nigdy.
     */
    @Test
    fun kryterium5_wielokrotneWlaczenieTrybu_nieDublujeInstancjiAniSesji() {
        start()
        val pierwszy = ostatni()
        val wypowiedzPoPierwszym = voice.wypowiedz

        start()
        assertSame("drugie wlaczenie nie tworzy drugiej instancji", pierwszy, ostatni())
        assertTrue("nasluch ma byc wlaczony dokladnie raz", voice.listening)
        assertTrue(
            "drugie wlaczenie zaczyna NOWA wypowiedz na tej samej instancji",
            voice.wypowiedz > wypowiedzPoPierwszym,
        )

        voice.stop()
        uplyw(2_000)
        assertFalse(voice.listening)
        assertSame("po stop() nic nie startuje w tle", pierwszy, ostatni())
    }

    // =====================================================================
    // 6. Jezyk odpowiedzi i jedno zaliczenie na wypowiedz.
    // =====================================================================
    @Test
    fun kryterium6_jezykOdpowiedziIJednoZaliczenieNaWypowiedz() {
        // JEZYK: w trybie losowym rozstrzyga przewaga odpowiedzi lezacych na planszy.
        val polskie = setOf("kolano", "plecy")
        val angielskie = setOf("knee", "back")
        assertEquals(true, odpowiedziPoPolsku(listOf("kolano", "plecy"), polskie, angielskie))
        assertEquals(false, odpowiedziPoPolsku(listOf("knee", "back"), polskie, angielskie))

        // JEDNA WYPOWIEDZ = JEDNO ZALICZENIE.
        assertTrue("pierwszy wynik zalicza", zaliczycWypowiedz("kolano", "", 1, 0))
        assertFalse(
            "koncowy po czesciowym TEJ SAMEJ wypowiedzi nie dokłada punktu",
            zaliczycWypowiedz("kolano", "kolano", 1, 1),
        )
        assertTrue(
            "dwa osobne 'kolano' to dwie odpowiedzi, choćby padly tuz po sobie",
            zaliczycWypowiedz("kolano", "kolano", 2, 1),
        )
        assertTrue("inne slowo w tej samej wypowiedzi liczy sie normalnie",
            zaliczycWypowiedz("plecy", "kolano", 1, 1))

        // Wynik czesciowy i koncowy tej samej wypowiedzi maja TEN SAM numer...
        start()
        val nr = voice.wypowiedz
        cien(ostatni()).triggerOnPartialResults(wynikBundle("kolano"))
        uplyw(10)
        assertEquals("wynik czesciowy nalezy do biezacej wypowiedzi", nr, voice.wypowiedz)
        cien(ostatni()).triggerOnResults(wynikBundle("kolano"))
        uplyw(10)
        assertEquals("koncowy nalezy do tej samej wypowiedzi", nr, voice.wypowiedz)
        // Wynik WYLACZNIE koncowy tez musi wystarczyc do zaliczenia — sa silniki bez czesciowych.
        assertTrue("koncowy wynik dochodzi do ekranu", uslyszane.contains(listOf("kolano")))

        // ...a nastepny cykl to juz nowa wypowiedz.
        uplyw(400)
        assertTrue("wznowienie zaczyna nowa wypowiedz", voice.wypowiedz > nr)
    }
}
