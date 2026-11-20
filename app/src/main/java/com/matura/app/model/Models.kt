package com.matura.app.model

import kotlinx.serialization.Serializable

/**
 * One row of a set, mirroring the Quizlet-style editor from the design document:
 * a term on the left, its definition on the right. A definition may list several
 * accepted translations separated by commas, e.g. "konsorcjum, syndykat".
 *
 * [id] to TRWALA tozsamosc wpisu i jedyny klucz, pod ktorym wolno trzymac postep nauki.
 * Przedtem tozsamosc byla podwojnie nietrwala: rownosc data class liczyla sie z tresci,
 * a edytor zestawow budowal liste OD ZERA z wierszy ekranu, wiec poprawka literowki albo
 * przestawienie wiersza robily z tego samego slowa nowe slowo. Zasady nadawania (prefiks
 * "w" dla katalogu, "u" dla wpisow wlasnych, zamrozony sens zamiast biezacego tlumaczenia)
 * opisuje com.matura.app.learn.EntryId.
 *
 * Pusta wartosc domyslna oznacza wpis SPRZED wersjonowania — taki dostaje identyfikator
 * w migracji zapisu (Repository.migrate), a nie w locie przy odczycie: identyfikator ma byc
 * nadany raz i utrwalony, inaczej postep gubilby sie przy kazdym starcie.
 */
@Serializable
data class Entry(
    val term: String,
    val definition: String,
    val id: String = "",
)

/** Which side of the set the player is asked to produce. */
@Serializable
enum class Direction { TERM_TO_DEF, DEF_TO_TERM, RANDOM }

/**
 * Rodzaj zadania, ktorym sprawdzono slowo. Rozroznienie jest MERYTORYCZNE, nie kosmetyczne:
 * wybor z czterech odpowiedzi dowodzi tylko tego, ze uczen rozpoznaje znaczenie posrod
 * podanych, i nie mowi nic o tym, czy potrafi je przypomniec sobie sam ani czy zna pisownie.
 * Dlatego [Srs] pozwala rozpoznaniu podniesc slowo najwyzej do pudelka 1, a pudelka 2 i 3
 * rezerwuje dla zadan produkcyjnych.
 *
 * Nazwy sa po polsku, bo to termin dydaktyczny z zalozen projektu (ang. recognition/recall),
 * a nie techniczny szczegol implementacji.
 */
@Serializable
enum class TaskKind {
    /** ABCD, prawda/falsz — uczen WYBIERA sposrod podanych. */
    ROZPOZNANIE,

    /** Wpisywanie z klawiatury, odpowiedz glosem — uczen PRODUKUJE odpowiedz sam. */
    PRZYPOMNIENIE,
}

/**
 * Stan nauki JEDNEGO celu, czyli pary (identyfikator wpisu, kierunek). Regulami rzadzi
 * com.matura.app.learn.Srs — tutaj jest wylacznie to, co musi przetrwac restart.
 *
 * Klasa mieszka w pakiecie model, mimo ze pojeciowo nalezy do nauki: [SaveData] musi ja
 * widziec, a pakiet learn juz zalezy od modelu ([Direction]). Trzymanie jej w learn zrobiloby
 * cykl zaleznosci miedzy pakietami. Reguly zostaja w jednym miejscu (Srs.kt), tutaj sa dane.
 *
 * Zapisujemy MINIMUM: pudelko, termin, dzien ostatniego awansu, dzien ostatniej pomocy, dwa
 * liczniki oraz najwyzsze osiagniete pudelko. Celowo NIE ma tu historii odpowiedzi, nagran ani
 * transkrypcji: do niczego w aplikacji nie sa potrzebne, a byloby to gromadzenie danych
 * o uczniu na zapas.
 *
 * Dni licza sie od epoki UTC (patrz Srs.dayOf), a nie od lokalnej polnocy.
 */
