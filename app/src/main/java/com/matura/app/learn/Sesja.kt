package com.matura.app.learn

import com.matura.app.model.Direction
import com.matura.app.model.WordProgress
import kotlinx.serialization.Serializable

/**
 * "Dzisiejsze 5 minut" — codzienna sesja nauki jako CZYSTE DANE.
 *
 * Piec minut jest ORIENTACJA, a nie obietnica i nie limitem. Plan mowi, ile mniej wiecej
 * potrwa dzisiejsza trasa, i nic poza tym: [Sesja.czasNaKoniec] potrafi tylko ZAPROPONOWAC
 * zakonczenie, i to wylacznie miedzy odpowiedziami. Ucinanie odpowiedzi w polowie dlatego,
 * ze minal szacunek, uczyloby ucznia, ze aplikacja waznieszy ma stoper niz to, co on wlasnie
 * pisze.
 *
 * CZEGO TU NIE MA I BYC NIE MOZE:
 *
 *  - drugiego harmonogramu. O tym, co jest zalegle i kiedy wroci, decyduje wylacznie [Srs];
 *    sesja tylko UKLADA W TRASE to, co harmonogram juz wie;
 *
 *  - wiedzy ucznia. Sesja nie trzyma ani jednej odpowiedzi: kazda proba idzie od razu do
 *    zapisu (Repository.recordAttempt), wiec wyjscie z aplikacji w polowie kroku nie kasuje
 *    nauki — najwyzej trase. [StanSesji] to tylko "gdzie jestem", nie "co umiem";
 *
 *  - kary za opuszczony dzien. Zaden zapis w tym pliku nie obniza pudelka. Dzien bez nauki
 *    znaczy dokladnie tyle, ze slowa staja sie zalegle — czyli jutro sesja bedzie dluzsza,
 *    a nie ubozsza. Serii dni tez tu nie liczymy: medale w [com.matura.app.model.Achievement]
 *    zliczaja rozgrywki, a nie dni, i budowanie obok nich drugiego systemu byloby powielaniem.
 *
 * Zegar wchodzi parametrem ([Clock]), tak jak w [Srs] — inaczej nie dalo by sie przetestowac
 * ani przerwy w sesji, ani granicy doby.
 */

/** Rodzaj kroku dziennej sesji. Kolejnosc stalych = kolejnosc w trasie. */
enum class RodzajKroku {
    /** Spokojna powtorka slow zaplanowanych na dzis. Bez zyc, bez presji. */
    POWTORKA,

    /** Krotka runda gry na tym samym slownictwie — to samo slowo w innej formie. */
    GRA,

    /**
     * Zadania w kontekscie (slowo w zdaniu). Krok istnieje w planie TYLKO wtedy, gdy takie
     * zadania naprawde sa — patrz [Sesja.zaplanuj].
     */
    KONTEKST,

    /** Zwiezle podsumowanie i termin nastepnej powtorki. Zawsze ostatni i zawsze obecny. */
    PODSUMOWANIE,
}

/**
 * Jeden krok trasy.
 *
 * [cele] to identyfikatory: wpisow slownika dla [RodzajKroku.POWTORKA] i [RodzajKroku.GRA],
 * a zadan dla [RodzajKroku.KONTEKST]. Kolejnosc jest znaczaca — tak maja isc pytania.
 */
data class KrokSesji(
    val rodzaj: RodzajKroku,
    val cele: List<String> = emptyList(),

    /**
     * SPOKOJNY WARIANT: krok nie odbiera zyc. Wlaczony wszedzie tam, gdzie uczen poznaje
     * NOWE slowa — za nieznajomosc slowa widzianego pierwszy raz nie ma za co karac, a strata
     * zycia w tym miejscu uczy tylko unikania nowych slow. Warstwa UI ma z tego zrobic runde
     * bez licznika zyc; sam ten plik gry nie uruchamia.
     */
    val spokojny: Boolean = true,

    /** Szacunek, nie limit. Zero dla kroku, ktory nie ma odpowiedzi (podsumowanie). */
    val orientacyjneSekundy: Int = 0,
)

/**
 * Plan dzisiejszej sesji. Powstaje z harmonogramu i jest niezmienny — trasa nie ma sie
 * przestawiac pod reka ucznia w trakcie kroku.
 */
