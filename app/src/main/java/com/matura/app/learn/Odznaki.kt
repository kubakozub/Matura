package com.matura.app.learn

import com.matura.app.model.Direction
import com.matura.app.model.Dorobek
import com.matura.app.model.SaveData
import com.matura.app.model.WordProgress

/**
 * Liczby, z ktorych licza sie odznaki. Kazda jest LICZBA PRAWDZIWYCH RZECZY policzona z zapisu
 * przez [Odznaki.liczniki]: slow, dni, powtorek, gier. Zadna nie jest procentem ani ocena.
 *
 * Piec pierwszych dotyczy wylacznie zestawow wbudowanych i opiera sie na
 * [WordProgress.szczyt], czyli na najwyzszym pudelku, do jakiego slowo kiedykolwiek doszlo:
 * pomylka cofa pudelko, ale nie moze cofnac licznika, bo uczniowi niczego nie odbieramy.
 */
data class Liczniki(
    /** Slowa, ktore wyszly z pudelka 0 w ktorymkolwiek kierunku. */
    val poznane: Int = 0,

    /** Slowa, ktore doszly do pudelka 3 w ktorymkolwiek kierunku. */
    val utrwalone: Int = 0,

    /** Slowa, ktore doszly co najmniej do pudelka 2 w OBU kierunkach. */
    val obieStrony: Int = 0,

    /** Dzialy tematyczne, w ktorych poznanych jest co najmniej [Odznaki.PROG_DZIALU] slow. */
    val dzialy: Int = 0,

    /** Zestawy wbudowane, w ktorych KAZDE slowo doszlo do pudelka 3 w ktorymkolwiek kierunku. */
    val zestawy: Int = 0,

    /** Powtorki w terminie zaliczone z pamieci ([Dorobek.powtorki]). */
    val powtorki: Int = 0,

    /** Dni z zaliczonym celem, lacznie ([Dorobek.dniNauki]). */
    val dniNauki: Int = 0,

    /** Najdluzsza seria dni z celem ([Dorobek.rekordSerii]). */
    val rekordSerii: Int = 0,

    /** W ilu trybach gry uczen ma choc jeden wynik. */
    val tryby: Int = 0,

    // Cztery liczniki profilu, pod nazwami z Profile, bo to te same liczby.
    val gamesPlayed: Int = 0,
    val totalKills: Int = 0,
    val bestScore: Int = 0,
    val bestStreak: Int = 0,
)

/** Grupa odznak: naglowek sekcji na ekranie. Kolejnosc stalych to kolejnosc sekcji. */
enum class GrupaOdznak(val pl: String, val en: String) {
    /** Slowa: poznane, utrwalone, powtorzone w terminie, umiane w obie strony. */
    NAUKA("NAUKA", "LEARNING"),

    /** Dni z zaliczonym celem i serie takich dni. */
    REGULARNOSC("REGULARNOŚĆ", "CONSISTENCY"),

    /** Pokrycie katalogu: dzialy tematyczne i cale zestawy. */
    DZIALY("DZIAŁY I ZESTAWY", "TOPICS AND SETS"),

    /** Gra: to, co do tej pory liczyl ekran OSIAGNIECIA. */
    GRA("GRA", "GAME"),
}