@Serializable
data class WordProgress(
    /** 0 = brak danych, 1..3 = pudelka Leitnera. */
    val box: Int = 0,

    /**
     * Dzien, od ktorego slowo znow czeka na sprawdzenie. Zero znaczy "od zawsze zalegle",
     * wiec swiezy wpis jest zalegly od razu i nie wymaga osobnego znacznika.
     */
    val dueDay: Int = 0,

    /**
     * Dzien ostatniego KWALIFIKUJACEGO awansu. Blokuje drugi awans tego samego dnia:
     * bez tego wystarczyloby kilka razy z rzedu trafic to samo slowo w jednej sesji,
     * zeby aplikacja uznala je za utrwalone.
     */
    val lastPromotionDay: Int = NO_DAY,

    /**
     * Dzien, w ktorym uczen potrzebowal przy tym slowie pomocy: pomylil sie, wzial
     * podpowiedz albo kazal pokazac odpowiedz. Poprawna odpowiedz w tym samym dniu jest
     * wtedy tylko poprawka, a nie dowodem pamieci, wiec nie awansuje.
     */
    val assistedDay: Int = NO_DAY,

    val mistakes: Int = 0,
    val hints: Int = 0,

    /** Rodzaj ostatniej proby — zeby ekran mogl uczciwie nazwac wynik ("rozpoznane" vs "umiesz"). */
    val lastTask: TaskKind? = null,

    /**
     * Znacznik ostatnio przetworzonej proby. Ta sama proba potrafi trafic do zapisu dwa razy
     * (ponowienie po bledzie zapisu, powrot na ekran, dwa zrodla zdarzen w trybie glosowym),
     * a dwa razy zaliczony awans to nieprawda o uczniu. Pusty znacznik wylacza ten straznik.
     */
    val lastAttemptId: String = "",

    /**
     * Najwyzsze pudelko, do jakiego to slowo w tym kierunku KIEDYKOLWIEK doszlo. Nigdy nie
     * maleje i nigdy nie jest mniejsze od [box]; pilnuje tego wylacznie Srs.apply.
     *
     * Po co osobne pole, skoro jest [box]: pomylka cofa pudelko do pierwszego, a liczniki
     * i odznaki nie moga wtedy spadac (uczniowi niczego nie odbieramy). Druga rzecz to punkty:
     * wejscie drugi raz na ten sam szczebel nie moze byc platne jak pierwsze, inaczej oplacaloby
     * sie mylic. Jedna liczba zalatwia obie sprawy bez trzymania historii odpowiedzi.
     *
     * Zero w starym pliku znaczy "pola jeszcze nie bylo" i jest podnoszone do [box] w migracji
     * zapisu (Repository.migrate, krok 3 -> 4).
     */
    val szczyt: Int = 0,
) {
    companion object {
        /** "Nigdy" dla pol dniowych. Uzywane WYLACZNIE do porownan rownosciowych — bez arytmetyki. */
        const val NO_DAY: Int = Int.MIN_VALUE
    }
}

/**
 * Dorobek ucznia: mala ksiega tego, co nauka przyniosla. Reguly mieszkaja w
 * com.matura.app.learn.Nagrody i com.matura.app.learn.Odznaki, tutaj jest wylacznie to, co musi
 * przetrwac restart (z tego samego powodu, dla ktorego [WordProgress] mieszka w modelu).
 *
 * Punkty, cel dnia i serie dopisuje JEDNO miejsce: Repository.recordAttempt, tym samym zapisem
 * co postep slowa. Kazdy wpis bierze sie z przejscia, ktore policzyl harmonogram (Srs), wiec
 * punktow nie da sie nabic graniem w kolko latwego zestawu: slowo awansuje najwyzej raz
 * dziennie. Repository.recordGame dopisuje tu wylacznie odznaki za gre, nigdy punkty.
 *
 * Trzy zasady, ktorych te pola pilnuja:
 *  - to sa LICZBY PRAWDZIWYCH RZECZY (punkty za zaliczenia, dni, powtorki), nie procent
 *    gotowosci i nie prognoza wyniku matury;
 *  - punkty nauki ([xp]) nie maja nic wspolnego z punktami za gre (Profile.bestScore): tamte
 *    daje sie takze za szybkosc i za trafienie potworka;
 *  - nic, co uczen zdobyl, tu nie maleje. Zmniejszaja sie tylko trzy rzeczy, ktore zdobycza
 *    nie sa: liczniki "na dzis" (kazdy dzien zaczynaja od zera), biezaca seria (po przerwie
 *    dluzszej niz zapas dni wolnych zaczyna sie od nowa, rekord zostaje) i sam zapas dni
 *    wolnych, ktory jest po to, zeby go zuzywac.
 *
 * Dni to dni harmonogramu (Srs.dayOf, UTC), te same, ktorymi liczy sie termin powtorki:
 * jeden zegar na cala nauke. [WordProgress.NO_DAY] znaczy "nigdy" i sluzy wylacznie do porownan.
 */
