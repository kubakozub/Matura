package com.matura.app.learn

import com.matura.app.model.Direction
import com.matura.app.model.WordProgress

/**
 * Trzy formy polskiej liczby mnogiej. Bez tego kazde zdanie z liczba brzmi jak formularz
 * ("Przecwiczone: 12 slowo(a)"), a uczen ma tu dostac zdanie po polsku, nie rekord z bazy.
 * Angielski wypelnia [kilka] i [wiele] ta sama forma i regula sama sie uprosci.
 */
data class FormyLiczby(val jeden: String, val kilka: String, val wiele: String)

/**
 * Co runda zrobila dla nauki. Kazde pole jest LICZBA POLICZONA Z DANYCH — z prob, ktore
 * naprawde padly, i z mapy postepu przed/po. Nie ma tu zadnego wskaznika "opanowania",
 * "szans na maturze" ani punktow z gry: punkt zdobywa sie takze za szybkosc i za trafienie
 * potworka, wiec nie mowi nic o pamieci.
 */
data class WynikRundy(
    /** Ile ROZNYCH slow zostalo w tej rundzie sprawdzonych (proby przerwane sie nie licza). */
    val przecwiczone: Int,

    /** Ile roznych slow potrzebowalo tego dnia pomocy — patrz [Podsumowanie.POMOC]. */
    val doPonowienia: Int,

    /** Ile slow wyszlo w tej rundzie z pudelka 0, czyli zostalo poznanych po raz pierwszy. */
    val nowoPoznane: Int,

    /** Ile terminow powtorki czeka w zakresie rundy na dni PO dzisiejszym. */
    val zaplanowane: Int,

    /** Za ile dni najblizszy termin; null, gdy w zakresie nie czeka nic. */
    val najblizszaZaDni: Int?,

    /** Ile powtorek wypada dokladnie w tym najblizszym dniu (a nie w calej przyszlosci). */
    val naNajblizszyDzien: Int,

    /** Identyfikatory slow zaproponowanych do krotkiej poprawki, od najwazniejszego. */
    val doPoprawki: List<String>,
) {
    /**
     * Runda bez ani jednej zaliczonej proby. Ekran ma wtedy napisac, ze nic nie sprawdzono,
     * zamiast pokazywac "Przecwiczone: 0 slow" — zero po polsku brzmi jak porazka, a to byl
     * po prostu przerwany albo czysto zrecznosciowy przebieg.
     */
    val pusta: Boolean get() = przecwiczone == 0
}

/**
 * Stan slowa w dziale, w czterech kubelkach, ktore sie NIE nakladaja i razem daja caly dzial.
 *
 * [zaawansowanie] mowi, ile pracy zostalo (0 = najwiecej). Jest potrzebne, bo przy ustawieniu
 * "losuj kierunek" jedno slowo ma dwa stany i trzeba wybrac jeden do pokazania: bierzemy
 * SLABSZY, bo kto umie "mature -> dojrzaly", a nie umie w druga strone, tego slowa jeszcze
 * nie umie.
 */
enum class KategoriaDzialu(val zaawansowanie: Int) {
    /** Pudelko 0: slowo nie bylo jeszcze ani razu sprawdzone. */
    NOWE(0),

    /** Ma juz historie i wlasnie minal jego termin. */
    DO_POWTORKI(1),

    /** Pudelko 1 albo 2, termin jeszcze przed nami. */
    W_NAUCE(2),

    /** Pudelko 3 i termin przed nami. */
    UTRWALONE(3),
}

/**
 * Rozklad dzialu na kategorie. [wszystkie] to liczba ROZNYCH niepustych identyfikatorow
 * w dziale i zawsze rowna sie sumie czterech pozostalych pol.
 */
data class PostepDzialu(
    val wszystkie: Int,
    val nowe: Int,
    val doPowtorki: Int,
    val wNauce: Int,
    val utrwalone: Int,
)