/**
 * Odznaki. Kazda ma grupe, prog ([cel]), licznik, z ktorego sie liczy, oraz tytul i opis
 * w obu jezykach.
 *
 * NAZWY STALYCH SA CZESCIA ZAPISU I NIE WOLNO ICH ZMIENIAC: [Dorobek.odznaki] trzyma zdobyte
 * odznaki pod nazwa stalej. Zmiana nazwy odebralaby uczniowi odznake, ktora juz ma, a ekran
 * oglosilby mu ja drugi raz. Zmieniac wolno cztery teksty, a kolejnosc stalych tylko z glowa:
 * to ona ustawia ekran. Pelna liste nazw trzyma test (OdznakiTest).
 *
 * Osiem odznak grupy [GrupaOdznak.GRA] to dawne osiagniecia ([com.matura.app.model.Achievement])
 * POD TYMI SAMYMI NAZWAMI, z tymi samymi tekstami i progami. Tamten enum i jego ekran zostaja
 * na razie nietkniete; test pilnuje, zeby oba spisy mowily o profilu to samo.
 *
 * TEKSTY. Tytul idzie na ekran czcionka pikselowa: najwyzej 24 znaki i tylko litery (takze
 * polskie), cyfry, spacja oraz . , ! ? : / - _ + ' (bez nawiasow, procentu i cudzyslowow).
 * Opis jest pisany czcionka systemowa i ma mowic WPROST, co jest liczone, jednym wzorem:
 * czasownik, liczba, rzecz. Opisy osmiu dawnych osiagniec zostaja slowo w slowo, razem z
 * brakiem kropki na koncu.
 *
 * Slownik opisow, zeby kazde slowo znaczylo jedno:
 *  - "poznaj" to pierwsze zaliczenie slowa (pudelko 1), takze przez rozpoznanie;
 *  - "pierwsza powtorka" to wejscie do pudelka 2, mozliwe tylko odpowiedzia z pamieci;
 *  - "utrwal" to pudelko 3;
 *  - "slownik gry" i "zestaw wbudowany" to katalog aplikacji. Do odznak za slowa, dzialy
 *    i zestawy wlasne zestawy ucznia sie NIE licza, bo inaczej dalo by sie je zdobyc na
 *    liscie pieciu latwych slow. Dni nauki, serie i powtorki w terminie licza sie z kazdej
 *    nauki, takze na wlasnych zestawach, i dlatego ich opisy o slowniku gry nie wspominaja.
 */
