package com.matura.app

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.matura.app.ui.JEZYKI_GRY
import com.matura.app.ui.ODSTEP_JEZYKA_MS
import com.matura.app.ui.VoiceInput
import com.matura.app.ui.VoiceMode
import com.matura.app.ui.modelZainstalowany
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
 * Diagnostyka z motoroli edge 50 neo (Android 16) pokazala silnik on-device, ktory przyjmuje
 * zlecenie z pl-PL, melduje gotowosc i milczy: zero probek poziomu, zero bledow, zero wynikow.
 * Tak wyglada rozpoznawanie on-device BEZ pobranego modelu jezyka — usluga jest, tylko nie ma
 * czym rozpoznawac. Do 0.9.15 gra zaczynala nasluch dokladnie od tego szczebla i czekala, az
 * straznik dzwieku (2,5 s) to zlapie. Od 0.9.16 pyta o model PRZED startem (checkRecognitionSupport,
 * API 33+) i bez modelu idzie od razu do silnika domyslnego — a ten sam uzyje modelu offline,
 * gdy go ma, albo rozpozna przez siec.
 */
class ModelZainstalowanyTest {

    @Test
    fun tenSamTag_liczySie() {
        assertTrue(modelZainstalowany("pl-PL", listOf("en-US", "pl-PL")))
        assertTrue("silniki podaja rozna pisownie", modelZainstalowany("pl-PL", listOf("pl_PL")))
        assertTrue("wielkosc liter nie ma znaczenia", modelZainstalowany("pl-PL", listOf("PL-pl")))
    }

    @Test
    fun samJezykBezRegionu_liczySie() {
        assertTrue("model podpisany samym jezykiem obsluguje kazdy region", modelZainstalowany("pl-PL", listOf("pl")))
        assertTrue("pytanie bez regionu zadowala dowolny region", modelZainstalowany("pl", listOf("pl-PL")))
    }

    @Test
    fun innyRegion_nieLiczySie() {
        // en-GB przy pytaniu o en-US bywa odrzucane kodem 13 — to sprawa szczebla "jezyk bez
        // regionu", a nie dowod, ze model jest.
        assertFalse(modelZainstalowany("en-US", listOf("en-GB")))
    }

    @Test
    fun pustaLista_iPustyTag_toBrak() {
        assertFalse(modelZainstalowany("pl-PL", emptyList()))
        assertFalse(modelZainstalowany("", listOf("pl-PL")))
        assertFalse(modelZainstalowany("pl-PL", listOf("", "  ")))
    }

    @Test
    fun tagiGry_toPolskiIAngielski() {
        assertEquals(listOf("pl-PL", "en-US"), JEZYKI_GRY)
    }
}

