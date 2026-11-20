package com.matura.app.learn

import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet

/**
 * Powod, dla ktorego slowo trafilo na liste "moich trudnych slow".
 *
 * KOLEJNOSC STALYCH JEST KOLEJNOSCIA PILNOSCI — lista sortuje sie po `ordinal`, wiec
 * przestawienie ich tutaj przestawia caly ekran. Uzasadnienie tej akurat kolejnosci:
 *
 *  1. [ZALEGLA_POWTORKA] jako jedyna mowi o DZISIAJ: harmonogram wyznaczyl termin, termin
 *     minal, a kazdy dzien zwloki to strata, ktorej pozniejsza praca nie odrobi. Pomylka
 *     sprzed tygodnia jest faktem historycznym, zalegly termin jest zadaniem na teraz.
 *     Swiezy blad i tak trafia na gore, bo [Srs.apply] przy BLEDNEJ ustawia termin na dzis.
 *  2. [COFNIETE_PO_BLEDZIE] — slowo stoi w pierwszym pudelku MIMO pomylek, czyli cala
 *     drabinka jest do przejscia od nowa. Najwiecej pracy przed uczniem.
 *  3. [POMYLKI] — bledy byly, ale slowo trzyma sie wyzej niz pierwsze pudelko.
 *  4. [PODPOWIEDZI] — odpowiedz padla samodzielnie, tylko po odkryciu liter. Najslabszy
 *     sygnal klopotu, wiec na dole.
 *
 * CZEGO TU CELOWO NIE MA: powodu wyprowadzonego z czasu odpowiedzi. Aplikacja czasu reakcji
 * nie zapisuje (patrz [WordProgress]), a jedyny slad "nie zdazyl" — `assistedDay` ustawiony
 * bez pomylek i podpowiedzi — pochodzi z Outcome.POKAZANA, czyli w tej grze z limitu czasu
 * w trybach PRAWDA/FALSZ i NA CZAS. To jest pomiar TEMPA, nie wiedzy; zalozenia zabraniaja
 * uznawac slowo za trudne na tej podstawie.
 */
enum class PowodTrudnosci {
    ZALEGLA_POWTORKA,
    COFNIETE_PO_BLEDZIE,
    POMYLKI,
    PODPOWIEDZI,
}

/**
 * Filtr dzialu dla listy. Dzial bierze sie z [WordSet.category], czyli z tytulu zestawu
 * ("Dzial - Podtemat"). Zestawy wlasne dzialu nie maja, wiec maja tu osobna wartosc —
 * inaczej nie dalo by sie ich wybrac, a "brak dzialu" mylilby sie z "wszystkie dzialy".
 * Nazwe dla ucznia dokleja warstwa UI, bo tam mieszka tlumaczenie.
 */
sealed interface DzialFiltr {
    data object Wszystkie : DzialFiltr
    data class Nazwany(val nazwa: String) : DzialFiltr
    data object Wlasne : DzialFiltr
}

/**
 * Jedna pozycja listy: slowo, jego stan nauki i WYLICZONY powod, dla ktorego tu jest.
 *
 * [postep] jedzie w komplecie, zeby ekran mogl pokazac liczniki (pomylki, podpowiedzi)
 * bez drugiego siegania do mapy — i zeby zadna liczba na ekranie nie byla zgadnieta.
 */
data class TrudneSlowo(
    val entry: Entry,
    val kierunek: Direction,
    val postep: WordProgress,
    /** Powody od najpilniejszego; nigdy pusta — pusta lista znaczy "slowo nie jest trudne". */
    val powody: List<PowodTrudnosci>,
    val zestawId: String,
    /** Tytul bez przedrostka dzialu — w karcie i tak obok stoi nazwa dzialu. */
    val zestawTytul: String,
    val dzial: String?,
    /** Strona, ktora ma sens przeczytac na glos (patrz [Trudne.stronaDoWymowy]). */
    val doWymowy: String,
    /** Ile dni minelo od terminu. Ujemne = termin dopiero przed uczniem. */
    val zaleglosc: Int,
) {
    val powod: PowodTrudnosci get() = powody.first()

    /** O co ekran pyta w tym kierunku. */
    val pytanie: String get() = if (kierunek == Direction.DEF_TO_TERM) entry.definition else entry.term

    /** Odpowiedz, ktorej uczen ma sie nauczyc w tym kierunku. */
    val odpowiedz: String get() = if (kierunek == Direction.DEF_TO_TERM) entry.term else entry.definition
}