data class PlanSesji(
    /** Dzien harmonogramu (Srs.dayOf), dla ktorego plan powstal. */
    val dzien: Int,
    val kierunek: Direction,

    /** Slowa z historia, ktore czekaja na sprawdzenie; najpilniejsze na poczatku. */
    val powtorki: List<String> = emptyList(),

    /** Slowa, ktorych uczen jeszcze nigdy nie probowal. */
    val nowe: List<String> = emptyList(),

    val kroki: List<KrokSesji> = emptyList(),

    /**
     * Czy dla tych slow nie ma ANI JEDNEGO wpisu postepu. Wtedy [nowe] jest malym zestawem
     * startowym, a ekran ma prawo nazwac to pierwszym dniem — patrz [Sesja.MAKS_NOWYCH].
     */
    val bezHistorii: Boolean = false,
) {
    /** Cale slownictwo sesji: powtorki, potem nowe. */
    val slownictwo: List<String> get() = powtorki + nowe

    /** Nic do roboty na dzis — zostaje samo podsumowanie z terminem nastepnej powtorki. */
    val pusty: Boolean get() = slownictwo.isEmpty()

    /** Suma szacunkow krokow. ORIENTACYJNA — patrz naglowek pliku. */
    val orientacyjneSekundy: Int get() = kroki.sumOf { it.orientacyjneSekundy }
}

/**
 * Gdzie uczen jest w trasie. Same wartosci proste i listy tekstow, zeby stan dalo sie zapisac
 * i wznowic bez zadnej konwersji.
 *
 * Stan JEST czescia zapisu (SaveData.sesja), wiec kazde pole dodane po pierwszym wydaniu musi
 * miec wartosc domyslna: plik zapisany starsza wersja tego pola nie ma i dalej ma sie wczytac.
 * Nauki tu nie ma: odpowiedzi ida do harmonogramu od razu, a stan pamieta tylko trase i miejsce
 * na niej.
 */
@Serializable
data class StanSesji(
    /** Dzien, dla ktorego stan powstal. Rozny dzien = inna sesja, patrz [Sesja.wznow]. */
    val dzien: Int,

    /** Indeks biezacego kroku w [PlanSesji.kroki]. */
    val krok: Int = 0,

    /** Powtorki przerobione w tej sesji, bez duplikatow. */
    val zrobionePowtorki: List<String> = emptyList(),

    /** Nowe slowa przerobione w tej sesji, bez duplikatow. */
    val zrobioneNowe: List<String> = emptyList(),

    /** Chwila startu sesji, do liczenia orientacyjnego czasu. */
    val startMs: Long = 0L,

    /**
     * Uczen wlasnie odpowiada. Jedyne zadanie tego pola: zablokowac propozycje zakonczenia
     * w polowie odpowiedzi (patrz [Sesja.czasNaKoniec]).
     */
    val odpowiedzWToku: Boolean = false,

    val zakonczona: Boolean = false,

    /**
     * TRASA DNIA: powtorki ulozone w chwili startu sesji, w kolejnosci planu. Razem z
     * [trasaNowe] zapada raz, w [Sesja.rozpocznij], i do konca dnia sie nie zmienia, bo to
     * z niej, a nie z biezacego harmonogramu, sklada sie plan (patrz [Sesja.planNaDzis]).
     * Obie listy puste znacza "stan bez trasy": zapisala go wersja sprzed tego pola.
     */
    val trasaPowtorki: List<String> = emptyList(),

    /** Nowe slowa trasy dnia, w kolejnosci planu. Patrz [trasaPowtorki]. */
    val trasaNowe: List<String> = emptyList(),

    /**
     * Kierunek, dla ktorego ulozono trase. Znaczy cokolwiek tylko razem z niepusta trasa:
     * wartosc domyslna jest tu wylacznie po to, zeby starszy zapis dalo sie wczytac.
     */
    val trasaKierunek: Direction = Direction.TERM_TO_DEF,
) {
    val zrobione: List<String> get() = zrobionePowtorki + zrobioneNowe
}

/**
 * Ile trasy za nami — wylacznie do pokazania na ekranie.
 *
 * Przyrostek "Sesji" nie jest ozdoba: w pakiecie learn mieszka juz podsumowanie POJEDYNCZEJ
 * RUNDY (Podsumowanie.kt) i postep dzialu, wiec bez niego nazwy zderzalyby sie ze soba.
 */
data class PostepSesji(
    /** Numer biezacego kroku, liczony od 1. */
    val krokNumer: Int,
    val krokow: Int,
    val celeZrobione: Int,
    val celeRazem: Int,
)

