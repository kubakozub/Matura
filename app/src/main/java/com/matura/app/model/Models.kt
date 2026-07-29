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
}

@Serializable
data class Options(
    val lives: Int = 3,
    val soundEnabled: Boolean = true,
    val texture: Texture = Texture.CLASSIC,
    val direction: Direction = Direction.TERM_TO_DEF,
    val uiLanguage: UiLanguage = UiLanguage.PL,
)

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
)

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
        COMBO_X5 -> p.bestStreak >= 12
        SCORE_500 -> p.bestScore >= 500
        SCORE_2000 -> p.bestScore >= 2000
    }
}

@Serializable
data class SaveData(
    val profile: Profile = Profile(),
    val options: Options = Options(),
    val sets: List<WordSet> = emptyList(),
    val scores: List<ScoreRecord> = emptyList(),
)