/**
 * Dobor slow na ekran "Moje trudne slowa". Czysta logika: zadnego Androida, zadnego zegara —
 * dzien harmonogramu wchodzi parametrem, tak samo jak w [Srs].
 *
 * Regula wejscia jest jedna i wazniejsza niz cala reszta pliku: na liste trafia TYLKO slowo,
 * ktore ma za soba historie ([powody] czegos dowodza). Gdyby zaleglosc wystarczala sama,
 * lista pokazalaby caly katalog — slowo bez wpisu ma `dueDay = 0`, czyli jest zalegle od
 * zawsze, i "trudne" znaczyloby "jeszcze nietkniete".
 */
object Trudne {

    /**
     * Kierunek, w ktorym da sie czytac postep. [Direction.RANDOM] jest USTAWIENIEM gry, a nie
     * kierunkiem — [Srs.key] rzuca nim wyjatkiem. Ekran dostaje kierunek z opcji ucznia, wiec
     * zamiana musi byc tutaj, a nie w kazdym wywolaniu.
     */
    fun kierunekDoListy(kierunek: Direction): Direction =
        if (kierunek == Direction.RANDOM) Direction.TERM_TO_DEF else kierunek

    /**
     * Powody dla jednego stanu nauki, od najpilniejszego. Pusta lista = slowo nie jest trudne.
     *
     * Zaleglosc liczy sie tylko dla slowa, ktore juz gdzies stoi w drabince ([Srs.FIRST_BOX]
     * w gore) — patrz komentarz obiektu. [PowodTrudnosci.COFNIETE_PO_BLEDZIE] i
     * [PowodTrudnosci.POMYLKI] wykluczaja sie: to ten sam fakt (byly bledy) widziany z dwoch
     * wysokosci drabinki, a dwa napisy o jednym bledzie tylko zaszumilyby karte.
     *
     * Czego dane NIE pozwalaja odroznic: spadku z pudelka 3 od slowa, ktore nigdy wyzej niz
     * pudelko 1 nie doszlo. [WordProgress] trzyma tylko biezace pudelko i dzien ostatniego
     * awansu, historii nie ma. Dlatego powod mowi o STANIE ("stoi w pierwszym pudelku, choc
     * byly bledy"), a nie o zdarzeniu, ktorego nikt nie zapisal.
     */
    fun powody(postep: WordProgress, dzis: Int): List<PowodTrudnosci> {
        val wynik = ArrayList<PowodTrudnosci>(3)
        val wDrabince = postep.box >= Srs.FIRST_BOX
        if (wDrabince && Srs.isDue(postep, dzis)) wynik += PowodTrudnosci.ZALEGLA_POWTORKA
        if (postep.mistakes > 0) {
            wynik += if (postep.box == Srs.FIRST_BOX) {
                PowodTrudnosci.COFNIETE_PO_BLEDZIE
            } else {
                PowodTrudnosci.POMYLKI
            }
        }
        if (postep.hints > 0) wynik += PowodTrudnosci.PODPOWIEDZI
        return wynik
    }

    /**
     * Cala lista dla jednego kierunku, posortowana, BEZ filtra dzialu i bez usuwania powtorzen —
     * z niej ekran bierze i liste dzialow ([dzialy]), i wynik dla wybranego dzialu ([filtruj]).
     * Dzieki temu przejscie miedzy dzialami nie przelicza katalogu od nowa.
     *
     * Wpisy bez identyfikatora sa pomijane w ciszy: identyfikator nadaje migracja zapisu
     * (Repository.migrate), wiec jego brak znaczy "ten wpis nie ma jeszcze jak miec postepu",
     * a nie "blad". [Srs.key] rzucilby na nim wyjatkiem w srodku rysowania ekranu.
     */
    fun wszystkie(
        zestawy: List<WordSet>,
        postep: Map<String, WordProgress>,
        kierunek: Direction,
        dzis: Int,
    ): List<TrudneSlowo> {
        val kier = kierunekDoListy(kierunek)
        val wynik = ArrayList<TrudneSlowo>()
        for (zestaw in zestawy) {
            for (wpis in zestaw.entries) {
                if (wpis.id.isBlank()) continue
                val stan = Srs.progressOf(postep, wpis.id, kier)
                val powodyWpisu = powody(stan, dzis)
                if (powodyWpisu.isEmpty()) continue
                wynik += TrudneSlowo(
                    entry = wpis,
                    kierunek = kier,
                    postep = stan,
                    powody = powodyWpisu,
                    zestawId = zestaw.id,
                    zestawTytul = zestaw.shortTitle,
                    dzial = zestaw.category,
                    doWymowy = stronaDoWymowy(zestaw, wpis),
                    zaleglosc = dzis - stan.dueDay,
                )
            }
        }
        return wynik.sortedWith(KOLEJNOSC)
    }

