package com.matura.app

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.matura.app.ui.GlosyPlanszy
import com.matura.app.ui.ODSTEP_JEZYKA_MS
import com.matura.app.ui.ReakcjaNaBlad
import com.matura.app.ui.VoiceInput
import com.matura.app.ui.Wznowienie
import com.matura.app.ui.odpowiedziPoPolsku
import com.matura.app.ui.policzStrony
import com.matura.app.ui.reakcjaNaBlad
import com.matura.app.ui.wygladaPoPolsku
import com.matura.app.ui.zejscPoMilczacymCyklu
import com.matura.app.ui.zmienicJezykNasluchu
import com.matura.app.ui.eskalowacPoPustym
import com.matura.app.ui.PUSTE_Z_MOWA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
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
 * Zgloszenie z Motoroli brzmi "pasek sie wlacza i wylacza, zadnego bledu, slowa nie dzialaja".
 * Tak samo wyglada kilka roznych awarii i zadnej z nich nie da sie wywolac na maszynie bez
 * telefonu. Sprawdzamy wiec to, co sprawdzic sie da: ze kod bledu o braku pakietu jezyka
 * prowadzi do zejscia drabinka (a nie do komunikatu i konca) i ze zamowione wznowienie
 * nasluchu naprawde traci waznosc po stop().
 */
class VoiceDrabinkaTest {

    // ---- kody bledow ------------------------------------------------------

    /**
     * SEDNO. Na Androidzie 13+ telefon BEZ pobranego pakietu polskiego zglasza dokladnie te dwa
     * kody. Gdyby wpadly do galezi "pusty cykl", aplikacja restartowalaby ten sam bezradny
     * silnik w kolko — czyli robilaby to, na co skarzy sie uzytkownik.
     */
    @Test
    fun kodyBrakuPakietuJezyka_maja_wlasna_galaz() {
        assertEquals(ReakcjaNaBlad.BRAK_JEZYKA, reakcjaNaBlad(12)) // LANGUAGE_NOT_SUPPORTED
        assertEquals(ReakcjaNaBlad.BRAK_JEZYKA, reakcjaNaBlad(13)) // LANGUAGE_UNAVAILABLE
    }

