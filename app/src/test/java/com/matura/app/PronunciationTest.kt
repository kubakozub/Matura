package com.matura.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matura.app.audio.Pronunciation
import com.matura.app.data.BuiltInSets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Weryfikuje warstwe wymowy na PRAWDZIWYM pakiecie audio z assets.
 * Nie sprawdza samego dzwieku (Robolectric go nie odtworzy), tylko to,
 * co realnie moze sie zepsuc: czy indeks sie wczytuje, czy kazde wbudowane
 * haslo ma nagranie i czy sciezki brzegowe nie rzucaja wyjatkiem.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PronunciationTest {

    private val wymowa = Pronunciation(ApplicationProvider.getApplicationContext())

    @After fun sprzatanie() = wymowa.release()

    @Test fun indeks_audio_jest_wczytany_z_assets() {
        assertTrue("pakiet audio nie zostal wczytany z assets", wymowa.hasClip("accommodation"))
    }

    @Test fun poprawiona_pisownia_ma_nagranie_a_bledna_nie_istnieje() {
        // Regresja: do 31.08.2026 w danych bylo bledne "accomodation" (jedno "m").
        assertTrue(wymowa.hasClip("accommodation"))
        assertFalse(wymowa.hasClip("accomodation"))
    }

    @Test fun KAZDE_wbudowane_haslo_ma_nagranie() {
        val bez = BuiltInSets.all()
            .flatMap { it.entries }
            .map { it.term }
            .distinct()
            .filterNot { wymowa.hasClip(it) }
        assertEquals("hasla bez nagrania: ${bez.take(20)}", 0, bez.size)
    }

    @Test fun wielkosc_liter_nie_ma_znaczenia() {
        assertTrue(wymowa.hasClip("ACCOMMODATION"))
        assertTrue(wymowa.hasClip("  Accommodation  "))
    }

    @Test fun sciezki_brzegowe_nie_wywracaja_aplikacji() {
        assertFalse(wymowa.hasClip("zzz-nie-ma-takiego-slowa"))
        wymowa.play("zzz-nie-ma-takiego-slowa", enabled = true)  // fallback na systemowy TTS
        wymowa.play("", enabled = true)
        wymowa.play("accommodation", enabled = false)
        assertTrue(true)
    }
}