    /**
     * Wybor dzialu i usuniecie powtorzen. Sortowanie [KOLEJNOSC] jest stabilne, a tu nic sie
     * nie sortuje, wiec kolejnosc z [wszystkie] zostaje nietknieta.
     *
     * To samo slowo potrafi lezec w dwoch zestawach i ma wtedy JEDEN wspolny postep (klucz
     * postepu nie zna zestawu — patrz SaveData.progress). Bez tego kroku ekran pokazalby dwa
     * identyczne wiersze, ktore odhaczaja sie razem. Powtorzenia odsiewamy PO filtrze dzialu,
     * bo inaczej wybor dzialu gubilby slowo, ktorego pierwsze wystapienie bylo gdzie indziej.
     */
    fun filtruj(lista: List<TrudneSlowo>, dzial: DzialFiltr): List<TrudneSlowo> {
        val widziane = HashSet<String>()
        return lista.filter { pasuje(it, dzial) && widziane.add(it.entry.id) }
    }

    fun zbierz(
        zestawy: List<WordSet>,
        postep: Map<String, WordProgress>,
        kierunek: Direction,
        dzis: Int,
        dzial: DzialFiltr = DzialFiltr.Wszystkie,
    ): List<TrudneSlowo> = filtruj(wszystkie(zestawy, postep, kierunek, dzis), dzial)

    /**
     * Dzialy, ktore naprawde maja co pokazac, w kolejnosci z katalogu (a ta idzie za numeracja
     * zakresu tematycznego CKE). Filtr prowadzacy do pustego ekranu bylby zaproszeniem do
     * kliknietego rozczarowania, wiec dzialow bez trudnych slow tu nie ma.
     */
    fun dzialy(zestawy: List<WordSet>, lista: List<TrudneSlowo>): List<DzialFiltr> {
        val obecne = HashSet<DzialFiltr>()
        for (slowo in lista) {
            obecne += slowo.dzial?.let { DzialFiltr.Nazwany(it) } ?: DzialFiltr.Wlasne
        }
        // Kolejnosc bierzemy z KATALOGU, a nie z listy trudnych slow: lista jest posortowana
        // pilnoscia, wiec zakladki dzialow skakalyby uczniowi z dnia na dzien.
        val wynik = LinkedHashSet<DzialFiltr>()
        for (zestaw in zestawy) {
            val dzial = zestaw.category?.let { DzialFiltr.Nazwany(it) } ?: DzialFiltr.Wlasne
            if (dzial in obecne) wynik += dzial
        }
        return wynik.toList()
    }

    fun pasuje(slowo: TrudneSlowo, dzial: DzialFiltr): Boolean = when (dzial) {
        DzialFiltr.Wszystkie -> true
        DzialFiltr.Wlasne -> slowo.dzial == null
        is DzialFiltr.Nazwany -> slowo.dzial == dzial.nazwa
    }

    /**
     * Strona wpisu, ktora ma sens puscic na glos.
     *
     * Wymowa (com.matura.app.audio.Pronunciation) ma paczke nagran angielskich, a zapasowy
     * silnik mowy ustawia Locale.UK — polskie tlumaczenie przeczytane po angielsku jest
     * halasem, a nie pomoca. Zestawy wlasne moga miec strony zamienione, wiec o to, ktora
     * strona jest angielska, pytamy DEKLARACJE JEZYKA w zestawie, a nie pozycji kolumny.
     * Gdy zadna strona nie deklaruje angielskiego (np. wlasna lista niemieckich slowek),
     * zostaje haslo — zgadywanie jezyka po tresci konczyloby sie gorzej.
     */
    fun stronaDoWymowy(zestaw: WordSet, wpis: Entry): String = when {
        czyAngielski(zestaw.termLanguage) -> wpis.term
        czyAngielski(zestaw.definitionLanguage) -> wpis.definition
        else -> wpis.term
    }

    /** Nazwy jezyka, ktore wpisuja edytor i katalog. Lista jawna, bo zgadywanie jest gorsze. */
    fun czyAngielski(nazwaJezyka: String): Boolean =
        nazwaJezyka.trim().lowercase() in NAZWY_ANGIELSKIEGO

    private val NAZWY_ANGIELSKIEGO = setOf(
        "english", "angielski", "angielskie", "en", "eng", "en-gb", "en-us",
    )

    /**
     * Najpilniejsze na gorze. Po powodzie decyduje to, co da sie policzyc z danych:
     * najdluzej zaniedbany termin, potem liczba pomylek, potem liczba podpowiedzi.
     * Sortowanie jest stabilne, wiec slowa nie do odroznienia zostaja w kolejnosci katalogu.
     */
    private val KOLEJNOSC: Comparator<TrudneSlowo> =
        compareBy<TrudneSlowo> { it.powod.ordinal }
            .thenByDescending { it.zaleglosc }
            .thenByDescending { it.postep.mistakes }
            .thenByDescending { it.postep.hints }
}
