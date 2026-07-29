package com.verbume.app

import com.verbume.app.game.FlashKind
import com.verbume.app.game.GameConfig
import com.verbume.app.game.GameEngine
import com.verbume.app.game.Matching
import com.verbume.app.game.SubmitResult
import com.verbume.app.model.Direction
import com.verbume.app.model.Entry
import com.verbume.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatchingTest {

    @Test
    fun `diacritics are folded so the A-Z keyboard can type Polish answers`() {
        assertEquals("zreczny", Matching.normalize("zręczny"))
        assertEquals("ciekawosc", Matching.normalize("ciekawość"))
        assertEquals("zolw", Matching.normalize("żółw"))
        assertEquals("laka", Matching.normalize("łąka"))
    }

    @Test
    fun `normalisation is case and whitespace insensitive`() {
        assertEquals("angel investor", Matching.normalize("  Angel   INVESTOR "))
        assertEquals("angel investor", Matching.normalize("angel investor"))
    }

    @Test
    fun `comma separated definitions each count as an accepted answer`() {
        val a = Matching.acceptedAnswers("konsorcjum, syndykat (związek przedsiębiorstw)")
        assertTrue("konsorcjum" in a)
        assertTrue("syndykat" in a)
        assertTrue("syndykat zwiazek przedsiebiorstw" in a)
    }

    @Test
    fun `parenthetical gloss is optional`() {
        val a = Matching.acceptedAnswers("indywidualny inwestor (inw. w spółkę)")
        assertTrue("indywidualny inwestor" in a)
    }

    @Test
    fun `prefix highlighting matches the worked example from the design document`() {
        // The document: typing 'cie' must light up both 'ciekawość' (curiosity)
        // and 'cierpki' (tart).
        val curiosity = Matching.acceptedAnswers("ciekawość")
        val tart = Matching.acceptedAnswers("cierpki")
        assertTrue(Matching.isPrefixOfAny("cie", curiosity))
        assertTrue(Matching.isPrefixOfAny("cie", tart))
        assertTrue(Matching.isPrefixOfAny("ciek", curiosity))
        assertFalse(Matching.isPrefixOfAny("ciek", tart))
    }

    @Test
    fun `empty input highlights nothing`() {
        assertFalse(Matching.isPrefixOfAny("", listOf("cokolwiek")))
        assertFalse(Matching.isPrefixOfAny("   ", listOf("cokolwiek")))
    }

    @Test
    fun `exact match ignores diacritics and case`() {
        val ans = Matching.acceptedAnswers("zręczny")
        assertTrue(Matching.isExactMatch("ZRECZNY", ans))
        assertTrue(Matching.isExactMatch("zręczny", ans))
        assertFalse(Matching.isExactMatch("zreczn", ans))
    }
}

class GameEngineTest {

    /**
     * Automatic waves are off so each test controls exactly what stands on the field;
     * otherwise a monster spawning mid-assertion would make counts non-deterministic.
     * Auto-spawning itself is covered by [the field does not overflow with monsters].
     */
    private fun engine(lives: Int = 3): GameEngine = GameEngine(
        set = WordSet(
            id = "t",
            title = "test",
            entries = listOf(
                Entry("agile", "zręczny"),
                Entry("curiosity", "ciekawość"),
                Entry("tart", "cierpki"),
            ),
        ),
        config = GameConfig(lives = lives, direction = Direction.TERM_TO_DEF, autoSpawn = false),
        random = Random(42),
    )

    /** Advance time until every shot in flight has landed. */
    private fun GameEngine.resolveShots(maxSteps: Int = 400) {
        var n = 0
        while (snapshot().projectiles.isNotEmpty() && n < maxSteps) {
            tick(0.016f)
            n++
        }
    }

    @Test
    fun `correct answer fires and the projectile kills an unarmoured monster`() {
        val e = engine()
        val m = e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
        e.setTyped("zreczny")
        val r = e.submit()
        assertTrue(r is SubmitResult.Fired)
        assertEquals(1, e.snapshot().projectiles.size)

        e.resolveShots()   // let the shot travel

        assertTrue(e.snapshot().monsters.none { it.id == m.id })
        assertEquals(1, e.kills)
        assertTrue(e.score > 0)
    }

    @Test
    fun `armoured monster survives the first shot and dies on the second`() {
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f, armored = true)