/**
 * Zwiezle podsumowanie sesji. Kazda liczba wynika z zapisanego postepu albo z tego, co uczen
 * naprawde zrobil — zadna nie jest tu wymyslona ani zaokraglona "na zachete".
 */
data class PodsumowanieSesji(
    val powtorzone: Int,
    val nowe: Int,

    /** Ile slow objela dzisiejsza trasa: zrobione plus te, ktore jeszcze na nia czekaja. */
    val zaplanowane: Int,

    /**
     * Za ile dni wraca najblizsze slowo z tej sesji; null, gdy zadne nie ma terminu w
     * przyszlosci (czyli wszystko wciaz jest zalegle — wtedy nie ma czego zapowiadac).
     */
    val nastepnaPowtorkaZaDni: Int?,

    /** Ile slow z tej sesji bedzie zaleglych jutro. */
    val naJutro: Int,
)

object Sesja {

    /**
     * Ile slow moze objac jedna sesja.
     *
     * Liczba wynika z budzetu, a nie z gustu: orientacyjne piec minut to 300 sekund, kazde
     * slowo przechodzi przez DWA kroki z odpowiedziami (powtorka i runda gry), a jedna
     * odpowiedz to okolo [SEKUND_NA_ODPOWIEDZ]. 300 / (2 * 12) = 12 slow. Wiecej znaczyloby
     * obiecywac piec minut i regularnie brac dziesiec.
     */
    const val MAKS_SLOW = 12

    /**
     * Ile NOWYCH slow wolno wprowadzic w jednej sesji — i zarazem rozmiar MALEGO ZESTAWU
     * STARTOWEGO, bo przy pustej historii nie ma zaleglosci i caly limit idzie na nowe.
     *
     * Piec, z trzech powodow naraz:
     *
     *  1. nowe slowo musi wrocic kilka razy tego samego dnia, zeby cokolwiek znaczylo. Piec
     *     slow razy trzy spotkania to 15 odpowiedzi, czyli okolo 180 sekund — miesci sie w
     *     orientacyjnych pieciu minutach RAZEM z powtorkami. Dziesiec nowych juz sie nie miesci;
     *
     *  2. w trzech pudelkach Leitnera piec nowych slow dziennie wraca jako piec powtorek
     *     jutro, piec za trzy dni i piec za tydzien. Przy [MAKS_SLOW] rownym 12 kolejka
     *     domyka sie sama; przy dziesieciu nowych dziennie zaleglosci rosna szybciej, niz
     *     sesja jest w stanie je oddac, i po tygodniu uczen dostaje wylacznie zaleglosci;
     *
     *  3. pierwszy dzien ma sie skonczyc czyms skonczonym. Piec slow da sie w jednym
     *     posiedzeniu naprawde przerobic i zobaczyc w podsumowaniu jako komplet — zestaw
     *     startowy na dwadziescia hasel jest lista, a nie osiagnieciem.
     */
    const val MAKS_NOWYCH = 5

    /**
     * Ile sekund zajmuje jedna odpowiedz. Sluzy WYLACZNIE do szacunku dlugosci sesji.
     *
     * Dwanascie sekund to przeczytanie hasla, przypomnienie sobie i wpisanie odpowiedzi na
     * telefonie — tempo tej gry (BASE_SPAWN_INTERVAL w GameEngine to 3.8 s na potworka przy
     * pieciu na planszy) daje ten sam rzad wielkosci. Gdy pojawia sie prawdziwe pomiary,
     * zmiana tej jednej stalej poprawia caly szacunek.
     */
    const val SEKUND_NA_ODPOWIEDZ = 12

    /** Orientacyjna dlugosc sesji z zalozen — do porownania, nie do egzekwowania. */
    const val ORIENTACYJNE_SEKUNDY = 300

