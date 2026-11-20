package com.matura.app

import com.matura.app.ui.RawMicTeksty
import com.matura.app.ui.RawWerdykt
import com.matura.app.ui.RawWynik
import com.matura.app.ui.nazwaZrodla
import com.matura.app.ui.opisRawWyniku
import com.matura.app.ui.rawDbfs
import com.matura.app.ui.rawDbfsOpis
import com.matura.app.ui.raportRaw
import com.matura.app.ui.werdyktRaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cala logika werdyktu chodzi na JVM — bez Androida, bez Robolectrica, bez telefonu. */
class RawMicTest_LogikaTest {

    private val t = RawMicTeksty(
        slyszy = "SLYSZY", sameZera = "SAME ZERA", brakRamek = "BRAK RAMEK", nieRuszyl = "NIE RUSZYL",
        brak = "BRAK", wyciszonyPrzezSystem = "WYCISZONY", cicho = "cicho", blad = "blad",
        brakInit = "brak init",
    )

    private fun w(
        nazwa: String = "MIC", init: Boolean = true, nagrywa: Boolean = true, ramki: Long = 32000,
        szczyt: Int = 0, zera: Long = 32000, silenced: Boolean? = false, blad: String? = null,
    ) = RawWynik(
        nazwa = nazwa, init = init, nagrywa = nagrywa, sampleRate = 16000, bufBajty = 4096,
        ramki = ramki, szczyt = szczyt, rmsDbfs = if (szczyt == 0) Float.NEGATIVE_INFINITY else -20f,
        zera = zera, wyciszonyPrzezSystem = silenced, wejscie = "BUILTIN_MIC #5", blad = blad,
        czasMs = 2000,
    )

    @Test fun `pelna skala to zero dBFS`() {
        assertEquals(0.0f, rawDbfs(32768.0 * 32768.0 * 10, 10), 0.05f)
    }

    @Test fun `same zera to minus nieskonczonosc, nie NaN`() {
        assertEquals(Float.NEGATIVE_INFINITY, rawDbfs(0.0, 32000))
        assertEquals("-∞", rawDbfsOpis(rawDbfs(0.0, 32000)))
    }

    @Test fun `brak ramek nie udaje pomiaru`() {
        assertEquals(Float.NEGATIVE_INFINITY, rawDbfs(0.0, 0))
    }

    @Test fun `jedna probka rozna od zera wystarczy, zeby mikrofon slyszal`() {
        assertEquals(RawWerdykt.SLYSZY, werdyktRaw(listOf(w(szczyt = 0), w(nazwa = "UNPROCESSED", szczyt = 3))))
    }

    @Test fun `ramki bez sygnalu to wyciszenie, nie cisza w pokoju`() {
        assertEquals(RawWerdykt.SAME_ZERA, werdyktRaw(listOf(w())))
    }

    @Test fun `strumien bez ramek to zatrzymany tor audio`() {
        assertEquals(RawWerdykt.BRAK_RAMEK, werdyktRaw(listOf(w(ramki = 0, zera = 0))))
    }

    @Test fun `brak inicjalizacji to osobny werdykt`() {
        assertEquals(
            RawWerdykt.NIE_RUSZYL,
            werdyktRaw(listOf(w(init = false, nagrywa = false, ramki = 0, zera = 0, blad = "state=UNINITIALIZED"))),
        )
    }

    @Test fun `pusty przebieg nie orzeka niczego`() {
        assertEquals(RawWerdykt.BRAK, werdyktRaw(emptyList()))
    }

    @Test fun `raport mowi ile prob i ze byl przerwany`() {
        val r = raportRaw("NAGLOWEK\n", listOf(w()), "WERDYKT", t, ile = 6, przerwany = true, powodPrzerwania = "ON_STOP")
        assertTrue(r.contains("próby: 1/6"))
        assertTrue(r.contains("PRZERWANY (ON_STOP)"))
        assertTrue(r.contains("WERDYKT"))
    }

    @Test fun `linia proby niesie liczby, po ktorych da sie orzekac`() {
        val linia = opisRawWyniku(w(szczyt = 1200, zera = 40, silenced = true), t)
        assertTrue(linia.contains("szczyt 1200"))
        assertTrue(linia.contains("zera 40/32000"))
        assertTrue(linia.contains("isClientSilenced=TAK"))
    }

    @Test fun `kody zrodel maja nazwy`() {
        assertEquals("VOICE_RECOGNITION", nazwaZrodla(6))
        assertEquals("UNPROCESSED", nazwaZrodla(9))
        assertEquals("VOICE_PERFORMANCE", nazwaZrodla(10))
    }
}