/** Szablony zdan podsumowania. Wypelnia je [Podsumowanie.linie]; "%s" bierze pierwsza wartosc. */
data class TekstyRundy(
    /** Gdy runda nie sprawdzila zadnego slowa. */
    val brakProb: String,
    /** "%s" = np. "12 slow". */
    val przecwiczone: String,
    /** "%s" = sama liczba. */
    val doPonowienia: String,
    /** Zamiast [doPonowienia], gdy nic nie wymaga ponowienia. */
    val bezBledow: String,
    /** "%s" = sama liczba. Pomijane, gdy nic nowego nie doszlo. */
    val nowoPoznane: String,
    /** "%s" = np. "5 powtorek". Uzywane, gdy najblizszy termin wypada jutro. */
    val jutro: String,
    /** "%1$s" = np. "3 dni", "%2$s" = np. "5 powtorek". */
    val zaDni: String,
    /** Gdy w zakresie nie czeka zadna powtorka. */
    val brakPowtorek: String,
    /** "%s" = sama liczba. Pokazywane tylko wtedy, gdy dalsze dni tez cos maja. */
    val zaplanowaneRazem: String,
    /** Mianownik: "1 slowo", "2 slowa", "5 slow". */
    val slowa: FormyLiczby,
    /**
     * DOPELNIACZ, bo obie uzywajace tego zdania koncza sie na "wroc do…": "do 1 powtorki",
     * "do 2 powtorek", "do 5 powtorek". Mianownik dalby "wroc do 2 powtorki" — zdanie po
     * polsku ma brzmiec po polsku, inaczej lepiej nie pisac go wcale.
     */
    val powtorki: FormyLiczby,
    /** Biernik po "za": "za 1 dzien", "za 3 dni". */
    val dni: FormyLiczby,
)

/** Etykiety kategorii dzialu. Krotkie — ida do plakietek czcionka pikselowa. */
data class TekstyDzialu(
    val naglowek: String,
    val nowe: String,
    val wNauce: String,
    val doPowtorki: String,
    val utrwalone: String,
)

/**
 * Podsumowanie nauki po rundzie — czysta arytmetyka nad tym, co juz policzyl [Srs].
 *
 * Wejsciem sa PROBY ([Attempt]), a nie wlasna lista "identyfikator + wynik". Powod: Attempt
 * niesie komplet potrzebnych pol (slowo, kierunek, rodzaj zadania, wynik), jest tym samym
 * typem, ktory ekran przekazuje do zapisu, i zmiana regul w Srs nie rozjedzie sie po cichu z
 * tym, co pokazuje podsumowanie. Rownolegly typ trzeba by utrzymywac w dwoch miejscach.
 *
 * Ten plik NICZEGO nie zapisuje i nie zna Androida — dzieki temu kazda liczba, ktora zobaczy
 * uczen, da sie sprawdzic testem jednostkowym.
 */
object Podsumowanie {

    /** Ile najwyzej slow proponujemy do poprawki. Ma byc "krotka poprawka", nie druga runda. */
    const val LIMIT_POPRAWKI = 5

    /**
     * Wyniki, po ktorych slowo wymaga ponowienia. To DOKLADNIE te, ktore w [Srs] ustawiaja
     * assistedDay, czyli blokuja awans tego dnia — wiec podsumowanie mowi to samo, co
     * harmonogram, i nie da sie zobaczyc "bez bledow" przy slowie, ktore i tak wroci dzis.
     * POKAZANA jest w komplecie celowo: przepisanie widocznej odpowiedzi to nie jest wiedza.
     */
    val POMOC = setOf(Outcome.BLEDNA, Outcome.PODPOWIEDZ, Outcome.POKAZANA)

    /** Waga wyniku przy ustalaniu kolejnosci poprawki: blad boli bardziej niz podpowiedz. */
    private val WAGA_POMOCY = mapOf(
        Outcome.BLEDNA to 3,
        Outcome.PODPOWIEDZ to 2,
        Outcome.POKAZANA to 1,
    )

