package com.matura.app.model

import kotlinx.serialization.Serializable

/**
 * One row of a set, mirroring the Quizlet-style editor from the design document:
 * a term on the left, its definition on the right. A definition may list several
 * accepted translations separated by commas, e.g. "konsorcjum, syndykat".
 */
@Serializable
data class Entry(
    val term: String,
    val definition: String,
)

/** Which side of the set the player is asked to produce. */
@Serializable
enum class Direction { TERM_TO_DEF, DEF_TO_TERM, RANDOM }

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
)
