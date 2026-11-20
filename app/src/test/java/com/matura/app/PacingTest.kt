package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.learn.Attempt
import com.matura.app.learn.Etap
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Outcome
import com.matura.app.learn.Sesja
import com.matura.app.learn.Srs
import com.matura.app.learn.Zaliczenie
import com.matura.app.model.Direction
import com.matura.app.model.Dorobek
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tempo: czy tabela punktow i drabina etapow pasuja do harmonogramu i do sesji dziennej.
 *
 * Progi etapow sa dobrane pod jedno zalozenie: uczen, ktory robi sama sesje dzienna, ma przez
 * caly rok maturalny co kilka tygodni nowy etap przed soba, a ostatniego samym minimum nie
 * osiaga. Tego nie widac w zadnej pojedynczej regule: wynika z trzech rzeczy naraz, czyli
 * z odstepow w [Srs], z pojemnosci [Sesja.zaplanuj] i z punktow w [Nagrody]. Zmiana ktorejkolwiek
 * (dluzsze odstepy, wieksza sesja, inna tabela) po cichu rozjezdza drabine. Ten test jest po
 * to, zeby wtedy padl i kazal przeliczyc progi.
 *
 * MODEL UCZNIA JEST ZALOZENIEM, NIE DANYMI Z APLIKACJI: 60 procent nowych slow uczen juz zna,
 * slowo z pudelka 1, 2 i 3 przypomina sobie z prawdopodobienstwem 85, 90 i 93 procent, a gdy
 * nie pamieta, w polowie przypadkow myli sie, a w polowie prosi o pokazanie odpowiedzi. Te
 * same liczby przyjal projekt drabiny. Zaden wynik stad nie trafia na ekran.
 *
 * Granice w asercjach sa szersze niz rozrzut miedzy ziarnami, wiec test nie zalezy od tego,
 * jak akurat padnie los, a mimo to lapie zmiane rzedu wielkosci.
 */
class PacingTest {

    private companion object {
        /** 20 listopada 2026: 167 dni przed domyslna data matury. */
        const val START = 20_777
        const val DNI = 167
        val KIERUNEK = Direction.TERM_TO_DEF
        val ZIARNA = 1..8
    }

    /** Slownik gry w kolejnosci katalogu, kazde slowo raz: tak czyta go sesja dzienna. */
    private val slownik: List<String> =
        BuiltInSets.all().flatMap { set -> set.entries.map { it.id } }.filter { it.isNotBlank() }.distinct()

    private class Przebieg(val xpPoDniu: IntArray, val ksiega: Dorobek) {
        /** W ktorym dniu (od 1) suma punktow doszla do progu etapu; null, gdy nie doszla. */
        fun dzienEtapu(etap: Etap): Int? =
            xpPoDniu.indexOfFirst { it >= etap.prog }.takeIf { it >= 0 }?.plus(1)

        /** Najdluzsze czekanie na kolejny etap, w dniach, wsrod etapow osiagnietych. */
        fun najdluzszeCzekanie(): Int =
            Etap.entries.mapNotNull { dzienEtapu(it) }.zipWithNext { a, b -> b - a }.max()
    }

