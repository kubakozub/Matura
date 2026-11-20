package com.matura.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.matura.app.ui.PixelText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Geometria napisu pikselowego i ZNANA LUKA: PixelText nie reaguje na powiekszona czcionke
 * systemowa.
 *
 * `pixelScaleFor` liczy skale jako `glyphHeight.toPx() / GLYPH_H`, a `Dp.toPx()` mnozy tylko
 * przez `density` i z definicji nie zna `fontScale`. Uczen, ktory ustawi w Androidzie wiekszy
 * rozmiar czcionki, widzi wiec, ze rosnie proza w systemowym Text, a etykiety przyciskow i
 * naglowki zostaja w tym samym rozmiarze. Luka jest STARA (te linie nie zmienily sie od lipca,
 * sa takie same w wersji sklepowej vc21), a poprawka jest ryzykowna przed samym wydaniem:
 * skala pikselowa musi zostac liczba calkowita, wiec przy 175% skok 5 -> 9 podwaja szerokosc
 * kazdej etykiety, takze w HUD-ie gry, gdzie tekst ma stale miejsce. Dlatego wchodzi po
 * wydaniu, z ogranicznikiem mnoznika i przejrzeniem ukladu.
 *
 * Tego pliku nie da sie uzyc jako bramki dla tej poprawki i test to UDOWADNIA: pod Robolectrikiem
 * `fontScale` nie zmienia rozmiaru nawet systemowego `Text`. Bramka musi wiec powstac na
 * urzadzeniu (test instrumentalny), a nie tutaj — inaczej ktos "naprawi" skalowanie, zobaczy
 * zielony build i wysle wydanie, ktore u ucznia nadal nie rosnie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class A11yFontScaleTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Pomiar(val pixel: Dp, val system: Dp)

    /**
     * Oba ustawienia czcionki w JEDNEJ kompozycji, bo regula testowa przyjmuje `setContent`
     * tylko raz na test. Pudelka nakladaja sie na siebie — mierzymy wysokosc, nie wyglad.
     */
    private fun zmierzObieSkale(): Pair<Pomiar, Pomiar> {
        rule.setContent {
            listOf(1.0f to "1", 2.0f to "2").forEach { (skala, tag) ->
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 3f, fontScale = skala)
                ) {
                    Box(Modifier.testTag("pixel$tag")) { PixelText("GRAJ", glyphHeight = 11.dp) }
                    Box(Modifier.testTag("system$tag")) { Text("GRAJ", fontSize = 14.sp) }
                }
            }
        }
        fun pomiar(tag: String): Pomiar {
            val p = rule.onNodeWithTag("pixel$tag").getUnclippedBoundsInRoot()
            val s = rule.onNodeWithTag("system$tag").getUnclippedBoundsInRoot()
            return Pomiar(p.bottom - p.top, s.bottom - s.top)
        }
        val jeden = pomiar("1")
        val dwa = pomiar("2")
        println("FONTSCALE=1.0  PixelText.h=${jeden.pixel}  SystemText.h=${jeden.system}")
        println("FONTSCALE=2.0  PixelText.h=${dwa.pixel}  SystemText.h=${dwa.system}")
        return jeden to dwa
    }

    @Test
    fun pixelText_nigdy_nie_jest_mniejszy_niz_zamowiony_glif() {
        val (jeden, _) = zmierzObieSkale()
        // Wiersz to 16/7 wysokosci glifu (LINE_HEIGHT / GLYPH_H), wiec napis zamowiony na
        // 11 dp nie ma prawa wyjsc nizszy niz te 11 dp. Gdyby zaokraglanie skali kiedys
        // zeszlo do zera, etykiety znikalyby z przyciskow.
        assertTrue(
            "PixelText(glyphHeight = 11.dp) zmierzony jako ${jeden.pixel} — mniej niz zamowiono",
            jeden.pixel >= 11.dp,
        )
    }

    @Test
    fun robolectric_nie_umie_zmierzyc_powiekszonej_czcionki() {
        val (jeden, dwa) = zmierzObieSkale()
        // To NIE jest zgoda na znalezisko "PixelText ignoruje fontScale" — to dowod, ze tego
        // znaleziska nie da sie tutaj zmierzyc: przy fontScale 2.0 nie rosnie rowniez systemowy
        // Text, ktory na prawdziwym telefonie rosnie zawsze. Gdy ta asercja kiedys pekne,
        // Robolectric zaczal liczyc fontScale — i wtedy (i dopiero wtedy) ma tu sens
        // assertTrue(dwa.pixel > jeden.pixel).
        assertEquals("systemowy Text urosl — rig zaczal uwzgledniac fontScale", jeden.system, dwa.system)
        assertEquals("PixelText urosl mimo bezwladnego riga", jeden.pixel, dwa.pixel)
    }
}
