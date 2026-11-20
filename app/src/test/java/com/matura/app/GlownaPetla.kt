package com.matura.app

import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * Wykonuje to, co czeka w glownej petli Robolectrica. Kazdy test, ktory tworzy MainActivity
 * kontrolerem Robolectrica, MUSI to zawolac na koncu (w @After), takze wtedy, gdy nie dolacza
 * okna i niczego nie rysuje.
 *
 * Dlaczego: setContent zapisuje stan Compose juz w onCreate, a Compose zatwierdza takie zapisy
 * wiadomoscia wyslana do glownej petli. Kontroler (create, destroy, close) petli nie oproznia,
 * a Robolectric po tescie wyrzuca wszystko, co w niej zostalo. Compose ma wtedy wiadomosc za
 * wyslana juz na zawsze i w tej maszynie testowej przestaje zatwierdzac zmiany stanu: kazdy
 * NASTEPNY test ekranu z automatycznym zegarem czeka 60 s i pada z AppNotIdleException
 * ("Compose did not get idle"). Winny test przechodzi, padaja niewinne po nim.
 *
 * Zmierzone osobno dla StartAktywnosciTest, ZmianaKonfiguracjiTest i MainActivityNaEkranieTest:
 * bez tego wywolania pierwszy test ekranu uruchomiony po kazdym z nich wisi 60 s i pada,
 * z nim przechodzi. W pelnym przebiegu wygladalo to jak 21 awarii w cudzych testach.
 *
 * Testy ekranow oparte o createComposeRule albo ActivityScenario tego nie potrzebuja:
 * ActivityScenario sam czeka na pusta petle przy kazdej zmianie stanu ekranu, takze przy
 * zamykaniu.
 */
internal fun wykonajToCoCzekaWGlownejPetli() {
    shadowOf(Looper.getMainLooper()).idle()
}