    /** Numery bierzemy z publicznego API, a nie z pamieci — 12 i 13 latwo zamienic miejscami. */
    @Test
    fun numeryKodowJezykowych_zgadzajaSieZeStalymiAndroida() {
        assertEquals(12, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(13, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    }

    /** Brak pakietu jezyka NIE moze byc traktowany jak cisza, brak zgody ani zajety silnik. */
    @Test
    fun brakPakietuJezyka_toNieCisza_iNieZgoda() {
        for (kod in listOf(12, 13)) {
            val r = reakcjaNaBlad(kod)
            assertTrue("kod " + kod + " nie moze uchodzic za cisze", r != ReakcjaNaBlad.PUSTY_CYKL)
            assertTrue("kod " + kod + " nie moze uchodzic za brak zgody", r != ReakcjaNaBlad.ZGODA)
            assertTrue("kod " + kod + " nie moze uchodzic za zajety silnik", r != ReakcjaNaBlad.ZAJETY)
        }
    }

    /** Cala tabela kodow z publicznego API — zeby przypadkowa zmiana byla widoczna od razu. */
    @Test
    fun tabelaKodow_zgadzaSieZeSpeechRecognizerem() {
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(1))        // NETWORK_TIMEOUT
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(2))        // NETWORK
        assertEquals(ReakcjaNaBlad.NAGRYWANIE, reakcjaNaBlad(3))  // AUDIO
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(4))        // SERVER
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(5))        // CLIENT
        assertEquals(ReakcjaNaBlad.PUSTY_CYKL, reakcjaNaBlad(6))  // SPEECH_TIMEOUT
        assertEquals(ReakcjaNaBlad.PUSTY_CYKL, reakcjaNaBlad(7))  // NO_MATCH
        assertEquals(ReakcjaNaBlad.ZAJETY, reakcjaNaBlad(8))      // RECOGNIZER_BUSY
        assertEquals(ReakcjaNaBlad.ZGODA, reakcjaNaBlad(9))       // INSUFFICIENT_PERMISSIONS
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(10))       // TOO_MANY_REQUESTS
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(11))       // SERVER_DISCONNECTED
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(14))       // CANNOT_CHECK_SUPPORT
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(15))       // CANNOT_LISTEN_TO_DOWNLOAD_EVENTS
    }

    /**
     * Kod spoza zakresu (silniki producentow zwracaja wlasne) oraz syntetyczny kod watchdoga
     * maja isc drabinka nizej, a nie zatrzymywac glosu. "Nie znam tego kodu" nie jest powodem,
     * zeby przestac probowac.
     */
    @Test
    fun nieznanyKod_schodziDrabinka() {
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(0))
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(99))   // ERROR_WATCHDOG z VoiceInput
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(-1))
        assertEquals(ReakcjaNaBlad.INNY, reakcjaNaBlad(1234))
    }

    // ---- zaplanowane wznowienie -------------------------------------------

    /**
     * Restart zamowiony chwile przed stop() nie moze wskrzesic nasluchu po wyjsciu z ekranu.
     * Sama kolejka handlera tego nie gwarantuje (akcja moze juz z niej wyjsc), wiec warunek
     * jest jawny — i tu sprawdzamy jego regule, a nizej caly przebieg.
     */
    /**
     * Straznik cyklu jest jedynym mechanizmem, ktory potrafi ZGASIC dzialajacy glos, i jedynym,
     * ktorego w logu nie da sie odroznic od awarii, ktora ma lapac. Regula musi wiec stac osobno
     * i byc sprawdzalna bez telefonu: degradujemy tylko silnik, ktory NIC jeszcze nie oddal.
     *
     * Gotowosc i strumien RMS dowodem nie sa (silnik bez modelu jezyka daje jedno i drugie),
     * a silnik, ktory juz rozpoznawal, po prostu czeka na ucznia — zejscie drabinka zabraloby mu
     * szczebel i slownik zestawu do konca sesji.
     */
    @Test
    fun milczacyCykl_degradujeTylkoSilnikBezWyniku() {
        assertTrue("silnik, ktory nic nie oddal, moze byc zawieszony", zejscPoMilczacymCyklu(oddalWynik = false))
        assertFalse("silnik, ktory juz rozpoznawal, tylko czeka na ucznia", zejscPoMilczacymCyklu(oddalWynik = true))
    }

    @Test
    fun stop_uniewaznia_zaplanowanyRestart() {
        val w = Wznowienie()
        val znacznik = w.zaplanuj()
        assertTrue(w.zaplanowane)
        assertTrue(w.aktualne(znacznik))

        w.anuluj()   // to robi stop() / release()

        assertFalse("po stop() nie ma zadnego wznowienia", w.zaplanowane)
        assertFalse("stara akcja nie ma prawa sie wykonac", w.aktualne(znacznik))
    }

    /** Nowe zamowienie uniewaznia poprzednie: nie chcemy dwoch nasluchow naraz. */
    @Test
    fun noweZamowienie_uniewaznia_poprzednie() {
        val w = Wznowienie()
        val stary = w.zaplanuj()
        val nowy = w.zaplanuj()
        assertFalse(w.aktualne(stary))
        assertTrue(w.aktualne(nowy))
    }

    /** Wykonana akcja nie wraca po raz drugi. */
    @Test
    fun wykonaneWznowienie_niePowtarzaSie() {
        val w = Wznowienie()
        val z = w.zaplanuj()
        w.wykonano(z)
        assertFalse(w.zaplanowane)
        assertFalse(w.aktualne(z))
    }

    /** Anulowanie bez zamowienia i podwojne anulowanie nie moga niczego zepsuc. */
    @Test
    fun anulowanieJestBezpieczneZawsze() {
        val w = Wznowienie()
        w.anuluj()
        w.anuluj()
        assertFalse(w.zaplanowane)
        val z = w.zaplanuj()
        assertTrue(w.aktualne(z))
    }
}

/**
 * Jezyk, ktory dostaje silnik mowy przy kierunku LOSOWYM.
 *
 * To jest druga — obok drabinki silnikow — rzecz, ktora daje objaw "slowa nie dzialaja, a
 * wpisane owszem": silnikowi mowy mozna podac tylko JEDEN jezyk, a plansza w trybie losowym
 * miesza odpowiedzi polskie i angielskie.
 */
class VoiceJezykOdpowiedziTest {