enum class Odznaka(
    val grupa: GrupaOdznak,
    val cel: Int,
    val tytulPl: String,
    val tytulEn: String,
    val opisPl: String,
    val opisEn: String,
    val licznik: (Liczniki) -> Int,
) {
    // --- nauka --------------------------------------------------------------------------
    SLOWA_5(
        GrupaOdznak.NAUKA, 5, "Pierwsza piątka", "First five",
        "Poznaj 5 słów ze słownika gry.",
        "Learn 5 words from the game's dictionary.",
        Liczniki::poznane,
    ),
    SLOWA_50(
        GrupaOdznak.NAUKA, 50, "Pół setki", "Half a hundred",
        "Poznaj 50 słów ze słownika gry.",
        "Learn 50 words from the game's dictionary.",
        Liczniki::poznane,
    ),
    SLOWA_250(
        GrupaOdznak.NAUKA, 250, "Ćwierć tysiąca", "Quarter thousand",
        "Poznaj 250 słów ze słownika gry.",
        "Learn 250 words from the game's dictionary.",
        Liczniki::poznane,
    ),
    SLOWA_1000(
        GrupaOdznak.NAUKA, 1000, "Tysiąc słów", "A thousand words",
        "Poznaj 1000 słów ze słownika gry.",
        "Learn 1000 words from the game's dictionary.",
        Liczniki::poznane,
    ),
    UTRWALONE_10(
        GrupaOdznak.NAUKA, 10, "Dziesięć na stałe", "Ten locked in",
        "Utrwal 10 słów ze słownika gry.",
        "Lock in 10 words from the game's dictionary.",
        Liczniki::utrwalone,
    ),
    UTRWALONE_50(
        GrupaOdznak.NAUKA, 50, "Pięćdziesiąt na stałe", "Fifty locked in",
        "Utrwal 50 słów ze słownika gry.",
        "Lock in 50 words from the game's dictionary.",
        Liczniki::utrwalone,
    ),
    UTRWALONE_200(
        GrupaOdznak.NAUKA, 200, "Dwieście na stałe", "Two hundred locked in",
        "Utrwal 200 słów ze słownika gry.",
        "Lock in 200 words from the game's dictionary.",
        Liczniki::utrwalone,
    ),
    UTRWALONE_500(
        GrupaOdznak.NAUKA, 500, "Pięćset na stałe", "Five hundred locked in",
        "Utrwal 500 słów ze słownika gry.",
        "Lock in 500 words from the game's dictionary.",
        Liczniki::utrwalone,
    ),
    POWTORKI_10(
        GrupaOdznak.NAUKA, 10, "W terminie", "On time",
        "Zalicz z pamięci 10 powtórek w terminie.",
        "Pass 10 on-time reviews from memory.",
        Liczniki::powtorki,
    ),
    POWTORKI_100(
        GrupaOdznak.NAUKA, 100, "Sto w terminie", "A hundred on time",
        "Zalicz z pamięci 100 powtórek w terminie.",
        "Pass 100 on-time reviews from memory.",
        Liczniki::powtorki,
    ),
    POWTORKI_500(
        GrupaOdznak.NAUKA, 500, "Pięćset w terminie", "Five hundred on time",
        "Zalicz z pamięci 500 powtórek w terminie.",
        "Pass 500 on-time reviews from memory.",
        Liczniki::powtorki,
    ),
    OBIE_10(
        GrupaOdznak.NAUKA, 10, "W obie strony", "Both ways",
        "Zalicz pierwszą powtórkę 10 słów ze słownika gry w obu kierunkach tłumaczenia.",
        "Pass the first review of 10 words from the game's dictionary in both translation directions.",
        Liczniki::obieStrony,
    ),
    OBIE_100(
        GrupaOdznak.NAUKA, 100, "Tam i z powrotem", "There and back again",
        "Zalicz pierwszą powtórkę 100 słów ze słownika gry w obu kierunkach tłumaczenia.",
        "Pass the first review of 100 words from the game's dictionary in both translation directions.",
        Liczniki::obieStrony,
    ),

    // --- regularnosc --------------------------------------------------------------------
    DNI_1(
        GrupaOdznak.REGULARNOSC, 1, "Pierwszy dzień", "Day one",
        "Osiągnij cel dnia po raz pierwszy.",
        "Meet the daily goal for the first time.",
        Liczniki::dniNauki,
    ),
    DNI_7(
        GrupaOdznak.REGULARNOSC, 7, "Siedem dni", "Seven days",
        "Osiągnij cel dnia w 7 różnych dniach, niekoniecznie z rzędu.",
        "Meet the daily goal on 7 different days, not necessarily in a row.",
        Liczniki::dniNauki,
    ),
    DNI_30(
        GrupaOdznak.REGULARNOSC, 30, "Trzydzieści dni", "Thirty days",
        "Osiągnij cel dnia w 30 różnych dniach, niekoniecznie z rzędu.",
        "Meet the daily goal on 30 different days, not necessarily in a row.",
        Liczniki::dniNauki,
    ),
    DNI_100(
        GrupaOdznak.REGULARNOSC, 100, "Własna studniówka", "Your own hundred days",
        "Osiągnij cel dnia w 100 różnych dniach, niekoniecznie z rzędu.",
        "Meet the daily goal on 100 different days, not necessarily in a row.",
        Liczniki::dniNauki,
    ),
    SERIA_3(
        GrupaOdznak.REGULARNOSC, 3, "Trzy z rzędu", "Three in a row",
        "Osiągnij cel dnia 3 razy z rzędu. Pojedynczy opuszczony dzień nie przerywa serii.",
        "Meet the daily goal 3 times in a row. A single missed day does not break the streak.",
        Liczniki::rekordSerii,
    ),
    SERIA_7(
        GrupaOdznak.REGULARNOSC, 7, "Tydzień z rzędu", "A week in a row",
        "Osiągnij cel dnia 7 razy z rzędu. Pojedynczy opuszczony dzień nie przerywa serii.",
        "Meet the daily goal 7 times in a row. A single missed day does not break the streak.",
        Liczniki::rekordSerii,
    ),
    SERIA_14(
        GrupaOdznak.REGULARNOSC, 14, "Dwa tygodnie z rzędu", "Two weeks in a row",
        "Osiągnij cel dnia 14 razy z rzędu. Pojedynczy opuszczony dzień nie przerywa serii.",
        "Meet the daily goal 14 times in a row. A single missed day does not break the streak.",
        Liczniki::rekordSerii,
    ),
    SERIA_30(
        GrupaOdznak.REGULARNOSC, 30, "Miesiąc z rzędu", "A month in a row",
        "Osiągnij cel dnia 30 razy z rzędu. Pojedynczy opuszczony dzień nie przerywa serii.",
        "Meet the daily goal 30 times in a row. A single missed day does not break the streak.",
        Liczniki::rekordSerii,
    ),

    // --- dzialy i zestawy ---------------------------------------------------------------
    DZIAL_1(
        GrupaOdznak.DZIALY, 1, "Pierwszy dział", "First topic",
        "Poznaj 10 słów z jednego działu.",
        "Learn 10 words from one topic.",
        Liczniki::dzialy,
    ),
    DZIALY_5(
        GrupaOdznak.DZIALY, 5, "Pięć działów", "Five topics",
        "Poznaj po 10 słów z 5 działów.",
        "Learn 10 words from each of 5 topics.",
        Liczniki::dzialy,
    ),
    DZIALY_14(
        GrupaOdznak.DZIALY, 14, "Cała podstawa", "The whole basic level",
        "Poznaj po 10 słów z każdego z 14 działów.",
        "Learn 10 words from each of the 14 topics.",
        Liczniki::dzialy,
    ),
    ZESTAW_1(
        GrupaOdznak.DZIALY, 1, "Zestaw na stałe", "A set locked in",
        "Utrwal wszystkie słowa z jednego wbudowanego zestawu.",
        "Lock in every word of one built-in set.",
        Liczniki::zestawy,
    ),
    ZESTAWY_5(
        GrupaOdznak.DZIALY, 5, "Pięć zestawów", "Five sets",
        "Utrwal wszystkie słowa z 5 wbudowanych zestawów.",
        "Lock in every word of 5 built-in sets.",
        Liczniki::zestawy,
    ),
    // Tytul cyframi, bo "Dwadzieścia pięć zestawów" ma 25 znakow, o jeden za duzo.
    ZESTAWY_25(
        GrupaOdznak.DZIALY, 25, "25 zestawów", "Twenty-five sets",
        "Utrwal wszystkie słowa z 25 wbudowanych zestawów.",
        "Lock in every word of 25 built-in sets.",
        Liczniki::zestawy,
    ),
    ZESTAWY_102(
        GrupaOdznak.DZIALY, 102, "Komplet", "The full set",
        "Utrwal wszystkie słowa ze wszystkich 102 wbudowanych zestawów.",
        "Lock in every word of all 102 built-in sets.",
        Liczniki::zestawy,
    ),

    // --- gra: osiem dawnych osiagniec i jedna nowa ----------------------------------------
    FIRST_BLOOD(
        GrupaOdznak.GRA, 1, "Pierwszy celny strzał", "First hit",
        "Pokonaj pierwszego potworka", "Defeat your first monster",
        Liczniki::totalKills,
    ),
    ROOKIE(
        GrupaOdznak.GRA, 5, "Rekrut", "Rookie",
        "Rozegraj 5 gier", "Play 5 games",
        Liczniki::gamesPlayed,
    ),
    VETERAN(
        GrupaOdznak.GRA, 25, "Weteran", "Veteran",
        "Rozegraj 25 gier", "Play 25 games",
        Liczniki::gamesPlayed,
    ),
    SHARPSHOOTER(
        GrupaOdznak.GRA, 100, "Strzelec wyborowy", "Sharpshooter",
        "Pokonaj 100 potworków", "Defeat 100 monsters",
        Liczniki::totalKills,
    ),
    // Mnoznik x3 wlacza sie przy serii 6 trafien, a x5 przy 9: progi z Achievement.unlockedBy.
    COMBO_X3(
        GrupaOdznak.GRA, 6, "Seria x3", "Combo x3",
        "Osiągnij mnożnik x3", "Reach the x3 multiplier",
        Liczniki::bestStreak,
    ),
    COMBO_X5(
        GrupaOdznak.GRA, 9, "Seria x5", "Combo x5",
        "Osiągnij mnożnik x5", "Reach the x5 multiplier",
        Liczniki::bestStreak,
    ),
    SCORE_500(
        GrupaOdznak.GRA, 500, "Pięćset", "Five hundred",
        "Zdobądź 500 punktów w jednej grze", "Score 500 in a single game",
        Liczniki::bestScore,
    ),
    SCORE_2000(
        GrupaOdznak.GRA, 2000, "Dwa tysiące", "Two thousand",
        "Zdobądź 2000 punktów w jednej grze", "Score 2000 in a single game",
        Liczniki::bestScore,
    ),
    TRYBY_4(
        GrupaOdznak.GRA, 4, "Cztery tryby", "All four modes",
        "Rozegraj grę w każdym z czterech trybów.",
        "Play a game in each of the four modes.",
        Liczniki::tryby,
    ),
}

