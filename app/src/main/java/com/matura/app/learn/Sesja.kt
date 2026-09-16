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
 * Klasa CELOWO nie jest jeszcze czescia SaveData: pole w zapisie nalezy do warstwy danych i
 * musialoby mieszkac w pakiecie model (tak jak [WordProgress], bo model nie widzi learn).
 * Do tego czasu stan zyje tyle, co ekran — a nauka i tak nie ginie, bo nie jest w nim trzymana.
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
        val slownictwo = powtorki + wybraneNowe

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
                    spokojny = wybraneNowe.isNotEmpty(),
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

        return PlanSesji(
            dzien = dzien,
            kierunek = kierunek,
            powtorki = powtorki,
            nowe = wybraneNowe,
            kroki = kroki,
            bezHistorii = bezHistorii,
        )
    }

    fun rozpocznij(plan: PlanSesji, clock: Clock): StanSesji =
        StanSesji(dzien = plan.dzien, startMs = clock.nowMs())

    /**
     * Stan gotowy do dalszej pracy z [plan].
     *
     * Wznawiamy TYLKO sesje z tego samego dnia harmonogramu i tylko niezakonczona. Stan z
     * wczoraj nie jest bledem i nie odbiera niczego: dzien bez nauki zmienia wylacznie to, ze
     * slowa sa zalegle, wiec dzis powstaje po prostu nowa trasa, a pudelka zostaja nietkniete.
     *
     * Indeks kroku jest przycinany do planu, bo plan powstaje na nowo z aktualnego
     * harmonogramu i po drodze mogl sie SKROCIC — slowo odpowiedziane przed przerwa nie jest
     * juz zalegle i nie wraca do trasy. Gdy nie zostalo nic, przyciecie laduje na podsumowaniu
     * (jest zawsze ostatnie), czyli dokladnie tam, gdzie uczen ma zobaczyc, co zrobil.
     */
    fun wznow(zapisany: StanSesji?, plan: PlanSesji, clock: Clock): StanSesji {
        if (zapisany == null || zapisany.zakonczona || zapisany.dzien != plan.dzien) {
            return rozpocznij(plan, clock)
        }
        return zapisany.copy(
            krok = zapisany.krok.coerceIn(0, plan.kroki.lastIndex.coerceAtLeast(0)),
            // Odpowiedz przerwana wyjsciem z ekranu nie odbyla sie — patrz [przerwij].
            odpowiedzWToku = false,
        )
    }

    /** Czy jest co wznawiac; ekran pyta o to, zanim zaproponuje "WZNÓW" zamiast "ZACZNIJ". */
    fun czyWznawialna(zapisany: StanSesji?, dzien: Int): Boolean =
        zapisany != null && !zapisany.zakonczona && zapisany.dzien == dzien

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

    fun biezacy(stan: StanSesji, plan: PlanSesji): KrokSesji? =
        if (stan.zakonczona) null else plan.kroki.getOrNull(stan.krok)

    /**
     * Slownictwo CALEJ dzisiejszej trasy: to, co jeszcze przed uczniem, PLUS to, co juz zrobil.
     *
     * Suma, a nie sam plan, bo plan powstaje na nowo z harmonogramu przy kazdym wejsciu na ekran
     * sesji, a slowo odpowiedziane poprawnie nie jest juz zalegle i z planu wypada. Licznik
     * liczony z samego planu pokazywalby po przerobieniu calej trasy "5 / 0", a podsumowanie
     * nie mialoby z czego wziac terminu nastepnej powtorki — mimo ze to wlasnie odpowiedzi
     * ucznia ten termin ustawily.
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
     */
    fun czasNaKoniec(stan: StanSesji, plan: PlanSesji, clock: Clock): Boolean =
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