    /**
     * 167 dni nauki. [budzet] rowny null to uczen, ktory robi SAMA sesje dzienna, raz dziennie,
     * dokladnie to, co ulozy [Sesja.zaplanuj]. Liczba to uczen, ktory odpowiada tyle razy
     * dziennie: najpierw na zalegle slowa, a reszte budzetu wydaje na nowe.
     */
    private fun przebieg(budzet: Int?, ziarno: Int): Przebieg {
        val los = Random(ziarno)
        var mapa = emptyMap<String, WordProgress>()
        var ksiega = Dorobek()
        var poznawane = 0
        val xpPoDniu = IntArray(DNI)
        for (nr in 0 until DNI) {
            val dzien = START + nr
            val naDzis: List<String> = if (budzet == null) {
                Sesja.zaplanuj(mapa, slownik, KIERUNEK, dzien).slownictwo
            } else {
                val zalegle = slownik.subList(0, poznawane)
                    .filter { Srs.isDue(Srs.progressOf(mapa, it, KIERUNEK), dzien) }
                    .sortedWith(
                        compareBy<String>(
                            { Srs.progressOf(mapa, it, KIERUNEK).dueDay },
                            { Srs.progressOf(mapa, it, KIERUNEK).box },
                        ),
                    )
                    .take(budzet)
                val ileNowych = minOf(budzet - zalegle.size, slownik.size - poznawane)
                val nowe = slownik.subList(poznawane, poznawane + ileNowych)
                poznawane += ileNowych
                zalegle + nowe
            }
            for (id in naDzis) {
                val klucz = Srs.key(id, KIERUNEK)
                val przed = mapa[klucz]
                val pamieta = when (przed?.box ?: 0) {
                    0 -> 0.60
                    1 -> 0.85
                    2 -> 0.90
                    else -> 0.93
                }
                val wynik = when {
                    los.nextDouble() < pamieta -> Outcome.POPRAWNA
                    // Slowo, ktorego uczen jeszcze nie zaliczyl, nie ma czego "pomylic": prosi
                    // o pokazanie. Przy znanym slowie pol na pol.
                    przed == null || przed.box == 0 || los.nextBoolean() -> Outcome.POKAZANA
                    else -> Outcome.BLEDNA
                }
                val proba = Attempt(id, KIERUNEK, TaskKind.PRZYPOMNIENIE, wynik, "$id#$dzien")
                val stanPrzed = przed ?: WordProgress()
                mapa = Srs.applyTo(mapa, proba, dzien)
                val zaliczenie = Nagrody.zaliczenie(stanPrzed, mapa[klucz] ?: WordProgress(), proba) ?: continue
                ksiega = Nagrody.dolicz(
                    ksiega, zaliczenie, dzien, Nagrody.CEL_DOMYSLNY, wlasne = false,
                    naprawa = zaliczenie == Zaliczenie.UTRWALENIE && stanPrzed.mistakes > 0,
                ).dorobek
            }
            xpPoDniu[nr] = ksiega.xp
        }
        return Przebieg(xpPoDniu, ksiega)
    }

    @Test
    fun `sama sesja dzienna daje nowy etap co kilka tygodni i nie dochodzi do ostatniego`() {
        for (ziarno in ZIARNA) {
            val p = przebieg(budzet = null, ziarno = ziarno)
            val gdzie = "ziarno $ziarno"

            // Pierwsza sesja to piec nowych slow. Drugi etap (30 punktow) pada pierwszego dnia,
            // gdy uczen zna co najmniej trzy z nich, a najpozniej nazajutrz.
            assertTrue("$gdzie: drugi etap", p.dzienEtapu(Etap.KARTKOWKA)!! <= 2)
            assertTrue("$gdzie: szosty etap", p.dzienEtapu(Etap.STUDNIOWKA)!! <= 25)
            assertNotNull("$gdzie: przedostatni etap przed matura", p.dzienEtapu(Etap.KARTA_ODPOWIEDZI))
            assertNull("$gdzie: ostatni etap samym minimum", p.dzienEtapu(Etap.WAKACJE))
            assertTrue("$gdzie: czekanie ${p.najdluzszeCzekanie()} dni", p.najdluzszeCzekanie() <= 45)

            // Domyslny cel dnia jest do zrobienia sama sesja prawie codziennie.
            assertTrue("$gdzie: dni z celem ${p.ksiega.dniNauki}", p.ksiega.dniNauki >= 160)
        }
    }

    @Test
    fun `dwadziescia piec odpowiedzi dziennie dochodzi do ostatniego etapu w polowie drogi do matury`() {
        for (ziarno in ZIARNA) {
            val p = przebieg(budzet = 25, ziarno = ziarno)

            val dzien = p.dzienEtapu(Etap.WAKACJE)
            assertNotNull("ziarno $ziarno", dzien)
            assertTrue("ziarno $ziarno: ostatni etap w dniu $dzien", dzien!! in 50..100)
            assertEquals("ziarno $ziarno: cel dnia codziennie", DNI, p.ksiega.dniNauki)
        }
    }

    @Test
    fun `piecdziesiat odpowiedzi dziennie nie przeskakuje drabiny w tydzien i potem robi okrazenia`() {
        for (ziarno in ZIARNA) {
            val p = przebieg(budzet = 50, ziarno = ziarno)

            val dzien = p.dzienEtapu(Etap.WAKACJE)!!
            assertTrue("ziarno $ziarno: ostatni etap w dniu $dzien", dzien in 15..40)
            // Po ostatnim etapie nauka dalej ma co pokazac: kilkanascie okrazen do matury.
            val okrazenia = Nagrody.okrazenie(p.ksiega.xp)
            assertTrue("ziarno $ziarno: okrazen $okrazenia", okrazenia in 8..25)
        }
    }
}
