package com.matura.app

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Co MainActivity robi z oknem i z wlasna instancja, zanim pokaze pierwszy ekran.
 *
 * Dwie rzeczy, obie niewidoczne w testach samych ekranow, bo tamte skladaja MaturaApp wprost:
 *
 *  1. PASKI SYSTEMOWE. Gra jest zawsze ciemna, a enableEdgeToEdge() bez argumentow dobiera
 *     ikony do motywu telefonu. Przy jasnym motywie, czyli domyslnym, zegar i bateria wychodzily
 *     ciemne na prawie czarnym tle.
 *
 *  2. DRUGA INSTANCJA Z IKONY. Na Androidzie 7 do 9 start z przycisku "Otworz" w sklepie albo
 *     instalatorze, a potem z ikony, kladzie druga MainActivity na pierwszej. Kazda ma wlasny
 *     obraz danych w pamieci, wiec ta odslonieta pozniej pokazuje i zapisuje stary stan.
 *
 * DLACZEGO SAMO create(), BEZ setup(). Okno dolaczone do systemu (visible()) uruchamia cala
 * kompozycje, a ten test nie ma reguly Compose, bo dziala tez w wariancie release. Wszystko,
 * co tu sprawdzamy, dzieje sie w onCreate, wiec okno nie jest potrzebne.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class StartAktywnosciTest {

    /** Bez tego nastepne testy ekranow w tej samej maszynie wisza: patrz GlownaPetla.kt. */
    @After
    fun posprzataj() = wykonajToCoCzekaWGlownejPetli()

    private val aplikacja get() = ApplicationProvider.getApplicationContext<Context>()

    /** Tak startuje ikona na pulpicie i przycisk "Otworz". */
    private fun intentZIkony() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(aplikacja, MainActivity::class.java)

    private fun ActivityController<MainActivity>.liczbaWidokowTresci(): Int =
        get().findViewById<ViewGroup>(android.R.id.content).childCount

    // --- paski systemowe --------------------------------------------------------------------

    @Test
    @Config(qualifiers = "+notnight")
    fun `przy jasnym motywie telefonu ikony paskow zostaja jasne`() {
        Robolectric.buildActivity(MainActivity::class.java, intentZIkony()).use { kontroler ->
            kontroler.create()
            val okno = kontroler.get().window
            val paski = WindowCompat.getInsetsController(okno, okno.decorView)

            // "Light" w nazwach systemowych znaczy JASNY PASEK, czyli ciemne ikony.
            assertFalse(
                "pasek stanu dostal ciemne ikony, a pod nim jest prawie czarne tlo gry",
                paski.isAppearanceLightStatusBars,
            )
            assertFalse(
                "pasek nawigacji dostal ciemne przyciski na ciemnym tle",
                paski.isAppearanceLightNavigationBars,
            )
            assertFalse(
                "system doklada wlasna polprzezroczysta nakladke pod przyciski nawigacji",
                okno.isNavigationBarContrastEnforced,
            )
        }
    }

    // --- druga instancja z ikony ------------------------------------------------------------

    @Test
    fun `druga instancja z ikony konczy sie, zanim cokolwiek pokaze`() {
        Robolectric.buildActivity(MainActivity::class.java, intentZIkony()).use { kontroler ->
            shadowOf(kontroler.get()).setIsTaskRoot(false)

            kontroler.create()

            assertTrue("zbedna instancja zostala na stosie", kontroler.get().isFinishing)
            assertEquals(
                "zbedna instancja zdazyla zlozyc ekran, czyli wczytac wlasna kopie danych",
                0,
                kontroler.liczbaWidokowTresci(),
            )
        }
    }

    @Test
    fun `zwykly start z ikony pokazuje gre`() {
        Robolectric.buildActivity(MainActivity::class.java, intentZIkony()).use { kontroler ->
            kontroler.create()

            assertFalse(kontroler.get().isFinishing)
            assertEquals(1, kontroler.liczbaWidokowTresci())
        }
    }

    @Test
    fun `instancja nie z ikony zostaje, nawet gdy nie jest korzeniem zadania`() {
        // Straznik ma lapac wylacznie podwojny start z ikony. Jawne uruchomienie z innej
        // aplikacji (bez kategorii LAUNCHER) to czyjas swiadoma decyzja, nie blad systemu.
        val jawny = Intent(aplikacja, MainActivity::class.java)
        Robolectric.buildActivity(MainActivity::class.java, jawny).use { kontroler ->
            shadowOf(kontroler.get()).setIsTaskRoot(false)

            kontroler.create()

            assertFalse(kontroler.get().isFinishing)
            assertEquals(1, kontroler.liczbaWidokowTresci())
        }
    }
}
