package com.matura.app.learn

import com.matura.app.model.Direction
import com.matura.app.model.Dorobek
import com.matura.app.model.SaveData
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet

/**
 * Liczby, z ktorych licza sie odznaki. Kazda jest LICZBA PRAWDZIWYCH RZECZY policzona z zapisu
 * przez [Odznaki.liczniki]: slow, dni, powtorek, gier. Zadna nie jest procentem ani ocena.
 *
 * Piec pierwszych dotyczy wylacznie slownika gry (katalogu dolaczonego do aplikacji) i opiera
 * sie na [WordProgress.szczyt], czyli na najwyzszym pudelku, do jakiego slowo kiedykolwiek
 * doszlo: pomylka cofa pudelko, ale nie moze cofnac licznika, bo uczniowi niczego nie odbieramy.
 */
data class Liczniki(
    /** Slowa, ktore wyszly z pudelka 0 w ktorymkolwiek kierunku. */
    val poznane: Int = 0,

    /** Slowa, ktore doszly do pudelka 3 w ktorymkolwiek kierunku. */
    val utrwalone: Int = 0,

    /** Slowa, ktore doszly co najmniej do pudelka 2 w OBU kierunkach. */
    val obieStrony: Int = 0,

    /** Dzialy, w ktorych co najmniej [Odznaki.PROG_DZIALU] slow doszlo do pudelka 2. */
    val dzialy: Int = 0,

    /** Zestawy slownika gry, w ktorych KAZDE slowo doszlo do pudelka 3 w ktorymkolwiek kierunku. */
    val zestawy: Int = 0,

    /** Powtorki w terminie zaliczone z pamieci ([Dorobek.powtorki]). */
    val powtorki: Int = 0,

    /** Slowa utrwalone mimo wczesniejszej pomylki ([Dorobek.naprawione]). */
    val naprawione: Int = 0,

    /** Dni z zaliczonym celem, lacznie ([Dorobek.dniNauki]). */
    val dniNauki: Int = 0,

    /** Najdluzsza seria dni z celem ([Dorobek.rekordSerii]). */
    val rekordSerii: Int = 0,

    /** W ilu trybach gry uczen ma choc jeden wynik na liscie, czyli zagral o wynik. */
    val tryby: Int = 0,

    // Cztery liczniki profilu, pod nazwami z Profile, bo to te same liczby.
    val gamesPlayed: Int = 0,
    val totalKills: Int = 0,
    val bestScore: Int = 0,
    val bestStreak: Int = 0,
)

/** Grupa odznak: naglowek sekcji na ekranie. Kolejnosc stalych to kolejnosc sekcji. */
enum class GrupaOdznak(val pl: String, val en: String) {
    /** Slowa: poznane, utrwalone, powtorzone w terminie, umiane w obie strony, naprawione. */
    NAUKA("NAUKA", "LEARNING"),

    /** Dni z zaliczonym celem i serie takich dni. */
    REGULARNOSC("REGULARNOŚĆ", "HABIT"),

    /** Pokrycie slownika gry: cale zestawy i dzialy tematyczne. */
    DZIALY("DZIAŁY", "TOPICS"),

    /** Gra: to, co do tej pory liczyl ekran OSIAGNIECIA. */
    GRA("GRA", "GAME"),
}

/**
 * Rodzina odznak: kolejne stopnie liczone z JEDNEGO licznika (25 slow, 75 slow, 250 slow).
 * Ekran pokazuje rodzine jednym wierszem: najwyzszy zdobyty stopien i droge do nastepnego.
 * Nazwy stalych nie sa czescia zapisu; w zapisie leza nazwy stalych [Odznaka].
 */