/**
 * KONTRAKT OD 0.9.17 — KOLEJNOSC SILNIKOW ODWROCONA.
 *
 * Do 0.9.16 gra ZACZYNALA od rozpoznawania on-device, bo isOnDeviceRecognitionAvailable mowilo
 * "tak". Test mowy z motoroli edge 50 neo (Android 16, 11 prob) pokazal, ze to bylo dokladnie
 * najgorsze mozliwe pierwsze posuniecie na tym telefonie:
 *
 *   on-device / offline / bias       -> "ko", urwane, ZERO probek poziomu
 *   on-device / offline / bias50     -> cisza,        ZERO probek poziomu
 *   on-device / offline              -> cisza,        ZERO probek poziomu
 *   service / com.google.android.as  -> cisza,        ZERO probek poziomu
 *   default (offline)                -> ROZPOZNANE w 1,5 s
 *   service / com.google.android.tts -> ROZPOZNANE
 *
 * Zepsuta jest DOKLADNIE JEDNA usluga (com.google.android.as) i gra trafiala w nia przy kazdym
 * starcie, bo createOnDeviceSpeechRecognizer IGNORUJE ComponentName — system_server podstawia
 * config_defaultOnDeviceSpeechRecognitionService i drabinka nie ma jak z tej uslugi zejsc.
 *
 * Bramka modelu jezyka z 0.9.16 tego NIE ratuje: polski model na tym telefonie JEST zainstalowany,
 * wiec bramka przepuszczala i wysylala aplikacje prosto w martwy silnik. Dlatego awaria przezyla
 * osiem wydan — poprawka celowala w niewlasciwa przyczyne.
 *
 * Nowy kontrakt, ktorego pilnuje ta klasa:
 *  1. punktem wyjscia jest silnik DOMYSLNY (ten, ktory system wskazuje aplikacjom trzecim),
 *  2. on-device jest OSTATNIM szczeblem drabinki, nie pierwszym,
 *  3. o offline prosimy tylko z POTWIERDZONYM modelem jezyka,
 *  4. silnik, ktory zglosil gotowosc i nie oddal ani probki dzwieku, ani tekstu, trafia na
 *     czarna liste i drabinka juz do niego nie wraca.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceModelOnDeviceTest {

    private lateinit var voice: VoiceInput
    private var wyczerpane = 0
    private val uslyszane = mutableListOf<List<String>>()

    private val PAKIET_USLUGI = "com.przyklad.mowa"
    private val petla get() = shadowOf(Looper.getMainLooper())

    private fun ostatni(): SpeechRecognizer =
        requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) { "nie utworzono rozpoznawacza" }

    private fun cien(r: SpeechRecognizer): ShadowSpeechRecognizer = Shadow.extract(r)

    private fun uplyw(ms: Long) = petla.idleFor(Duration.ofMillis(ms))

    @Before
    fun ustaw() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val komponent = ComponentName(PAKIET_USLUGI, PAKIET_USLUGI + ".Rozpoznawanie")
        shadowOf(ctx.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(ctx.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        // Telefon Z usluga on-device — jak motorola z Androidem 16.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        voice = VoiceInput(ctx)
        voice.onExhausted = { wyczerpane += 1 }
    }

    @After
    fun sprzataj() {
        voice.release()
        uplyw(200)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
    }

    private fun start(jezyk: String = "pl-PL") {
        voice.start(jezyk, listOf("kolano", "plecy", "głowa")) { slowa, _ -> uslyszane.add(slowa) }
        uplyw(50)
    }

    private fun wsparcie(zainstalowane: List<String>, obslugiwane: List<String> = zainstalowane): RecognitionSupport =
        RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(zainstalowane)
            .setSupportedOnDeviceLanguages(obslugiwane)
            .setOnlineLanguages(listOf("pl-PL", "en-US"))
            .build()

    /** Odpowiedz sondy w tle dla JEDNEGO tagu; sonda odczekuje ODSTEP_WIAZANIA_MS przed nastepnym. */
    private fun odpowiedzOModelu(r: SpeechRecognizer, zainstalowane: List<String>, obslugiwane: List<String> = zainstalowane) {
        cien(r).triggerSupportResult(wsparcie(zainstalowane, obslugiwane))
        uplyw(500)
    }

    /** Sonda w tle odpowiada za OBA tagi gry — po tym modelOnDevice zna odpowiedz. */
    private fun sondaWTle(zainstalowane: List<String>) {
        voice.refresh()
        uplyw(50)
        odpowiedzOModelu(ostatni(), zainstalowane)
        odpowiedzOModelu(ostatni(), zainstalowane)
    }

    private fun wynikBundle(vararg slowa: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(slowa.toList()))
    }

    /** Doprowadza biezacy silnik do zejscia przez straznika dzwieku: gotowosc i cisza. */
    private fun gotowoscBezDzwieku() {
        cien(ostatni()).triggerOnReadyForSpeech(Bundle())
        uplyw(3_000)   // DZWIEK_MS z zapasem
    }

    // ---- 1. punkt wyjscia --------------------------------------------------

    /**
     * SEDNO POPRAWKI. Telefon ma usluge on-device i silnik domyslny; nasluch ma ruszyc
     * NATYCHMIAST na silniku domyslnym, bez pytania o model i bez wiazania on-device.
     */
    @Test
    fun start_idzieOdRazuDoSilnikaDomyslnego_aNieDoOnDevice() {
        start()

        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent) { "zlecenie ma poleciec od razu" }
        assertEquals(VoiceMode.ONLINE, voice.mode)
        assertEquals("pl-PL", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue("nasluch trwa", voice.listening)
        assertEquals(0, wyczerpane)
    }

    /** Bez potwierdzonego modelu nie prosimy o offline — silnik domyslny sam wybierze droge. */
    @Test
    fun bezPotwierdzonegoModelu_silnikDomyslnyBezWymuszonegoOffline() {
        start()
        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertFalse(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(
            "slownik zestawu jest udokumentowany dla on-device, nie dla silnika domyslnego",
            i.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS).isNullOrEmpty(),
        )
    }

    /**
     * Z POTWIERDZONYM modelem tego jezyka silnik domyslny dostaje prosbe o prace offline —
     * na telefonie ze zgloszenia ta wlasnie konfiguracja oddala wynik w 1,5 s.
     */
    @Test
    fun zPotwierdzonymModelem_silnikDomyslnyProsiOOffline() {
        sondaWTle(listOf("pl-PL", "en-US"))
        start()

        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals(VoiceMode.ONLINE, voice.mode)
        assertTrue("model potwierdzony -> prosimy o offline", i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(voice.diagnostyka().contains("pl-PL zainstalowany"))
    }

    /** Model, ktorego nie ma, nie zamyka drogi: silnik domyslny startuje, tylko bez offline. */
    @Test
    fun brakModelu_silnikDomyslnyStartujeBezOffline() {
        sondaWTle(emptyList())
        start()

        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals(VoiceMode.ONLINE, voice.mode)
        assertFalse(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals("to nie awaria — ekran nie dostaje sygnalu", 0, wyczerpane)
        assertTrue(voice.diagnostyka().contains("pl-PL brak"))
    }

    /** Sonda w tle chodzi takze wtedy, gdy sesja zaczyna w trybie ONLINE (regresja 0.9.17). */
    @Test
    fun sondaWTle_dziala_mimoZeSesjaZaczynaOnline() {
        voice.refresh()
        uplyw(50)
        val sonda = ostatni()
        assertNull("sonda w tle nie startuje nasluchu", cien(sonda).lastRecognizerIntent)
        odpowiedzOModelu(sonda, listOf("pl-PL"))
        val sondaEn = ostatni()
        assertNotSame("po polskim pyta o angielski", sonda, sondaEn)
        odpowiedzOModelu(sondaEn, listOf("pl-PL"))
        assertFalse(voice.listening)
        assertTrue(voice.diagnostyka().contains("en-US brak"))
    }

    // ---- 2. on-device jako OSTATNI szczebel --------------------------------

    /**
     * Drabinka: silnik domyslny -> konkretne uslugi -> DOPIERO NA KONCU on-device.
     * Kazdy szczebel schodzi przez straznika dzwieku (gotowosc bez ani jednej probki poziomu) —
     * czyli dokladnie tym objawem, ktory przyszedl z telefonu.
     */
    @Test
    fun onDeviceJestOstatnimSzczeblem_aNiePierwszym() {
        sondaWTle(listOf("pl-PL"))
        start()
        assertEquals("zaczynamy od domyslnego", VoiceMode.ONLINE, voice.mode)

        var onDevice = false
        repeat(6) {
            if (!onDevice && wyczerpane == 0) {
                gotowoscBezDzwieku()
                if (voice.mode == VoiceMode.ON_DEVICE) onDevice = true
            }
        }

        assertTrue("on-device ma dostac szanse, ale na koncu drabinki", onDevice || wyczerpane == 1)
    }

    /**
     * Silnik, ktory zglosil gotowosc i zamilkl, trafia na czarna liste — i nie wraca po
     * dotknieciu paska. Bez tego kazde wlaczenie kosztowalo ucznia pelne DZWIEK_MS na ten sam
     * martwy silnik (na telefonie ze zgloszenia: com.google.android.as).
     */
    @Test
    fun martwySilnik_trafiaNaCzarnaListe_iNieWracaPoDotknieciu() {
        sondaWTle(listOf("pl-PL"))
        start()

        // Schodzimy drabinka, az biezacym silnikiem bedzie KONKRETNA usluga (silnik domyslny
        // nie ma pakietu, ktory dalo by sie skreslic — patrz odnotujAwarieSilnika).
        var skreslony = false
        repeat(6) {
            if (!skreslony && wyczerpane == 0) {
                gotowoscBezDzwieku()
                skreslony = voice.diagnostyka().contains("pominięte pakiety: " + PAKIET_USLUGI) ||
                    voice.diagnostyka().contains("on-device skreślony")
            }
        }

        assertTrue(
            "usluga, ktora zglosila gotowosc i zamilkla, ma zostac skreslona na te sesje",
            skreslony,
        )
    }

    // ---- 3. reszta kontraktu ----------------------------------------------

    /** Zmiana jezyka w rundzie nie wywraca sesji i nie wraca na on-device. */
    @Test
    fun przelaczenieJezyka_zostajeNaSilnikuDomyslnym() {
        sondaWTle(listOf("pl-PL"))
        start()
        assertEquals(VoiceMode.ONLINE, voice.mode)

        uplyw(ODSTEP_JEZYKA_MS + 100)
        assertTrue(voice.przelaczJezyk("en-US"))
        uplyw(300)

        assertEquals(VoiceMode.ONLINE, voice.mode)
        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals("en-US", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(voice.listening)
    }

    /** stop() w trakcie sondy w tle: spozniona odpowiedz nie wskrzesza nasluchu. */
    @Test
    fun stopWTrakcieSprawdzania_odpowiedzNieStartujeNasluchu() {
        voice.refresh()
        uplyw(50)
        val sonda = ostatni()
        voice.stop()
        odpowiedzOModelu(sonda, listOf("pl-PL"))
        uplyw(2_000)
        assertFalse(voice.listening)
    }

    /** Po wyczerpaniu drabinki i ostygnieciu blokady nowa sesja znow ma czym probowac. */
    @Test
    fun poWyczerpaniuIOstygnieciu_drabinkaWracaDoDyspozycji() {
        sondaWTle(listOf("pl-PL"))
        start()
        repeat(8) { if (wyczerpane == 0) gotowoscBezDzwieku() }
        assertEquals("drabinka ma sie w koncu wyczerpac", 1, wyczerpane)

        uplyw(26_000)   // BLOKADA_MS = 25 s
        voice.refresh()

        assertTrue("po ostygnieciu jest czym probowac", voice.maSilnik)
    }
}

/**
 * Bledy przejsciowe (CLIENT / SERVER / siec) na silniku, ktory w tej sesji juz oddal tekst.
 *
 * Czesc uslug odbija ERROR_CLIENT tuz po cancel()+startListening, czyli po KAZDYM wyniku —
 * do 0.9.15 kazdy taki kod schodzil drabinka: sprawdzony silnik tracil szczebel i slownik
 * po pierwszym rozpoznanym slowie, a gra konczyla na oknie systemowym mimo dzialajacego glosu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceBledyPrzejscioweTest {

    private lateinit var voice: VoiceInput
    private var wyczerpane = 0
    private val uslyszane = mutableListOf<List<String>>()
    private val PAKIET_USLUGI = "com.przyklad.mowa"
    private val petla get() = shadowOf(Looper.getMainLooper())

    private fun ostatni(): SpeechRecognizer =
        requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun cien(r: SpeechRecognizer): ShadowSpeechRecognizer = Shadow.extract(r)
    private fun uplyw(ms: Long) = petla.idleFor(Duration.ofMillis(ms))

    @Before
    fun ustaw() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val komponent = ComponentName(PAKIET_USLUGI, PAKIET_USLUGI + ".Rozpoznawanie")
        shadowOf(ctx.packageManager).addServiceIfNotPresent(komponent)
        shadowOf(ctx.packageManager)
            .addIntentFilterForService(komponent, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        voice = VoiceInput(ctx)
        voice.onExhausted = { wyczerpane += 1 }
    }

    @After
    fun sprzataj() {
        voice.release()
        uplyw(200)
    }

    private fun start() {
        voice.start("pl-PL", listOf("kolano", "plecy")) { slowa, _ -> uslyszane.add(slowa) }
        uplyw(50)
    }

    private fun wynikBundle(vararg slowa: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(slowa.toList()))
    }

    @Test
    fun bladPrzejsciowy_naSprawdzonymSilniku_ponawiaBezZejscia() {
        start()
        cien(ostatni()).triggerOnResults(wynikBundle("kolano"))
        uplyw(300)
        assertEquals(listOf(listOf("kolano")), uslyszane)
        val opisPrzed = voice.silnikOpis

        cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        uplyw(600)

        assertEquals("sprawdzony silnik nie traci szczebla za jeden CLIENT", opisPrzed, voice.silnikOpis)
        assertEquals(0, wyczerpane)
        assertTrue("nasluch trwa", voice.listening)
        assertNotNull("po ponowieniu leci nowe zlecenie", cien(ostatni()).lastRecognizerIntent)
        assertTrue("diagnostyka pokazuje ponowienie", voice.diagnostyka().contains("ponowienie po 5"))
    }

    /** Ponowienia sa skonczone: seria bledow bez zadnego wyniku w koncu schodzi drabinka. */
    @Test
    fun seriaBledowPrzejsciowych_wKoncuSchodziDrabinka() {
        start()
        cien(ostatni()).triggerOnResults(wynikBundle("kolano"))
        uplyw(300)
        val opisPrzed = voice.silnikOpis

        repeat(4) {   // MAX_PRZEJSCIOWE = 3 ponowienia, czwarty blad schodzi
            cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_SERVER)
            uplyw(600)
        }

        assertTrue(
            "po wyczerpaniu ponowien drabinka schodzi albo konczy",
            voice.silnikOpis != opisPrzed || wyczerpane == 1,
        )
    }

    /** Kontrola ujemna: silnik, ktory NIC jeszcze nie oddal, za CLIENT schodzi od razu — jak dotad. */
    @Test
    fun bladNaNiesprawdzonymSilniku_schodziOdRazu() {
        start()
        val opisPrzed = voice.silnikOpis
        cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        uplyw(600)
        assertTrue(voice.silnikOpis != opisPrzed || wyczerpane == 1)
    }

    /**
     * EMULATOR (Android 16, mikrofon hosta): szum cichego pokoju daje 1–2 dB, a prog "slyszal mowe"
     * lezal na -1,5 dB. Kazdy pusty cykl liczyl sie wiec jako mowa bez rozpoznania i sprawny
     * silnik on-device tracil szczebel po dwoch cyklach, zanim ktokolwiek sie odezwal.
     */
    @Test
    fun szumOtoczenia_nieLiczySieJakoMowa() {
        start()
        val opisPrzed = voice.silnikOpis
        repeat(3) {
            cien(ostatni()).triggerOnRmsChanged(1.5f)   // szum, nie mowa
            uplyw(10)
            cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
            uplyw(300)
        }
        assertEquals("trzy ciche cykle to jeszcze nie awaria silnika", opisPrzed, voice.silnikOpis)
        assertEquals(0, wyczerpane)
    }

    /** Kontrola dodatnia: prawdziwa mowa (8 dB) bez rozpoznania schodzi drabinka po dwoch cyklach. */
    @Test
    fun mowaBezRozpoznania_schodziPoDwochCyklach() {
        start()
        val opisPrzed = voice.silnikOpis
        repeat(2) {
            cien(ostatni()).triggerOnRmsChanged(8f)
            uplyw(10)
            cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
            uplyw(300)
        }
        assertTrue(voice.silnikOpis != opisPrzed || wyczerpane == 1)
    }

    /** Wynik kasuje licznik ponowien — dzialajacy silnik ma je zawsze od nowa. */
    @Test
    fun wynikMiedzyBledami_zerujeLicznikPonowien() {
        start()
        val opisPrzed = voice.silnikOpis
        repeat(3) {
            cien(ostatni()).triggerOnResults(wynikBundle("kolano"))
            uplyw(300)
            cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
            uplyw(600)
            cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
            uplyw(600)
        }
        assertEquals("dwa bledy miedzy wynikami nigdy nie wyczerpuja ponowien", opisPrzed, voice.silnikOpis)
        assertEquals(0, wyczerpane)
        assertTrue(voice.listening)
    }
}
