package com.matura.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import com.matura.app.data.BuiltInSets
import com.matura.app.model.Options
import com.matura.app.model.UiLanguage
import com.matura.app.ui.PlayScreen
import com.matura.app.ui.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Tryb glosowy aplikacji opiera sie na uslugach rozpoznawania mowy i na czesci telefonow
 * (potwierdzone na Samsungu uzytkownika) odmawia dzialania: silnik zglasza sie jako dostepny,
 * po czym odrzuca zadanie bledem ERROR_CLIENT — i tak konczy KAZDY z trzech szczebli fallbacku.
 *
 * Opcja "klawiatura telefonu" omija ten mechanizm CALKOWICIE: zamiast wbudowanej klawiatury
 * pikselowej (ktora mikrofonu nie ma) gra pokazuje zwykle pole tekstowe, a mikrofon pochodzi
 * z klawiatury systemowej uzytkownika. To nie jest rozpoznawanie mowy w naszej aplikacji,
 * tylko zwykle wprowadzanie tekstu — nie ma wiec jak zawiesc tak, jak zawodzi SpeechRecognizer.
 *
 * Tego NIE da sie sprawdzic na emulatorze: obrazy systemowe nie maja ani uslugi Samsunga,
 * ani dyktowania Google. Sprawdzalny jest natomiast warunek konieczny — czy po wlaczeniu opcji
 * gra faktycznie wystawia pole, ktore klawiatura systemowa moze wypelnic, i czy wpisany tekst
 * dociera do silnika gry. I to wlasnie tu mierzymy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SystemKeyboardTest {

    @get:Rule
    val rule = createComposeRule()

    private val zestaw = BuiltInSets.all().first { it.entries.size >= 5 }

    private fun pokaz(systemowa: Boolean) {
        // Ekran gry ma nieprzerwana petle (potworki ida co klatke), wiec Compose NIGDY
        // nie zglosi bezczynnosci i domyslne czekanie testu wisi 60 s. Zatrzymujemy zegar
        // testowy i sterujemy nim recznie — stan po zlozeniu drzewa jest tym, co badamy.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PlayScreen(
                set = zestaw,
                options = Options(systemKeyboard = systemowa, soundEnabled = false),
                str = Str(UiLanguage.PL),
                playerName = "Test",
                onExit = {},
                onFinished = {},
            )
        }
    }

    /** Warunek konieczny: z opcja WLACZONA istnieje pole, do ktorego IME moze pisac. */
    @Test
    fun zWlaczonaOpcja_jestPoleDlaKlawiaturySystemowej() {
        pokaz(systemowa = true)
        rule.mainClock.advanceTimeByFrame()
        val pola = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(
            "Z wlaczona opcja gra musi wystawic pole tekstowe — bez niego klawiatura " +
                "systemowa (a wiec i jej mikrofon) jest nieosiagalna. Znaleziono: ${pola.size}",
            pola.isNotEmpty(),
        )
    }

    /** Kontrola negatywna: bez opcji pola NIE MA — inaczej test powyzej niczego nie dowodzi. */
    @Test
    fun bezOpcji_niemaPolaTekstowego() {
        pokaz(systemowa = false)
        rule.mainClock.advanceTimeByFrame()
        val pola = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals(
            "Bez opcji gra ma uzywac wlasnej klawiatury pikselowej, wiec pola IME byc nie powinno",
            0, pola.size,
        )
    }

    /** Tekst wpisany przez klawiature systemowa musi DOTRZEC do silnika gry. */
    @Test
    fun tekstZKlawiaturySystemowej_docieraDoGry() {
        pokaz(systemowa = true)
        rule.mainClock.advanceTimeByFrame()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("test")
        rule.mainClock.advanceTimeByFrame()
        val widoczne = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            .flatMap { it.config.toString().let { c -> listOf(c) } }
            .joinToString(" ")
        assertTrue(
            "Wpisany tekst powinien byc widoczny w polu (a wiec i w stanie gry). Semantyka: " +
                widoczne.take(300),
            widoczne.contains("test", ignoreCase = true),
        )
    }
}