    /**
     * Uklada trase na dzis.
     *
     * Dobor slow, w tej kolejnosci:
     *  1. ZALEGLE z historia — od najdawniej przeterminowanych, przy rownym terminie od
     *     najslabszego pudelka. To one sa powodem, dla ktorego sesja w ogole istnieje;
     *  2. NOWE — tylko tyle, ile zostalo miejsca, i nie wiecej niz [MAKS_NOWYCH].
     *
     * Maly zestaw startowy nie jest osobna sciezka: przy pustej historii nie ma zaleglosci,
     * wiec caly limit zostaje wolny i wchodzi [MAKS_NOWYCH] nowych slow. Jedna regula zamiast
     * dwoch to jedno miejsce, w ktorym moze byc blad.
     *
     * @param entryIds slownictwo do wyboru, w kolejnosci wolajacego (tematycznej).
     * @param zadaniaWKontekscie identyfikatory zadan "slowo w zdaniu". DZIS ZAWSZE PUSTE —
     *   takich zadan aplikacja jeszcze nie ma. Pusta lista znaczy: kroku [RodzajKroku.KONTEKST]
     *   w planie NIE MA, a sesja plynnie przechodzi do podsumowania. Udawany krok, ktory
     *   niczego nie sprawdza, byloby gorszy niz jego brak.
     */
    fun zaplanuj(
        progress: Map<String, WordProgress>,
        entryIds: List<String>,
        kierunek: Direction,
        dzien: Int,
        zadaniaWKontekscie: List<String> = emptyList(),
    ): PlanSesji {
        require(kierunek != Direction.RANDOM) {
            "Direction.RANDOM to ustawienie, nie kierunek — rozlosuj go przed ulozeniem sesji"
        }

        // Wpis sprzed wersjonowania nie ma identyfikatora, wiec nie ma do czego przypiac
        // postepu. Odsiewamy go tutaj, zamiast wywracac cala sesje wyjatkiem z Srs.key.
        // distinct(), bo to samo slowo potrafi lezec w kilku zestawach naraz.
        val cele = entryIds.filter { it.isNotBlank() }.distinct()

        val bezHistorii = cele.none { progress.containsKey(Srs.key(it, kierunek)) }

        val zalegle = Srs.dueEntryIds(progress, cele, kierunek, dzien)
        val zHistoria = ArrayList<String>()
        val nowe = ArrayList<String>()
        for (id in zalegle) {
            // "Nowe" znaczy: uczen nigdy tego slowa nie probowal. Slowo z pudelkiem 0, ale z
            // pomylka sprzed godziny, ma juz historie i jest POWTORKA — inaczej podsumowanie
            // liczyloby je uczniowi jako nowe za kazdym razem, gdy sie na nim potknie.
            if (progress.containsKey(Srs.key(id, kierunek))) zHistoria.add(id) else nowe.add(id)
        }

        val powtorki = zHistoria
            .sortedWith(
                compareBy<String>(
                    { Srs.progressOf(progress, it, kierunek).dueDay },
                    { Srs.progressOf(progress, it, kierunek).box },
                )
            )
            .take(MAKS_SLOW)

        val wolne = (MAKS_SLOW - powtorki.size).coerceAtLeast(0)
        val wybraneNowe = nowe.take(minOf(MAKS_NOWYCH, wolne))

        return PlanSesji(
            dzien = dzien,
            kierunek = kierunek,
            powtorki = powtorki,
            nowe = wybraneNowe,
            kroki = krokiTrasy(powtorki, wybraneNowe, zadaniaWKontekscie),
            bezHistorii = bezHistorii,
        )
    }

    /**
     * Kroki dla trasy z podanych slow. Jedna funkcja dla planu ulozonego na swiezo ([zaplanuj])
     * i dla odtworzonego z zapisu ([planNaDzis]): gdyby kazdy skladal kroki sam, trasa po
     * powrocie z rundy moglaby miec inny ksztalt niz ta, ktora uczen zaczal.
     */
    private fun krokiTrasy(
        powtorki: List<String>,
        nowe: List<String>,
        zadaniaWKontekscie: List<String>,
    ): List<KrokSesji> {
        val slownictwo = powtorki + nowe
        val kroki = ArrayList<KrokSesji>(4)
        if (slownictwo.isNotEmpty()) {
            kroki.add(
                KrokSesji(
                    rodzaj = RodzajKroku.POWTORKA,
                    cele = slownictwo,
                    // Powtorka jest nauka, a nie rozgrywka — zyc nie ma tu w ogole.
                    spokojny = true,
                    orientacyjneSekundy = slownictwo.size * SEKUND_NA_ODPOWIEDZ,
                )
            )
            kroki.add(
                KrokSesji(
                    rodzaj = RodzajKroku.GRA,
                    // To samo slownictwo drugi raz i to jest cel: slowo spotkane raz nie jest
                    // jeszcze niczyje. Runda gry sprawdza je pod presja czasu, po spokojnym
                    // przejsciu tych samych hasel.
                    cele = slownictwo,
                    spokojny = nowe.isNotEmpty(),
                    orientacyjneSekundy = slownictwo.size * SEKUND_NA_ODPOWIEDZ,
                )
            )
        }
        if (zadaniaWKontekscie.isNotEmpty()) {
            kroki.add(
                KrokSesji(
                    rodzaj = RodzajKroku.KONTEKST,
                    cele = zadaniaWKontekscie,
                    spokojny = true,
                    // Ten sam szacunek co dla slowa, bo pomiarow zadan w kontekscie jeszcze
                    // nie ma. Gdy beda, poprawka jest jednoliniowa.
                    orientacyjneSekundy = zadaniaWKontekscie.size * SEKUND_NA_ODPOWIEDZ,
                )
            )
        }
        kroki.add(KrokSesji(rodzaj = RodzajKroku.PODSUMOWANIE))
        return kroki
    }