enum class RodzinaOdznak(val grupa: GrupaOdznak, val licznik: (Liczniki) -> Int) {
    SLOWA(GrupaOdznak.NAUKA, Liczniki::poznane),
    UTRWALONE(GrupaOdznak.NAUKA, Liczniki::utrwalone),
    POWTORKI(GrupaOdznak.NAUKA, Liczniki::powtorki),
    OBIE(GrupaOdznak.NAUKA, Liczniki::obieStrony),
    NAPRAWA(GrupaOdznak.NAUKA, Liczniki::naprawione),
    DNI(GrupaOdznak.REGULARNOSC, Liczniki::dniNauki),
    SERIA(GrupaOdznak.REGULARNOSC, Liczniki::rekordSerii),
    ZESTAWY(GrupaOdznak.DZIALY, Liczniki::zestawy),
    DZIALY(GrupaOdznak.DZIALY, Liczniki::dzialy),
    TRYBY(GrupaOdznak.GRA, Liczniki::tryby),

    // Cztery pary dawnych osiagniec ([com.matura.app.model.Achievement]).
    POKONANE(GrupaOdznak.GRA, Liczniki::totalKills),
    GRY(GrupaOdznak.GRA, Liczniki::gamesPlayed),
    MNOZNIK(GrupaOdznak.GRA, Liczniki::bestStreak),
    WYNIK(GrupaOdznak.GRA, Liczniki::bestScore),
}

// Opisy rodzin. Wszystkie stopnie rodziny mowia to samo, bo opis tlumaczy, CO jest liczone,
// a ile trzeba, mowi tytul i pasek postepu. Slownik, zeby kazde slowo znaczylo jedno:
//  - "zaliczone choc raz" to wyjscie z pudelka 0, takze przez rozpoznanie;
//  - "z pamieci po przerwie" to pudelko 2, mozliwe tylko odpowiedzia wpisana albo powiedziana
//    co najmniej dzien po pierwszym zaliczeniu;
//  - "utrwalenie" to pudelko 3;
//  - "slownik gry" to katalog dolaczony do aplikacji. Wlasne zestawy ucznia sie do tych
//    licznikow NIE licza, bo inaczej dalo by sie je nabic lista pieciu latwych slow. Dni nauki,
//    serie i powtorki w terminie licza sie z kazdej nauki, takze na wlasnych zestawach,
//    i dlatego ich opisy o slowniku gry nie wspominaja.
// Bez "na stale", "na zawsze" i "z rzedu": utrwalenie to stan harmonogramu, a nie obietnica,
// a seria z dniami wolnymi nie jest doslownie dzien po dniu.
private const val SLOWA_PL = "Słowa ze słownika gry zaliczone choć raz: z pamięci albo w trybach wyboru."
private const val SLOWA_EN = "Dictionary words passed at least once: from memory or in a choice mode."
private const val UTRWALONE_PL =
    "Słowa ze słownika gry wpisane albo powiedziane z pamięci w trzech różnych terminach. " +
        "Liczy się każde, które choć raz doszło do utrwalenia."
private const val UTRWALONE_EN =
    "Dictionary words typed or spoken from memory on three separate scheduled days. " +
        "Every word that got there at least once counts."
private const val POWTORKI_PL = "Powtórki zaliczone z pamięci wtedy, gdy słowo wróciło."
private const val POWTORKI_EN = "Reviews passed from memory when the word came back."
private const val OBIE_PL = "Słowa zaliczone z pamięci po przerwie w obu kierunkach tłumaczenia."
private const val OBIE_EN = "Words passed from memory after a break in both translation directions."
private const val NAPRAWA_PL = "Słowa utrwalone mimo wcześniejszej pomyłki. Każde liczy się raz."
private const val NAPRAWA_EN = "Words consolidated despite an earlier mistake. Each counts once."
private const val DNI_PL = "Dni z zaliczonym celem dnia. Nie muszą być po kolei."
private const val DNI_EN = "Days with the daily goal met. They need not be consecutive."
private const val SERIA_PL =
    "Najdłuższa seria dni z zaliczonym celem. Opuszczony dzień pokrywa dzień wolny z zapasu."
private const val SERIA_EN =
    "Your longest streak of days with the goal met. " +
        "A missed day is covered by a day off from your reserve."
private const val ZESTAWY_PL =
    "Zestawy ze słownika gry, w których każde słowo choć raz doszło do utrwalenia."
private const val ZESTAWY_EN = "Dictionary sets in which every word has been consolidated at least once."
private const val DZIALY_PL =
    "Działy, w których co najmniej 10 słów masz zaliczonych z pamięci po przerwie."