    /**
     * [Direction.RANDOM] to ustawienie "losuj kierunek", a nie kierunek (patrz [Srs.key]).
     * Przy liczeniu zakresu rozwijamy je na oba prawdziwe kierunki, bo przy tym ustawieniu
     * uczen naprawde cwiczy oba i oba maja swoj termin.
     */
    private fun rozwin(kierunek: Direction): List<Direction> =
        if (kierunek == Direction.RANDOM) {
            listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)
        } else {
            listOf(kierunek)
        }

    /**
     * Podsumowanie jednej rundy.
     *
     * @param proby proby z tej rundy, w kolejnosci, w jakiej padly.
     * @param przed mapa postepu SPRZED rundy — sluzy wylacznie do rozpoznania, ktore slowa
     *   wyszly z pudelka 0; bez niej "nowe slowa" trzeba by zgadywac.
     * @param po mapa postepu PO rundzie — stad biora sie terminy powtorek.
     * @param zakres identyfikatory slow, ktorych dotyczy runda (zwykle caly grany zestaw).
     *   Terminy liczymy tylko w nim, zeby "wroc jutro" mowilo o tym, co uczen wlasnie cwiczyl.
     * @param kierunek ustawienie kierunku z opcji; [Direction.RANDOM] jest tu dozwolone.
     * @param dzis dzien od epoki UTC — [Srs.dayOf].
     */
    fun rundy(
        proby: List<Attempt>,
        przed: Map<String, WordProgress>,
        po: Map<String, WordProgress>,
        zakres: List<String>,
        kierunek: Direction,
        dzis: Int,
        limitPoprawki: Int = LIMIT_POPRAWKI,
    ): WynikRundy {
        // Proba przerwana nie odbyla sie (Srs nie zmienia po niej nic), wiec nie moze tez
        // podbijac licznika przecwiczonych. Pusty identyfikator to wpis sprzed migracji —
        // nie ma do czego przypiac postepu, wiec nie ma tez czego o nim powiedziec.
        val realne = proby.filter { it.outcome != Outcome.PRZERWANA && it.entryId.isNotBlank() }

        // LinkedHashSet: slowo liczy sie RAZ, choc w rundzie moglo wypasc kilka razy, a kolejnosc
        // pierwszego wystapienia zostaje — poprawka ma isc po kolei tak, jak szla runda.
        val przecwiczone = LinkedHashSet<String>()
        val zPomoca = LinkedHashSet<String>()
        realne.forEach {
            przecwiczone += it.entryId
            if (it.outcome in POMOC) zPomoca += it.entryId
        }

        // Kierunek RANDOM w probie to blad warstwy gry (ma rozlosowac PRZED pytaniem), ale
        // wyjatek z ekranu podsumowania skasowalby cala skonczona runde. Slowo zostaje w
        // licznikach opartych o sam identyfikator, wypada tylko tam, gdzie potrzebny jest klucz.
        val zKluczem = realne.filter { it.direction != Direction.RANDOM }

        val nowoPoznane = zKluczem
            .filter { proba ->
                Srs.progressOf(przed, proba.entryId, proba.direction).box == Srs.NO_BOX &&
                    Srs.progressOf(po, proba.entryId, proba.direction).box > Srs.NO_BOX
            }
            .map { it.entryId }
            .toSet()
            .size

        val kierunki = rozwin(kierunek)
        val terminy = zakres.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .flatMap { id -> kierunki.asSequence().map { Srs.progressOf(po, id, it).dueDay } }
            .filter { it > dzis }
            .toList()
        val najblizszyDzien = terminy.minOrNull()

        return WynikRundy(
            przecwiczone = przecwiczone.size,
            doPonowienia = zPomoca.size,
            nowoPoznane = nowoPoznane,
            zaplanowane = terminy.size,
            najblizszaZaDni = najblizszyDzien?.minus(dzis),
            naNajblizszyDzien = terminy.count { it == najblizszyDzien },
            doPoprawki = doPoprawki(zKluczem, zPomoca, po, limitPoprawki),
        )
    }

    /**
     * Kilka slow do krotkiej poprawki, od najwazniejszego. Kolejnosc bierze sie z danych, nie
     * z widzimisie: najpierw ciezar tego, co sie stalo w rundzie (blad przed podpowiedzia,
     * podpowiedz przed pokazana odpowiedzia), przy remisie slowo z dluzsza historia pomylek.
     * Sortowanie jest stabilne, wiec przy pelnym remisie zostaje kolejnosc rundy.
     */
    private fun doPoprawki(
        proby: List<Attempt>,
        zPomoca: Set<String>,
        po: Map<String, WordProgress>,
        limit: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val ciezar = HashMap<String, Int>()
        val pomylki = HashMap<String, Int>()
        proby.forEach { proba ->
            if (proba.entryId !in zPomoca) return@forEach
            val waga = WAGA_POMOCY[proba.outcome] ?: 0
            ciezar[proba.entryId] = maxOf(ciezar[proba.entryId] ?: 0, waga)
            val historia = Srs.progressOf(po, proba.entryId, proba.direction).mistakes
            pomylki[proba.entryId] = maxOf(pomylki[proba.entryId] ?: 0, historia)
        }
        return zPomoca
            .filter { it in ciezar }
            .sortedWith(
                compareByDescending<String> { ciezar[it] ?: 0 }
                    .thenByDescending { pomylki[it] ?: 0 }
            )
            .take(limit)
    }

    /** Kategoria jednego slowa. Kolejnosc warunkow jest tu cala trescia — patrz [dzialu]. */
    fun kategoria(postep: WordProgress, dzis: Int): KategoriaDzialu = when {
        // Slowo bez historii jest formalnie zalegle (dueDay = 0), ale nazwanie go "do powtorki"
        // byloby klamstwem: nie ma czego powtarzac. Dlatego pudelko 0 sprawdzamy PIERWSZE.
        Srs.stageOf(postep) == Stage.BRAK_DANYCH -> KategoriaDzialu.NOWE

        // Termin przed utrwaleniem: slowo z pudelka 3, ktoremu minelo siedem dni, naprawde
        // czeka na sprawdzenie. "Utrwalone" znaczy "potwierdzone w terminie", a nie "zaliczone
        // raz na zawsze" — inaczej licznik utrwalonych rosnie i nigdy nie mowi o pracy, ktora
        // dzis czeka.
        Srs.isDue(postep, dzis) -> KategoriaDzialu.DO_POWTORKI

        Srs.stageOf(postep) == Stage.UTRWALONE -> KategoriaDzialu.UTRWALONE
        else -> KategoriaDzialu.W_NAUCE
    }

    /**
     * Rozklad dzialu (zestawu, tematu) na cztery kategorie.
     *
     * CELOWO nie ma tu ani procentu, ani oceny, ani prognozy wyniku matury: z pudelek Leitnera
     * nie da sie takiej liczby uczciwie wyprowadzic, a podana uczniowi zaczela by zyc wlasnym
     * zyciem. Sa cztery liczby slow i tyle.
     *
     * @param entryIds slowa dzialu; puste i powtorzone identyfikatory sa pomijane, wiec
     *   [PostepDzialu.wszystkie] moze byc mniejsze niz rozmiar listy — ale zawsze rowna sie
     *   sumie kategorii.
     */
    fun dzialu(
        progress: Map<String, WordProgress>,
        entryIds: List<String>,
        kierunek: Direction,
        dzis: Int,
    ): PostepDzialu {
        val slowa = entryIds.filter { it.isNotBlank() }.distinct()
        val kierunki = rozwin(kierunek)
        var nowe = 0
        var doPowtorki = 0
        var wNauce = 0
        var utrwalone = 0
        slowa.forEach { id ->
            val najslabsza = kierunki
                .map { kategoria(Srs.progressOf(progress, id, it), dzis) }
                .minByOrNull { it.zaawansowanie } ?: KategoriaDzialu.NOWE
            when (najslabsza) {
                KategoriaDzialu.NOWE -> nowe++
                KategoriaDzialu.DO_POWTORKI -> doPowtorki++
                KategoriaDzialu.W_NAUCE -> wNauce++
                KategoriaDzialu.UTRWALONE -> utrwalone++
            }
        }
        return PostepDzialu(slowa.size, nowe, doPowtorki, wNauce, utrwalone)
    }

    /**
     * Polska liczba mnoga: 1 slowo, 2..4 slowa, 5 slow, ale 12..14 juz "slow".
     * Angielski dostaje te sama forme w [FormyLiczby.kilka] i [FormyLiczby.wiele].
     */
    fun forma(n: Int, formy: FormyLiczby): String {
        val bez = if (n < 0) -n else n
        val ostatnia = bez % 10
        val dwie = bez % 100
        return when {
            bez == 1 -> formy.jeden
            ostatnia in 2..4 && dwie !in 12..14 -> formy.kilka
            else -> formy.wiele
        }
    }

    /** "12 slow" — liczba razem z odmieniona nazwa. */
    fun liczba(n: Int, formy: FormyLiczby): String = "$n " + forma(n, formy)

    /**
     * Zdania podsumowania, gotowe do wyswietlenia. Rozdzielenie "liczby" od "zdan" jest tu po
     * to, zeby test mogl sprawdzic OBIE rzeczy osobno: czy liczby zgadzaja sie z danymi i czy
     * zdanie o nich nie klamie.
     */
    fun linie(wynik: WynikRundy, teksty: TekstyRundy): List<String> {
        val out = ArrayList<String>(4)
        if (wynik.pusta) {
            out += teksty.brakProb
        } else {
            out += wstaw(teksty.przecwiczone, liczba(wynik.przecwiczone, teksty.slowa))
            out += if (wynik.doPonowienia == 0) {
                teksty.bezBledow
            } else {
                wstaw(teksty.doPonowienia, wynik.doPonowienia.toString())
            }
            if (wynik.nowoPoznane > 0) {
                out += wstaw(teksty.nowoPoznane, wynik.nowoPoznane.toString())
            }
        }
        val zaDni = wynik.najblizszaZaDni
        val naDzien = liczba(wynik.naNajblizszyDzien, teksty.powtorki)
        out += when {
            zaDni == null || wynik.naNajblizszyDzien == 0 -> teksty.brakPowtorek
            zaDni <= 1 -> wstaw(teksty.jutro, naDzien)
            else -> wstaw(teksty.zaDni, liczba(zaDni, teksty.dni), naDzien)
        }
        // Druga liczba pojawia sie TYLKO wtedy, gdy naprawde rozni sie od pierwszej — inaczej
        // uczen czytalby dwa razy to samo innymi slowami.
        if (wynik.zaplanowane > wynik.naNajblizszyDzien) {
            out += wstaw(teksty.zaplanowaneRazem, wynik.zaplanowane.toString())
        }
        return out
    }

    /**
     * Podstawienie wartosci w szablon. Recznie, a nie przez String.format, bo format rzuca
     * wyjatkiem przy niezgodnej liczbie znacznikow — a szablon przychodzi z tabeli tlumaczen
     * i literowka w niej nie moze wywalic ekranu po skonczonej rundzie.
     */
    private fun wstaw(szablon: String, vararg wartosci: String): String {
        var wynik = szablon
        wartosci.forEachIndexed { i, wartosc ->
            wynik = wynik.replace("%" + (i + 1) + "\$s", wartosc)
        }
        if (wartosci.isNotEmpty()) wynik = wynik.replace("%s", wartosci[0])
        return wynik
    }
}
