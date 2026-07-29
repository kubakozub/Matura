package com.verbume.app.data

import android.content.Context
import com.verbume.app.model.Entry
import com.verbume.app.model.Profile
import com.verbume.app.model.Options
import com.verbume.app.model.SaveData
import com.verbume.app.model.ScoreRecord
import com.verbume.app.model.WordSet
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local persistence. Everything is one JSON document in the app's private files
 * directory — the design document's accounts, shared sets and global leaderboards
 * need a server, which this build deliberately does not have, so "play anonymously"
 * is the only path and all data stays on the device.
 */
class Repository(context: Context) {

    private val file = File(context.filesDir, "verbume.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: SaveData? = null

    @Synchronized
    fun load(): SaveData {
        cache?.let { return it }
        val data = runCatching {
            if (file.exists()) json.decodeFromString<SaveData>(file.readText()) else null
        }.getOrNull() ?: SaveData(sets = SampleData.builtInSets())

        val withBuiltIns = if (data.sets.none { it.builtIn }) {
            data.copy(sets = SampleData.builtInSets() + data.sets)
        } else data

        cache = withBuiltIns
        return withBuiltIns
    }

    @Synchronized
    fun save(data: SaveData) {
        cache = data
        runCatching { file.writeText(json.encodeToString(SaveData.serializer(), data)) }
    }

    fun update(block: (SaveData) -> SaveData) {
        save(block(load()))
    }

    fun upsertSet(set: WordSet) = update { d ->
        val idx = d.sets.indexOfFirst { it.id == set.id }
        val sets = if (idx >= 0) d.sets.toMutableList().also { it[idx] = set } else d.sets + set
        d.copy(sets = sets)
    }

    fun deleteSet(id: String) = update { d -> d.copy(sets = d.sets.filterNot { it.id == id && !it.builtIn }) }

    fun setOptions(options: Options) = update { it.copy(options = options) }

    fun setProfile(profile: Profile) = update { it.copy(profile = profile) }

    /** Records the outcome of a finished run and rolls the profile counters forward. */
    fun recordGame(record: ScoreRecord, setId: String) = update { d ->
        val p = d.profile
        d.copy(
            profile = p.copy(
                gamesPlayed = p.gamesPlayed + 1,
                totalKills = p.totalKills + record.kills,
                bestScore = maxOf(p.bestScore, record.score),
                bestStreak = maxOf(p.bestStreak, record.bestStreak),
            ),
            scores = (d.scores + record).sortedByDescending { it.score }.take(50),
            sets = d.sets.map { if (it.id == setId) it.copy(timesPlayed = it.timesPlayed + 1) else it },
        )
    }
}

object SampleData {

    /**
     * Starter content: the thematic sets covering the Polish matura podstawowa
     * vocabulary. Generated into [BuiltInSets] from the curated word list, with a
     * hard rule that every term and every translation is at most two words.
     */
    fun builtInSets(): List<WordSet> = BuiltInSets.all()

    /** Kept so a set's shape stays obvious when reading this file. */
    @Suppress("unused")
    private val example = Entry("agile", "zręczny")
}
