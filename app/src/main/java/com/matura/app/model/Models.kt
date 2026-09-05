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
 * Zapisujemy MINIMUM: pudelko, termin, dzien ostatniego awansu, dzien ostatniej pomocy oraz
 * dwa liczniki. Celowo NIE ma tu historii odpowiedzi, nagran ani transkrypcji — do niczego
 * w aplikacji nie sa potrzebne, a byloby to gromadzenie danych o uczniu na zapas.
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
) {
    companion object {
        /** "Nigdy" dla pol dniowych. Uzywane WYLACZNIE do porownan rownosciowych — bez arytmetyki. */
        const val NO_DAY: Int = Int.MIN_VALUE
    }
}

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

/** Badge definitions, awarded from [Profile] counters. Pure data, no Android types. */
enum class Achievement(
    val titlePl: String,
    val titleEn: String,
    val descPl: String,
    val descEn: String,
) {
    FIRST_BLOOD("Pierwsza krew", "First blood", "Zabij pierwszego potworka", "Kill your first monster"),
    ROOKIE("Rekrut", "Rookie", "Rozegraj 5 gier", "Play 5 games"),
    VETERAN("Weteran", "Veteran", "Rozegraj 25 gier", "Play 25 games"),
    SHARPSHOOTER("Strzelec wyborowy", "Sharpshooter", "Zabij 100 potworków", "Kill 100 monsters"),
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
)
