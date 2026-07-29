package com.verbume.app

import com.verbume.app.data.BuiltInSets
import com.verbume.app.game.GameConfig
import com.verbume.app.game.GameEngine
import com.verbume.app.game.Matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in vocabulary is generated, so these tests are the guard rail: they fail
 * the build if a regeneration ever breaks the rules the content is supposed to obey.
 */
class BuiltInSetsTest {

    private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    @Test
    fun `every term is at most two words`() {
        val offenders = BuiltInSets.all().flatMap { set ->
            set.entries.filter { wordCount(it.term) > 2 }.map { "${set.title}: '${it.term}'" }
        }
        assertTrue("terms longer than two words: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every translation alternative is at most two words`() {
        val offenders = BuiltInSets.all().flatMap { set ->
            set.entries.flatMap { e ->
                e.definition.split(',')
                    .filter { wordCount(it) > 2 }
                    .map { "${set.title}: '${e.term}' -> '${it.trim()}'" }
            }
        }
        assertTrue("translations longer than two words: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no set is empty and no entry has a blank side`() {
        for (set in BuiltInSets.all()) {
            assertTrue("${set.title} is empty", set.entries.isNotEmpty())
            for (e in set.entries) {
                assertFalse("${set.title}: blank term", e.term.isBlank())
                assertFalse("${set.title}: blank definition for '${e.term}'", e.definition.isBlank())
            }
        }
    }

    @Test
    fun `set ids and titles are unique`() {
        val sets = BuiltInSets.all()
        assertEquals("duplicate ids", sets.size, sets.map { it.id }.toSet().size)
        assertEquals("duplicate titles", sets.size, sets.map { it.title }.toSet().size)
    }

    @Test
    fun `no set repeats a term`() {
        for (set in BuiltInSets.all()) {
            val terms = set.entries.map { it.term.lowercase() }
            assertEquals("duplicate term in ${set.title}", terms.size, terms.toSet().size)
        }
    }

    @Test
    fun `every entry produces a typeable answer on the A-Z keyboard`() {
        for (set in BuiltInSets.all()) {
            for (e in set.entries) {
                val answers = Matching.acceptedAnswers(e.definition)
                assertTrue("'${e.term}' in ${set.title} has no typeable answer", answers.isNotEmpty())
                assertTrue(
                    "'${e.definition}' normalises to something with non A-Z characters",
                    answers.all { it.all { ch -> ch.isLetterOrDigit() || ch == ' ' } }
                )
            }
        }
    }

    @Test
    fun `every set is playable and can be cleared by typing the answers`() {
        for (set in BuiltInSets.all()) {
            val engine = GameEngine(set, GameConfig(autoSpawn = false))
            assertTrue("${set.title} is not playable", engine.isPlayable)
            val entry = set.entries.first()
            engine.debugAddMonster(entry.term, entry.definition, 0.9f, 0.5f)
            engine.setTyped(Matching.normalize(entry.definition.substringBefore(',')))
            assertTrue(
                "'${entry.term}' was not accepted in ${set.title}",
                engine.submit() is com.verbume.app.game.SubmitResult.Fired
            )
        }
    }

    @Test
    fun `all fourteen matura topic areas are present`() {
        val areas = BuiltInSets.all().map { it.title.substringBefore(" · ") }.toSet()
        val expected = listOf(
            "Człowiek", "Miejsce zamieszkania", "Edukacja", "Praca", "Życie prywatne",
            "Żywienie", "Zakupy", "Podróże", "Kultura", "Sport", "Zdrowie",
            "Nauka i technika", "Przyroda", "Życie społeczne",
        )
        for (a in expected) assertTrue("missing topic area: $a", a in areas)
        assertEquals(expected.size, areas.size)
    }

    @Test
    fun `every set is small enough to be cleared in one run`() {
        // Celem podzialu jest ok. 20 hasel: tyle przerabia sie w jednej rozgrywce,
        // wiec kazde haslo wraca kilka razy zamiast pojawic sie raz na kwadrans.
        val tooBig = BuiltInSets.all().filter { it.entries.size > 25 }.map { it.title }
        assertTrue("sets over 25 entries: $tooBig", tooBig.isEmpty())
        val tooSmall = BuiltInSets.all().filter { it.entries.size < 20 }.map { it.title }
        assertTrue("sets under 20 entries: $tooSmall", tooSmall.isEmpty())
    }

    @Test
    fun `the collection is large enough to be worth studying`() {
        val total = BuiltInSets.all().sumOf { it.entries.size }
        assertTrue("only $total entries", total >= 2000)
        assertTrue("only ${BuiltInSets.all().size} sets", BuiltInSets.all().size >= 100)
    }
}