private const val DZIALY_EN = "Topics where you have passed at least 10 words from memory after a break."

/**
 * Odznaki. Kazda ma rodzine (a przez nia grupe i licznik, z ktorego sie liczy), prog ([cel])
 * oraz tytul i opis w obu jezykach.
 *
 * NAZWY STALYCH SA CZESCIA ZAPISU I NIE WOLNO ICH ZMIENIAC: [Dorobek.odznaki] trzyma zdobyte
 * odznaki pod nazwa stalej. Zmiana nazwy odebralaby uczniowi odznake, ktora juz ma, a ekran
 * oglosilby mu ja drugi raz. Lista jest zamrozona od wydania, w ktorym ksiega weszla; pelny
 * spis nazw trzyma test (OdznakiTest). Wyzsze stopnie dopisuje sie jako NOWE stale. Zmieniac
 * wolno cztery teksty, a kolejnosc stalych tylko z glowa: ustawia ekran, a stopnie jednej
 * rodziny musza stac obok siebie, od najnizszego progu.
 *
 * Osiem odznak z czterech ostatnich rodzin to dawne osiagniecia
 * ([com.matura.app.model.Achievement]) POD TYMI SAMYMI NAZWAMI, z tymi samymi tekstami
 * i progami. Tamten enum i jego ekran zostaja na razie nietkniete; test pilnuje, zeby oba spisy
 * mowily o profilu to samo.
 *
 * PROGI sa ustawione pod dzisiejszy harmonogram, w ktorym pudelko 3 wraca co 7 dni, wiec
 * liczba slow w obiegu rosnie wolno (w symulacji z projektu sama sesja dzienna daje do matury
 * okolo 80 slow). Prog w rodzaju "tysiac slow" bylby odznaka, ktorej nie zdobedzie nikt.
 * Gdy odstepy powtorek sie wydluza, wyzsze stopnie dojda jako nowe stale.
 *
 * TEKSTY. Tytul idzie na ekran czcionka pikselowa: najwyzej 24 znaki i tylko litery (takze
 * polskie), cyfry, spacja oraz . , ! ? : / - _ + ' (bez nawiasow, procentu i cudzyslowow).
 * Tytuly nowych odznak sa wersalikami, jak etapy. Opis jest pisany czcionka systemowa i mowi,
 * CO jest liczone; jest wspolny dla calej rodziny (patrz stale nad tym enumem). Tytuly i opisy
 * osmiu dawnych osiagniec zostaja slowo w slowo, razem z brakiem kropki na koncu.
 */