@Serializable
data class Dorobek(
    /** Suma punktow nauki. Nigdy nie maleje. */
    val xp: Int = 0,

    /** Dzien, ktorego dotycza cztery liczniki "na dzis": [xpDzis] i trzy nastepne. */
    val dzien: Int = WordProgress.NO_DAY,

    /** Punkty nauki zdobyte w dniu [dzien]. To one wypelniaja cel dnia. */
    val xpDzis: Int = 0,

    /**
     * Slowa spotkane pierwszy raz w dniu [dzien]: wyjscia z pudelka 0, z pamieci i przez
     * rozpoznanie, takze te, za ktore dzienny limit nie dal juz punktow. Sluzy wylacznie do
     * uwagi "dzis doszlo juz 30 nowych slow": wszystkie wroca jutro jako powtorki.
     */
    val noweDzis: Int = 0,

    /** Rozpoznania, za ktore w dniu [dzien] byly punkty; od 0 do limitu z Nagrody. */
    val rozpoznaneDzis: Int = 0,

    /** Zaliczenia slow spoza slownika gry, za ktore w dniu [dzien] byly punkty; od 0 do limitu. */
    val wlasneDzis: Int = 0,

    /**
     * Dlugosc ostatniego lancucha dni z zaliczonym celem. Zostaje w zapisie takze wtedy, gdy
     * lancuch juz sie urwal, wiec ekran ma czytac com.matura.app.learn.Nagrody.seriaNaDzis.
     */
    val seria: Int = 0,

    /** Ostatni dzien, w ktorym cel dnia zostal zaliczony. */
    val seriaDzien: Int = WordProgress.NO_DAY,

    /**
     * Dni wolne w zapasie, od 0 do 2. Opuszczony dzien zuzywa jeden i seria trwa dalej; bez
     * zapasu seria zaczyna sie od nowa. Kazdy uczen zaczyna z jednym, kolejny dochodzi za
     * kazde 7 dni serii. Jawny, policzalny zapas zamiast cichego "jeden dzien sie wybacza":
     * przy nieograniczonym wybaczaniu "seria 7" wychodzila z siedmiu dni nauki w trzynastu.
     */
    val wolne: Int = 1,

    /** Najdluzszy lancuch w historii. */
    val rekordSerii: Int = 0,

    /** Ile bylo dni z zaliczonym celem, lacznie; nie musza isc po kolei. */
    val dniNauki: Int = 0,

    /**
     * Ile bylo zaplaconych zaliczen z pamieci slow, ktore WROCILY w terminie: pierwsza
     * powtorka (pudelko 2), utrwalenie (pudelko 3) i kazda nastepna powtorka w terminie.
     */
    val powtorki: Int = 0,

    /** Ile slow ze slownika gry doszlo pierwszy raz do utrwalenia MIMO wczesniejszej pomylki. */
    val naprawione: Int = 0,

    /**
     * Zdobyte odznaki: nazwa stalej com.matura.app.learn.Odznaka -> dzien zdobycia. Zero znaczy
     * "zanim powstala ksiega" (odznaka przyznana po cichu w migracji zapisu). Nazwy sa przez to
     * czescia zapisu, tak jak nazwy stalych [Achievement].
     */
    val odznaki: Map<String, Int> = emptyMap(),

    /**
     * Odznaki zdobyte, ale jeszcze niepokazane uczniowi (te same nazwy, co w [odznaki]).
     * Wyjscie w polowie rundy nie moze zgubic ogloszenia: odznaka czeka tu, az pokaze ja ekran
     * wyniku albo ekran osiagniec, i dopiero wtedy Repository czysci ten zbior.
     */
    val odznakiNowe: Set<String> = emptySet(),
)

@Serializable
data class WordSet(
    val id: String,
    val title: String,
    val termLanguage: String = "English",
    val definitionLanguage: String = "Polski",
    val entries: List<Entry> = emptyList(),
    val builtIn: Boolean = false,
    val createdAt: Long = 0L,
    val timesPlayed: Int = 0,
) {
    val size: Int get() = entries.size

    /**
     * Wbudowane zestawy nazywaja sie "Temat · Podtemat" (zakres tematyczny CKE),
     * wiec folder da sie wyprowadzic z samego tytulu - bez migracji danych i bez
     * dodatkowego pola do utrzymania. Zestawy wlasne nie maja separatora i trafiaja
     * do wlasnego kosza, ktory nazywa juz warstwa UI (bo tam jest tlumaczenie).
     */
    val category: String?
        get() = title.indexOf('\u00B7').let { if (it > 0) title.take(it).trim() else null }

    /** Tytul bez przedrostka tematu - w srodku folderu nie powtarzamy nazwy folderu. */
    val shortTitle: String
        get() = title.indexOf('\u00B7').let {
            if (it > 0 && it + 1 < title.length) title.substring(it + 1).trim() else title
        }
}