    private val polskie = setOf("plecy", "kolano", "glowa", "reka", "szyja")
    private val angielskie = setOf("back", "knee", "head", "hand", "neck")

    @Test
    fun przewagaPolskichOdpowiedzi_dajePolski() {
        assertEquals(true, odpowiedziPoPolsku(listOf("plecy", "kolano", "back"), polskie, angielskie))
    }

    @Test
    fun przewagaAngielskichOdpowiedzi_dajeAngielski() {
        assertEquals(false, odpowiedziPoPolsku(listOf("back", "knee", "plecy"), polskie, angielskie))
    }

    /** Remis i pusta plansza: nie ma czego policzyc, decyduje zgadywanka po ksztalcie slowa. */
    @Test
    fun remis_oddajeDecyzjeZgadywance() {
        assertNull(odpowiedziPoPolsku(listOf("plecy", "back"), polskie, angielskie))
        assertNull(odpowiedziPoPolsku(emptyList(), polskie, angielskie))
    }

    /** Slowo lezace po obu stronach zestawu (albo po zadnej) nie ma prawa glosowac. */
    @Test
    fun slowoZobuStron_nieGlosuje() {
        val obie = setOf("stop")
        assertNull(odpowiedziPoPolsku(listOf("stop", "stop"), polskie + obie, angielskie + obie))
        assertNull(odpowiedziPoPolsku(listOf("cokolwiek"), polskie, angielskie))
    }

    /**
     * SEDNO. Dokladnie te slowa podal uzytkownik z Motoroli. Zadne z nich nie ma dwuznaku
     * cz/sz/rz/dz, wiec stara zgadywanka orzekala ANGIELSKI i silnik dostawal en-US — po polsku
     * nie dalo sie trafic w nic. Liczenie po stronach zestawu daje poprawna odpowiedz.
     */
    @Test
    fun czesciCiala_bezDwuznakow_toNadalPolski() {
        val plansza = listOf("plecy", "kolano", "glowa")
        assertFalse("stara zgadywanka mylila sie na tym zestawie", wygladaPoPolsku(plansza))
        assertEquals(true, odpowiedziPoPolsku(plansza, polskie, angielskie))
    }

    /** Zgadywanka zostaje jako ostatnia deska i ma dzialac jak dotad. */
    /**
     * Zliczanie glosow jest podstawa i dla wyboru jezyka, i dla decyzji o zmianie w trakcie
     * rundy — wiec sprawdzamy je wprost, a nie tylko przez wynik.
     */
    @Test
    fun policzStrony_liczyTylkoJednoznaczneOdpowiedzi() {
        val g = policzStrony(
            listOf("kolano", "knee", "kolano", "obie", "nieznane"),
            setOf("kolano", "obie"),
            setOf("knee", "obie"),
        )
        assertEquals(2, g.pl)
        assertEquals(1, g.en)
        assertEquals(true, g.przewaga())
    }

    /**
     * Jezyk zapada przy starcie nasluchu, a plansza w trybie losowym zmienia sklad w trakcie
     * rundy. Przewaga jednego glosu NIE moze przestawiac silnika: kazdy zabity potwor przy
     * wyniku 3:2 zrywalby nasluch, a restart kosztuje ulamek sekundy i grozi ERROR_BUSY.
     */
    @Test
    fun przewagaJednegoGlosu_nieRestartujeNasluchu() {
        assertFalse(zmienicJezykNasluchu(biezacyPolski = true, glosy = GlosyPlanszy(pl = 2, en = 3)))
        assertFalse(zmienicJezykNasluchu(biezacyPolski = false, glosy = GlosyPlanszy(pl = 3, en = 2)))
    }

    /** Wyrazna przewaga drugiej strony to juz ten sam blad, ktory naprawiamy — tylko w czasie. */
    @Test
    fun wyraznaPrzewagaDrugiejStrony_przestawiaJezyk() {
        assertTrue(zmienicJezykNasluchu(biezacyPolski = true, glosy = GlosyPlanszy(pl = 1, en = 3)))
        assertTrue(zmienicJezykNasluchu(biezacyPolski = false, glosy = GlosyPlanszy(pl = 4, en = 0)))
    }

