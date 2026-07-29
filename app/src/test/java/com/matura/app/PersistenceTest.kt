package com.matura.app

import com.matura.app.data.SampleData
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.Matching
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.Texture
import com.matura.app.model.WordSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save file is the one thing that runs on every cold start, so a broken
 * serializer would mean a crash before the menu ever appears. These tests exercise
 * the exact Json configuration the repository uses.
 */
class PersistenceTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `save data survives a write and read round trip`() {
        val original = SaveData(
            profile = Profile(name = "Kuba", anonymous = true, gamesPlayed = 4, totalKills = 40, bestScore = 900, bestStreak = 7),
            options = Options(lives = 5, soundEnabled = false, texture = Texture.NIGHT, direction = Direction.RANDOM),
            sets = SampleData.builtInSets() + WordSet(
                id = "user-1",
                title = "Moje słówka",
                entries = listOf(Entry("dog", "pies")),
            ),
            scores = listOf(ScoreRecord("Kuba", "Moje słówka", 900, 5, 7, 40, 1_700_000_000_000L)),
        )

        val text = json.encodeToString(SaveData.serializer(), original)
        val restored = json.decodeFromString(SaveData.serializer(), text)

        assertEquals(original, restored)
        assertEquals(Texture.NIGHT, restored.options.texture)
        assertEquals(Direction.RANDOM, restored.options.direction)
        assertEquals("Moje słówka", restored.sets.last().title)
    }

    @Test
    fun `an empty save file falls back to defaults instead of throwing`() {
        val restored = json.decodeFromString(SaveData.serializer(), "{}")
        assertEquals(Profile(), restored.profile)
        assertEquals(Options(), restored.options)
        assertTrue(restored.sets.isEmpty())
    }

    @Test
    fun `a save file from a future version with extra keys still loads`() {
        val text = """{"profile":{"name":"X","somethingNew":123},"unknownSection":{"a":1}}"""
        val restored = json.decodeFromString(SaveData.serializer(), text)
        assertEquals("X", restored.profile.name)
    }

    @Test
    fun `every built-in set is immediately playable`() {
        val sets = SampleData.builtInSets()
        assertTrue(sets.isNotEmpty())
        for (set in sets) {
            assertTrue("${set.title} has no entries", set.entries.isNotEmpty())
            for (e in set.entries) {
                assertFalse("blank term in ${set.title}", e.term.isBlank())
                assertFalse("blank definition in ${set.title}", e.definition.isBlank())
                assertTrue(
                    "'${e.definition}' produces no typeable answer",
                    Matching.acceptedAnswers(e.definition).isNotEmpty(),
                )
            }
            val engine = GameEngine(set, GameConfig(autoSpawn = false))
            assertTrue("${set.title} is not playable", engine.isPlayable)
        }
    }

    @Test
    fun `shipped sets really do contain prefix collisions, so highlighting earns its keep`() {
        // The design document's example is two words whose translations share 'cie'.
        // The feature is only worth anything if the shipped vocabulary behaves the same
        // way, so assert it against real content rather than a hand-made fixture.
        var collisionsFound = 0
        for (set in SampleData.builtInSets()) {
            val answers = set.entries.map { it.term to Matching.acceptedAnswers(it.definition) }
            for ((_, a) in answers) {
                val prefix = a.firstOrNull()?.take(3) ?: continue
                if (prefix.length < 3) continue
                val matches = answers.count { (_, other) -> other.any { it.startsWith(prefix) } }
                if (matches > 1) collisionsFound++
            }
        }
        assertTrue(
            "no set has two entries whose answers share a three-letter prefix",
            collisionsFound > 0,
        )
    }

    @Test
    fun `built-in sets can be played end to end without diacritics on the keyboard`() {
        for (set in SampleData.builtInSets()) {
            val engine = GameEngine(set, GameConfig(autoSpawn = false))
            for (entry in set.entries) {
                engine.debugAddMonster(entry.term, entry.definition, 0.9f, 0.5f)
                // type the answer the way the A-Z keyboard would produce it
                val typed = Matching.normalize(entry.definition.substringBefore(','))
                engine.setTyped(typed)
                val result = engine.submit()
                assertTrue(
                    "'${entry.term}' -> '$typed' was not accepted in ${set.title}",
                    result is com.matura.app.game.SubmitResult.Fired,
                )
                repeat(200) { engine.tick(0.016f) }
            }
            assertEquals(set.entries.size, engine.kills)
        }
    }
}