enum class Odznaka(
    val rodzina: RodzinaOdznak,
    val cel: Int,
    val tytulPl: String,
    val tytulEn: String,
    val opisPl: String,
    val opisEn: String,
) {
    // --- nauka --------------------------------------------------------------------------
    SLOWA_1(RodzinaOdznak.SLOWA, 1, "PIERWSZE SŁOWO", "FIRST WORD", SLOWA_PL, SLOWA_EN),
    SLOWA_25(RodzinaOdznak.SLOWA, 25, "25 SŁÓW", "25 WORDS", SLOWA_PL, SLOWA_EN),
    SLOWA_75(RodzinaOdznak.SLOWA, 75, "75 SŁÓW", "75 WORDS", SLOWA_PL, SLOWA_EN),
    SLOWA_250(RodzinaOdznak.SLOWA, 250, "ĆWIERĆ TYSIĄCA", "QUARTER THOUSAND", SLOWA_PL, SLOWA_EN),

    UTRWALONE_5(
        RodzinaOdznak.UTRWALONE, 5, "5 UTRWALONYCH", "5 CONSOLIDATED", UTRWALONE_PL, UTRWALONE_EN,
    ),
    UTRWALONE_20(
        RodzinaOdznak.UTRWALONE, 20, "20 UTRWALONYCH", "20 CONSOLIDATED", UTRWALONE_PL, UTRWALONE_EN,
    ),
    UTRWALONE_60(
        RodzinaOdznak.UTRWALONE, 60, "60 UTRWALONYCH", "60 CONSOLIDATED", UTRWALONE_PL, UTRWALONE_EN,
    ),
    UTRWALONE_200(
        RodzinaOdznak.UTRWALONE, 200, "200 UTRWALONYCH", "200 CONSOLIDATED", UTRWALONE_PL, UTRWALONE_EN,
    ),

    POWTORKI_10(RodzinaOdznak.POWTORKI, 10, "W TERMINIE", "ON TIME", POWTORKI_PL, POWTORKI_EN),
    POWTORKI_100(
        RodzinaOdznak.POWTORKI, 100, "STO W TERMINIE", "A HUNDRED ON TIME", POWTORKI_PL, POWTORKI_EN,
    ),
    POWTORKI_500(
        RodzinaOdznak.POWTORKI, 500, "PIĘĆSET W TERMINIE", "FIVE HUNDRED ON TIME",
        POWTORKI_PL, POWTORKI_EN,
    ),
    POWTORKI_1500(
        RodzinaOdznak.POWTORKI, 1500, "1500 W TERMINIE", "1500 ON TIME", POWTORKI_PL, POWTORKI_EN,
    ),

    OBIE_10(RodzinaOdznak.OBIE, 10, "W OBIE STRONY", "BOTH WAYS", OBIE_PL, OBIE_EN),
    OBIE_50(RodzinaOdznak.OBIE, 50, "TAM I Z POWROTEM", "THERE AND BACK AGAIN", OBIE_PL, OBIE_EN),

    NAPRAWA_1(
        RodzinaOdznak.NAPRAWA, 1, "DO TRZECH RAZY SZTUKA", "THIRD TIME LUCKY", NAPRAWA_PL, NAPRAWA_EN,
    ),
    NAPRAWA_5(
        RodzinaOdznak.NAPRAWA, 5, "TRUDNE? JUŻ NIE", "HARD? NOT ANYMORE", NAPRAWA_PL, NAPRAWA_EN,
    ),
    NAPRAWA_25(RodzinaOdznak.NAPRAWA, 25, "25 PO POPRAWCE", "25 FIXED", NAPRAWA_PL, NAPRAWA_EN),

    // --- regularnosc --------------------------------------------------------------------
    DNI_10(RodzinaOdznak.DNI, 10, "10 DNI NAUKI", "10 STUDY DAYS", DNI_PL, DNI_EN),
    DNI_25(RodzinaOdznak.DNI, 25, "25 DNI NAUKI", "25 STUDY DAYS", DNI_PL, DNI_EN),
    DNI_50(RodzinaOdznak.DNI, 50, "50 DNI NAUKI", "50 STUDY DAYS", DNI_PL, DNI_EN),
    // Jedyny stopien z wlasnym dopiskiem: sto dni to u maturzystow liczba z tradycja.
    DNI_100(
        RodzinaOdznak.DNI, 100, "STO DNI NAUKI", "A HUNDRED STUDY DAYS",
        "$DNI_PL Tyle, ile od studniówki do matury.",
        "$DNI_EN As many as from the studniówka ball to the exam.",
    ),

    // Serie koncza sie na 30 celowo: dalej licznik bylby wazniejszy od slow.
    SERIA_3(RodzinaOdznak.SERIA, 3, "SERIA 3", "STREAK 3", SERIA_PL, SERIA_EN),
    SERIA_7(RodzinaOdznak.SERIA, 7, "SERIA 7", "STREAK 7", SERIA_PL, SERIA_EN),
    SERIA_14(RodzinaOdznak.SERIA, 14, "SERIA 14", "STREAK 14", SERIA_PL, SERIA_EN),
    SERIA_30(RodzinaOdznak.SERIA, 30, "SERIA 30", "STREAK 30", SERIA_PL, SERIA_EN),

    // --- zestawy i dzialy ---------------------------------------------------------------
    ZESTAW_1(RodzinaOdznak.ZESTAWY, 1, "PIERWSZY ZESTAW", "FIRST SET", ZESTAWY_PL, ZESTAWY_EN),
    ZESTAWY_3(RodzinaOdznak.ZESTAWY, 3, "TRZY ZESTAWY", "THREE SETS", ZESTAWY_PL, ZESTAWY_EN),
    ZESTAWY_8(RodzinaOdznak.ZESTAWY, 8, "OSIEM ZESTAWÓW", "EIGHT SETS", ZESTAWY_PL, ZESTAWY_EN),
    ZESTAWY_15(
        RodzinaOdznak.ZESTAWY, 15, "PIĘTNAŚCIE ZESTAWÓW", "FIFTEEN SETS", ZESTAWY_PL, ZESTAWY_EN,
    ),

    DZIAL_1(RodzinaOdznak.DZIALY, 1, "PIERWSZY DZIAŁ", "FIRST TOPIC", DZIALY_PL, DZIALY_EN),
    DZIALY_5(RodzinaOdznak.DZIALY, 5, "PIĘĆ DZIAŁÓW", "FIVE TOPICS", DZIALY_PL, DZIALY_EN),
    DZIALY_14(RodzinaOdznak.DZIALY, 14, "OBJAZDÓWKA", "GRAND TOUR", DZIALY_PL, DZIALY_EN),

    // --- gra: jedna nowa i osiem dawnych osiagniec -----------------------------------------
    TRYBY_4(
        RodzinaOdznak.TRYBY, 4, "CZTERY TRYBY", "ALL FOUR MODES",
        "Zagraj o wynik w każdym z czterech trybów.",
        "Play a ranked game in each of the four modes.",
    ),
    FIRST_BLOOD(
        RodzinaOdznak.POKONANE, 1, "Pierwszy celny strzał", "First hit",
        "Pokonaj pierwszego potworka", "Defeat your first monster",
    ),
    SHARPSHOOTER(
        RodzinaOdznak.POKONANE, 100, "Strzelec wyborowy", "Sharpshooter",
        "Pokonaj 100 potworków", "Defeat 100 monsters",
    ),
    ROOKIE(RodzinaOdznak.GRY, 5, "Rekrut", "Rookie", "Rozegraj 5 gier", "Play 5 games"),
    VETERAN(RodzinaOdznak.GRY, 25, "Weteran", "Veteran", "Rozegraj 25 gier", "Play 25 games"),
    // Mnoznik x3 wlacza sie przy serii 6 trafien, a x5 przy 9: progi z Achievement.unlockedBy.
    COMBO_X3(
        RodzinaOdznak.MNOZNIK, 6, "Seria x3", "Combo x3",
        "Osiągnij mnożnik x3", "Reach the x3 multiplier",
    ),
    COMBO_X5(
        RodzinaOdznak.MNOZNIK, 9, "Seria x5", "Combo x5",
        "Osiągnij mnożnik x5", "Reach the x5 multiplier",
    ),
    SCORE_500(
        RodzinaOdznak.WYNIK, 500, "Pięćset", "Five hundred",
        "Zdobądź 500 punktów w jednej grze", "Score 500 in a single game",
    ),
    SCORE_2000(
        RodzinaOdznak.WYNIK, 2000, "Dwa tysiące", "Two thousand",
        "Zdobądź 2000 punktów w jednej grze", "Score 2000 in a single game",
    );

    val grupa: GrupaOdznak get() = rodzina.grupa

    /** Licznik, z ktorego liczy sie ta odznaka: licznik jej rodziny. */
    val licznik: (Liczniki) -> Int get() = rodzina.licznik
}