    /**
     * Plan, z ktorym dzis pracuje ekran sesji i kod uruchamiany po rundzie.
     *
     * TRASA DNIA JEST ZAMROZONA. Wczesniej plan powstawal od nowa z biezacego harmonogramu przy
     * kazdym wejsciu na ekran sesji, a stan pamietal tylko numer kroku i listy zrobionych slow.
     * Slowo odpowiedziane poprawnie przestaje byc zalegle, wiec wypadalo z nowego planu, a w
     * zwolnione miejsca wchodzilo piec KOLEJNYCH nigdy nieprobowanych slow. Na swiezej instalacji
     * uczen odpowiadal na slowa 1..5, wracal i w kroku pierwszym znajdowal slowa 6..10; krok
     * "to samo slownictwo jeszcze raz" nigdy nie powtarzal kroku pierwszego, licznik szedl
     * 5 / 10, potem 10 / 15 i nie mial jak sie domknac, a podsumowanie liczylo slowa, ktorych
     * nikt uczniowi nie pokazal.
     *
     * Dlatego trasa zapada raz, w [rozpocznij], i jedzie w [StanSesji]. Dopoki zapisany stan
     * jest z dzisiaj, nie jest zakonczony i ma trase, plan sklada sie Z NIEJ i w kierunku,
     * w ktorym ja ulozono (takze wtedy, gdy uczen zmienil w miedzyczasie ustawienie kierunku:
     * liczniki i podsumowanie maja mowic o tych samych slowach co runda). W kazdym innym
     * przypadku plan powstaje na swiezo z [zaplanuj]: stanu nie ma, sesja jest zakonczona, stan
     * jest z innego dnia (dzien bez nauki niczego nie odbiera, po prostu zaczyna sie nowa trasa)
     * albo zapisala go wersja, ktora trasy jeszcze nie znala. Taki stan dostaje trase w [wznow].
     *
     * Drugiego harmonogramu tu nie ma. O tym, CO trafilo na trase, zdecydowal [Srs] w chwili
     * startu, a o awansach dalej decyduje tylko on: zamrozona jest kolejnosc pracy, nie wiedza.
     *
     * @param kierunek kierunek dla planu ukladanego na swiezo; trasa z zapisu ma wlasny.
     * @param entryIds dzisiejszy slownik. Slowo trasy, ktorego juz w nim nie ma (uczen usunal
     *   wlasny zestaw), wypada z planu, bo zadna runda nie umialaby go pokazac i licznik
     *   czekalby na nie do konca dnia.
     */
    fun planNaDzis(
        zapisany: StanSesji?,
        progress: Map<String, WordProgress>,
        entryIds: List<String>,
        kierunek: Direction,
        dzien: Int,
        zadaniaWKontekscie: List<String> = emptyList(),
    ): PlanSesji {
        if (zapisany == null || zapisany.zakonczona || zapisany.dzien != dzien ||
            // RANDOM to ustawienie, nie kierunek. W zapisie moglby sie wziac tylko z uszkodzonego
            // pliku, a Srs.key rzucilby na nim wyjatkiem w srodku rysowania ekranu.
            zapisany.trasaKierunek == Direction.RANDOM
        ) {
            return zaplanuj(progress, entryIds, kierunek, dzien, zadaniaWKontekscie)
        }
        val slownik = entryIds.filter { it.isNotBlank() }.toSet()
        val powtorki = zapisany.trasaPowtorki.filter { it in slownik }.distinct()
        val nowe = zapisany.trasaNowe.filter { it in slownik && it !in powtorki }.distinct()
        if (powtorki.isEmpty() && nowe.isEmpty()) {
            return zaplanuj(progress, entryIds, kierunek, dzien, zadaniaWKontekscie)
        }
        val trasa = (powtorki + nowe).toSet()
        return PlanSesji(
            dzien = dzien,
            kierunek = zapisany.trasaKierunek,
            powtorki = powtorki,
            nowe = nowe,
            kroki = krokiTrasy(powtorki, nowe, zadaniaWKontekscie),
            // "Pierwszy dzien" ma zostac prawda takze po pierwszej rundzie, a wtedy slowa trasy
            // maja juz historie, bo uczen wlasnie na nie odpowiedzial. Pytamy wiec o reszte
            // slownika: sama trasa nowych slow i nic poza nia to dalej pierwszy dzien.
            bezHistorii = powtorki.isEmpty() && slownik.none {
                it !in trasa && progress.containsKey(Srs.key(it, zapisany.trasaKierunek))
            },
        )
    }