@Serializable
data class Options(
    val lives: Int = 3,
    val soundEnabled: Boolean = true,
    val texture: Texture = Texture.CLASSIC,
    val direction: Direction = Direction.TERM_TO_DEF,
    val uiLanguage: UiLanguage = UiLanguage.PL,
    val speed: Speed = Speed.NORMAL,
    /**
     * Wpisywanie klawiatura SYSTEMOWA zamiast wbudowanej pikselowej.
     *
     * Powod jest jeden i bardzo praktyczny: wbudowana klawiatura nie ma mikrofonu, a na czesci
     * telefonow (m.in. Samsungi) rozpoznawanie mowy uruchamiane przez aplikacje odmawia dzialania
     * — silnik zglasza sie jako dostepny, po czym odrzuca zadanie bledem ERROR_CLIENT. Klawiatura
     * systemowa ma wlasne dyktowanie, ktore nie przechodzi przez ten mechanizm w ogole: to zwykle
     * wprowadzanie tekstu. Wlaczenie tej opcji daje wiec glos tam, gdzie tryb glosowy zawodzi.
     */
    val systemKeyboard: Boolean = false,

    /**
     * Cel dnia w PUNKTACH NAUKI: ile ma ich przyniesc jeden dzien, zeby wszedl do serii.
     * Dozwolone wartosci to 30, 60 i 120 (com.matura.app.learn.Nagrody.CELE_DNIA); inna liczba
     * w pliku jest czytana jak domyslna. Trzydziesci to trzy nowe slowa podane z pamieci albo
     * szesc powtorek w terminie.
     */
    val celDnia: Int = 30,

    /**
     * Dzien egzaminu jako KALENDARZOWY dzien epoki (dni od 1970-01-01), a nie dzien
     * harmonogramu: sluzy wylacznie do odliczania, ktore ma sie zgadzac z kalendarzem na
     * scianie (com.matura.app.learn.Kalendarz). Domyslne 20944 to 6 maja 2027, pisemny
     * angielski na poziomie podstawowym; uczen z innym terminem ustawia wlasny.
     */
    val maturaDzien: Int = 20944,

    /** Czy pokazywac odliczanie do matury. Nie kazdy chce codziennie widziec te liczbe. */
    val odliczanie: Boolean = true,
)

/**
 * How fast monsters walk toward the cannon. Testers with slower typing asked for a
 * gentler pace, so tempo is a setting rather than a fixed constant; the multiplier
 * scales the whole speed curve, including the per-level increase.
 */
@Serializable
enum class Speed(val factor: Float) {
    SLOW(0.75f),
    NORMAL(1.0f),
    FAST(1.3f),
}

@Serializable
enum class Texture { CLASSIC, NIGHT, DESERT }

@Serializable
enum class UiLanguage { PL, EN }

@Serializable
data class ScoreRecord(
    val playerName: String,
    val setTitle: String,
    val score: Int,
    val level: Int,
    val bestStreak: Int,
    val kills: Int,
    val playedAt: Long,
    /** Tryb rozgrywki. Wartosc domyslna trzyma zgodnosc ze starymi zapisami JSON. */
    val mode: GameMode = GameMode.CLASSIC,
)

/** Tryby gry dostepne w aplikacji. */
@Serializable
enum class GameMode { CLASSIC, TRUE_FALSE, MULTIPLE_CHOICE, TIME_ATTACK }

@Serializable
data class Profile(
    val name: String = "Gracz",
    val anonymous: Boolean = true,
    val gamesPlayed: Int = 0,
    val totalKills: Int = 0,
    val bestScore: Int = 0,
    val bestStreak: Int = 0,
)

/**
 * Badge definitions, awarded from [Profile] counters. Pure data, no Android types.
 *
 * NAZWY STALYCH SA CZESCIA ZAPISU I NIE WOLNO ICH ZMIENIAC — po nich rozpoznajemy odznake.
 * Zmieniac wolno wylacznie cztery teksty w nawiasie, bo to one ida na ekran OSIAGNIECIA.
 *
 * Teksty przeszly na lagodniejsze slownictwo w 0.9.18, z tego samego powodu, dla ktorego
 * zrobil to HUD w 0.9.9 (commit 140f33e: "Zabicia" -> "Pokonane"): ekran OSIAGNIECIA trafia
 * na zrzuty do sklepu i do materialow dla szkol, a gra idzie do licealistow. Tamta zmiana
 * ominela ten enum, wiec obok HUD-u z napisem "Pokonane" staly dalej "Pierwsza krew"
 * i "Zabij 100 potworkow".
 */
