package com.matura.app

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.matura.app.ui.PowodStop
import com.matura.app.ui.VoiceInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Podpis pod paskiem glosu po wylaczeniu nasluchu.
 *
 * Komunikat z trwajacej sesji ("probuje innego silnika mowy...", podpowiedz o cichym
 * mikrofonie) zostawal po stop() na ekranie az do nastepnego startu: pasek byl zgaszony,
 * a podpis dalej twierdzil, ze cos sie dzieje. Komunikat, ktory jest WYNIKIEM (koniec
 * drabinki, brak zgody, zajety mikrofon), ma zostac, bo to z niego uczen dowiaduje sie,
 * co dalej, i pozniejsza pauza nie moze mu go zabrac.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceStopStatusTest {

    private lateinit var voice: VoiceInput
    private var wyczerpane = 0
    private var brakZgody = 0

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
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        voice = VoiceInput(ctx)
        voice.tekstInnySilnik = "INNY SILNIK"
        voice.tekstCiszy = "MIKROFON MILCZY"
        voice.tekstBrakZgody = "BRAK ZGODY"
        voice.onExhausted = { wyczerpane += 1 }
        voice.onBrakZgody = { brakZgody += 1 }
    }

    /** Kazdy test oddaje mikrofon i NISZCZY rozpoznawacz: patrz komentarz w VoiceDrabinkaTest. */
    @After
    fun sprzataj() {
        voice.release()
        uplyw(200)
    }

    private fun start() {
        voice.start("pl-PL", listOf("kolano", "plecy", "głowa")) { _, _ -> }
        uplyw(50)
    }

    private fun blad(kod: Int, potem: Long = 300) {
        cien(ostatni()).triggerOnError(kod)
        uplyw(potem)
    }

    /** Straznik dzwieku schodzi na inny silnik i mowi o tym uczniowi; nasluch trwa. */
    private fun zejscieNaInnySilnik() {
        start()
        cien(ostatni()).triggerOnReadyForSpeech(Bundle())
        uplyw(3_000)
        assertEquals("INNY SILNIK", voice.status)
        assertTrue("to nadal trwajacy nasluch", voice.listening)
    }

    private fun wylaczenieKasujePodpis(powod: PowodStop) {
        zejscieNaInnySilnik()
        voice.stop(powod)
        assertFalse(voice.listening)
        assertEquals("po wylaczeniu (" + powod + ") nie ma juz czego opisywac", "", voice.status)
        // i nic spoznionego nie przywraca go po chwili
        uplyw(3_000)
        assertEquals("", voice.status)
    }

    @Test
    fun dotknieciePaska_kasujePodpisTrwajacejSesji() = wylaczenieKasujePodpis(PowodStop.DOTKNIECIE)

    @Test
    fun pauza_kasujePodpisTrwajacejSesji() = wylaczenieKasujePodpis(PowodStop.PAUZA)

    @Test
    fun zejscieWTlo_kasujePodpisTrwajacejSesji() = wylaczenieKasujePodpis(PowodStop.TLO)

    @Test
    fun wyjscieZEkranu_kasujePodpisTrwajacejSesji() {
        zejscieNaInnySilnik()
        voice.release()
        assertEquals("", voice.status)
    }

    /** Podpowiedz o cichym mikrofonie tez opisuje sesje, ktora trwa. */
    @Test
    fun podpowiedzOCichymMikrofonie_znikaPoWylaczeniu() {
        start()
        blad(SpeechRecognizer.ERROR_AUDIO, potem = 600)
        blad(SpeechRecognizer.ERROR_AUDIO)
        assertEquals("MIKROFON MILCZY", voice.status)
        assertTrue(voice.listening)

        voice.stop(PowodStop.DOTKNIECIE)
        assertEquals("", voice.status)
    }

    /**
     * Koniec drabinki: komunikat ustawia sie PO stop(DRABINKA), a ekran zaraz potem zwykle
     * wstrzymuje gre. Pauza po wygaszonym nasluchu nie moze zabrac uczniowi wyjasnienia.
     */
    @Test
    fun koniecDrabinki_zostawiaKomunikat_takzePoPauzie() {
        start()
        repeat(3) { blad(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
        assertEquals("drabinka ma sie skonczyc", 1, wyczerpane)
        assertFalse(voice.listening)
        val komunikat = voice.status
        assertTrue("komunikat ma nazwac przyczyne", komunikat.isNotEmpty())

        for (powod in listOf(PowodStop.PAUZA, PowodStop.TLO, PowodStop.DOTKNIECIE)) {
            voice.stop(powod)
            assertEquals("stop(" + powod + ") po koncu drabinki", komunikat, voice.status)
        }
    }

    @Test
    fun brakZgody_zostawiaKomunikat() {
        val aplikacja = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(aplikacja).denyPermissions(Manifest.permission.RECORD_AUDIO)
        start()
        blad(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals("brak zgody to sprawa ekranu", 1, brakZgody)
        assertFalse(voice.listening)
        assertTrue("komunikat: " + voice.status, voice.status.startsWith("BRAK ZGODY"))

        voice.stop(PowodStop.PAUZA)
        assertTrue("pauza po odmowie: " + voice.status, voice.status.startsWith("BRAK ZGODY"))
    }

    @Test
    fun zajetyMikrofon_zostawiaPodpowiedz() {
        start()
        blad(SpeechRecognizer.ERROR_AUDIO, potem = 600)
        repeat(5) { blad(SpeechRecognizer.ERROR_AUDIO, potem = 2_000) }
        assertFalse("po serii bledow nagrywania nasluch gasnie", voice.listening)
        assertEquals("MIKROFON MILCZY", voice.status)

        voice.stop(PowodStop.TLO)
        assertEquals("MIKROFON MILCZY", voice.status)
    }

    /** Wywolanie bez podanego powodu zachowuje sie tak jak przed zmiana. */
    @Test
    fun stopBezPowodu_zostawiaPodpis() {
        zejscieNaInnySilnik()
        voice.stop()
        assertEquals("INNY SILNIK", voice.status)
    }
}
