package com.matura.app

import com.matura.app.game.FlashKind
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.GameSnapshot
import com.matura.app.game.Matching
import com.matura.app.game.MissedWord
import com.matura.app.game.SubmitResult
import com.matura.app.learn.Srs
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
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
    private fun engine(lives: Int = 3, calm: Boolean = false, finite: Boolean = false): GameEngine = GameEngine(
        set = WordSet(
            id = "t",
            title = "test",
            entries = listOf(
                Entry("agile", "zręczny"),
                Entry("curiosity", "ciekawość"),
                Entry("tart", "cierpki"),
            ),
        ),
        config = GameConfig(
            lives = lives,
            direction = Direction.TERM_TO_DEF,
            autoSpawn = false,
            calm = calm,
            finite = finite,
        ),
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

    /**
     * Let every monster on the field walk all the way to the cannon. Half-second steps keep a
     * monster from stepping over the cannon (it covers far less than CANNON_RADIUS per step).
     */
    private fun GameEngine.walkToCannon(maxSteps: Int = 200) {
        var n = 0
        while (snapshot().monsters.isNotEmpty() && !gameOver && n < maxSteps) {
            tick(0.5f)
            n++
        }
        assertTrue("the monsters should have reached the cannon", snapshot().monsters.isEmpty() || gameOver)
    }

    /** Let the answer on show go out: until then the word it belongs to stays off the field. */
    private fun GameEngine.waitOutReveal() {
        var n = 0
        while (snapshot().reveal != null && n < 200) {
            tick(0.1f)
            n++
        }
        assertNull("the answer should have gone out", snapshot().reveal)
    }

    /**
     * Plays an auto-spawning round the way a student who knows every word would: whenever a
     * monster stands on the field with no shot on its way, its own first answer is typed.
     * [onFrame] sees every frame, so a test can watch for things that must never appear.
     */
    private fun GameEngine.playPerfectly(seconds: Float, onFrame: (GameSnapshot) -> Unit = {}) {
        var t = 0f
        while (t < seconds && !gameOver) {
            tick(0.05f)
            t += 0.05f
            val s = snapshot()
            onFrame(s)
            val m = s.monsters.firstOrNull { it.incoming == 0 } ?: continue
            setTyped(m.answers.first())
            submit()
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
    fun `multiplier climbs x2 x3 x5 with consecutive kills`() {
        val e = engine()
        val seen = mutableListOf<Int>()
        repeat(13) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
            seen.add(e.multiplier)
        }
        // Ujednolicona krzywa 3/6/9 -> x2/x3/x5 (spojna z 3 nowymi trybami i odznakami
        // badge_x2/x3/x5). Po 3 zabiciach x2, po 6 x3, po 9 x5.
        assertEquals(1, seen[1])
        assertEquals(2, seen[2])
        assertEquals(3, seen[5])
        assertEquals(5, seen[8])
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

    // ---- entries that share a prompt ---------------------------------------

    /**
     * arm and hand are both "ręka": the set from AnswersForPromptTest, this time on the
     * classic field. Over a dozen built-in sets have such a pair inside one set (kind and
     * polite, clever and wise, pill and tablet), and the random mix brings together more.
     */
    private fun bodyEngine(direction: Direction, seed: Int = 5): GameEngine = GameEngine(
        set = WordSet(
            id = "t",
            title = "body",
            entries = listOf(
                Entry("arm", "ręka", "w1"),
                Entry("hand", "ręka", "w2"),
                Entry("leg", "noga", "w3"),
            ),
        ),
        config = GameConfig(direction = direction, autoSpawn = false),
        random = Random(seed),
    )

    @Test
    fun `entries that share a prompt accept each other's answers`() {
        // The prompt alone cannot tell the student which row a monster was built from, so
        // every correct translation of that prompt has to count. Before, "hand" typed at the
        // "ręka" of the arm row was a miss and broke the streak.
        val e = bodyEngine(Direction.DEF_TO_TERM)
        val otherRow = mapOf("w1" to "hand", "w2" to "arm")
        val seen = mutableSetOf<String>()
        repeat(3) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            if (m.prompt == "ręka") {
                e.setTyped("ar")
                assertTrue("typing towards arm lights the monster up", m.id in e.highlightedIds())
                e.setTyped("han")
                assertTrue("and so does typing towards hand", m.id in e.highlightedIds())
                e.setTyped("le")
                assertFalse("leg belongs to another prompt", m.id in e.highlightedIds())
                e.setTyped(otherRow.getValue(m.entryId))
                assertTrue("the other row's word is a correct answer", e.submit() is SubmitResult.Fired)
                seen.add(m.entryId)
            } else {
                e.setTyped("arm")
                assertTrue("arm does not translate noga", e.submit() is SubmitResult.Miss)
                e.setTyped("leg")
                assertTrue(e.submit() is SubmitResult.Fired)
            }
            e.resolveShots()
        }
        assertEquals("both rows have been on the field", setOf("w1", "w2"), seen)
        assertEquals(3, e.kills)
    }

    @Test
    fun `sharing an answer is not sharing a prompt`() {
        // The union follows the PROMPT side of the direction being played. Asked in English,
        // arm and hand are two different prompts, so each keeps exactly its own answers.
        val e = bodyEngine(Direction.TERM_TO_DEF)
        repeat(3) { e.spawnMonster() }
        val answers = e.snapshot().monsters.associate { it.prompt to it.answers }
        assertEquals(Matching.acceptedAnswers("ręka"), answers["arm"])
        assertEquals(Matching.acceptedAnswers("ręka"), answers["hand"])
        assertEquals(Matching.acceptedAnswers("noga"), answers["leg"])
    }

    @Test
    fun `in the random direction each row keeps its own side and still shares answers`() {
        // RANDOM picks a side per row. A row asked from the Polish side must accept every
        // English word for that prompt, including the word of a row that this round asks the
        // other way round.
        val directions = mutableSetOf<Direction>()
        for (seed in 1..20) {
            val e = bodyEngine(Direction.RANDOM, seed)
            repeat(3) {
                e.spawnMonster()
                val m = e.snapshot().monsters.single()
                directions.add(m.direction)
                val expected = when {
                    m.direction == Direction.TERM_TO_DEF && m.prompt == "leg" -> listOf("noga")
                    m.direction == Direction.TERM_TO_DEF -> listOf("reka")
                    m.prompt == "noga" -> listOf("leg")
                    else -> listOf("arm", "hand")
                }
                assertEquals("seed $seed, ${m.prompt}", expected.toSet(), m.answers.toSet())
                e.setTyped(m.answers.first()); e.submit()
                e.resolveShots()
            }
        }
        assertEquals(setOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM), directions)
    }

    @Test
    fun `rows that share a prompt each get their turn in one pass through the set`() {
        // While the "ręka" of one row stands on the field the other row cannot come out: two
        // identical prompts would leave a shot without a clear target. It has to wait for its
        // turn in the same pass, not lose it or be swapped for the row that has just been asked.
        val entries = listOf(
            Entry("arm", "ręka", "w1"),
            Entry("hand", "ręka", "w2"),
            Entry("leg", "noga", "w3"),
            Entry("foot", "stopa", "w4"),
        )
        for (seed in 1..30) {
            val e = GameEngine(
                set = WordSet(id = "t", title = "body", entries = entries),
                config = GameConfig(direction = Direction.DEF_TO_TERM, autoSpawn = false),
                random = Random(seed),
            )
            repeat(3) { e.spawnMonster() }
            val field = e.snapshot().monsters
            assertEquals("seed $seed", setOf("ręka", "noga", "stopa"), field.map { it.prompt }.toSet())

            val first = field.single { it.prompt == "ręka" }
            e.setTyped(first.answers.first()); e.submit()
            e.resolveShots()
            e.spawnMonster()
            val second = e.snapshot().monsters.last()
            assertEquals("seed $seed", "ręka", second.prompt)
            assertEquals(
                "seed $seed: the row that waited comes out, not the one just answered",
                setOf("w1", "w2"), setOf(first.entryId, second.entryId),
            )
        }
    }

    @Test
    fun `a row kept waiting by its twin until the bag runs out is first in the next pass`() {
        // The first "ręka" is left standing while the rest of the bag comes out and is answered.
        // What is left in the bag is its twin, which cannot stand next to it, so a new pass has
        // to start. The twin has not had its turn yet and must not lose it: before this was
        // looked after, both rows went to the back of the new bag in shuffled order, and every
        // other time the row that had just been asked came out again while its twin had not
        // been asked once.
        val entries = listOf(
            Entry("arm", "ręka", "w1"),
            Entry("hand", "ręka", "w2"),
            Entry("leg", "noga", "w3"),
            Entry("foot", "stopa", "w4"),
        )
        for (seed in 1..30) {
            val e = GameEngine(
                set = WordSet(id = "t", title = "body", entries = entries),
                config = GameConfig(direction = Direction.DEF_TO_TERM, autoSpawn = false),
                random = Random(seed),
            )
            repeat(3) { e.spawnMonster() }
            val first = e.snapshot().monsters.single { it.prompt == "ręka" }
            for (m in e.snapshot().monsters.filter { it.prompt != "ręka" }) {
                e.setTyped(m.answers.first()); e.submit()
                e.resolveShots()
            }

            // The new pass begins with a word that can stand on the field, never a second "ręka".
            e.spawnMonster()
            assertEquals("seed $seed", 2, e.snapshot().monsters.size)
            assertTrue("seed $seed", e.snapshot().monsters.last().prompt != "ręka")

            // The first twin leaves. The row that waited is next, before anything else.
            e.setTyped(first.answers.first())
            assertTrue(e.submit() is SubmitResult.Fired)
            e.resolveShots()
            e.spawnMonster()
            val second = e.snapshot().monsters.last()
            assertEquals("seed $seed: the waiting row comes out as soon as its prompt is free", "ręka", second.prompt)
            assertEquals(
                "seed $seed: both rows have been asked before either is asked twice",
                setOf("w1", "w2"), setOf(first.entryId, second.entryId),
            )
        }
    }

    @Test
    fun `a prompt without letters or digits stays playable`() {
        // Such a prompt normalises to nothing, so it cannot be looked up among the prompts of
        // the set. The row's own answers must not depend on that lookup, or a user set with
        // "+" or "?" in the term column would lose those rows.
        val e = GameEngine(
            set = WordSet(id = "t", title = "signs", entries = listOf(Entry("+", "plus"), Entry("?", "pytajnik"))),
            config = GameConfig(autoSpawn = false),
            random = Random(1),
        )
        assertTrue(e.isPlayable)
        repeat(2) { e.spawnMonster() }
        val answers = e.snapshot().monsters.associate { it.prompt to it.answers }
        assertEquals(mapOf("+" to listOf("plus"), "?" to listOf("pytajnik")), answers)
    }

    // ---- words that got through --------------------------------------------

    @Test
    fun `a word that reaches the cannon lands on the review list with its translation`() {
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
        e.tick(0.016f)

        assertEquals(2, e.lives)
        val missed = e.missedWords.single()
        assertEquals("agile", missed.prompt)
        // The translation as the set spells it, diacritics included, not the normalised answer.
        assertEquals("zręczny", missed.solution)
        assertEquals(1, missed.times)
        assertEquals(missed, e.snapshot().missedWords.single())
    }

    @Test
    fun `a spawned word that gets through is listed with its entry and direction`() {
        // Not a hand-placed monster this time: the word goes the whole way, from the set
        // through the queue to the cannon. Padding, a hyphen and comma-separated variants are
        // exactly what normalisation strips, so they show whether the spelling survived.
        val set = WordSet(
            id = "t",
            title = "t",
            entries = listOf(Entry(" sister-in-law ", " szwagierka, bratowa ", "w1")),
        )
        val forward = GameEngine(set, GameConfig(autoSpawn = false), Random(1))
        forward.spawnMonster()
        forward.walkToCannon()
        assertEquals(
            listOf(MissedWord("sister-in-law", "szwagierka, bratowa", "w1", Direction.TERM_TO_DEF, 1)),
            forward.missedWords,
        )

        val backward = GameEngine(set, GameConfig(direction = Direction.DEF_TO_TERM, autoSpawn = false), Random(1))
        backward.spawnMonster()
        backward.walkToCannon()
        assertEquals(
            listOf(MissedWord("szwagierka, bratowa", "sister-in-law", "w1", Direction.DEF_TO_TERM, 1)),
            backward.missedWords,
        )
    }

    @Test
    fun `monsters and crates carry the translation as the set spells it`() {
        val entries = listOf(
            Entry("agile", "zręczny"),
            Entry("open-minded", "otwarty, tolerancyjny"),
            Entry("curiosity", "ciekawość"),
            Entry(" sister-in-law ", "szwagierka"),
            Entry("tart", "cierpki"),
            Entry("stepfather", "ojczym"),
        )
        for (direction in listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)) {
            val forward = direction == Direction.TERM_TO_DEF
            val spelling = entries.associate {
                if (forward) it.term.trim() to it.definition.trim() else it.definition.trim() to it.term.trim()
            }
            val e = GameEngine(
                set = WordSet(id = "t", title = "t", entries = entries),
                config = GameConfig(lives = 99, direction = direction),
                random = Random(4),
            )
            var monsters = 0
            var crates = 0
            e.playPerfectly(seconds = 90f) { s ->
                for (m in s.monsters) {
                    assertEquals(spelling[m.prompt], m.solution)
                    monsters++
                }
                for (c in s.crates) {
                    assertEquals(spelling[c.prompt], c.solution)
                    crates++
                }
            }
            assertTrue(monsters > 0)
            assertTrue("the round has to run long enough for a crate to appear", crates > 0)
        }
    }

    @Test
    fun `the same word missed twice is one line with a counter`() {
        val e = engine(lives = 5)
        repeat(2) {
            e.debugAddMonster("agile", "zręczny", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
            e.tick(0.016f)
        }
        assertEquals(1, e.missedWords.size)
        assertEquals(2, e.missedWords.single().times)
    }

    @Test
    fun `the review list keeps the order in which words were first missed`() {
        val e = engine(lives = 9)
        for ((prompt, answer) in listOf("tart" to "cierpki", "agile" to "zręczny", "tart" to "cierpki")) {
            e.debugAddMonster(prompt, answer, x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
            e.tick(0.016f)
        }
        assertEquals(listOf("tart", "agile"), e.missedWords.map { it.prompt })
        assertEquals(listOf(2, 1), e.missedWords.map { it.times })
    }

    @Test
    fun `two entries sharing a prompt stay apart on the review list`() {
        // "uprzejmy" translates both kind and polite. Keyed by the prompt alone the two would
        // merge into one line and show the answer of the wrong row.
        val e = engine(lives = 9)
        e.debugAddMonster("uprzejmy", "kind", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
        e.tick(0.016f)
        e.debugAddMonster("uprzejmy", "polite", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
        e.tick(0.016f)

        assertEquals("two lines, not one line with a counter", 2, e.missedWords.size)
        assertEquals(listOf("kind", "polite"), e.missedWords.map { it.solution })
        assertTrue(e.missedWords.all { it.prompt == "uprzejmy" && it.times == 1 })
    }

    @Test
    fun `with entry ids the review list follows the entry and the direction`() {
        // The same entry asked the other way round is a different thing to learn (the
        // schedule keeps the two apart as well), so it gets a line of its own.
        val e = engine(lives = 9)
        fun miss(prompt: String, answer: String, direction: Direction) {
            e.debugAddMonster(
                prompt, answer,
                x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y,
                entryId = "w1", direction = direction,
            )
            e.tick(0.016f)
        }
        miss("dog", "pies", Direction.TERM_TO_DEF)
        miss("pies", "dog", Direction.DEF_TO_TERM)
        miss("dog", "pies", Direction.TERM_TO_DEF)

        assertEquals(
            listOf(
                MissedWord("dog", "pies", "w1", Direction.TERM_TO_DEF, 2),
                MissedWord("pies", "dog", "w1", Direction.DEF_TO_TERM, 1),
            ),
            e.missedWords,
        )

        // The id decides, not the texts: another entry spelled exactly the same (the same word
        // in a set of the student's own) is still another line.
        e.debugAddMonster(
            "dog", "pies",
            x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y,
            entryId = "u9", direction = Direction.TERM_TO_DEF,
        )
        e.tick(0.016f)
        assertEquals(listOf("w1", "w1", "u9"), e.missedWords.map { it.entryId })
        assertEquals(listOf(2, 1, 1), e.missedWords.map { it.times })
    }

    @Test
    fun `a clean run leaves the review list empty and shows no answer`() {
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
        e.setTyped("zreczny"); e.submit()
        e.resolveShots()
        assertTrue(e.missedWords.isEmpty())
        assertTrue(e.snapshot().missedWords.isEmpty())
        assertNull(e.snapshot().reveal)
    }

    @Test
    fun `the answer is shown when a monster reaches the cannon and goes out after REVEAL_TIME`() {
        // Until now the classic mode never showed the right answer: an unknown word cost a
        // life and stayed unknown.
        val e = engine(lives = 5)
        assertEquals("long enough to read a pair of words", 3.0f, GameEngine.REVEAL_TIME, 0f)
        assertNull(e.snapshot().reveal)
        e.debugAddMonster("agile", "zręczny", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
        e.tick(0.05f)

        val shown = e.snapshot().reveal
        assertNotNull(shown)
        assertEquals("agile", shown!!.prompt)
        assertEquals("zręczny", shown.solution)
        assertEquals("the whole time is still ahead", GameEngine.REVEAL_TIME, shown.ttl, 0f)

        var t = 0f
        while (t < GameEngine.REVEAL_TIME - 0.2f) {
            e.tick(0.05f)
            t += 0.05f
        }
        val fading = e.snapshot().reveal
        assertNotNull("still readable shortly before the time is up", fading)
        assertEquals("agile", fading!!.prompt)
        assertTrue("with the clock nearly run down", fading.ttl > 0f && fading.ttl < 0.5f)

        while (t < GameEngine.REVEAL_TIME + 0.2f) {
            e.tick(0.05f)
            t += 0.05f
        }
        assertNull("gone once the time is up", e.snapshot().reveal)
        assertEquals("the word itself stays on the review list", 1, e.missedWords.size)
    }

    @Test
    fun `a later word replaces the answer on show`() {
        val e = engine(lives = 5)
        e.debugAddMonster("agile", "zręczny", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
        repeat(40) { e.tick(0.05f) }
        assertEquals("agile", e.snapshot().reveal?.prompt)

        e.debugAddMonster("tart", "cierpki", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
        e.tick(0.05f)
        val shown = e.snapshot().reveal
        assertEquals("tart", shown?.prompt)
        assertEquals("cierpki", shown?.solution)
        assertEquals("and its clock starts again", GameEngine.REVEAL_TIME, shown!!.ttl, 0f)
    }

    @Test
    fun `a word does not come back while its answer is on show`() {
        // The answer stays on the field for three seconds and a wave leaves every 3.8 s. When
        // the word that got through was the only one left to hand out (the tail of every
        // finite round), it walked back in under its own translation and could be copied.
        val e = engine(calm = true, finite = true)
        repeat(2) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            e.setTyped(m.answers.first()); e.submit()
            e.resolveShots()
        }
        e.spawnMonster()
        val missed = e.snapshot().monsters.single()
        e.walkToCannon()
        assertEquals(missed.prompt, e.snapshot().reveal?.prompt)

        var t = 0f
        while (t < GameEngine.REVEAL_TIME - 0.1f) {
            e.spawnMonster()
            assertTrue("back on the field under its own answer, after $t s", e.snapshot().monsters.isEmpty())
            e.tick(0.05f)
            t += 0.05f
        }
        assertNotNull(e.snapshot().reveal)

        e.waitOutReveal()
        e.spawnMonster()
        assertEquals("once the answer is gone the word returns", missed.prompt, e.snapshot().monsters.single().prompt)
        assertFalse(e.gameOver)
    }

    @Test
    fun `the wave held back by an answer on show leaves when the answer goes out`() {
        // With waves running by themselves: the word is never on the field together with its
        // answer, and holding it back costs no more than the answer's own three seconds. A
        // wave that found nothing to hand out is not lost for a whole interval.
        for (seed in 1..25) {
            val e = GameEngine(
                set = WordSet(id = "t", title = "t", entries = listOf(Entry("agile", "zręczny", "w1"))),
                config = GameConfig(calm = true, finite = true),
                random = Random(seed),
            )
            var reached = -1f
            var back = -1f
            var frames = 0
            while (back < 0f && frames++ < 20_000) {
                e.tick(0.01f)
                val s = e.snapshot()
                if (s.reveal != null) {
                    if (reached < 0f) reached = e.elapsed
                    assertTrue("seed $seed: the word is back while its answer is shown", s.monsters.isEmpty())
                } else if (reached >= 0f && s.monsters.isNotEmpty()) {
                    back = e.elapsed
                }
            }
            assertTrue("seed $seed: the word never came back", back >= 0f)
            val gap = back - reached
            assertTrue("seed $seed: back after $gap s", gap >= GameEngine.REVEAL_TIME - 0.02f)
            // 3.8 s is one wave interval at level 1: the latest the word came back before.
            assertTrue("seed $seed: back only after $gap s", gap <= 3.8f + 0.05f)
        }
    }

    @Test
    fun `an endless round waits for the answer to go out rather than double a prompt`() {
        // Two words, both off the field, one of them with its answer on show. The other one
        // comes out; after that an endless round used to fill the lane with whatever was next,
        // and now it may only wait: the held word is about to be free, so nothing is doubled.
        val e = GameEngine(
            set = WordSet(id = "t", title = "t", entries = listOf(Entry("agile", "zręczny"), Entry("tart", "cierpki"))),
            config = GameConfig(lives = 5, autoSpawn = false),
            random = Random(3),
        )
        e.spawnMonster()
        val missed = e.snapshot().monsters.single()
        e.walkToCannon()

        e.spawnMonster()
        val other = e.snapshot().monsters.single()
        assertTrue(other.prompt != missed.prompt)
        e.spawnMonster()
        assertEquals("nothing is doubled while a word waits for its answer to go out", 1, e.snapshot().monsters.size)

        e.waitOutReveal()
        e.spawnMonster()
        assertEquals(setOf(missed.prompt, other.prompt), e.snapshot().monsters.map { it.prompt }.toSet())
        // Now everything stands on the field, and a set smaller than the field still spawns.
        e.spawnMonster()
        assertEquals(3, e.snapshot().monsters.size)
    }

    @Test
    fun `a monster with a shot already on its way is not a missed word`() {
        // The answer was right, the shot was only slower than the last step. Listing the word
        // for review, or showing its answer, would contradict what the student has just typed.
        val e = engine()
        e.debugAddMonster("agile", "zręczny", x = GameEngine.CANNON_X + 0.0601f, y = GameEngine.CANNON_Y)
        e.setTyped("zreczny")
        assertTrue(e.submit() is SubmitResult.Fired)

        // Short steps: the monster covers its last ten-thousandth before the shot arrives.
        repeat(20) { e.tick(0.001f) }
        assertTrue("the monster should have reached the cannon", e.snapshot().monsters.isEmpty())
        assertEquals("it was not shot down on the way", 0, e.kills)
        assertTrue(e.missedWords.isEmpty())
        assertNull(e.snapshot().reveal)
    }

    // ---- calm rounds -------------------------------------------------------

    @Test
    fun `in a calm round a monster at the cannon costs no life and cannot end the round`() {
        val e = engine(lives = 1, calm = true)
        repeat(3) {
            e.debugAddMonster("agile", "zręczny", x = 0.9f, y = 0.5f)
            e.setTyped("zreczny"); e.submit()
            e.resolveShots()
        }
        assertEquals(2, e.multiplier)

        // One life, and more monsters get through than any round with lives could survive.
        repeat(4) {
            e.debugAddMonster("tart", "cierpki", x = GameEngine.CANNON_X, y = GameEngine.CANNON_Y)
            e.tick(0.016f)
        }
        assertEquals("no life is taken", 1, e.lives)
        assertFalse("so the round cannot be lost", e.gameOver)
        assertFalse(e.completed)
        assertTrue("the snapshot says so, for a HUD that hides the hearts", e.snapshot().calm)
        assertFalse(engine().snapshot().calm)

        // Everything else about a word getting through stays as it is in a round with lives.
        assertEquals("the streak still breaks", 0, e.streak)
        assertEquals(1, e.multiplier)
        assertEquals(4, e.missedWords.single().times)
        assertEquals("cierpki", e.snapshot().reveal?.solution)
        assertEquals("and the screen gets the same cue", FlashKind.LIFE_LOST, e.snapshot().flash)
    }

    /** What a whole auto-spawning round looked like to someone watching the field. */
    private class RoundLog {
        var kills = 0
        var armoured = 0
        var crateFrames = 0
        var topLevel = 1
        var lowSpeed = Float.MAX_VALUE
        var topSpeed = 0f

        /** Seconds between one monster entering the field and the next. */
        val spawnGaps = mutableListOf<Float>()

        /** Points paid for each kill, in order. */
        val killScores = mutableListOf<Int>()
    }

    private fun watchRound(calm: Boolean): RoundLog {
        val e = GameEngine(
            set = WordSet(id = "t", title = "t", entries = (1..12).map { Entry("word$it", "slowo$it") }),
            config = GameConfig(lives = 99, calm = calm),
            random = Random(9),
        )
        val log = RoundLog()
        val seen = mutableSetOf<Long>()
        var lastSpawnAt = -1f
        var lastScore = 0
        e.playPerfectly(seconds = 160f) { s ->
            for (m in s.monsters) {
                if (!seen.add(m.id)) continue
                if (lastSpawnAt >= 0f) log.spawnGaps.add(s.elapsed - lastSpawnAt)
                lastSpawnAt = s.elapsed
                if (m.armored) log.armoured++
                log.lowSpeed = min(log.lowSpeed, m.speed)
                log.topSpeed = max(log.topSpeed, m.speed)
                log.topLevel = max(log.topLevel, m.spawnLevel)
            }
            if (s.crates.isNotEmpty()) log.crateFrames++
            log.topLevel = max(log.topLevel, s.level)
            val gained = s.kills - log.kills
            if (gained > 0) {
                // Two shots can land in one frame; split the points so every kill has its own line.
                repeat(gained) { log.killScores.add((s.score - lastScore) / gained) }
                lastScore = s.score
                log.kills = s.kills
            }
        }
        return log
    }

    @Test
    fun `a calm round never raises the difficulty`() {
        val calm = watchRound(calm = true)
        assertTrue("the round has to be long enough to matter: ${calm.kills} kills", calm.kills >= 30)
        assertEquals("no armour", 0, calm.armoured)
        assertEquals("no crates", 0, calm.crateFrames)
        assertEquals("the level stays where it started", 1, calm.topLevel)
        // Within one level the fastest monster is at most 28 percent quicker than the slowest
        // (each gets a factor between 0.9 and 1.15), so a wider spread means a faster level.
        assertTrue(
            "monsters do not speed up: ${calm.lowSpeed} to ${calm.topSpeed}",
            calm.topSpeed <= calm.lowSpeed * 1.3f,
        )
        assertTrue(
            "waves do not come faster: ${calm.spawnGaps}",
            calm.spawnGaps.max() - calm.spawnGaps.min() < 0.11f,
        )
        assertEquals(
            "a kill at full streak pays the same from start to finish: ${calm.killScores}",
            1, calm.killScores.drop(9).distinct().size,
        )

        // The same round with lives, as a control: every one of those things does change
        // there, so the assertions above are not passing for want of time.
        val usual = watchRound(calm = false)
        assertTrue(usual.armoured > 0)
        assertTrue(usual.crateFrames > 0)
        assertTrue(usual.topLevel >= 4)
        assertTrue(usual.topSpeed > usual.lowSpeed * 1.5f)
        assertTrue(usual.spawnGaps.min() < usual.spawnGaps.first() - 0.5f)
        assertTrue(usual.killScores.drop(9).distinct().size > 1)
    }

    // ---- finite rounds -----------------------------------------------------

    @Test
    fun `a finite round ends by itself once every word has been answered`() {
        val e = engine(finite = true)
        assertEquals(0, e.snapshot().targetsDone)
        assertEquals(3, e.snapshot().targetsTotal)

        val seen = mutableListOf<String>()
        repeat(3) { i ->
            assertFalse(e.gameOver)
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            seen.add(m.prompt)
            e.setTyped(m.answers.first())
            assertTrue(e.submit() is SubmitResult.Fired)
            assertEquals("an answer counts the moment it is given", i + 1, e.snapshot().targetsDone)
            assertFalse("but the round waits for the shot to land", e.completed)
            e.resolveShots()
        }

        assertEquals("every word once", setOf("agile", "curiosity", "tart"), seen.toSet())
        assertTrue(e.completed)
        assertTrue("an ended round is over, whatever ended it", e.gameOver)
        val s = e.snapshot()
        assertTrue(s.completed)
        assertTrue(s.gameOver)
        assertEquals(3, s.targetsDone)
        assertEquals(3, s.targetsTotal)
        assertEquals("the last kill belongs to the round", 3, s.kills)
        assertEquals(3, s.lives)

        // The guards that used to mean "out of lives" hold for a completed round as well.
        e.type('x')
        assertEquals("", e.snapshot().typed)
        assertTrue(e.submit() is SubmitResult.Ignored)
        e.spawnMonster()
        assertTrue("nothing is left to put on the field", e.snapshot().monsters.isEmpty())
    }

    @Test
    fun `a finite round does not repeat a word to fill the field`() {
        // An endless round repeats a word rather than leave a lane empty. Here a repeat would
        // show a second copy of a word the student is still working on.
        val e = engine(finite = true)
        repeat(6) { e.spawnMonster() }
        assertEquals(setOf("agile", "curiosity", "tart"), e.snapshot().monsters.map { it.prompt }.toSet())
        assertEquals(3, e.snapshot().monsters.size)
        assertFalse(e.gameOver)
    }

    @Test
    fun `a word that got through a finite round comes back until it is answered`() {
        val e = engine(lives = 1, calm = true, finite = true)
        e.spawnMonster()
        val missed = e.snapshot().monsters.single()
        e.walkToCannon()
        assertEquals("nothing has been answered yet", 0, e.snapshot().targetsDone)
        assertFalse(e.gameOver)

        // The rest of the queue goes first: the word that got through comes back later,
        // not straight away.
        val others = mutableListOf<String>()
        repeat(2) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            others.add(m.prompt)
            e.setTyped(m.answers.first()); e.submit()
            e.resolveShots()
        }
        assertEquals(2, others.toSet().size)
        assertFalse(missed.prompt in others)
        assertEquals(2, e.snapshot().targetsDone)
        assertFalse("one word is still owed", e.gameOver)

        // Now it returns, as often as it takes, and nothing else does. Each time it first
        // waits for its answer to leave the field.
        repeat(2) {
            e.waitOutReveal()
            e.spawnMonster()
            assertEquals(missed.prompt, e.snapshot().monsters.single().prompt)
            e.walkToCannon()
            assertFalse(e.gameOver)
        }
        e.waitOutReveal()
        e.spawnMonster()
        assertEquals(missed.prompt, e.snapshot().monsters.single().prompt)
        e.setTyped(missed.answers.first()); e.submit()
        e.resolveShots()

        assertTrue(e.completed)
        assertEquals(3, e.missedWords.single().times)
        assertEquals(3, e.snapshot().targetsDone)
    }

    @Test
    fun `a finite round with lives still ends when they run out`() {
        val e = engine(lives = 1, finite = true)
        e.spawnMonster()
        e.walkToCannon()
        assertTrue(e.gameOver)
        assertFalse("lost, not completed", e.completed)
        assertFalse(e.snapshot().completed)
        assertEquals(0, e.lives)
        assertEquals(0, e.snapshot().targetsDone)
    }

    @Test
    fun `losing the last life in the frame that clears the field is still a loss`() {
        val e = engine(lives = 1, finite = true)
        // Two of the three words are answered with time to spare.
        repeat(2) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            e.setTyped(m.answers.first()); e.submit()
            e.resolveShots()
        }
        // The last word is answered too, but something else is already at the cannon. One
        // long step lands the shot and lets that monster through in the same frame.
        e.spawnMonster()
        val last = e.snapshot().monsters.single()
        e.debugAddMonster("stepfather", "ojczym", x = GameEngine.CANNON_X + 0.05f, y = GameEngine.CANNON_Y)
        e.setTyped(last.answers.first())
        assertTrue(e.submit() is SubmitResult.Fired)
        e.tick(1.0f)

        assertEquals("every word was answered", 3, e.snapshot().targetsDone)
        assertEquals(3, e.kills)
        assertTrue(e.snapshot().monsters.isEmpty())
        assertEquals(0, e.lives)
        assertTrue(e.gameOver)
        assertFalse("out of lives comes first", e.completed)
    }

    @Test
    fun `rows that share a prompt are separate words of a finite round`() {
        // arm and hand show the same prompt and accept the same answers, yet they are two
        // entries with two records in the schedule, so the round asks "ręka" twice. Either
        // word counts both times: the round cannot know which one the student has in mind.
        val e = GameEngine(
            set = WordSet(
                id = "t",
                title = "body",
                entries = listOf(Entry("arm", "ręka", "w1"), Entry("hand", "ręka", "w2"), Entry("leg", "noga", "w3")),
            ),
            config = GameConfig(direction = Direction.DEF_TO_TERM, autoSpawn = false, finite = true),
            random = Random(5),
        )
        assertEquals(3, e.snapshot().targetsTotal)
        val asked = mutableListOf<String>()
        val entries = mutableListOf<String>()
        while (!e.gameOver && asked.size < 10) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            asked.add(m.prompt)
            entries.add(m.entryId)
            e.setTyped(if (m.prompt == "ręka") "arm" else "leg")
            assertTrue(e.submit() is SubmitResult.Fired)
            e.resolveShots()
        }
        assertEquals(listOf("noga", "ręka", "ręka"), asked.sorted())
        assertEquals(setOf("w1", "w2", "w3"), entries.toSet())
        assertTrue(e.completed)
    }

    @Test
    fun `a finite round waits until the field is clear`() {
        val e = engine(finite = true)
        // Not one of the words of the round, but still a monster the student has to deal with.
        val extra = e.debugAddMonster("stepfather", "ojczym", x = 0.95f, y = 0.14f)
        repeat(3) {
            e.spawnMonster()
            val m = e.snapshot().monsters.last()
            e.setTyped(m.answers.first()); e.submit()
            e.resolveShots()
        }
        assertEquals(3, e.snapshot().targetsDone)
        assertEquals(listOf(extra.id), e.snapshot().monsters.map { it.id })
        repeat(10) { e.tick(0.05f) }
        assertFalse("a monster is still walking", e.gameOver)

        e.setTyped("ojczym"); e.submit()
        e.resolveShots()
        assertTrue(e.completed)
    }

    @Test
    fun `taking a crate answers its word in a finite round`() {
        val e = engine(finite = true)
        e.debugAddCrate("curiosity", "ciekawość")
        e.setTyped("ciekawosc")
        assertTrue(e.submit() is SubmitResult.CrateTaken)
        assertEquals(1, e.snapshot().targetsDone)

        val rest = mutableListOf<String>()
        repeat(2) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            rest.add(m.prompt)
            e.setTyped(m.answers.first()); e.submit()
            e.resolveShots()
        }
        assertEquals("the crate's word is not asked again", setOf("agile", "tart"), rest.toSet())
        assertTrue(e.completed)
    }

    @Test
    fun `an endless round has no targets and never completes`() {
        val e = engine()
        repeat(6) {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            e.setTyped(m.answers.first()); e.submit()
            e.resolveShots()
        }
        assertEquals(6, e.kills)
        assertEquals(0, e.snapshot().targetsDone)
        assertEquals(0, e.snapshot().targetsTotal)
        assertFalse(e.completed)
        assertFalse(e.gameOver)
    }

    @Test
    fun `an empty set does not complete a finite round`() {
        // Nothing was asked, so nothing was completed: the screen shows its empty-set notice
        // instead of a finished round, and no score is recorded.
        val e = GameEngine(
            set = WordSet(id = "e", title = "empty", entries = emptyList()),
            config = GameConfig(finite = true),
        )
        repeat(300) { e.tick(0.05f) }
        assertFalse(e.completed)
        assertFalse(e.gameOver)
        assertEquals(0, e.snapshot().targetsTotal)
    }

    // ---- whole rounds ------------------------------------------------------

    /**
     * Rounds of every kind, played by a student who answers some monsters and lets others
     * through. There is no single scenario here: the point is what has to hold in all of them,
     * whatever the set, the direction, the mode and the luck of the draw. The timeout is part
     * of the test, because a queue that stops handing out words looks like a hang, not a failure.
     */
    @Test(timeout = 60_000L)
    fun `rounds of every kind keep their promises`() {
        var completedRounds = 0
        for (seed in 1..150) {
            val r = Random(seed)
            val size = 1 + r.nextInt(14)
            // Every third row shares its Polish side with another row, the way arm and hand do.
            val entries = (1..size).map {
                Entry("word$it", if (it % 3 == 0) "wspolne${it / 6}" else "slowo$it", "w$it")
            }
            val direction = Direction.values()[r.nextInt(3)]
            val calm = r.nextBoolean()
            val finite = r.nextBoolean()
            val history = if (r.nextBoolean()) emptyMap() else entries.filter { r.nextInt(3) != 0 }.associate {
                val side = if (r.nextBoolean()) Direction.TERM_TO_DEF else Direction.DEF_TO_TERM
                Srs.key(it.id, side) to WordProgress(box = r.nextInt(4), dueDay = 95 + r.nextInt(10))
            }
            // A diligent student answers within a moment; the others only now and then.
            val diligent = r.nextBoolean()
            val startLives = 1 + r.nextInt(9)
            val e = GameEngine(
                set = WordSet(id = "t", title = "t", entries = entries),
                config = GameConfig(
                    lives = startLives, direction = direction, calm = calm, finite = finite,
                    progress = history, today = 100, newWordsPerRound = 1 + r.nextInt(12),
                ),
                random = Random(seed * 31 + 7),
            )
            val round = "seed $seed: $size words, $direction, calm=$calm, finite=$finite, " +
                "history=${history.size}, diligent=$diligent"

            var done = 0
            var frames = 0
            while (!e.gameOver && frames++ < 6_000) {
                e.tick(0.05f)
                val s = e.snapshot()
                assertTrue(round, s.monsters.size <= GameEngine.MAX_MONSTERS)
                assertTrue(round, (s.monsters.map { it.solution } + s.crates.map { it.solution }).none { it.isBlank() })
                if (finite) {
                    val onField = s.monsters.map { it.prompt } + s.crates.map { it.prompt }
                    assertEquals("no prompt stands twice in a finite round, $round", onField.toSet().size, onField.size)
                    assertTrue("progress never goes back, $round", s.targetsDone in done..s.targetsTotal)
                    done = s.targetsDone
                } else {
                    assertEquals(round, 0, s.targetsTotal)
                    assertFalse(round, s.completed)
                }
                if (calm) {
                    assertEquals("a calm round takes no lives, $round", startLives, s.lives)
                    assertTrue("and has no armour, $round", s.monsters.none { it.armored })
                    assertTrue("and no crates, $round", s.crates.isEmpty())
                    assertEquals(round, 1, s.level)
                }
                if (r.nextFloat() < (if (diligent) 0.2f else 0.01f)) {
                    val m = s.monsters.filter { it.incoming == 0 }.randomOrNull(r) ?: continue
                    e.setTyped(m.answers[r.nextInt(m.answers.size)])
                    assertTrue(round, e.submit() is SubmitResult.Fired)
                }
            }

            val end = e.snapshot()
            if (end.completed) {
                completedRounds++
                assertTrue(round, finite && end.gameOver)
                assertEquals(round, end.targetsTotal, end.targetsDone)
                assertTrue(round, end.monsters.isEmpty() && end.projectiles.isEmpty())
            }
            if (calm && !finite) assertFalse("nothing ends a calm endless round, $round", end.gameOver)
            if (calm && finite && diligent) assertTrue("a calm finite round gets finished, $round", end.completed)
        }
        assertTrue("the sample has to include rounds that were completed", completedRounds >= 30)
    }
}