enum class Achievement(
    val titlePl: String,
    val titleEn: String,
    val descPl: String,
    val descEn: String,
) {
    FIRST_BLOOD("Pierwszy celny strzał", "First hit", "Pokonaj pierwszego potworka", "Defeat your first monster"),
    ROOKIE("Rekrut", "Rookie", "Rozegraj 5 gier", "Play 5 games"),
    VETERAN("Weteran", "Veteran", "Rozegraj 25 gier", "Play 25 games"),
    SHARPSHOOTER("Strzelec wyborowy", "Sharpshooter", "Pokonaj 100 potworków", "Defeat 100 monsters"),
    COMBO_X3("Seria x3", "Combo x3", "Osiągnij mnożnik x3", "Reach the x3 multiplier"),
    COMBO_X5("Seria x5", "Combo x5", "Osiągnij mnożnik x5", "Reach the x5 multiplier"),
    SCORE_500("Pięćset", "Five hundred", "Zdobądź 500 punktów w jednej grze", "Score 500 in a single game"),
    SCORE_2000("Dwa tysiące", "Two thousand", "Zdobądź 2000 punktów w jednej grze", "Score 2000 in a single game");

    fun unlockedBy(p: Profile): Boolean = when (this) {
        FIRST_BLOOD -> p.totalKills >= 1
        ROOKIE -> p.gamesPlayed >= 5
        VETERAN -> p.gamesPlayed >= 25
        SHARPSHOOTER -> p.totalKills >= 100
        COMBO_X3 -> p.bestStreak >= 6
        COMBO_X5 -> p.bestStreak >= 9
        SCORE_500 -> p.bestScore >= 500
        SCORE_2000 -> p.bestScore >= 2000
    }
}

@Serializable
data class SaveData(
    /**
     * Wersja ukladu danych w pliku. Pole jest PIERWSZE, bo laduje na poczatku JSON-a i widac
     * je od razu przy recznym ogladaniu zapisu.
     *
     * Zero znaczy "zapis sprzed wersjonowania": stare pliki tego pola nie maja, wiec
     * deserializacja podstawia wartosc domyslna i po tym Repository poznaje, ze dane trzeba
     * przepuscic przez migracje (Repository.migrate).
     */
    val schemaVersion: Int = 0,
    val profile: Profile = Profile(),
    /**
     * Przerwana sesja "Dzisiejsze 5 minut". Zapisujemy ja, bo brief wymaga, zeby przerwa nie
     * kasowala nauki — a sesja bez zapisu ginie razem z procesem. null znaczy "nic nie trwa";
     * sesja z innego dnia jest odrzucana przy wznowieniu (Sesja.czyWznawialna), wiec stary
     * wpis nie zalega w nieskonczonosc.
     */
    val sesja: com.matura.app.learn.StanSesji? = null,
    val options: Options = Options(),
    val sets: List<WordSet> = emptyList(),
    val scores: List<ScoreRecord> = emptyList(),

    /**
     * Postep per slowo. Klucz buduje Srs.key(identyfikatorWpisu, kierunek) — postep jest
     * wiec zwiazany ze SLOWEM, a nie z zestawem: to samo znaczenie w dwoch zestawach dzieli
     * jeden stan, a usuniecie zestawu nie kasuje nauki.
     *
     * Mapa NIE jest sprzatana przy zmianie katalogu. Wpis, ktory zniknal ze slownika, moze
     * do niego wrocic (katalog jest generowany i bywa poprawiany), a wtedy uczen odzyskuje
     * swoja historie zamiast zaczynac od zera. Koszt jest zaniedbywalny: kilkadziesiat bajtow
     * na slowo, ktore uczen naprawde cwiczyl.
     */
    val progress: Map<String, WordProgress> = emptyMap(),

    /**
     * Ksiega dorobku: punkty nauki, cel dnia, seria i odznaki. Wypelnia ja wylacznie
     * Repository.recordAttempt (tym samym zapisem co [progress]) oraz Repository.recordGame
     * (same odznaki); Repository.potwierdzOdznaki tylko odhacza odznaki juz pokazane. Stary plik tego pola nie ma: migracja zapisu (Repository.migrate,
     * krok 3 -> 4) zaklada wtedy ksiege z pudelek, do ktorych uczen naprawde doszedl, a nie
     * z punktow za gry.
     */
    val dorobek: Dorobek = Dorobek(),
)