    /** Nowa sesja: zerowy krok, puste listy i trasa wzieta z [plan], zamrozona do konca dnia. */
    fun rozpocznij(plan: PlanSesji, clock: Clock): StanSesji =
        zTrasa(StanSesji(dzien = plan.dzien, startMs = clock.nowMs()), plan)

    /** [stan] z trasa i kierunkiem przepisanymi z [plan]. */
    private fun zTrasa(stan: StanSesji, plan: PlanSesji): StanSesji = stan.copy(
        trasaPowtorki = plan.powtorki,
        trasaNowe = plan.nowe,
        trasaKierunek = plan.kierunek,
    )

    /**
     * Stan gotowy do dalszej pracy z [plan].
     *
     * Wznawiamy TYLKO sesje z tego samego dnia harmonogramu i tylko niezakonczona. Stan z
     * wczoraj nie jest bledem i nie odbiera niczego: dzien bez nauki zmienia wylacznie to, ze
     * slowa sa zalegle, wiec dzis powstaje po prostu nowa trasa, a pudelka zostaja nietkniete.
     *
     * Wynik niesie trase z [plan]. Gdy plan pochodzi z [planNaDzis], dla stanu z trasa nic sie
     * przez to nie zmienia (plan powstal wlasnie z niej), a stan sprzed wprowadzenia trasy
     * dostaje ja w tym miejscu: od najblizszego zapisu jest zamrozona jak kazda inna.
     *
     * Indeks kroku jest przycinany do planu na wypadek takiego starszego stanu: jego plan
     * powstal na swiezo i mogl wyjsc krotszy niz ten, w ktorym uczen przerwal. Przyciecie
     * laduje wtedy na podsumowaniu (jest zawsze ostatnie), czyli tam, gdzie widac, co zrobil.
     */
    fun wznow(zapisany: StanSesji?, plan: PlanSesji, clock: Clock): StanSesji {
        if (zapisany == null || zapisany.zakonczona || zapisany.dzien != plan.dzien) {
            return rozpocznij(plan, clock)
        }
        return zTrasa(zapisany, plan).copy(
            krok = zapisany.krok.coerceIn(0, plan.kroki.lastIndex.coerceAtLeast(0)),
            // Odpowiedz przerwana wyjsciem z ekranu nie odbyla sie — patrz [przerwij].
            odpowiedzWToku = false,
        )
    }

    /**
     * Czy w sesji cokolwiek sie juz wydarzylo: uczen przeszedl choc jeden krok albo odpowiedzial
     * na choc jedno slowo. Sesja tylko otwarta i zamknieta nie jest jeszcze niczym, co warto
     * zapisywac albo nazywac wznowiona.
     */
    fun czyRuszona(stan: StanSesji): Boolean = stan.krok > 0 || stan.zrobione.isNotEmpty()

    /**
     * Czy jest co wznawiac: zapisana sesja jest z dzisiaj, nie jest zakonczona i uczen cos juz
     * w niej zrobil ([czyRuszona]). Ekran pokazuje po tym plakietke WZNOWIONA; bez ostatniego
     * warunku wisiala ona takze nad sesja, ktora uczen tylko obejrzal i z ktorej wyszedl.
     */
    fun czyWznawialna(zapisany: StanSesji?, dzien: Int): Boolean =
        zapisany != null && !zapisany.zakonczona && zapisany.dzien == dzien && czyRuszona(zapisany)