/**
 * Liczenie i przyznawanie odznak. Czyste funkcje nad zapisem: bez Androida, bez zegara, dzien
 * wchodzi parametrem. Zdobyte odznaki utrwala Repository w [Dorobek.odznaki].
 */
object Odznaki {

    /** Ile slow dzialu trzeba poznac, zeby dzial liczyl sie do odznak [GrupaOdznak.DZIALY]. */
    const val PROG_DZIALU = 10

    /**
     * Dzien wpisywany odznakom przyznanym w migracji zapisu: "zanim powstala ksiega". Ekran ma
     * przy takiej odznace nie pokazywac daty, bo prawdziwej nikt nie zapisal.
     */
    const val DZIEN_SPRZED_KSIEGI = 0

    /**
     * Liczniki z calego zapisu.
     *
     * Slowa liczymy po ROZNYCH identyfikatorach wpisow BIEZACYCH zestawow wbudowanych, a nie
     * po mapie postepu. Powody sa trzy i kazdy z nich zawyzalby wynik:
     *  - mapa postepu nie jest sprzatana, wiec po korekcie katalogu to samo slowo lezy w niej
     *    pod starym i pod nowym kluczem (patrz SaveData.progress);
     *  - czesc hasel powtarza sie miedzy dzialami i dzieli jeden identyfikator, wiec liczone
     *    zestaw po zestawie wchodzilyby do sumy dwa razy;
     *  - w mapie lezy tez postep z wlasnych zestawow ucznia, a te nie moga zarabiac odznak
     *    katalogu (wystarczylaby wlasna lista pieciu latwych slow).
     *
     * GRANICA, ktorej zapis nie pozwala dzis przekroczyc: ekran ZESTAWY otwiera w edytorze
     * takze zestaw wbudowany, a wiersz dopisany tam przez ucznia wyglada w danych tak samo,
     * jak wiersz z katalogu. Zestaw skrocony w edytorze latwiej wiec "utrwalic w calosci".
     * Rozroznienie wymaga znacznika we wpisie, ktorego model nie ma (ta sama luka, ktora
     * opisuje Repository.odswiezZestawyWbudowane).
     */
    fun liczniki(data: SaveData): Liczniki {
        val wbudowane = data.sets.filter { it.builtIn }

        // Szczyt kazdego slowa liczony RAZ: identyfikatory powtarzaja sie miedzy zestawami.
        // Wpis bez identyfikatora (zapis sprzed migracji) nie ma jak miec postepu.
        val szczyty = HashMap<String, Szczyty>()
        for (zestaw in wbudowane) {
            for (wpis in zestaw.entries) {
                if (wpis.id.isBlank() || szczyty.containsKey(wpis.id)) continue
                szczyty[wpis.id] = Szczyty(
                    szczyt(data.progress, wpis.id, Direction.TERM_TO_DEF),
                    szczyt(data.progress, wpis.id, Direction.DEF_TO_TERM),
                )
            }
        }

        val poznaneWDziale = HashMap<String, HashSet<String>>()
        for (zestaw in wbudowane) {
            // Zestaw wbudowany bez dzialu w tytule nie nalezy do zadnego dzialu.
            val dzial = zestaw.category ?: continue
            for (wpis in zestaw.entries) {
                if ((szczyty[wpis.id]?.wyzszy ?: 0) >= Srs.FIRST_BOX) {
                    poznaneWDziale.getOrPut(dzial) { HashSet() }.add(wpis.id)
                }
            }
        }

        return Liczniki(
            poznane = szczyty.values.count { it.wyzszy >= Srs.FIRST_BOX },
            utrwalone = szczyty.values.count { it.wyzszy >= Srs.MAX_BOX },
            obieStrony = szczyty.values.count { it.nizszy >= PUDELKO_PIERWSZEJ_POWTORKI },
            dzialy = poznaneWDziale.values.count { it.size >= PROG_DZIALU },
            // Pusty zestaw nie jest "utrwalony w calosci": all() na pustej liscie daje prawde.
            zestawy = wbudowane.count { zestaw ->
                zestaw.entries.isNotEmpty() &&
                    zestaw.entries.all { (szczyty[it.id]?.wyzszy ?: 0) >= Srs.MAX_BOX }
            },
            powtorki = data.dorobek.powtorki,
            dniNauki = data.dorobek.dniNauki,
            rekordSerii = data.dorobek.rekordSerii,
            tryby = data.scores.mapTo(HashSet()) { it.mode }.size,
            gamesPlayed = data.profile.gamesPlayed,
            totalKills = data.profile.totalKills,
            bestScore = data.profile.bestScore,
            bestStreak = data.profile.bestStreak,
        )
    }

