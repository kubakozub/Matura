package com.matura.app

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * ZMIANA USTAWIEN TELEFONU NIE MOZE BUDOWAC EKRANU OD NOWA.
 *
 * Caly stan aplikacji poza zapisem (stos ekranow, trwajaca runda, wiersze wpisywanego zestawu)
 * siedzi w zwyklym remember, wiec zyje dokladnie tak dlugo, jak instancja MainActivity. Kazda
 * zmiana konfiguracji, ktorej manifest nie deklaruje w android:configChanges, konczy sie nowa
 * instancja: uczen laduje na ekranie z imieniem, runda znika bez wyniku, a wpisywany zestaw
 * przepada. Do 0.9.18 manifest deklarowal tylko orientation, screenSize, keyboardHidden
 * i uiMode, czyli restartem konczyly sie rzeczy zupelnie codzienne: wieksza czcionka,
 * rozlozenie skladaka, dzielony ekran, wlaczenie klawiatury Bluetooth.
 *
 * Test idzie przez ten sam mechanizm co system: ActivityController porownuje zmienione pola
 * z ActivityInfo.configChanges ze SCALONEGO manifestu i albo wola onConfigurationChanged, albo
 * buduje nowa instancje. Usuniecie jednej flagi z manifestu wywraca odpowiedni krok.
 *
 * Czy ekrany po takiej zmianie dalej dobrze sie rysuja (Compose czyta gestosc na nowo) i czy
 * wpisany tekst zostaje, sprawdza MainActivityNaEkranieTest: potrzebuje reguly Compose, a ta
 * jest tylko w wariancie debug.
 *
 * Z tego samego powodu ekran jest tu wznowiony, ale jego okno NIE jest dolaczone (brak
 * visible()): dolaczone okno uruchamia cala kompozycje, a ta bez reguly nie ma kto prowadzic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ZmianaKonfiguracjiTest {

    /** Bez tego nastepne testy ekranow w tej samej maszynie wisza: patrz GlownaPetla.kt. */
    @After
    fun posprzataj() = wykonajToCoCzekaWGlownejPetli()

    /** Jedno zdarzenie z zycia telefonu: co zmienia w konfiguracji i ktorych flag manifestu wymaga. */
    private class Zdarzenie(val opis: String, val flagi: Int, val zmiana: Configuration.() -> Unit)

    private val zdarzenia = listOf(
        Zdarzenie("wieksza czcionka w ustawieniach telefonu", ActivityInfo.CONFIG_FONT_SCALE) {
            fontScale *= 1.3f
        },
        Zdarzenie(
            "inny rozmiar wyswietlania",
            ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
        ) {
            // Te same piksele, gestsza siatka: ekran ma mniej dp w obu osiach.
            densityDpi = 540
            screenWidthDp = 365
            screenHeightDp = 792
            smallestScreenWidthDp = 365
        },
        Zdarzenie(
            "rozlozony skladak albo dzielony ekran",
            ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT,
        ) {
            screenWidthDp = 700
            screenHeightDp = 780
            smallestScreenWidthDp = 700
            screenLayout = (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()) or
                Configuration.SCREENLAYOUT_SIZE_LARGE
        },
        Zdarzenie(
            "podlaczona klawiatura Bluetooth",
            ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_NAVIGATION,
        ) {
            keyboard = Configuration.KEYBOARD_QWERTY
            keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
            hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            navigation = Configuration.NAVIGATION_DPAD
        },
        Zdarzenie(
            "inny jezyk systemu, pisany od prawej",
            ActivityInfo.CONFIG_LOCALE or ActivityInfo.CONFIG_LAYOUT_DIRECTION,
        ) {
            setLocale(Locale.forLanguageTag("ar-EG"))
        },
    )

    private class LicznikInstancji : Application.ActivityLifecycleCallbacks {
        var utworzone = 0
        var zniszczone = 0
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { utworzone++ }
        override fun onActivityDestroyed(activity: Activity) { zniszczone++ }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    @Test
    fun `zmiany ustawien telefonu trafiaja do tej samej instancji ekranu`() {
        val licznik = LicznikInstancji()
        ApplicationProvider.getApplicationContext<Application>()
            .registerActivityLifecycleCallbacks(licznik)

        Robolectric.buildActivity(MainActivity::class.java).use { kontroler ->
            kontroler.create().start().resume()
            val ekran = kontroler.get()

            for (z in zdarzenia) {
                val przed = Configuration(ekran.resources.configuration)
                val po = Configuration(przed).apply(z.zmiana)
                // Kontrola samego testu: gdyby "zmiana" niczego nie zmieniala, kontroler po
                // prostu zawolalby onConfigurationChanged i test przeszedlby przy kazdym manifescie.
                assertEquals(
                    "${z.opis}: konfiguracja testowa nie rusza pol, o ktore chodzi",
                    z.flagi,
                    przed.diff(po) and z.flagi,
                )

                kontroler.configurationChange(po)

                assertSame(
                    "${z.opis}: system zbudowal MainActivity od nowa, a z nia zniknal caly " +
                        "stan trzymany w remember (runda, wpisywany zestaw, stos ekranow)",
                    ekran,
                    kontroler.get(),
                )
                assertFalse("${z.opis}: instancja zostala zniszczona", ekran.isDestroyed)
                assertEquals("${z.opis}: liczba utworzonych instancji", 1, licznik.utworzone)
                assertEquals("${z.opis}: liczba zniszczonych instancji", 0, licznik.zniszczone)
            }
        }
    }
}