    /** Uczen zaczyna odpowiadac. Od tej chwili sesja nie zaproponuje zakonczenia. */
    fun zacznijOdpowiedz(stan: StanSesji): StanSesji =
        if (stan.odpowiedzWToku) stan else stan.copy(odpowiedzWToku = true)

    /**
     * Odpowiedz sie odbyla. Sama nauka jest juz zapisana przez warstwe danych — tutaj zostaje
     * tylko slad w trasie, zeby postep na ekranie sie zgadzal po wznowieniu.
     *
     * [Outcome.PRZERWANA] nie liczy sie do niczego, dokladnie tak jak w [Srs]: awaria silnika
     * mowy albo wyjscie z ekranu to nie jest przerobione slowo. Bledna odpowiedz liczy sie —
     * slowo BYLO powtorzone, a o tym, czy uczen je umie, mowi pudelko, nie ten licznik.
     */
    fun zaliczOdpowiedz(
        stan: StanSesji,
        plan: PlanSesji,
        entryId: String,
        outcome: Outcome,
    ): StanSesji {
        val wolny = stan.copy(odpowiedzWToku = false)
        if (outcome == Outcome.PRZERWANA) return wolny
        return when {
            entryId in stan.zrobionePowtorki || entryId in stan.zrobioneNowe -> wolny
            entryId in plan.nowe -> wolny.copy(zrobioneNowe = stan.zrobioneNowe + entryId)
            entryId in plan.powtorki -> wolny.copy(zrobionePowtorki = stan.zrobionePowtorki + entryId)
            // Slowo spoza planu (gra dobiera cele sama, gdy plan sie wyczerpie) nie psuje
            // licznikow sesji — postep nauki i tak juz poszedl do zapisu.
            else -> wolny
        }
    }

    /**
     * Wyjscie z ekranu w polowie kroku. Nie ma tu nic do zapisania poza zdjeciem flagi
     * odpowiedzi: postep nauki lezy juz na dysku, a stan trasy jest zwyklymi danymi i wolajacy
     * moze go odlozyc, gdzie chce.
     */
    fun przerwij(stan: StanSesji): StanSesji = stan.copy(odpowiedzWToku = false)

    /** Przejscie do nastepnego kroku. Za ostatnim krokiem sesja jest zakonczona. */
    fun dalej(stan: StanSesji, plan: PlanSesji): StanSesji {
        if (stan.zakonczona) return stan
        val nastepny = stan.krok + 1
        if (nastepny > plan.kroki.lastIndex) return zakoncz(stan, plan)
        return stan.copy(krok = nastepny, odpowiedzWToku = false)
    }

    fun zakoncz(stan: StanSesji, plan: PlanSesji): StanSesji = stan.copy(
        krok = plan.kroki.lastIndex.coerceAtLeast(0),
        odpowiedzWToku = false,
        zakonczona = true,
    )

    /**
     * Uczen wlasnie UKONCZYL runde, ktora nalezala do kroku [krokRundy] tego planu: trasa
     * przechodzi do nastepnego kroku sama.
     *
     * Wczesniej runda z sesji konczyla sie w MENU, a uczen musial wrocic na ekran sesji i
     * nacisnac DALEJ recznie. Kto tego nie zrobil, zastawal ten sam krok jako biezacy i
     * przerabial go drugi raz.
     *
     * Numer kroku wchodzi parametrem, bo ukonczona runde mozna zagrac JESZCZE RAZ, a druga
     * ukonczona runda tego samego kroku nie moze przesunac trasy drugi raz. Stan rusza sie wiec
     * tylko wtedy, gdy dalej stoi na [krokRundy] i ten krok jest runda ([RodzajKroku.POWTORKA]
     * albo [RodzajKroku.GRA]). W kazdym innym przypadku wraca bez zmian: uczen poszedl juz dalej
     * sam, stan jest z innego dnia niz plan albo sesja jest zakonczona.
     *
     * Funkcja NIGDY nie konczy sesji. Za ostatnia runda stoi podsumowanie i to uczen zamyka je
     * przyciskiem, a listy zrobionych slow zostaja takie, jakie byly.
     *
     * Rundy przegranej (koniec zyc) ani porzuconej wolajacy tu nie zglasza: o tym, czy runda
     * zostala ukonczona, mowi silnik gry, a nie to, ze ekran gry zniknal.
     */
    fun poUkonczonejRundzie(stan: StanSesji, plan: PlanSesji, krokRundy: Int): StanSesji {
        if (stan.zakonczona || stan.dzien != plan.dzien || stan.krok != krokRundy) return stan
        val krok = plan.kroki.getOrNull(krokRundy) ?: return stan
        if (krok.rodzaj != RodzajKroku.POWTORKA && krok.rodzaj != RodzajKroku.GRA) return stan
        // Plan z [zaplanuj] i z [planNaDzis] zawsze konczy sie podsumowaniem, wiec runda nie
        // bywa ostatnim krokiem. Gdyby byla, [dalej] zamknelaby sesje za plecami ucznia.
        if (krokRundy >= plan.kroki.lastIndex) return stan
        return dalej(stan, plan)
    }