    /** Jezyk zgodny z plansza (albo pusta plansza) nie daje powodu do restartu. */
    @Test
    fun jezykZgodnyZPlansza_zostaje() {
        assertFalse(zmienicJezykNasluchu(true, GlosyPlanszy(pl = 5, en = 0)))
        assertFalse(zmienicJezykNasluchu(false, GlosyPlanszy(pl = 0, en = 5)))
        assertFalse(zmienicJezykNasluchu(true, GlosyPlanszy(pl = 0, en = 0)))
    }

    /** Po przestawieniu ta sama plansza nie moze zaraz kazac wrocic — inaczej byloby migotanie. */
    @Test
    fun poPrzestawieniuJezyka_taSamaPlanszaNieKazeWracac() {
        val glosy = GlosyPlanszy(pl = 1, en = 4)
        assertTrue(zmienicJezykNasluchu(biezacyPolski = true, glosy = glosy))
        val poRestarcie = glosy.przewaga() ?: true   // tak liczy to PlayScreen przy starcie
        assertFalse(zmienicJezykNasluchu(poRestarcie, glosy))
    }

    @Test
    fun zgadywanka_dzialaJakDotad() {
        assertTrue("dwuznaki = polski", wygladaPoPolsku(listOf("szyja", "brzuch szczeka", "rzeka")))
        assertFalse("bez dwuznakow = angielski", wygladaPoPolsku(listOf("head", "knee", "hand")))
        assertTrue("pusta plansza to polski", wygladaPoPolsku(emptyList()))
    }
}