/**
 * Liczenie i przyznawanie odznak. Czyste funkcje nad zapisem: bez Androida, bez zegara, dzien
 * wchodzi parametrem. Zdobyte odznaki utrwala Repository w [Dorobek.odznaki].
 */
object Odznaki {

    /**
     * Ile slow dzialu trzeba zaliczyc z pamieci po przerwie, zeby dzial liczyl sie do odznak.
     * Ta sama liczba stoi w opisie rodziny dzialow; pilnuje tego test.
     */
    const val PROG_DZIALU = 10

    /**
     * Dzien wpisywany odznakom przyznanym w migracji zapisu: "zanim powstala ksiega". Ekran ma
     * przy takiej odznace nie pokazywac daty, bo prawdziwej nikt nie zapisal.
     */
    const val DZIEN_SPRZED_KSIEGI = 0

    /**
     * Liczniki z calego zapisu.
     *
     * @param katalog slownik gry DOLACZONY DO APLIKACJI (SampleData.builtInSets), a nie
     *   zestawy z zapisu.
     *
     * Slowa liczymy po ROZNYCH identyfikatorach wpisow katalogu, a nie po mapie postepu ani
     * po zestawach z zapisu. Kazda z tych drog zawyzalaby wynik:
     *  - mapa postepu nie jest sprzatana, wiec po korekcie katalogu to samo slowo lezy w niej
     *    pod starym i pod nowym kluczem (patrz SaveData.progress), a obok lezy postep
     *    z wlasnych zestawow ucznia;
     *  - czesc hasel powtarza sie miedzy dzialami i dzieli jeden identyfikator, wiec liczone
     *    zestaw po zestawie wchodzilyby do sumy dwa razy;
     *  - zapis trzyma WLASNA kopie zestawow wbudowanych, ktora uczen moze otworzyc w edytorze.
     *    Skrocilby zestaw do trzech latwych slow i mial "caly zestaw utrwalony", a wiersz
     *    dopisany do kopii wygladalby w danych jak haslo ze slownika. Katalogu z aplikacji
     *    uczen zmienic nie moze.
     */
    fun liczniki(data: SaveData, katalog: List<WordSet>): Liczniki {
        // Szczyt kazdego slowa liczony RAZ: identyfikatory powtarzaja sie miedzy zestawami.
        val szczyty = HashMap<String, Szczyty>()
        for (zestaw in katalog) {
            for (wpis in zestaw.entries) {
                if (wpis.id.isBlank() || szczyty.containsKey(wpis.id)) continue
                szczyty[wpis.id] = Szczyty(
                    szczyt(data.progress, wpis.id, Direction.TERM_TO_DEF),
                    szczyt(data.progress, wpis.id, Direction.DEF_TO_TERM),
                )
            }
        }

        // Dzial liczy sie ze slow przypomnianych PO PRZERWIE (pudelko 2), nie z samych poznanych:
        // dziesiec kafli trafionych w trybie wyboru to jeszcze nie jest znajomosc dzialu.
        val poPrzerwieWDziale = HashMap<String, HashSet<String>>()
        for (zestaw in katalog) {
            // Zestaw bez dzialu w tytule nie nalezy do zadnego dzialu.
            val dzial = zestaw.category ?: continue
            for (wpis in zestaw.entries) {
                if ((szczyty[wpis.id]?.wyzszy ?: 0) >= PUDELKO_PO_PRZERWIE) {
                    poPrzerwieWDziale.getOrPut(dzial) { HashSet() }.add(wpis.id)
                }
            }
        }

        return Liczniki(
            poznane = szczyty.values.count { it.wyzszy >= Srs.FIRST_BOX },
            utrwalone = szczyty.values.count { it.wyzszy >= Srs.MAX_BOX },
            obieStrony = szczyty.values.count { it.nizszy >= PUDELKO_PO_PRZERWIE },
            dzialy = poPrzerwieWDziale.values.count { it.size >= PROG_DZIALU },
            // Pusty zestaw nie jest "utrwalony w calosci": all() na pustej liscie daje prawde.
            zestawy = katalog.count { zestaw ->
                zestaw.entries.isNotEmpty() &&
                    zestaw.entries.all { (szczyty[it.id]?.wyzszy ?: 0) >= Srs.MAX_BOX }
            },
            powtorki = data.dorobek.powtorki,
            naprawione = data.dorobek.naprawione,
            dniNauki = data.dorobek.dniNauki,
            rekordSerii = data.dorobek.rekordSerii,
            tryby = data.scores.mapTo(HashSet()) { it.mode }.size,
            gamesPlayed = data.profile.gamesPlayed,
            totalKills = data.profile.totalKills,
            bestScore = data.profile.bestScore,
            bestStreak = data.profile.bestStreak,
        )
    }

