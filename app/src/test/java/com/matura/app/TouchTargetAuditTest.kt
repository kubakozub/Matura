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
import com.matura.app.ui.Str
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NIEZALEZNA WERYFIKACJA zarzutu o cele dotykowe.
 * Mierzy PROSTOKAT KLIKALNY (hasClickAction) w dp, nie w px — bez recznego
 * przeliczania przez gestosc, wiec bez ryzyka bledu konwersji.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TouchTargetAuditTest {

    @get:Rule
    val rule = createComposeRule()

    private fun dump(tag: String) {
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        println("##### $tag: klikalnych wezlow = ${nodes.size}")
        var badBoth = 0
        var badOne = 0
        var ok = 0
        nodes.forEachIndexed { i, _ ->
            val n: SemanticsNodeInteraction = rule.onAllNodes(hasClickAction())[i]
            val b = n.getUnclippedBoundsInRoot()
            val w = b.width.value
            val h = b.height.value
            val label = try {
                n.fetchSemanticsNode().config.toString().take(90).replace("\n", " ")
            } catch (e: Throwable) { "?" }
            val verdict = when {
                w < 48f && h < 48f -> { badBoth++; "OBIE<48" }
                w < 48f || h < 48f -> { badOne++; "JEDNA<48" }
                else -> { ok++; "OK" }
            }
            println("  [$i] ${"%.1f".format(w)} x ${"%.1f".format(h)} dp  $verdict   $label")
        }
        println("##### $tag PODSUMOWANIE: OK=$ok  jedna-os<48=$badOne  obie-osie<48=$badBoth")
    }

    @Test
    fun keyboard_touchTargets() {
        rule.setContent {
            GameKeyboard(
                onChar = {}, onBackspace = {}, onEnter = {},
                onSpace = {}, onLeft = {}, onRight = {},
            )
        }
        dump("KLAWIATURA")
    }

    @Test
    fun options_touchTargets() {
        rule.setContent {
            OptionsScreen(
                str = Str(UiLanguage.PL),
                options = Options(),
                onBack = {}, onChange = {},
            )
        }
        dump("OPCJE")
    }

    @Test
    fun menu_touchTargets() {
        rule.setContent {
            MenuScreen(
                str = Str(UiLanguage.PL),
                profile = Profile(name = "Ala"),
                onDaily = {}, onHardWords = {}, onPlay = {}, onSets = {}, onOptions = {},
                onAchievements = {}, onScores = {}, onExit = {},
            )
        }
        dump("MENU")
    }
}