/**
 * Przebieg drabinki na cieniu [SpeechRecognizer] z Robolectrica. Nie zastapi telefonu — cien
 * nie ma ani mikrofonu, ani modelu jezyka — ale odpowiada na pytania, ktore w zgloszeniu
 * pozostawaly bez odpowiedzi: czy po bledzie o braku pakietu jezyka LECI DRUGIE zlecenie,
 * czym sie ono rozni od pierwszego i czy po wyjsciu z ekranu naprawde nic juz nie startuje.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceInputPrzebiegTest {

    private lateinit var voice: VoiceInput
    private var wyczerpane = 0

    /** Udawana usluga rozpoznawania mowy — nazwa bez "google", zeby sortowanie nic nie mieszalo. */
    private val PAKIET_USLUGI = "com.przyklad.mowa"

    private val petla get() = shadowOf(Looper.getMainLooper())

    /** Ostatnio utworzony rozpoznawacz i jego cien. */
    private fun ostatni(): SpeechRecognizer =
        requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) {
            "start() nie utworzyl zadnego rozpoznawacza"
        }

    private fun cien(r: SpeechRecognizer): ShadowSpeechRecognizer = Shadow.extract(r)

    private fun uplyw(ms: Long) = petla.idleFor(Duration.ofMillis(ms))

    @Before
    fun ustaw() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // JEDNA usluga rozpoznawania na "urzadzeniu". Bez niej SpeechRecognizer.isRecognitionAvailable
        // zwraca false i drabinka nie ma dokad zejsc — a sprawdzamy wlasnie schodzenie.
        // Przy jednej usludze drabinka ma dwa szczeble: "zdejmij offline" i "zwiaz sie po nazwie".
        val komponent = ComponentName(PAKIET_USLUGI, PAKIET_USLUGI + ".Rozpoznawanie")
        shadowOf(ctx.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(ctx.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        // Chcemy zaczac od szczebla sieciowego: to on jest punktem wyjscia na wiekszosci telefonow.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        voice = VoiceInput(ctx)
        voice.onExhausted = { wyczerpane += 1 }
    }

    /**
     * Kazdy test oddaje mikrofon i NISZCZY rozpoznawacz.
     *
     * stop() robi tylko cancel(), wiec cien rozpoznawacza zostaje zwiazany z usluga. W jednej
     * maszynie wirtualnej z reszta zestawu to wystarczylo, zeby Compose w NASTEPNEJ klasie nigdy
     * nie doczekal sie bezczynnosci: testy ekranu przewracaly sie na AppNotIdleException po 60 s,
     * lacznie z tymi, ktore z glosem nie maja nic wspolnego. Efekt zalezal od tego, ktory test
     * wypadl w klasie ostatni, czyli od kolejnosci nazw metod — najgorszy rodzaj usterki zestawu.
     * release() to dokladnie to, co robi wyjscie z ekranu gry.
     */
    @After
    fun sprzataj() {
        voice.release()
        uplyw(200)
    }

    /** Start nasluchu i przepuszczenie petli: cien sklada zlecenie dopiero z glownego watku. */
    private fun start() {
        voice.start("pl-PL", listOf("kolano", "plecy", "głowa")) { _, _ -> }
        uplyw(50)
    }

    /** Wynik rozpoznawania w postaci, w ktorej dostaje go sluchacz. */
    private fun wynikBundle(vararg slowa: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(slowa.toList()))
    }

    /** Blad od wskazanego silnika + czas na to, co zaplanuje drabinka. */
    private fun blad(r: SpeechRecognizer, kod: Int) {
        cien(r).triggerOnError(kod)
        uplyw(300)
    }

    /** Warunek wstepny: start() naprawde sklada zlecenie i podaje jezyk odpowiedzi. */
    @Test
    fun start_sklada_zlecenie_zJezykiemOdpowiedzi() {
        start()
        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertNotNull("start() musi zlozyc zlecenie rozpoznawania", i)
        assertEquals("pl-PL", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue("pierwsze podejscie prosi o rozpoznanie bez sieci",
            i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue("pierwsze podejscie niesie slownik zestawu",
            i.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)?.isNotEmpty() == true)
    }

    /**
     * SEDNO. Kod 13 (brak pakietu jezyka) ma zejsc drabinka nizej, a nie skonczyc komunikatem:
     * leci DRUGIE zlecenie, tym razem bez wymuszonego offline i bez podpowiedzi slownikowych —
     * czyli bez dwoch rzeczy, ktore silnik bez pakietu odrzuca najpierw. Do 0.9.10 szczebel
     * "zdejmij offline" tylko przestawial flage, wiec podpowiedzi jechaly dalej.
     */
    @Test
    fun brakPakietuJezyka_ponawia_bezOfflineIbezPodpowiedzi() {
        start()
        val pierwszy = ostatni()
        val zlecenie1 = cien(pierwszy).lastRecognizerIntent

        blad(pierwszy, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        assertEquals("drabinka ma zejsc, a nie oddac decyzje ekranowi", 0, wyczerpane)
        val zlecenie2 = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertNotSame("po kodzie 13 musi polecic NOWE zlecenie", zlecenie1, zlecenie2)
        assertFalse("drugie podejscie nie moze wymuszac offline",
            zlecenie2.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue("drugie podejscie idzie bez podpowiedzi slownikowych",
            zlecenie2.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS).isNullOrEmpty())
    }

    /** Kod 12 to ta sama sytuacja co 13 — obie stale opisuja brak modelu jezyka. */
    @Test
    fun kod12_takzeSchodziDrabinka() {
        start()
        val pierwszy = ostatni()
        blad(pierwszy, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(0, wyczerpane)
        assertNotSame(pierwszy, ostatni())
    }

    /**
     * Komunikat dopiero na KONCU drabinki. Przy jednej usludze na "urzadzeniu" szczeble sa dwa
     * (zdejmij offline, zwiaz sie po nazwie), wiec dopiero TRZECI kod 13 konczy drabinke — i
     * dopiero wtedy decyzja trafia do ekranu (okno systemowe / klawiatura), zamiast zostac cisza.
     */
    @Test
    fun dopieroKoniecDrabinki_oddajeDecyzjeEkranowi() {
        start()
        blad(ostatni(), SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals("po pierwszym kodzie drabinka jeszcze schodzi", 0, wyczerpane)
        blad(ostatni(), SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals("po drugim tez — jest jeszcze usluga do zwiazania", 0, wyczerpane)
        blad(ostatni(), SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals("po wyczerpaniu drabinki ekran musi dostac sygnal", 1, wyczerpane)
        assertFalse("nasluch nie moze zostac zapalony", voice.listening)
        assertFalse("pasek ma przestac obiecywac silnik", voice.maSilnik)
        assertTrue("komunikat ma nazwac przyczyne", voice.status.isNotEmpty())
    }

    /**
     * Spozniony blad od silnika, ktory juz zniszczylismy, nie moze liczyc sie nowemu: inaczej
     * jedna awaria zjadala dwa szczeble drabinki naraz i glos gasl w ulamku sekundy.
     */
    @Test
    fun spoznionyBladPoprzedniegoSilnika_nieZjadaSzczebla() {
        start()
        val pierwszy = ostatni()
        blad(pierwszy, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        val drugi = ostatni()
        assertNotSame(pierwszy, drugi)

        // Stary silnik odbija sie ERROR_CLIENT juz po destroy() — to ma trafic do kosza.
        blad(pierwszy, SpeechRecognizer.ERROR_CLIENT)

        assertEquals("spozniony blad nie moze konczyc drabinki", 0, wyczerpane)
        assertSame("ani przestawiac silnika", drugi, ostatni())
    }

    /**
     * Kontrola dodatnia dla testu ponizej: pusty cykl SAM Z SIEBIE planuje wznowienie,
     * wiec brak wznowienia po stop() bedzie znaczyl cos konkretnego.
     */
    @Test
    fun pustyCykl_planuje_wznowienieNasluchu() {
        start()
        val r = ostatni()
        val zlecenie1 = requireNotNull(cien(r).lastRecognizerIntent)
        cien(r).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        uplyw(1_000)
        assertNotSame("po pustym cyklu nasluch ma ruszyc od nowa", zlecenie1, cien(r).lastRecognizerIntent)
    }

    /**
     * SEDNO nr 2: wznowienie zamowione tuz przed stop() nie ma prawa wystartowac. Bez tego
     * nasluch odzywal po wyjsciu z ekranu — z mikrofonem zajetym i bez nikogo, kto by go
     * wylaczyl.
     */
    @Test
    fun stop_kasuje_zaplanowanyRestart() {
        start()
        val r = ostatni()
        cien(r).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)   // planuje wznowienie
        val zlecenie1 = requireNotNull(cien(r).lastRecognizerIntent)

        voice.stop()
        uplyw(1_000)

        assertSame("stop() musi skasowac zaplanowane wznowienie", zlecenie1, cien(r).lastRecognizerIntent)
        assertFalse(voice.listening)
    }

    /** To samo przy wyjsciu z ekranu: release() nie zostawia niczego, co by wystartowalo. */
    @Test
    fun release_niePozostawia_zywegoRestartu() {
        start()
        val r = ostatni()
        cien(r).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        val zlecenie1 = requireNotNull(cien(r).lastRecognizerIntent)

        voice.release()
        uplyw(10_000)   // dosc dlugo, zeby zlapac takze watchdoga

        assertSame(zlecenie1, cien(r).lastRecognizerIntent)
        assertEquals("po wyjsciu z ekranu nikt juz niczego nie oddaje ekranowi", 0, wyczerpane)
    }

    /**
     * SEDNO tej wersji. Silnik, ktory przyjmuje zlecenie, mowi "gotowy" i potem MILCZY — ani
     * wyniku, ani bledu — do 0.9.11 wieszal nasluch na zawsze: watchdog kasowala wlasnie ta
     * gotowosc ([zyje]), a drugiego limitu czasu nie bylo. Pasek swiecil "SŁUCHAM…", drabinka
     * stala, status byl pusty. To jest co do slowa zgloszenie z Motoroli — i jedyna awaria
     * z listy, ktorej nie widac ani w logu, ani w diagnostyce.
     */
    @Test
    fun milczacyCykl_poGotowosci_schodziDrabinka() {
        start()
        val pierwszy = ostatni()
        cien(pierwszy).triggerOnReadyForSpeech(Bundle())
        uplyw(200)
        assertSame("sama gotowosc niczego jeszcze nie przestawia", pierwszy, ostatni())

        uplyw(20_000)   // wiecej niz limit cyklu

        assertNotSame("po limicie cyklu drabinka musi zejsc nizej", pierwszy, ostatni())
        assertEquals("ale jeszcze nie oddawac decyzji ekranowi", 0, wyczerpane)
    }

    /**
     * Strumien RMS to nie dowod, ze silnik cokolwiek odda: czesc silnikow raportuje poziom
     * i milczy. zyje() kasuje watchdoga takze przy onRmsChanged, wiec gdyby straznik cyklu
     * schodzil razem z nim, ta sciezka zostalaby bez zadnego limitu czasu.
     */
    @Test
    fun strumienRms_nieRatujeMilczacegoSilnika() {
        start()
        val pierwszy = ostatni()
        cien(pierwszy).triggerOnReadyForSpeech(Bundle())
        repeat(30) {
            cien(pierwszy).triggerOnRmsChanged(-2.1f)
            uplyw(100)
        }
        assertSame("trzy sekundy poziomu to jeszcze nie awaria", pierwszy, ostatni())

        uplyw(20_000)

        assertNotSame("ale silnik, ktory tylko mierzy poziom, musi ustapic miejsca", pierwszy, ostatni())
    }

    /** Wynik czesciowy jest dowodem pracy: dluga wypowiedz nie moze zjadac szczebla drabinki. */
    @Test
    fun wynikiCzesciowe_przedluzajaCykl() {
        start()
        val pierwszy = ostatni()
        cien(pierwszy).triggerOnReadyForSpeech(Bundle())
        // Co 10 s jeden wynik czesciowy: bez przedluzania limit cyklu zdazylby zejsc drabinka.
        repeat(3) {
            cien(pierwszy).triggerOnPartialResults(wynikBundle("kola"))
            uplyw(10_000)
        }
        assertSame("silnik, ktory sypie wynikami czesciowymi, PRACUJE", pierwszy, ostatni())
        assertEquals(0, wyczerpane)
    }

    /**
     * Milczacy silnik nie moze byc slepa uliczka: gdy drabinka sie skonczy, decyzja idzie do
     * ekranu (okno systemowe / klawiatura telefonu), a nie zostaje zapalony pasek bez komunikatu.
     */
    @Test
    fun milczaceCykle_naKoncuDrabinki_oddajaDecyzjeEkranowi() {
        start()
        repeat(3) {
            cien(ostatni()).triggerOnReadyForSpeech(Bundle())
            uplyw(20_000)
        }
        assertEquals("po wyczerpaniu drabinki ekran musi dostac sygnal", 1, wyczerpane)
        assertFalse("nasluch nie moze zostac zapalony", voice.listening)
        assertTrue("komunikat ma nazwac przyczyne", voice.status.isNotEmpty())
    }

    /**
     * REGRESJA, ktorej straznik cyklu omal nie wprowadzil. Uczen zastanawiajacy sie nad
     * odpowiedzia dluzej niz limit cyklu, przy silniku o dluzszym wlasnym limicie ciszy (typowe
     * dla silnikow producenta, ktore nie zglaszaja SPEECH_TIMEOUT tak szybko jak Google), tracil
     * szczebel drabinki. useEngine() zapala przy okazji bezBiasingu, wiec do konca sesji znikal
     * tez slownik zestawu — czyli glos pogarszal sie dokladnie temu, komu akurat dzialal, a w
     * logu wygladalo to identycznie jak awaria silnika.
     *
     * Kontrola ujemna dla tego testu stoi wyzej: [milczacyCykl_poGotowosci_schodziDrabinka]
     * — silnik, ktory zglosil tylko gotowosc, MUSI ustapic miejsca.
     */
    @Test
    fun silnikKtoryJuzRozpoznawal_nieTraciSzczebla_naDlugimNamysle() {
        start()
        val pierwszy = ostatni()
        cien(pierwszy).triggerOnReadyForSpeech(Bundle())
        // Dowod pracy: silnik zamienil mowe na tekst. Od tej chwili cisza jest sprawa ucznia.
        cien(pierwszy).triggerOnResults(wynikBundle("kolano"))
        uplyw(300)
        cien(pierwszy).triggerOnReadyForSpeech(Bundle())

        uplyw(18_000)   // dluzej niz limit cyklu — uczen po prostu sie zastanawia

        assertSame("dzialajacy silnik ma zostac", pierwszy, ostatni())
        assertEquals("i nikt nie oddaje decyzji ekranowi", 0, wyczerpane)
        assertTrue("nasluch ma trwac dalej", voice.listening)
    }

    /**
     * Gdy straznik jednak schodzi drabinka, uczen ma to zobaczyc — tak samo jak w galeziach
     * PUSTY_CYKL i BRAK_JEZYKA. Bez tego pasek milczy przez cala zmiane silnika i wyglada jak
     * zawieszenie, czyli jak awaria, ktora wlasnie obchodzimy.
     */
    @Test
    fun zejscieStraznika_mowiUczniowiZeZmieniamySilnik() {
        voice.tekstInnySilnik = "INNY SILNIK"
        start()
        cien(ostatni()).triggerOnReadyForSpeech(Bundle())
        uplyw(16_000)
        assertEquals("INNY SILNIK", voice.status)
    }

    /**
     * Przelaczenie jezyka w trakcie rundy (tryb losowy) jest WZNOWIENIEM tej samej sesji.
     * stop() + start() kasowalo dziennik zdarzen, czyli jedyne zrodlo nakladki diagnostycznej —
     * a plansza w trybie losowym zmienia sklad po kazdym zabitym potworze, wiec trafialo to
     * czasem dokladnie w chwile, gdy uczen sprawdzal, czemu mikrofon milczy.
     */
    @Test
    fun przelaczenieJezyka_zostawiaDziennikIniePrzerywaSesji() {
        start()
        assertFalse("tuz po starcie przelaczanie jest za wczesnie", voice.przelaczJezyk("en-US"))

        uplyw(ODSTEP_JEZYKA_MS + 100)
        assertTrue("po odstepie zmiana ma dojsc do skutku", voice.przelaczJezyk("en-US"))
        uplyw(300)

        val zlecenie = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals("en-US", zlecenie.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue("nasluch ma trwac dalej", voice.listening)
        assertEquals("zmiana jezyka to nie awaria", 0, wyczerpane)
        val diag = voice.diagnostyka()
        assertTrue("dziennik pamieta start sprzed przelaczenia", diag.contains("start (pl-PL"))
        assertTrue("i sama zmiane jezyka", diag.contains("język -> en-US"))
    }

    /** Ten sam jezyk i wylaczony nasluch nie maja czego przelaczac — zadnego restartu. */
    @Test
    fun przelaczenieJezyka_naToSamo_albuPoStop_nicNieRobi() {
        start()
        uplyw(ODSTEP_JEZYKA_MS + 100)
        assertFalse("ten sam tag jezyka", voice.przelaczJezyk("pl-PL"))
        voice.stop()
        assertFalse("po wylaczeniu nasluchu nie ma czego wznawiac", voice.przelaczJezyk("en-US"))
    }

    /**
     * Po eskalacji podpowiedzi slownikowe znikaja (useEngine) — ale NOWE wlaczenie nasluchu musi
     * zaczynac od pelnego zlecenia. Do 0.9.11 bezBiasingu nie wracalo juz nigdy: po jednej wpadce
     * w dowolnej rundzie slownik zestawu znikal do konca zycia ekranu gry.
     */
    @Test
    fun poEskalacji_noweWlaczenie_znowuNiesiePodpowiedzi() {
        start()
        blad(ostatni(), SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertTrue(
            "kontrola: po eskalacji zlecenie idzie bez podpowiedzi",
            cien(ostatni()).lastRecognizerIntent
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS).isNullOrEmpty(),
        )

        voice.stop()
        start()

        val zlecenie = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertTrue(
            "nowa sesja zaczyna od pelnego zlecenia",
            zlecenie.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)?.isNotEmpty() == true,
        )
    }

    /** stop() i release() bez wczesniejszego startu nie moga sie wywrocic. */
    @Test
    fun stopIrelease_saBezpieczneBezStartu() {
        voice.stop()
        voice.release()
        assertFalse(voice.listening)
    }

    /**
     * Regresja z przegladu 0.9.11: po zmianie jezyka w trakcie sesji wskazniki oceny silnika
     * (dzialal / oddalWynik) zostawaly z POPRZEDNIEGO jezyka. Silnik, ktory rozpoznawal po
     * angielsku, po przestawieniu na pl-PL nie mial modelu i milkl, a zaden straznik nie schodzil
     * drabinka nizej — pasek swiecil, zero bledow, zero reakcji. Ten sam objaw, ktory naprawiamy.
     */
    @Test
    fun przelaczenieJezyka_pozwalaZejscDrabinka() {
        // Silnik, ktory juz cos oddal, nie eskaluje po pustym cyklu — to jest poprawne zachowanie.
        assertFalse(eskalowacPoPustym(dzialal = true, pusteCykle = 9, slyszalMowe = true))
        // Po zmianie jezyka traktujemy go jak nowy: te same puste cykle MAJA zejsc drabinka.
        assertTrue(eskalowacPoPustym(dzialal = false, pusteCykle = PUSTE_Z_MOWA, slyszalMowe = true))
    }
}