    /** Stopnie rodziny, od najnizszego progu. */
    fun stopnie(r: RodzinaOdznak): List<Odznaka> = Odznaka.entries.filter { it.rodzina == r }

    /**
     * Pierwszy stopien rodziny, ktorego w ksiedze jeszcze nie ma; null, gdy sa wszystkie.
     * To do niego ekran rysuje pasek: [postep] tego stopnia na tle jego progu.
     */
    fun nastepna(r: RodzinaOdznak, d: Dorobek): Odznaka? = stopnie(r).firstOrNull { !zdobyta(it, d) }

    /**
     * Postep odznaki do pokazania jako "12 / 25": licznik przyciety do progu. Dla odznaki juz
     * zdobytej ([zdobyta]) ekran ma pokazac pelny prog bez pytania licznika, bo odznaki nie
     * odbieramy, nawet gdyby licznik kiedys wypadl nizej.
     */
    fun postep(o: Odznaka, l: Liczniki): Int = o.licznik(l).coerceIn(0, o.cel)

    /** Czy liczniki spelniaja prog. To jeszcze nie jest "zdobyta": te stempluje [odblokuj]. */
    fun spelniona(o: Odznaka, l: Liczniki): Boolean = o.licznik(l) >= o.cel

    /** Czy odznaka jest w ksiedze. Raz zdobyta zostaje na zawsze. */
    fun zdobyta(o: Odznaka, d: Dorobek): Boolean = o.name in d.odznaki

