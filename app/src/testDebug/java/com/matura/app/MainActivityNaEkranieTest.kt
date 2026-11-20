package com.matura.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.matura.app.data.Repository
import com.matura.app.data.RepositoryHolder
import com.matura.app.model.UiLanguage
import com.matura.app.ui.PixelFont
import com.matura.app.ui.Str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.roundToInt

/**
 * MainActivity ogladana tak, jak widzi ja uczen: przez to, co jest na ekranie.
 *
 * Testy ekranow skladaja MaturaApp wprost i podaja mu wlasne repozytorium, wiec nie widza
 * niczego, co dzieje sie MIEDZY systemem a aplikacja. Tutaj startuje prawdziwa MainActivity
 * ze scalonym manifestem, a ekran obslugujemy regula Compose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityNaEkranieTest {

    @get:Rule val rule = createEmptyComposeRule()

    /**
     * Kazdy test zamyka swoja MainActivity sam, kontrolerem Robolectrica, a nie przez
     * ActivityScenario. Bez tego sprzatania nastepne testy ekranow w tej samej maszynie
     * wisza: patrz GlownaPetla.kt.
     */
    @After
    fun posprzataj() = wykonajToCoCzekaWGlownejPetli()

    private val str = Str(UiLanguage.PL)

    private val tytulZestawu = "Czasowniki nieregularne"

    /** Pola edytora w kolejnosci ukladu: tytul, dwa jezyki, potem wiersze (haslo, tlumaczenie). */
    private fun pole(nr: Int) = rule.onAllNodes(hasSetTextAction())[nr]

    /** Naglowek ekranu: napis z fontu bitmapowego, wiec jego piksele zaleza wprost od gestosci. */
    private fun naglowekEdytora() =
        rule.onNodeWithContentDescription(str.createSet).fetchSemanticsNode()

    /** Wysokosc wiersza PixelText w pikselach dla zamowionego glifu, tak jak liczy ja PixelFont.kt. */
    private fun wierszPx(glifDp: Int, gestosc: Float): Int =
        PixelFont.LINE_HEIGHT * maxOf(1, (glifDp * gestosc / PixelFont.GLYPH_H).roundToInt())

    @Test
    fun `wpisywany zestaw przezywa zmiane czcionki, gestosci, rozmiaru ekranu i jezyka systemu`() {
        Robolectric.buildActivity(MainActivity::class.java).use { kontroler ->
            kontroler.setup()
            val ekran = kontroler.get()

            // START -> MENU -> ZESTAWY -> nowy zestaw, z tytulem i pierwszym haslem.
            rule.onNodeWithContentDescription(str.playAnonymously).performClick()
            rule.onNodeWithContentDescription(str.sets).performClick()
            rule.onNodeWithContentDescription(str.createSet).performClick()
            pole(0).performTextInput(tytulZestawu)
            pole(3).performTextInput("went")
            rule.waitForIdle()

            val przed = Configuration(ekran.resources.configuration)
            assertEquals(3f, naglowekEdytora().layoutInfo.density.density, 0.001f)
            assertEquals(wierszPx(13, 3f), naglowekEdytora().size.height)

            // Wieksza czcionka, ekran o innej gestosci i innym rozmiarze naraz: tak wyglada
            // przeniesienie gry na drugi ekran skladaka po zmianie rozmiaru czcionki.
            val po = Configuration(przed).apply {
                fontScale = 1.3f
                densityDpi = 320
                screenWidthDp = 616
                screenHeightDp = 1336
                smallestScreenWidthDp = 616
                screenLayout = (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()) or
                    Configuration.SCREENLAYOUT_SIZE_LARGE
            }
            kontroler.configurationChange(po)
            rule.waitForIdle()

            assertSame("MainActivity zostala zbudowana od nowa", ekran, kontroler.get())

            // Stan ekranu: dalej edytor, z tym, co uczen wpisal.
            rule.onNodeWithText(tytulZestawu).assertExists()
            rule.onNodeWithText("went").assertExists()

            // Uklad: Compose przeczytal nowa gestosc i skale czcionki, a napis bitmapowy
            // przeliczyl sie na nowe piksele. Gdyby ktorys ekran trzymal stare przeliczenie
            // dp na px, zostalby w rozmiarze sprzed zmiany.
            val gestosc = naglowekEdytora().layoutInfo.density
            assertEquals(2f, gestosc.density, 0.001f)
            assertEquals(1.3f, gestosc.fontScale, 0.001f)
            assertEquals(wierszPx(13, 2f), naglowekEdytora().size.height)

            // Jezyk systemu pisany od prawej: ekran odwraca sie w miejscu, bez nowej instancji.
            assertEquals(LayoutDirection.Ltr, naglowekEdytora().layoutInfo.layoutDirection)
            kontroler.configurationChange(Configuration(po).apply { setLocale(Locale.forLanguageTag("ar-EG")) })
            rule.waitForIdle()

            assertSame("MainActivity zostala zbudowana od nowa", ekran, kontroler.get())
            assertEquals(LayoutDirection.Rtl, naglowekEdytora().layoutInfo.layoutDirection)
            rule.onNodeWithText(tytulZestawu).assertExists()
            rule.onNodeWithText("went").assertExists()
        }
    }

    @Test
    fun `ekran zapisuje przez repozytorium wspolne dla calego procesu`() {
        val aplikacja = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.buildActivity(MainActivity::class.java).use { kontroler ->
            // Ekran startowy jest zlozony, czyli MainActivity ma juz dane w pamieci.
            kontroler.setup()
            rule.waitForIdle()

            // Ktos inny w tym samym procesie zapisuje zmiane. Na telefonie jest to druga
            // instancja MainActivity; tutaj wystarczy to samo repozytorium, ktore by dostala.
            val wspolne = RepositoryHolder.of(aplikacja)
            assertTrue(wspolne.setOptions(wspolne.load().options.copy(soundEnabled = false)))

            // Teraz zapisuje ekran: imie z ekranu startowego.
            rule.onNodeWithContentDescription(str.playAnonymously).performClick()
            rule.waitForIdle()

            val naDysku = Repository(aplikacja.filesDir).load()
            assertEquals(str.defaultPlayerName, naDysku.profile.name)
            assertFalse(
                "zapis z ekranu cofnal zmiane zapisana obok: MainActivity pracuje na wlasnej, " +
                    "starej kopii danych zamiast na repozytorium wspolnym dla procesu",
                naDysku.options.soundEnabled,
            )
        }
    }
}
