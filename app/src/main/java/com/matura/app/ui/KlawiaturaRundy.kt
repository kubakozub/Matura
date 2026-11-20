package com.matura.app.ui

import com.matura.app.game.GameEngine
import com.matura.app.game.Matching
import com.matura.app.model.Direction
import com.matura.app.model.Entry

/**
 * Czym w trybie klasycznym da sie odpowiedziec PISZAC.
 *
 * Runda skonczona (krok dziennej sesji, cwiczenie trudnych slow) zamyka sie dopiero wtedy, gdy
 * kazde slowo dostanie poprawna odpowiedz. Slowo, ktorego odpowiedzi nie da sie wpisac, wracalo
 * w niej po kazdym pokazaniu tlumaczenia i runda nie konczyla sie nigdy. Wbudowany slownik
 * takich slow nie ma; ma je zestaw ucznia z cyfra w odpowiedzi ("drukarka 3D"), z litera spoza
 * alfabetu klawiatury gry albo z odpowiedzia dluzsza, niz miesci pole wpisu.
 *
 * Reguly sa tu, a nie w silniku, bo silnik nie wie, czym uczen pisze. Kazda opisuje jedna
 * z dwoch drog wpisywania na ekranie gry i musi sie zmieniac razem z nia: klawiature gry
 * (Keyboard.kt) i pole klawiatury telefonu (PlayScreen.kt).
 */

/**
 * Czy [odpowiedz] da sie wpisac. Ma byc w postaci znormalizowanej, czyli takiej, w jakiej
 * porownuje ja gra (Matching.acceptedAnswers): male litery bez ogonkow, cyfry i pojedyncze spacje.
 *
 *  - Klawiatura gry ma litery A-Z i spacje, nic wiecej.
 *  - Pole klawiatury telefonu przepuszcza kazda litere, takze spoza A-Z, ale cyfr juz nie.
 *  - Zadna z nich nie wpisze wiecej niz [GameEngine.MAX_TYPED] znakow.
 */
fun daSieWpisac(odpowiedz: String, klawiaturaTelefonu: Boolean): Boolean =
    odpowiedz.isNotEmpty() && odpowiedz.length <= GameEngine.MAX_TYPED &&
        odpowiedz.all { it == ' ' || if (klawiaturaTelefonu) it.isLetter() else it in 'a'..'z' }

/**
 * Wpisy, na ktore w rundzie granej w [kierunek] da sie odpowiedziec piszac: choc jedna
 * z przyjmowanych odpowiedzi przechodzi przez [daSieWpisac].
 *
 * [Direction.RANDOM] to ustawienie, a nie kierunek: silnik losuje strone dla kazdego wiersza
 * osobno, wiec wpis zostaje tylko wtedy, gdy da sie go wpisac w obie strony.
 *
 * Pominiete slowo nie znika z harmonogramu ani z zestawu. Dalej wychodzi w zwyklej grze
 * i w trybach z wyborem odpowiedzi; nie trafia tylko do rundy, ktorej nie daloby sie skonczyc.
 */
fun doRundyPisanej(wpisy: List<Entry>, kierunek: Direction, klawiaturaTelefonu: Boolean): List<Entry> =
    wpisy.filter { wpis ->
        (kierunek == Direction.DEF_TO_TERM || maOdpowiedzDoWpisania(wpis.definition, klawiaturaTelefonu)) &&
            (kierunek == Direction.TERM_TO_DEF || maOdpowiedzDoWpisania(wpis.term, klawiaturaTelefonu))
    }

private fun maOdpowiedzDoWpisania(strona: String, klawiaturaTelefonu: Boolean): Boolean {
    // Cala komorka jest zawsze pierwsza z przyjmowanych odpowiedzi. Prawie kazdy wpis przechodzi
    // juz na niej, a to pytanie pada o caly katalog naraz: rozbijanie kazdej komorki na warianty
    // zostaje dla tych kilku, ktore go potrzebuja.
    if (daSieWpisac(Matching.normalize(strona), klawiaturaTelefonu)) return true
    return Matching.acceptedAnswers(strona).any { daSieWpisac(it, klawiaturaTelefonu) }
}