    /** Czy w ksiedze sa juz wszystkie odznaki; wtedy nie ma po co liczyc licznikow. */
    fun komplet(d: Dorobek): Boolean = Odznaka.entries.all { it.name in d.odznaki }

    /**
     * Dopisuje do ksiegi kazda odznake, ktorej prog jest spelniony, a ktorej w ksiedze jeszcze
     * nie ma, z dniem [dzien], i odklada ja do pokazania ([Dorobek.odznakiNowe]). NICZEGO nie
     * usuwa i nie przestemplowuje: odznaka zdobyta zostaje z dniem, w ktorym padla. Nazwy,
     * ktorych ten kod nie zna (zapis z nowszej wersji), tez zostaja nietkniete. Gdy nie ma
     * czego dopisac, oddaje te sama ksiege.
     *
     * @param poCichu odznaki wchodza do ksiegi, ale NIE do pokazania. Tak robi migracja zapisu:
     *   uczen ma je od miesiecy, wiec ogloszenie ich po aktualizacji jako nowosci byloby
     *   nieprawda.
     */
    fun odblokuj(d: Dorobek, l: Liczniki, dzien: Int, poCichu: Boolean = false): Dorobek {
        val nowe = Odznaka.entries.filter { it.name !in d.odznaki && spelniona(it, l) }
        if (nowe.isEmpty()) return d
        return d.copy(
            odznaki = d.odznaki + nowe.associate { it.name to dzien },
            odznakiNowe = if (poCichu) d.odznakiNowe else d.odznakiNowe + nowe.map { it.name },
        )
    }

    /**
     * Odhacza odznaki jako pokazane: czysci [Dorobek.odznakiNowe], samych odznak nie rusza.
     * Gdy nie ma czego odhaczyc, oddaje te sama ksiege, zeby wolajacy mogl pominac zapis.
     */
    fun potwierdz(d: Dorobek): Dorobek =
        if (d.odznakiNowe.isEmpty()) d else d.copy(odznakiNowe = emptySet())

    /** Pudelko 2: slowo przypomniane z pamieci po przerwie. Prog dla dzialow i obu stron. */
    private const val PUDELKO_PO_PRZERWIE = 2

    /** Szczyty jednego slowa w obu kierunkach, juz uporzadkowane. */
    private class Szczyty(a: Int, b: Int) {
        val wyzszy: Int = maxOf(a, b)
        val nizszy: Int = minOf(a, b)
    }

    /** Szczyt razem z pudelkiem: stan sprzed migracji moze miec pudelko wyzej niz szczyt. */
    private fun szczyt(
        progress: Map<String, WordProgress>,
        entryId: String,
        kierunek: Direction,
    ): Int {
        val stan = progress[Srs.key(entryId, kierunek)] ?: return 0
        return maxOf(stan.szczyt, stan.box)
    }
}