    /**
     * Postep odznaki do pokazania jako "12 / 50": licznik przyciety do progu. Dla odznaki juz
     * zdobytej ([zdobyta]) ekran ma pokazac pelny prog bez pytania licznika, bo odznaki nie
     * odbieramy, nawet gdyby licznik kiedys wypadl nizej.
     */
    fun postep(o: Odznaka, l: Liczniki): Int = o.licznik(l).coerceIn(0, o.cel)

    /** Czy liczniki spelniaja prog. To jeszcze nie jest "zdobyta": te stempluje [odblokuj]. */
    fun spelniona(o: Odznaka, l: Liczniki): Boolean = o.licznik(l) >= o.cel

    /** Czy odznaka jest w ksiedze. Raz zdobyta zostaje na zawsze. */
    fun zdobyta(o: Odznaka, d: Dorobek): Boolean = o.name in d.odznaki

    /**
     * Dopisuje do ksiegi kazda odznake, ktorej prog jest spelniony, a ktorej w ksiedze jeszcze
     * nie ma, z dniem [dzien]. NICZEGO nie usuwa i nie przestemplowuje: odznaka zdobyta zostaje
     * z dniem, w ktorym padla. Nazwy, ktorych ten kod nie zna (zapis z nowszej wersji), tez
     * zostaja nietkniete. Gdy nie ma czego dopisac, oddaje te sama ksiege.
     */
    fun odblokuj(d: Dorobek, l: Liczniki, dzien: Int): Dorobek {
        val nowe = Odznaka.entries.filter { it.name !in d.odznaki && spelniona(it, l) }
        if (nowe.isEmpty()) return d
        return d.copy(odznaki = d.odznaki + nowe.associate { it.name to dzien })
    }

    /** Pudelko 2: slowo przeszlo pierwsza powtorke z pamieci. Prog dla [Liczniki.obieStrony]. */
    private const val PUDELKO_PIERWSZEJ_POWTORKI = 2

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
