package com.matura.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
// `onAllNodes` to METODA reguly testowej (SemanticsNodeInteractionsProvider), nie funkcja
// najwyzszego poziomu — import jej nie znajdzie. `width`/`height` to z kolei rozszerzenia
// DpRect z pakietu ui.unit, a nie z ui.test. Bez tych dwoch poprawek plik sie nie kompilowal
// i blokowal CALY build release.
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.UiLanguage
import com.matura.app.ui.GameKeyboard
import com.matura.app.ui.MenuScreen
import com.matura.app.ui.OptionsScreen
import com.matura.app.ui.PrivacyScreen
import com.matura.app.ui.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BRAMKA: jak duzy jest prostokat, w ktory uczen ma trafic palcem.
 *
 * Mierzy PROSTOKAT KLIKALNY (hasClickAction) w dp, nie w px — bez recznego przeliczania
 * przez gestosc, wiec bez ryzyka bledu konwersji.
 *
 * Do wydania 0.9.18 ten plik tylko DRUKOWAL wynik: wydrukowal "OPCJE: OK=0 z 19, najmniejszy
 * 31 x 28 dp" i nikogo nie zatrzymal, bo nie mial ani jednej asercji. Teraz kazdy ekran ma
 * prog albo jawny, opisany limit przejsciowy — inaczej liczby po cichu rosna dalej.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TouchTargetAuditTest {

    private companion object {
        /** Prog z wytycznych dostepnosci (Material, WCAG 2.5.5); mniej wiecej opuszek palca. */
        const val PROG = 48f

        /**
         * Tolerancja porownania.
         *
         * Zmierzone: chip o dokladnie 48 dp w przewijanym poziomo rzedzie wraca z pomiaru jako
         * 47,99999 (px -> dp idzie przez Float), wiec goly warunek `< 48f` zglaszal jako za maly
         * cel, ktory ma dokladnie tyle, ile trzeba. 0,05 dp to mniej niz jeden piksel na
         * najgestszym ekranie, wiec zadnego realnego niedomiaru nie przepusci.
         */
        const val TOLERANCJA = 0.05f

        /**
         * Klawiatura gry jest jedynym miejscem, gdzie 48 dp jest niewykonalne: rzad ma dziesiec
         * klawiszy, a ekran 411 dp szerokosci, wiec na klawisz wypada najwyzej 41 dp. Gboard na
         * tym samym ekranie ma klawisze ~38 dp i nikt nie uwaza go za niedostepny. Wysokosc 44 dp
         * (46 dp slotu minus 1 dp luzu z kazdej strony) i 54 dp dolnego rzedu to wartosci
         * dostrojone na urzadzeniu — w dolny rzad testerzy najczesciej nie trafiali, dlatego jest
         * wyzszy. Prog pilnuje wiec, zeby klawisze nie zrobily sie MNIEJSZE niz dzis.
         */
        const val KLAWISZ_MIN_SZEROKOSC = 37f
        const val KLAWISZ_MIN_WYSOKOSC = 44f
    }

    @get:Rule
    val rule = createComposeRule()

    private data class Cel(val nr: Int, val szerokosc: Float, val wysokosc: Float, val opis: String) {
        override fun toString() = "[$nr] ${"%.1f".format(szerokosc)} x ${"%.1f".format(wysokosc)} dp $opis"
    }

    private fun zaMaly(wymiar: Float, prog: Float = PROG) = wymiar < prog - TOLERANCJA

    private fun zmierz(tag: String): List<Cel> {
        val liczba = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val cele = (0 until liczba).map { i ->
            val n: SemanticsNodeInteraction = rule.onAllNodes(hasClickAction())[i]
            val b = n.getUnclippedBoundsInRoot()
            val opis = try {
                n.fetchSemanticsNode().config.toString().take(90).replace("\n", " ")
            } catch (e: Throwable) {
                "?"
            }
            Cel(i, b.width.value, b.height.value, opis)
        }
        println("##### $tag: klikalnych wezlow = ${cele.size}")
        cele.forEach { println("  $it") }
        return cele
    }

    /**
     * @param limitJednaOs ile celow wolno jeszcze miec ponizej progu w JEDNEJ osi. Limit nie
     *   jest ustepstwem "bo tak wyszlo": to cele z Common.kt (przycisk WSTECZ 74,3 x 37,3 dp
     *   i GhostButton 47,3 dp wysokosci), czyli z pliku poza zakresem tej poprawki. Kazdy nowy
     *   maly cel podnosi te liczbe i test pekne.
     */
    private fun wymagajProgu(tag: String, limitJednaOs: Int) {
        val cele = zmierz(tag)
        val obieOsie = cele.filter { zaMaly(it.szerokosc) && zaMaly(it.wysokosc) }
        val jednaOs = cele.filter { zaMaly(it.szerokosc) != zaMaly(it.wysokosc) }

        assertEquals(
            "$tag: cele mniejsze niz $PROG dp w OBU osiach: $obieOsie",
            emptyList<Cel>(),
            obieOsie,
        )
        assertTrue(
            "$tag: celow ponizej $PROG dp w jednej osi jest ${jednaOs.size}, a limit to " +
                "$limitJednaOs. Jesli przybyl nowy maly cel — powieksz go, a nie limit. " +
                "Jesli ubyl — obniz limit, zeby bramka dalej cos znaczyla. $jednaOs",
            jednaOs.size <= limitJednaOs,
        )
    }

    @Test
    fun menu_ma_pelne_cele() {
        rule.setContent {
            MenuScreen(
                str = Str(UiLanguage.PL),
                profile = Profile(name = "Ala"),
                onDaily = {}, onHardWords = {}, onPlay = {}, onSets = {}, onOptions = {},
                onAchievements = {}, onScores = {}, onExit = {},
            )
        }
        // Menu glowne jest jedynym ekranem bez przycisku WSTECZ, wiec przechodzi bez ustepstw.
        wymagajProgu("MENU", limitJednaOs = 0)
    }

    @Test
    fun opcje_maja_pelne_cele() {
        rule.setContent {
            OptionsScreen(
                str = Str(UiLanguage.PL),
                options = Options(),
                onBack = {}, onChange = {},
            )
        }
        // 1 x WSTECZ + 3 x GhostButton (prywatnosc i dwa narzedzia diagnostyczne z buildu debug).
        wymagajProgu("OPCJE", limitJednaOs = 4)
    }

    @Test
    fun trudne_slowa_maja_pelne_cele() {
        rule.setContent { A11yEkrany.TrudneSlowa() }
        // 1 x WSTECZ + 3 x GhostButton CWICZ (po jednym na karte slowa).
        wymagajProgu("TRUDNE SLOWA", limitJednaOs = 4)
    }

    @Test
    fun dzisiejsza_sesja_ma_pelne_cele() {
        rule.setContent { A11yEkrany.DzisiejszaSesja() }
        // 1 x WSTECZ + 1 x GhostButton DALEJ.
        wymagajProgu("DZISIEJSZA SESJA", limitJednaOs = 2)
    }

    @Test
    fun prywatnosc_ma_pelne_cele() {
        rule.setContent { PrivacyScreen(str = Str(UiLanguage.PL), onBack = {}) }
        // 1 x WSTECZ + 1 x GhostButton "Otworz pelna wersje".
        wymagajProgu("PRYWATNOSC", limitJednaOs = 2)
    }

    @Test
    fun klawiatura_nie_moze_zmalec() {
        rule.setContent {
            GameKeyboard(
                onChar = {}, onBackspace = {}, onEnter = {},
                onSpace = {}, onLeft = {}, onRight = {},
            )
        }
        val cele = zmierz("KLAWIATURA")
        assertEquals("klawiatura ma miec 31 celow (26 liter + 5 funkcyjnych)", 31, cele.size)
        val zaMale = cele.filter {
            zaMaly(it.szerokosc, KLAWISZ_MIN_SZEROKOSC) || zaMaly(it.wysokosc, KLAWISZ_MIN_WYSOKOSC)
        }
        assertEquals(
            "KLAWIATURA: klawisze ponizej dostrojonego minimum " +
                "$KLAWISZ_MIN_SZEROKOSC x $KLAWISZ_MIN_WYSOKOSC dp: $zaMale",
            emptyList<Cel>(),
            zaMale,
        )
    }
}
