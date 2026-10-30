package com.matura.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.UiLanguage
import com.matura.app.ui.GameKeyboard
import com.matura.app.ui.MenuScreen
import com.matura.app.ui.OptionsScreen
import com.matura.app.ui.PrivacyScreen
import com.matura.app.ui.Str
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BRAMKA: co da sie dotknac, to musi miec nazwe dla czytnika ekranu.
 *
 * Do wydania 0.9.18 ten plik byl SONDA — drukowal drzewo semantyki i nie mial ani jednej
 * asercji, wiec build swiecil na zielono, kiedy 6 z 8 przyciskow MENU GLOWNEGO nie mialo
 * zadnej etykiety (PixelText rysuje napis po Canvas i nie trafia do semantyki). Niewidomy
 * uczen slyszal osiem razy "przycisk" i metoda prob trafial w WYJSCIE, bo to ostatnia
 * pozycja listy. Sonda ten stan wydrukowala i nikogo nie zatrzymala — stad asercje.
 *
 * Aplikacja idzie do szkol publicznych, wiec bramka obejmuje TAKZE ekrany dodane po wersji
 * sklepowej 0.9.4 (prywatnosc, trudne slowa, dzisiejsza sesja), ktorych stara sonda nie
 * dotykala w ogole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class A11ySemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Wezly z akcja OnClick, ktore nie maja ANI tekstu, ANI opisu — czyli te, ktore
     * TalkBack odczyta jako samo "przycisk".
     *
     * Czytamy drzewo SCALONE (domyslne `useUnmergedTree = false`), bo tak samo czyta je
     * czytnik ekranu: etykieta dziecka (PixelText) jest nazwa klikalnego rodzica.
     */
    private fun bezEtykiety(): List<String> {
        val wezly = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes()
        return wezly.mapIndexedNotNull { i, n ->
            val tekst = n.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                .orEmpty()
            val opis = n.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                .orEmpty()
            if (tekst.isBlank() && opis.isBlank()) {
                "[$i] ${n.config.toString().take(120).replace("\n", " ")}"
            } else {
                null
            }
        }
    }

    private fun wymagajEtykiet(ekran: String) {
        val nieme = bezEtykiety()
        if (nieme.isNotEmpty()) {
            println("########## $ekran — WEZLY BEZ ETYKIETY ##########")
            nieme.forEach { println("  $it") }
            println(rule.onRoot().printToString(maxDepth = 100))
        }
        assertEquals(
            "$ekran: wezly klikalne bez nazwy dla czytnika ekranu -> $nieme",
            emptyList<String>(),
            nieme,
        )
    }

    @Test
    fun menu_kazdy_przycisk_ma_nazwe() {
        rule.setContent {
            MenuScreen(
                str = Str(UiLanguage.PL),
                profile = Profile(name = "Ala"),
                onDaily = {}, onHardWords = {}, onPlay = {}, onSets = {}, onOptions = {},
                onAchievements = {}, onScores = {}, onExit = {},
            )
        }
        wymagajEtykiet("MENU")
    }

    @Test
    fun opcje_kazdy_przelacznik_ma_nazwe() {
        rule.setContent {
            OptionsScreen(
                str = Str(UiLanguage.PL),
                options = Options(),
                onBack = {}, onChange = {},
            )
        }
        wymagajEtykiet("OPCJE")
    }

    @Test
    fun prywatnosc_kazdy_przycisk_ma_nazwe() {
        rule.setContent {
            PrivacyScreen(str = Str(UiLanguage.PL), onBack = {})
        }
        wymagajEtykiet("PRYWATNOSC")
    }

    @Test
    fun trudne_slowa_kazdy_przycisk_ma_nazwe() {
        rule.setContent { A11yEkrany.TrudneSlowa() }
        wymagajEtykiet("TRUDNE SLOWA")
    }

    @Test
    fun dzisiejsza_sesja_kazdy_przycisk_ma_nazwe() {
        rule.setContent { A11yEkrany.DzisiejszaSesja() }
        wymagajEtykiet("DZISIEJSZA SESJA")
    }

    /**
     * Klawiatura gry to 31 celow dotyku i jedyna droga przez tryb KLASYCZNY. Litery biora
     * nazwe z PixelText, ale backspace, enter, spacja i obie strzalki maja glif wypalony
     * w sprite'cie (PixelImage ustawia contentDescription = null), wiec nazwe musza dostac
     * wprost — patrz OpisyKlawiszy w Keyboard.kt.
     */
    @Test
    fun klawiatura_kazdy_klawisz_ma_nazwe() {
        rule.setContent { GameKeyboard({}, {}, {}, {}, {}, {}) }
        wymagajEtykiet("KLAWIATURA")
    }
}
