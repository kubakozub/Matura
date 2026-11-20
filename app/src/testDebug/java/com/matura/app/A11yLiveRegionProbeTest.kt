package com.matura.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import com.matura.app.model.UiLanguage
import com.matura.app.ui.SetEditorScreen
import com.matura.app.ui.Str
import com.matura.app.ui.VoiceBar
import com.matura.app.ui.VoiceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edytor zestawu i pasek glosu — dwa miejsca, ktorych bramka z A11ySemanticsTest jeszcze nie
 * obejmuje w calosci, wiec maja tu wlasne, jawnie opisane limity.
 *
 * Do wydania 0.9.18 byly to sondy bez ani jednej asercji: drukowaly "21 z 22 wezlow bez
 * etykiety" i "wezlow z LiveRegion: 0", a build swiecil na zielono.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class A11yLiveRegionProbeTest {

    private companion object {
        /**
         * Ile wezlow klikalnych edytora wolno jeszcze miec bez nazwy.
         *
         * 18 = 3 pola tekstowe naglowka (tytul zestawu, jezyk hasel, jezyk tlumaczen) + piec
         * wierszy po trzy wezly (haslo, tlumaczenie, kasowanie wiersza). Pole tekstowe bierze
         * nazwe ze swojej TRESCI, wiec puste pole nowego zestawu nie ma jej skad wziac, a
         * kosze przy wierszach to same ikony. Jedno i drugie wymaga nowych napisow w Strings.kt
         * ("Haslo w wierszu %s", "Usun wiersz %s"), czyli pliku, ktory w tym etapie jest w
         * innym zakresie prac — dlatego limit, a nie zero. Przyciski akcji (WSTECZ, DODAJ
         * WIERSZ, ZAPISZ) nazwy juz maja i pilnuje ich osobna asercja ponizej.
         */
        const val LIMIT_BEZ_NAZWY_W_EDYTORZE = 18
    }

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun edytor_zestawu_ma_nazwane_przyciski_akcji() {
        rule.setContent {
            SetEditorScreen(str = Str(UiLanguage.PL), existing = null, onBack = {}, onSave = {})
        }

        val klikalne = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ).fetchSemanticsNodes()

        val nazwy = klikalne.map { n ->
            val tekst = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            val opis = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            (tekst + " " + opis).trim()
        }
        val bezNazwy = nazwy.count { it.isBlank() }
        println("########## EDYTOR ##########")
        println("KLIKALNYCH WEZLOW: ${klikalne.size}, bez nazwy: $bezNazwy")
        if (bezNazwy > LIMIT_BEZ_NAZWY_W_EDYTORZE) println(rule.onRoot().printToString(maxDepth = 100))

        val str = Str(UiLanguage.PL)
        listOf(str.back, str.addRow, str.save).forEach { oczekiwana ->
            assertTrue(
                "edytor zestawu: przycisk \"$oczekiwana\" nie ma nazwy dla czytnika ekranu; " +
                    "znalezione nazwy: $nazwy",
                nazwy.any { it.equals(oczekiwana, ignoreCase = true) },
            )
        }
        assertTrue(
            "edytor zestawu: wezlow klikalnych bez nazwy jest $bezNazwy, a limit to " +
                "$LIMIT_BEZ_NAZWY_W_EDYTORZE — patrz komentarz przy limicie",
            bezNazwy <= LIMIT_BEZ_NAZWY_W_EDYTORZE,
        )
    }

    @Test
    fun pasek_glosu_pokazuje_komunikat_bledu_w_semantyce() {
        val komunikat = "Bez zgody na mikrofon tryb glosowy nie zadziala."
        rule.setContent {
            VoiceBar(
                listening = false,
                mode = VoiceMode.ONLINE,
                message = komunikat,
                str = Str(UiLanguage.PL),
                onToggle = {},
            )
        }

        val live = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        ).fetchSemanticsNodes()
        // Zero live regionow znaczy, ze komunikat nie jest OGLASZANY sam z siebie — uczen
        // uslyszy go dopiero, gdy na niego najedzie. Tego nie zmieniamy przed wydaniem (pasek
        // glosu jest w innym zakresie prac), ale minimum jest takie: komunikat MUSI byc w
        // semantyce, a nie tylko na obrazku. Pasek rysuje go systemowym Text, wiec jest.
        println("WEZLOW Z LiveRegion: ${live.size}  (komunikat bez ogloszenia, patrz komentarz)")

        val znalezione = rule.onAllNodes(hasText(komunikat, substring = true)).fetchSemanticsNodes()
        assertEquals(
            "pasek glosu: komunikat \"$komunikat\" nie istnieje w semantyce, wiec czytnik " +
                "ekranu nie odczyta go nawet po najechaniu",
            1,
            znalezione.size,
        )
    }
}
