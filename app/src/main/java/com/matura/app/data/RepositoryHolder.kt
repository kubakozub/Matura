package com.matura.app.data

import android.content.Context
import java.io.File

/**
 * JEDNO repozytorium na proces.
 *
 * [Repository] trzyma w pamieci caly zapis i po pierwszym load() nie zaglada juz na dysk, wiec
 * jest poprawne tylko wtedy, gdy na dany plik patrzy JEDEN obiekt. MainActivity budowala wlasne
 * repozytorium w kazdym onCreate. Dwie zywe instancje ekranu (znany przypadek z Androida 7 do 9:
 * pierwszy start z przycisku "Otworz" w sklepie albo instalatorze, drugi z ikony) mialy przez to
 * dwie kopie zapisu. Starsza instancja, odslonieta po zamknieciu nowszej, przy pierwszym zapisie
 * nadpisywala plik swoim starym stanem, a przy drugim wypychala nowsze dane takze z kopii
 * zapasowej. Cala nauka z nowszej instancji znikala bez komunikatu.
 *
 * Osobny obiekt, a nie pole w samym Repository, bo klasa ma zostac zwykla: katalog wchodzi do
 * niej konstruktorem i testy dalej buduja wlasne repozytoria nad katalogiem tymczasowym. To, ze
 * APLIKACJA ma miec jedno, jest decyzja aplikacji.
 */
object RepositoryHolder {

    private val repozytoria = HashMap<File, Repository>()

    /**
     * Kluczem jest katalog zapisu, a nie kontekst: kazda instancja Activity jest innym
     * kontekstem, a wszystkie maja dostac ten sam obiekt. Katalog jest tez dokladnie tym, czego
     * dotyczy regula "jeden obiekt na plik".
     *
     * Mapa, a nie jedno pole "raz na zawsze": pod Robolectrikiem kazdy test dostaje nowa
     * aplikacje z wlasnym katalogiem plikow, a pola statyczne zyja dalej miedzy testami. Jedno
     * pole oddaloby drugiemu testowi repozytorium z danymi pierwszego.
     */
    @Synchronized
    fun of(context: Context): Repository {
        val katalog = context.filesDir
        return repozytoria.getOrPut(katalog) { Repository(katalog) }
    }
}
