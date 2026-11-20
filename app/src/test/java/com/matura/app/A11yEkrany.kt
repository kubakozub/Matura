package com.matura.app

import androidx.compose.runtime.Composable
import com.matura.app.learn.Attempt
import com.matura.app.learn.Outcome
import com.matura.app.learn.Sesja
import com.matura.app.learn.Srs
import com.matura.app.learn.StanSesji
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.TaskKind
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import com.matura.app.ui.DzisiejszaSesjaScreen
import com.matura.app.ui.Str
import com.matura.app.ui.TrudneSlowaScreen
import com.matura.app.ui.TrudneTeksty

/**
 * Ekrany dodane po wersji sklepowej 0.9.4, gotowe do wstawienia w bramki dostepnosci.
 *
 * Powstalo, bo przed wydaniem 0.9.18 ZADEN z czterech zestawow testow dostepnosci nie
 * dotykal ani TRUDNYCH SLOW, ani DZISIEJSZEJ SESJI, ani prywatnosci — czyli dokladnie tego,
 * co w tym wydaniu jest nowe. Skladanie tych ekranow z danych jest na tyle zmudne (katalog,
 * mapa postepu, plan dnia), ze dopoki mieszkalo w kazdym tescie osobno, po prostu nikt ich
 * nie testowal.
 *
 * Dane sa budowane tak, jak buduje je gra — przez [Srs.applyTo] — zeby ekran dostal uklad
 * pol, ktory u ucznia naprawde powstaje.
 */
internal object A11yEkrany {

    /** Dowolny dzien od epoki UTC; ten sam co w SrsTest i SesjaTest, zeby liczby sie zgadzaly. */
    const val DZIS = 19_875

    private val KIERUNEK = Direction.TERM_TO_DEF

    private val ache = Entry("ache", "ból", "w000000000001")
    private val salary = Entry("salary", "pensja", "w000000000002")
    private val homework = Entry("homework", "praca domowa", "w000000000003")

    /**
     * Dwa dzialy, bo przy jednym ekran TRUDNYCH SLOW nie rysuje paska zakladek
     * (TrudneSlowaScreen: `if (dzialy.size > 1)`) — a to wlasnie te zakladki sa glowna
     * nawigacja ekranu i najmniejszym celem dotyku.
     */
    val zestawy: List<WordSet> = listOf(
        WordSet(id = "z1", title = "Zdrowie · U lekarza", entries = listOf(ache)),
        WordSet(id = "z2", title = "Praca · Rozmowa o prace", entries = listOf(salary, homework)),
    )

    /** Historia z pomylkami — bez niej lista trudnych slow jest pusta i nie ma czego mierzyc. */
    val postep: Map<String, WordProgress> = run {
        var mapa: Map<String, WordProgress> = emptyMap()
        listOf(ache, salary, homework).forEachIndexed { i, wpis ->
            mapa = Srs.applyTo(
                mapa,
                Attempt(wpis.id, KIERUNEK, TaskKind.PRZYPOMNIENIE, Outcome.BLEDNA, "${wpis.id}#$i"),
                DZIS - 3,
            )
        }
        mapa
    }

    /** Sklejone dokladnie tak, jak sklada je warstwa nawigacji w App.kt. */
    fun trudneTeksty(str: Str) = TrudneTeksty(
        tytul = str.hardWords,
        wroc = str.back,
        wszystkieDzialy = str.filterAll,
        mojeZestawy = str.ownSets,
        kierunekHasloZnaczenie = str.dirTermToDef,
        kierunekZnaczenieHaslo = str.dirDefToTerm,
        etykietaLiczby = str.hardWordsCount,
        powodZalegle = str.hardReasonDue,
        powodCofniete = str.hardReasonBackToStart,
        powodPomylki = str.hardReasonMistakes,
        powodPodpowiedzi = str.hardReasonHints,
        etykietaPomylki = str.hardCountMistakes,
        etykietaPodpowiedzi = str.hardCountHints,
        etykietaPoTerminie = str.hardOverdueDays,
        terminDzis = str.hardDueToday,
        wymowa = str.hardListen,
        cwicz = str.hardPractice,
        cwiczWszystkie = str.hardPracticeAll,
        pustoBrakHistorii = str.hardEmptyNoHistory,
        pustoNicTrudnego = str.hardEmptyNothingHard,
    )

    @Composable
    fun TrudneSlowa() {
        val str = Str(UiLanguage.PL)
        TrudneSlowaScreen(
            teksty = trudneTeksty(str),
            zestawy = zestawy,
            postep = postep,
            dzis = DZIS,
            onBack = {},
            onWymowa = {},
            onCwicz = {},
            onCwiczWszystkie = {},
        )
    }

    @Composable
    fun DzisiejszaSesja() {
        val plan = Sesja.zaplanuj(postep, zestawy.flatMap { it.entries }.map { it.id }, KIERUNEK, DZIS)
        val stan = StanSesji(dzien = DZIS)
        DzisiejszaSesjaScreen(
            jezyk = UiLanguage.PL,
            plan = plan,
            stan = stan,
            podsumowanie = Sesja.podsumuj(plan, stan, postep, DZIS),
            onPrzerwij = {},
            onKrok = {},
            onDalej = {},
            onZakoncz = {},
        )
    }
}
