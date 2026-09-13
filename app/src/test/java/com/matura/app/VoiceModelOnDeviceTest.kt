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
 * Przebieg na cieniu Robolectrica z usluga on-device "dostepna": cien przyjmuje pytanie o model
 * i oddaje odpowiedz, ktora test sam podstawia — dokladnie tak, jak robi to telefon.
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

    private fun odpowiedzOModelu(r: SpeechRecognizer, zainstalowane: List<String>, obslugiwane: List<String> = zainstalowane) {
        cien(r).triggerSupportResult(wsparcie(zainstalowane, obslugiwane))
        // Sonda w tle odczekuje ODSTEP_WIAZANIA_MS (400 ms) przed nastepnym wiazaniem tej samej
        // uslugi — wyscig odlaczenia na Androidzie 15+ — wiec czekamy z zapasem.
        uplyw(500)
    }

    private fun wynikBundle(vararg slowa: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(slowa.toList()))
    }

    /** Warunek wstepny: start z usluga on-device NAJPIERW pyta o model, a nie sklada zlecenia. */
    @Test
    fun start_naOnDevice_najpierwPytaOModel() {
        start()
        val sonda = ostatni()
        assertNull("przed odpowiedzia o modelu nie wolno startowac nasluchu", cien(sonda).lastRecognizerIntent)
        assertTrue("nasluch jest juz wlaczony (pasek: uruchamiam mikrofon)", voice.listening)
        assertEquals(0, wyczerpane)
    }

    /** Model JEST: on-device, offline, ze slownikiem zestawu — najlepsza droga, gdy dziala. */
    @Test
    fun modelJest_startOnDevice_offline_zeSlownikiem() {
        start()
        val sonda = ostatni()
        odpowiedzOModelu(sonda, listOf("pl-PL", "en-US"))

        val r = ostatni()
        val i = requireNotNull(cien(r).lastRecognizerIntent) { "po potwierdzeniu modelu ma poleciec zlecenie" }
        assertEquals(VoiceMode.ON_DEVICE, voice.mode)
        assertEquals("pl-PL", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue("on-device z modelem prosi o offline", i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue("i niesie slownik zestawu", i.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)?.isNotEmpty() == true)
        assertTrue(voice.diagnostyka().contains("pl-PL zainstalowany"))
    }

    /**
     * SEDNO (zgloszenie z Motoroli). Usluga on-device jest, modelu pl-PL NIE MA: nasluch idzie
     * od razu do silnika domyslnego — bez wymuszonego offline (nie ma czym rozpoznawac offline)
     * i bez slownika. Do 0.9.15 gra startowala tu on-device i czekala na straznika.
     */
    @Test
    fun modelBrak_startOdSilnikaDomyslnego_bezOfflineIbezSlownika() {
        start()
        val sonda = ostatni()
        odpowiedzOModelu(sonda, zainstalowane = emptyList(), obslugiwane = listOf("pl-PL"))

        val r = ostatni()
        assertNotSame("sonda on-device nie moze byc rozpoznawaczem sesji", sonda, r)
        assertTrue("sonda zostaje zwolniona", cien(sonda).isDestroyed)
        val i = requireNotNull(cien(r).lastRecognizerIntent)
        assertEquals(VoiceMode.ONLINE, voice.mode)
        assertFalse("bez modelu offline nie ma sensu", i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue("slownik tylko dla on-device", i.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS).isNullOrEmpty())
        assertEquals("to nie awaria — ekran nie dostaje sygnalu", 0, wyczerpane)
        assertTrue(voice.listening)
        assertTrue(voice.diagnostyka().contains("pl-PL brak"))
    }

    /** Usluga, ktora na pytanie o model nie odpowiada wcale, nie moze trzymac paska w nieskonczonosc. */
    @Test
    fun brakOdpowiedziOModel_poLimicieStartujeSieciowo() {
        start()
        val sonda = ostatni()
        uplyw(1_000)
        assertNull("w limicie jeszcze czekamy", cien(sonda).lastRecognizerIntent)

        uplyw(1_000)   // MODEL_MS = 1,5 s

        val r = ostatni()
        assertNotSame(sonda, r)
        assertNotNull("po limicie nasluch ma ruszyc", cien(r).lastRecognizerIntent)
        assertEquals("bez odpowiedzi nie ufamy on-device", VoiceMode.ONLINE, voice.mode)
        assertEquals(0, wyczerpane)
    }

    /** Blad z checkRecognitionSupport to tez odpowiedz: nie on-device. */
    @Test
    fun bladSprawdzeniaModelu_startujeSieciowo() {
        start()
        val sonda = ostatni()
        cien(sonda).triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        uplyw(100)
        assertEquals(VoiceMode.ONLINE, voice.mode)
        assertNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals(0, wyczerpane)
    }

    /**
     * Sonda w tle (refresh) pyta o oba tagi gry, zeby dotkniecie paska nie czekalo: po odpowiedzi
     * start() sklada zlecenie od razu, bez drugiego pytania.
     */
    @Test
    fun sondaWTle_odpowiadaZaOba_tagi_aStartNieCzeka() {
        voice.refresh()
        uplyw(50)
        val sondaPl = ostatni()
        assertNull("sonda w tle nie startuje nasluchu", cien(sondaPl).lastRecognizerIntent)
        odpowiedzOModelu(sondaPl, listOf("pl-PL"))
        val sondaEn = ostatni()
        assertNotSame("po polskim pyta o angielski", sondaPl, sondaEn)
        odpowiedzOModelu(sondaEn, listOf("pl-PL"))   // angielskiego nie ma
        assertFalse(voice.listening)

        start()

        val r = ostatni()
        val i = requireNotNull(cien(r).lastRecognizerIntent) { "z odpowiedzia w kieszeni start nie czeka" }
        assertEquals(VoiceMode.ON_DEVICE, voice.mode)
        assertTrue(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(voice.diagnostyka().contains("en-US brak"))
    }

    /** Zmiana jezyka w rundzie na tag bez modelu: silnik sieciowy, a nie milczacy on-device. */
    @Test
    fun przelaczenieNaJezykBezModelu_idzieSieciowo() {
        voice.refresh()
        uplyw(50)
        odpowiedzOModelu(ostatni(), listOf("pl-PL"))
        odpowiedzOModelu(ostatni(), listOf("pl-PL"))
        start()
        assertEquals(VoiceMode.ON_DEVICE, voice.mode)

        uplyw(ODSTEP_JEZYKA_MS + 100)
        assertTrue(voice.przelaczJezyk("en-US"))
        uplyw(300)

        assertEquals(VoiceMode.ONLINE, voice.mode)
        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals("en-US", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(voice.listening)
    }

    /**
     * Po zejsciu z on-device (kod 13 mimo "modelu") nowa sesja NIE wraca na slepo na on-device:
     * silnik, ktory zawiodl, nie dostaje pierwszenstwa tylko dlatego, ze uczen wlaczyl pasek
     * ponownie. Slownik zestawu znika razem z on-device — na silniku domyslnym nie jest wysylany.
     */
    @Test
    fun poZejsciuZOnDevice_noweWlaczenie_zostajeNaSilnikuDomyslnym() {
        start()
        odpowiedzOModelu(ostatni(), listOf("pl-PL"))
        val onDevice = ostatni()
        cien(onDevice).triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        uplyw(300)
        assertEquals("kod 13 na on-device schodzi do silnika domyslnego", VoiceMode.ONLINE, voice.mode)
        assertEquals(0, wyczerpane)

        voice.stop()
        uplyw(100)
        start()

        val i = requireNotNull(cien(ostatni()).lastRecognizerIntent)
        assertEquals(VoiceMode.ONLINE, voice.mode)
        assertFalse(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(i.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS).isNullOrEmpty())
    }

    /**
     * Po wyczerpaniu drabinki i ostygnieciu blokady nowa sesja wraca do trybu z sondy (on-device),
     * a nie zostaje na ostatnim szczeblu. Emulator: on-device porzucony raz nie wracal do konca
     * ekranu, choc byl jedynym silnikiem z pobranym modelem.
     */
    @Test
    fun poWyczerpaniuIOstygnieciu_noweWlaczenieWracaDoOnDevice() {
        start()
        odpowiedzOModelu(ostatni(), listOf("pl-PL"))
        assertEquals(VoiceMode.ON_DEVICE, voice.mode)
        repeat(6) { if (wyczerpane == 0) { cien(ostatni()).triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE); uplyw(300) } }
        assertEquals("drabinka ma sie wyczerpac", 1, wyczerpane)
        assertEquals("ostatni szczebel byl sieciowy", VoiceMode.ONLINE, voice.mode)

        uplyw(26_000)   // BLOKADA_MS = 25 s
        voice.refresh()

        assertEquals("po ostygnieciu wracamy do trybu z sondy", VoiceMode.ON_DEVICE, voice.mode)
        assertTrue("i drabinka znow jest do dyspozycji", voice.maSilnik)
    }

    /** stop() w trakcie pytania o model: odpowiedz, ktora przyjdzie pozniej, nie wskrzesza nasluchu. */
    @Test
    fun stopWTrakcieSprawdzania_odpowiedzNieStartujeNasluchu() {
        start()
        val sonda = ostatni()
        voice.stop()
        odpowiedzOModelu(sonda, listOf("pl-PL"))
        uplyw(2_000)
        assertFalse(voice.listening)
        assertNull("po stop() nikt nie sklada zlecenia", cien(ostatni()).lastRecognizerIntent)
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