        e.setTyped("zreczny"); e.submit()
        e.resolveShots()
        assertEquals("armour should absorb the first hit", 1, e.snapshot().monsters.size)
        assertFalse("armour is stripped", e.snapshot().monsters.first().armored)
        assertEquals(0, e.kills)

        e.setTyped("zreczny"); e.submit()
        e.resolveShots()
        assertTrue(e.snapshot().monsters.isEmpty())
        assertEquals(1, e.kills)
    }

    @Test
    fun `heavy ammunition destroys an armoured monster in one shot and is consumed`() {
        val e = engine()
        e.debugAddCrate("curiosity", "ciekawość", amount = 3)
        e.setTyped("ciekawosc")
        assertTrue(e.submit() is SubmitResult.CrateTaken)
        assertEquals(3, e.heavyAmmo)

        e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f, armored = true)
        e.setTyped("zreczny"); e.submit()
        assertEquals("heavy round is spent at fire time", 2, e.heavyAmmo)
        e.resolveShots()
        assertTrue(e.snapshot().monsters.isEmpty())
        assertEquals(1, e.kills)
    }

    @Test
    fun `basic ammunition is unlimited`() {
        val e = engine()
        repeat(5) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
        }
        assertEquals(5, e.kills)
        assertEquals(0, e.heavyAmmo)
    }

    @Test
    fun `multiplier climbs x2 x3 x4 x5 with consecutive kills`() {
        val e = engine()
        val seen = mutableListOf<Int>()
        repeat(13) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
            seen.add(e.multiplier)
        }
        // after 3 kills x2, after 6 x3, after 9 x4, after 12 x5
        assertEquals(1, seen[1])
        assertEquals(2, seen[2])
        assertEquals(3, seen[5])
        assertEquals(4, seen[8])
        assertEquals(5, seen[11])
    }

    @Test
    fun `a wrong answer breaks the streak but costs no life`() {
        val e = engine()
        repeat(3) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
        }
        assertEquals(2, e.multiplier)
        e.setTyped("kompletnie zle");
        assertTrue(e.submit() is SubmitResult.Miss)
        assertEquals(1, e.multiplier)
        assertEquals(3, e.lives)
    }

    @Test
    fun `a monster reaching the cannon costs a life and resets the streak`() {
        val e = engine(lives = 2)
        repeat(3) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
        }
        assertEquals(2, e.multiplier)

        e.debugAddMonster("tart", "cierpki", x = GameEngine.CANNON_X + 0.061f, y = GameEngine.CANNON_Y)
        repeat(120) { e.tick(0.05f) }

        assertEquals(1, e.lives)
        assertEquals(1, e.multiplier)
        assertFalse(e.gameOver)
    }

    @Test
    fun `losing every life ends the game and freezes input`() {
        val e = engine(lives = 1)
        e.debugAddMonster("tart", "cierpki", x = GameEngine.CANNON_X + 0.061f, y = GameEngine.CANNON_Y)
        repeat(120) { e.tick(0.05f) }
        assertTrue(e.gameOver)
        assertEquals(0, e.lives)

        val before = e.snapshot().typed
        e.type('x')
        assertEquals("typing is ignored after game over", before, e.snapshot().typed)
        assertTrue(e.submit() is SubmitResult.Ignored)
    }

    @Test
    fun `difficulty level rises with kills`() {
        val e = engine()
        assertEquals(1, e.level)
        repeat(8) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
        }
        assertEquals(2, e.level)
    }

    @Test
    fun `typing highlights every monster whose answer shares the prefix`() {
        val e = engine()
        val curiosity = e.debugAddMonster("curiosity", "ciekawość", x = 0.2f, y = 0.3f)
        val tart = e.debugAddMonster("tart", "cierpki", x = 0.8f, y = 0.7f)
        val agile = e.debugAddMonster("agile", "zręczny", x = 0.8f, y = 0.2f)

        e.setTyped("cie")
        val lit = e.highlightedIds()
        assertTrue(curiosity.id in lit)
        assertTrue(tart.id in lit)
        assertFalse(agile.id in lit)

        e.setTyped("ciek")
        assertEquals(setOf(curiosity.id), e.highlightedIds())
    }

    @Test
    fun `two monsters sharing an answer are shot one at a time`() {
        val e = engine()
        val near = e.debugAddMonster("agile", "zręczny", x = 0.65f, y = 0.5f)
        val far = e.debugAddMonster("agile", "zręczny", x = 0.98f, y = 0.5f)

        e.setTyped("zreczny")
        val first = e.submit()
        assertTrue(first is SubmitResult.Fired)
        assertEquals("closest monster is targeted first", near.id, (first as SubmitResult.Fired).monsterId)

        e.setTyped("zreczny")
        val second = e.submit()
        assertTrue(second is SubmitResult.Fired)
        assertEquals(
            "the second shot goes to the other monster, not the one already targeted",
            far.id, (second as SubmitResult.Fired).monsterId
        )
    }

    @Test
    fun `submitting blank input does nothing`() {
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
        e.setTyped("   ")
        assertTrue(e.submit() is SubmitResult.Ignored)
        assertEquals(3, e.lives)
        assertEquals(0, e.kills)
    }

    @Test
    fun `backspace and typing build the answer`() {
        val e = engine()
        e.type('z'); e.type('r'); e.type('x')
        e.backspace()
        e.type('e')
        assertEquals("zre", e.snapshot().typed)
    }

    @Test
    fun `cursor keys let the player fix a letter in the middle of the word`() {
        val e = engine()
        "zrczny".forEach { e.type(it) }
        repeat(4) { e.cursorLeft() }      // caret sits between 'r' and 'c'
        e.type('e')
        assertEquals("zreczny", e.snapshot().typed)
        assertEquals(3, e.snapshot().cursor)

        e.cursorRight(); e.backspace()    // delete the 'c'
        assertEquals("zrezny", e.snapshot().typed)
    }

    @Test
    fun `cursor cannot run off either end`() {
        val e = engine()
        repeat(5) { e.cursorLeft() }
        assertEquals(0, e.snapshot().cursor)
        e.backspace()
        assertEquals("", e.snapshot().typed)
        e.type('a')
        repeat(5) { e.cursorRight() }
        assertEquals(1, e.snapshot().cursor)
    }

    @Test
    fun `submitting resets both the text and the caret`() {
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
        e.setTyped("zreczny")
        e.submit()
        assertEquals("", e.snapshot().typed)
        assertEquals(0, e.snapshot().cursor)
    }

    @Test
    fun `reverse direction asks for the term instead of the definition`() {
        val e = GameEngine(
            set = WordSet(id = "t", title = "t", entries = listOf(Entry("agile", "zręczny"))),
            config = GameConfig(direction = Direction.DEF_TO_TERM),
            random = Random(1),
        )
        e.spawnMonster()
        val m = e.snapshot().monsters.single()
        assertEquals("zręczny", m.prompt)
        e.setTyped("agile")
        assertTrue(e.submit() is SubmitResult.Fired)
    }

    @Test
    fun `an empty set is reported as unplayable instead of crashing`() {
        val e = GameEngine(set = WordSet(id = "e", title = "empty", entries = emptyList()))
        assertFalse(e.isPlayable)
        e.spawnMonster()
        assertTrue(e.snapshot().monsters.isEmpty())
        repeat(300) { e.tick(0.05f) }
        assertFalse(e.gameOver)
    }

    @Test
    fun `entries with a blank side are skipped rather than spawning unanswerable monsters`() {
        val e = GameEngine(
            set = WordSet(
                id = "t", title = "t",
                entries = listOf(Entry("agile", ""), Entry("curiosity", "ciekawość")),
            ),
            random = Random(7),
        )
        repeat(20) { e.spawnMonster() }
        assertTrue(e.snapshot().monsters.isNotEmpty())
        assertTrue(
            "no monster may carry an empty answer list",
            e.snapshot().monsters.all { it.answers.isNotEmpty() }
        )
        assertTrue(e.snapshot().monsters.none { it.prompt == "agile" })
    }

    @Test
    fun `the field does not overflow with monsters`() {
        val e = GameEngine(
            set = WordSet(
                id = "t", title = "t",
                entries = listOf(Entry("agile", "zręczny"), Entry("curiosity", "ciekawość")),
            ),
            config = GameConfig(lives = 99, autoSpawn = true),
            random = Random(3),
        )
        repeat(2000) { e.tick(0.05f) }
        assertTrue(e.snapshot().monsters.size <= GameEngine.MAX_MONSTERS)
    }

    @Test
    fun `a hit raises a flash the UI can react to`() {
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
        e.setTyped("zreczny"); e.submit()
        e.resolveShots()
        assertNotNull(e.snapshot().flash)
        assertEquals(FlashKind.HIT, e.snapshot().flash)
    }
}