    fun biezacy(stan: StanSesji, plan: PlanSesji): KrokSesji? =
        if (stan.zakonczona) null else plan.kroki.getOrNull(stan.krok)

    /**
     * Slownictwo CALEJ dzisiejszej trasy: to, co jeszcze przed uczniem, PLUS to, co juz zrobil.
     *
     * Przy trasie zamrozonej ([planNaDzis]) plan niesie ja cala i zrobione slowa sa jej czescia,
     * wiec suma niczego nie dodaje, a licznik domyka sie na dlugosci trasy. Zostaje dla planu
     * ulozonego na swiezo przy stanie, ktory ma juz zrobione slowa (zapis sprzed wprowadzenia
     * trasy): slowo odpowiedziane poprawnie nie jest juz zalegle i w takim planie go nie ma,
     * wiec licznik liczony z samego planu pokazywalby "5 / 0", a podsumowanie nie mialoby
     * z czego wziac terminu nastepnej powtorki.
     */
    private fun slownictwoTrasy(plan: PlanSesji, stan: StanSesji): List<String> =
        (plan.slownictwo + stan.zrobione).distinct()

    fun postep(stan: StanSesji, plan: PlanSesji): PostepSesji = PostepSesji(
        krokNumer = (stan.krok + 1).coerceAtMost(plan.kroki.size),
        krokow = plan.kroki.size,
        celeZrobione = stan.zrobione.size,
        celeRazem = slownictwoTrasy(plan, stan).size,
    )

    /** Ile sekund trwa sesja. Ujemna roznica (zegar cofniety) to zero, a nie liczba ujemna. */
    fun uplyneloSekund(stan: StanSesji, clock: Clock): Int {
        val ms = clock.nowMs() - stan.startMs
        if (ms <= 0L) return 0
        return (ms / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Czy WOLNO zaproponowac zakonczenie sesji.
     *
     * Dwa warunki naraz i drugi jest wazniejszy od pierwszego: orientacyjny czas minal ORAZ
     * uczen nie jest w polowie odpowiedzi. Funkcja niczego nie konczy — decyzje podejmuje
     * uczen, a ekran ma z tego zrobic najwyzej podpowiedz "to dobry moment, zeby skonczyc".
     *
     * Pusty plan nie ma szacunku (zero sekund), wiec nie ma tez czasu, ktory moglby minac:
     * bez tego warunku podpowiedz wisiala nad trasa, na ktorej nic nie zalega, od pierwszej
     * sekundy.
     */
    fun czasNaKoniec(stan: StanSesji, plan: PlanSesji, clock: Clock): Boolean =
        !plan.pusty &&
            !stan.odpowiedzWToku &&
            !stan.zakonczona &&
            uplyneloSekund(stan, clock) >= plan.orientacyjneSekundy

    /**
     * Podsumowanie na koniec trasy.
     *
     * [progress] ma byc mapa AKTUALNA, czyli juz z odpowiedziami z tej sesji — terminy licza
     * sie z niej, a nie z planu, wiec to, co uczen wlasnie zrobil, jest w nich widoczne.
     */
    fun podsumuj(
        plan: PlanSesji,
        stan: StanSesji,
        progress: Map<String, WordProgress>,
        dzien: Int,
    ): PodsumowanieSesji {
        val slownictwo = slownictwoTrasy(plan, stan)
        val terminy = slownictwo.map { Srs.progressOf(progress, it, plan.kierunek).dueDay }
        return PodsumowanieSesji(
            powtorzone = stan.zrobionePowtorki.size,
            nowe = stan.zrobioneNowe.size,
            zaplanowane = slownictwo.size,
            nastepnaPowtorkaZaDni = terminy.filter { it > dzien }.minOrNull()?.minus(dzien),
            naJutro = terminy.count { it <= dzien + 1 },
        )
    }
}
