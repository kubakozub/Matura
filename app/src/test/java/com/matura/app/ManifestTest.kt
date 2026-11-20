package com.matura.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Wpisy manifestu, ktorych brak NIE wywraca ani kompilacji, ani zadnego ekranu, tylko po cichu
 * zabiera cos uczniowi na czesci telefonow.
 *
 * Czytamy manifest SCALONY, czyli ten, ktory wchodzi do paczki (sciezke podaje wtyczka Androida
 * w test_config.properties, tak samo znajduje go Robolectric), a nie plik zrodlowy: biblioteki
 * dopisuja do manifestu wlasne wpisy i tylko wynik scalenia mowi, co dostaje telefon.
 *
 * Czego ten test NIE widzi: funkcji sprzetowych, ktore narzedzia sklepu DOPOWIADAJA sobie same
 * (uses-implied-feature). To pokazuje dopiero `aapt2 dump badging` na zbudowanej paczce. Tutaj
 * pilnujemy wpisow, ktore to dopowiadanie wylaczaja.
 */
class ManifestTest {

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val KONFIGURACJA = "com/android/tools/test_config.properties"
    }

    private val manifest: Element by lazy {
        val wlasciwosci = Properties()
        val strumien = javaClass.classLoader?.getResourceAsStream(KONFIGURACJA)
        assertNotNull("brak $KONFIGURACJA: wtyczka Androida nie podala sciezki manifestu", strumien)
        strumien!!.use { wlasciwosci.load(it) }
        val plik = File(wlasciwosci.getProperty("android_merged_manifest"))
        assertTrue("nie ma scalonego manifestu: ${plik.absolutePath}", plik.isFile)
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(plik)
            .documentElement
    }

    private fun Element.dzieci(nazwa: String): List<Element> {
        val wezly = childNodes
        return (0 until wezly.length).map { wezly.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == nazwa }
    }

    private fun Element.atrybut(nazwa: String): String = getAttributeNS(ANDROID, nazwa)

    private fun akcjeWidoczneDlaAplikacji(): List<String> =
        manifest.dzieci("queries")
            .flatMap { it.dzieci("intent") }
            .flatMap { it.dzieci("action") }
            .map { it.atrybut("name") }

    /** "true", "false" albo null, gdy wpisu nie ma wcale. */
    private fun wymaganaFunkcja(nazwa: String): String? =
        manifest.dzieci("uses-feature").firstOrNull { it.atrybut("name") == nazwa }?.atrybut("required")

    @Test
    fun `system pokazuje aplikacji silnik, ktory czyta na glos wlasne zestawy`() {
        // Nagrania sa tylko dla hasel wbudowanych; slowa z wlasnych zestawow czyta TextToSpeech.
        // Bez tego wpisu Android 11+ moze ukryc silnik przed aplikacja i glosnik milczy bez bledu.
        assertTrue(
            "w <queries> nie ma android.intent.action.TTS_SERVICE",
            "android.intent.action.TTS_SERVICE" in akcjeWidoczneDlaAplikacji(),
        )
    }

    @Test
    fun `system pokazuje aplikacji rozpoznawanie mowy`() {
        val akcje = akcjeWidoczneDlaAplikacji()
        assertTrue("android.speech.RecognitionService" in akcje)
        assertTrue("android.speech.action.RECOGNIZE_SPEECH" in akcje)
    }

    @Test
    fun `mikrofon i ekran pionowy nie sa sprzetem wymaganym`() {
        // Brak wpisu znaczy tu "wymagane": sklep sam dopowiada mikrofon z uprawnienia
        // RECORD_AUDIO, a pion z android:screenOrientation, i ukrywa aplikacje przed
        // urzadzeniami, ktore ich nie zglaszaja.
        for (funkcja in listOf("android.hardware.microphone", "android.hardware.screen.portrait")) {
            assertEquals("$funkcja ma byc zadeklarowane jako opcjonalne", "false", wymaganaFunkcja(funkcja))
        }
    }

    @Test
    fun `aplikacja jest zgloszona jako gra`() {
        // Od Androida 16 tylko gra zachowuje blokade pionu na ekranach od 600 dp.
        val aplikacja = manifest.dzieci("application").single()
        assertEquals("game", aplikacja.atrybut("appCategory"))
    }
}
