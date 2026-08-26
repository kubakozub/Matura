package com.matura.app.game

import com.matura.app.model.Direction
import com.matura.app.model.Speed
import com.matura.app.model.WordSet
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * CLASSIC mode simulation.
 *
 * Everything lives in a unit square: the cannon sits at (0.5, 0.5), monsters enter
 * from the left and right edges and walk toward it. The UI maps those coordinates
 * onto the actual canvas, so this class stays free of Android and Compose types and
 * can be exercised by plain JUnit tests.
 */

data class Monster(
    val id: Long,
    val prompt: String,
    val answers: List<String>,
    var x: Float,
    var y: Float,
    val speed: Float,
    var armored: Boolean,
    val variant: Int,
    var incoming: Int = 0,
    /** Difficulty level at spawn time — picks the sprite, so it must not drift mid-life. */
    val spawnLevel: Int = 1,
    /** Counts down after a hit so the UI can show the impact frame. */
    var hitFlash: Float = 0f,
)

data class Crate(
    val id: Long,
    val prompt: String,
    val answers: List<String>,
    val x: Float,
    val y: Float,
    var ttl: Float,
    val amount: Int,
)

data class Projectile(
    val id: Long,
    var x: Float,
    var y: Float,
    val targetId: Long,
    val heavy: Boolean,
)

/**
 * A monster that has just been killed, kept around only long enough for the UI to show
 * its death frame. The asset pack ships one `_death` frame per monster and asks for
 * roughly 350 ms on screen.
 */
data class Corpse(
    val id: Long,
    val x: Float,
    val y: Float,
    val spawnLevel: Int,
    val variant: Int,
    var ttl: Float,
)

/** Immutable view handed to the UI once per frame. */
data class GameSnapshot(
    val monsters: List<Monster>,
    val crates: List<Crate>,
    val projectiles: List<Projectile>,
    val corpses: List<Corpse>,
    val typed: String,
    val cursor: Int,
    val lives: Int,
    val maxLives: Int,
    val heavyAmmo: Int,
    val score: Int,
    val level: Int,
    val kills: Int,
    val streak: Int,
    val multiplier: Int,
    val bestStreak: Int,
    val gameOver: Boolean,
    val elapsed: Float,
    val flash: FlashKind?,
    /** Seconds left on the "new level" banner; zero when it should not be shown. */
    val levelUpBanner: Float,
    /** Words that got through to the cannon — the review list on the game-over screen. */
    val missedWords: List<MissedWord> = emptyList(),
)

/**
 * One word the player let through, together with the answer that was expected.
 *
 * A tester asked for a list of everything missed "wraz z ich tłumaczeniem" at the end of
 * a run, to repeat straight away; [times] counts how often the same word beat them.
 */
data class MissedWord(
    val prompt: String,
    val solution: String,
    val times: Int,
)

enum class FlashKind { HIT, ARMOR, MISS, CRATE, LIFE_LOST }

sealed interface SubmitResult {
    data class Fired(val monsterId: Long, val heavy: Boolean) : SubmitResult
    data class ArmorOnly(val monsterId: Long) : SubmitResult
    data class CrateTaken(val amount: Int) : SubmitResult
    data object Miss : SubmitResult
    data object Ignored : SubmitResult
}

data class GameConfig(
    val lives: Int = 3,
    val direction: Direction = Direction.TERM_TO_DEF,
    /** Player-chosen tempo; scales the whole monster speed curve. */
    val speed: Speed = Speed.NORMAL,
    /** Test seam: with automatic waves off, a test controls exactly what is on the field. */
    val autoSpawn: Boolean = true,
)

class GameEngine(
    private val set: WordSet,
    private val config: GameConfig = GameConfig(),
    private val random: Random = Random.Default,
) {
    companion object {
        const val CANNON_X = 0.5f
        const val CANNON_Y = 0.5f
        const val CANNON_RADIUS = 0.06f

        // Tempo obnizone w 0.7.0. Testerzy zglaszali, ze przy dluzszych haslach
        // potworek dochodzil do dzialka, zanim dalo sie dokonczyc pisanie —
        // wolniejszy marsz i rzadsze fale daja czas na wpisanie calego slowa.
        private const val BASE_SPEED = 0.024f
        private const val SPEED_PER_LEVEL = 0.0042f
        private const val PROJECTILE_SPEED = 1.25f
        private const val BASE_SPAWN_INTERVAL = 3.8f
        private const val MIN_SPAWN_INTERVAL = 1.4f
        private const val KILLS_PER_LEVEL = 8
        private const val CRATE_INTERVAL = 19f
        private const val CRATE_TTL = 13f
        private const val CRATE_AMOUNT = 3
        const val MAX_MONSTERS = 7
        const val DEATH_FRAME_TIME = 0.35f
        const val LEVEL_BANNER_TIME = 1.6f
        /** Walk cycle: the pack asks for the base frame and `_f2` to alternate ~every 200 ms. */
        const val WALK_FRAME_TIME = 0.2f
    }

    private var nextId = 1L
    private val monsters = mutableListOf<Monster>()
    private val crates = mutableListOf<Crate>()
    private val projectiles = mutableListOf<Projectile>()
    private val corpses = mutableListOf<Corpse>()

    private var spawnTimer = 0.9f
    private var crateTimer = CRATE_INTERVAL
    private var flash: FlashKind? = null
    private var flashTtl = 0f
    private var levelUpTtl = 0f
    private var lastLevel = 1

    var typed: String = ""
        private set

    /** Caret position inside [typed]; the design document asks for cursor keys. */
    var cursor: Int = 0
        private set

    var lives: Int = config.lives
        private set
    var heavyAmmo: Int = 0
        private set
    var score: Int = 0
        private set
    var kills: Int = 0
        private set
    var streak: Int = 0
        private set
    var bestStreak: Int = 0
        private set
    var gameOver: Boolean = false
        private set
    var elapsed: Float = 0f
        private set

    val level: Int get() = 1 + kills / KILLS_PER_LEVEL

    /** Consecutive-kill bonus: x2 from 3 in a row, then x3, x4 and x5. */
    val multiplier: Int
        get() = when {
            streak >= 12 -> 5
            streak >= 9 -> 4
            streak >= 6 -> 3
            streak >= 3 -> 2
            else -> 1
        }

    /**
     * A word as the field uses it: what stands over the monster, what counts as typed
     * correctly, and how the answer is spelled for the player to read afterwards.
     */
    private data class PlayableWord(
        val prompt: String,
        val answers: List<String>,
        /** The counterpart exactly as the set spells it — with hyphens and diacritics. */
        val solution: String,
    )

    private val playable: List<PlayableWord> = buildPlayable()
    private val byPrompt: Map<String, PlayableWord> = playable.associateBy { it.prompt }

    private fun buildPlayable(): List<PlayableWord> {
        val out = mutableListOf<PlayableWord>()
        for (e in set.entries) {
            val term = e.term.trim()
            val definition = e.definition.trim()
            val termToDef = PlayableWord(term, Matching.acceptedAnswers(definition), definition)
            val defToTerm = PlayableWord(definition, Matching.acceptedAnswers(term), term)
            when (config.direction) {
                Direction.TERM_TO_DEF -> out.add(termToDef)
                Direction.DEF_TO_TERM -> out.add(defToTerm)
                Direction.RANDOM -> out.add(if (random.nextBoolean()) termToDef else defToTerm)
            }
        }
        return out.filter { it.prompt.isNotBlank() && it.answers.isNotEmpty() }
    }

    val isPlayable: Boolean get() = playable.isNotEmpty()

    // Tester: "ograniczenie powtarzania sie slowek, tak aby w jednej rozgrywce dane slowo
    // nie pojawialo sie ponownie". Losowanie ze zwracaniem potrafilo pokazac jedno haslo
    // pieciokrotnie, a innego nie pokazac wcale. Worek tasuje caly zestaw i dobiera bez
    // zwracania; dopiero po jego wyczerpaniu tasuje od nowa. Slowa nadal wracaja w ciagu
    // rozgrywki — o to chodzi w zestawach po ok. 20 hasel — ale rowno, po kolejce.
    private val bag = ArrayDeque<PlayableWord>()
    private var lastDrawn: String? = null

    private fun refillBag() {
        val shuffled = playable.shuffled(random).toMutableList()
        // Nowa kolejka nie zaczyna sie haslem, ktorym skonczyla sie poprzednia — inaczej
        // na styku dwoch kolejek to samo slowo wypadaloby dwa razy pod rzad.
        if (shuffled.size > 1 && shuffled.first().prompt == lastDrawn) {
            val other = 1 + random.nextInt(shuffled.size - 1)
            val head = shuffled[0]
            shuffled[0] = shuffled[other]
            shuffled[other] = head
        }
        bag.addAll(shuffled)
    }

    /**
     * Next word for a monster or a crate, skipping anything already on the field.
     * A word standing on the field stays in the bag and comes back on the next draw.
     */
    private fun drawWord(taken: Set<String>): PlayableWord? {
        if (playable.isEmpty()) return null
        if (bag.isEmpty()) refillBag()
        val free = bag.firstOrNull { it.prompt !in taken }
        if (free != null) {
            bag.remove(free)
            lastDrawn = free.prompt
            return free
        }
        // Maly zestaw, caly worek stoi juz na planszy: bierz cokolwiek, byle nie duplikat.
        val rest = playable.filter { it.prompt !in taken }.ifEmpty { playable }
        val pick = rest[random.nextInt(rest.size)]
        lastDrawn = pick.prompt
        return pick
    }

    // Kolejnosc wstawiania = kolejnosc, w jakiej gracz je przegral. To samo haslo
    // przegrane dwa razy zostaje jednym wpisem z licznikiem.
    private val missed = LinkedHashMap<String, MissedWord>()

    /** Words that reached the cannon, in the order they got through. */
    val missedWords: List<MissedWord> get() = missed.values.toList()

    private fun recordMiss(prompt: String) {
        val word = byPrompt[prompt] ?: return
        val seen = missed[prompt]
        missed[prompt] =
            if (seen == null) MissedWord(word.prompt, word.solution, 1)
            else seen.copy(times = seen.times + 1)
    }

    fun snapshot(): GameSnapshot = GameSnapshot(
        monsters = monsters.map { it.copy() },
        crates = crates.map { it.copy() },
        projectiles = projectiles.map { it.copy() },
        corpses = corpses.map { it.copy() },
        typed = typed,
        cursor = cursor,
        lives = lives,
        maxLives = config.lives,
        heavyAmmo = heavyAmmo,
        score = score,
        level = level,
        kills = kills,
        streak = streak,
        multiplier = multiplier,
        bestStreak = bestStreak,
        gameOver = gameOver,
        elapsed = elapsed,
        flash = flash,
        levelUpBanner = levelUpTtl,
        missedWords = missedWords,
    )

    // ---- input -------------------------------------------------------------

    fun type(ch: Char) {
        if (gameOver) return
        if (typed.length >= 40) return
        typed = typed.substring(0, cursor) + ch + typed.substring(cursor)
        cursor++
    }

    fun backspace() {
        if (gameOver) return
        if (cursor <= 0) return
        typed = typed.substring(0, cursor - 1) + typed.substring(cursor)
        cursor--
    }

    fun cursorLeft() {
        if (cursor > 0) cursor--
    }

    fun cursorRight() {
        if (cursor < typed.length) cursor++
    }

    fun clearTyped() {
        typed = ""
        cursor = 0
    }

    fun setTyped(value: String) {
        if (gameOver) return
        typed = value.take(40)
        cursor = typed.length
    }

    /** Monsters whose answer starts with what is currently typed — these get highlighted. */
    fun highlightedIds(): Set<Long> {
        if (typed.isBlank()) return emptySet()
        return monsters.filter { Matching.isPrefixOfAny(typed, it.answers) }.map { it.id }.toSet()
    }

    fun highlightedCrateIds(): Set<Long> {
        if (typed.isBlank()) return emptySet()
        return crates.filter { Matching.isPrefixOfAny(typed, it.answers) }.map { it.id }.toSet()
    }

    /** Commit the typed text: fire at a matching monster, grab a crate, or break the streak. */
    fun submit(): SubmitResult {
        if (gameOver) return SubmitResult.Ignored
        val answer = typed
        if (Matching.normalize(answer).isEmpty()) return SubmitResult.Ignored
        clearTyped()

        val hits = monsters.filter { Matching.isExactMatch(answer, it.answers) }
        if (hits.isNotEmpty()) {
            // Prefer a monster nothing is flying at yet, then the one closest to the cannon.
            val target = hits.minWithOrNull(
                compareBy({ it.incoming }, { distanceToCannon(it.x, it.y) })
            )!!
            val heavy = target.armored && heavyAmmo > 0
            if (heavy) heavyAmmo--
            target.incoming++
            projectiles.add(
                Projectile(nextId++, CANNON_X, CANNON_Y, target.id, heavy)
            )
            return SubmitResult.Fired(target.id, heavy)
        }

        val crate = crates.firstOrNull { Matching.isExactMatch(answer, it.answers) }
        if (crate != null) {
            crates.remove(crate)
            heavyAmmo += crate.amount
            setFlash(FlashKind.CRATE)
            return SubmitResult.CrateTaken(crate.amount)
        }

        streak = 0
        setFlash(FlashKind.MISS)
        return SubmitResult.Miss
    }

    // ---- simulation --------------------------------------------------------

    fun tick(dt: Float) {
        if (gameOver || dt <= 0f) return
        elapsed += dt

        if (flashTtl > 0f) {
            flashTtl -= dt
            if (flashTtl <= 0f) flash = null
        }
        if (levelUpTtl > 0f) levelUpTtl -= dt
        if (level != lastLevel) {
            lastLevel = level
            levelUpTtl = LEVEL_BANNER_TIME
        }
        for (c in corpses) c.ttl -= dt
        corpses.removeAll { it.ttl <= 0f }

        moveProjectiles(dt)
        moveMonsters(dt)
        ageCrates(dt)
        maybeSpawnMonster(dt)
        maybeSpawnCrate(dt)
    }

    private fun moveProjectiles(dt: Float) {
        val landed = mutableListOf<Projectile>()
        for (p in projectiles) {
            val target = monsters.firstOrNull { it.id == p.targetId }
            if (target == null) {
                landed.add(p)  // target already gone; the shot fizzles out
                continue
            }
            val dx = target.x - p.x
            val dy = target.y - p.y
            val dist = hypot(dx, dy)
            val step = PROJECTILE_SPEED * dt
            if (dist <= step || dist < 1e-4f) {
                landed.add(p)
                resolveHit(p, target)
            } else {
                p.x += dx / dist * step
                p.y += dy / dist * step
            }
        }
        projectiles.removeAll(landed)
    }

    private fun resolveHit(p: Projectile, target: Monster) {
        target.incoming = max(0, target.incoming - 1)
        if (target.armored && !p.heavy) {
            // First shot only strips the armour; a second translation is needed.
            target.armored = false
            target.hitFlash = 0.18f
            setFlash(FlashKind.ARMOR)
            return
        }
        corpses.add(
            Corpse(nextId++, target.x, target.y, target.spawnLevel, target.variant, DEATH_FRAME_TIME)
        )
        monsters.remove(target)
        kills++
        streak++
        if (streak > bestStreak) bestStreak = streak
        score += (10 + 2 * level) * multiplier
        setFlash(FlashKind.HIT)
    }

    private fun moveMonsters(dt: Float) {
        val reached = mutableListOf<Monster>()
        for (m in monsters) {
            if (m.hitFlash > 0f) m.hitFlash -= dt
            val dx = CANNON_X - m.x
            val dy = CANNON_Y - m.y
            val dist = hypot(dx, dy)
            if (dist <= CANNON_RADIUS) {
                reached.add(m)
                continue
            }
            val step = m.speed * dt
            m.x += dx / dist * step
            m.y += dy / dist * step
        }
        if (reached.isNotEmpty()) {
            for (m in reached) {
                monsters.remove(m)
                projectiles.removeAll { it.targetId == m.id }
                recordMiss(m.prompt)
            }
            lives -= reached.size
            streak = 0
            setFlash(FlashKind.LIFE_LOST)
            if (lives <= 0) {
                lives = 0
                gameOver = true
            }
        }
    }

    private fun ageCrates(dt: Float) {
        for (c in crates) c.ttl -= dt
        crates.removeAll { it.ttl <= 0f }
    }

    private fun spawnInterval(): Float =
        max(MIN_SPAWN_INTERVAL, BASE_SPAWN_INTERVAL - (level - 1) * 0.22f)

    private fun monsterSpeed(): Float =
        (BASE_SPEED + (level - 1) * SPEED_PER_LEVEL) * config.speed.factor

    private fun armorChance(): Float =
        if (level < 3) 0f else min(0.6f, (level - 2) * 0.12f)

    private fun maybeSpawnMonster(dt: Float) {
        if (!config.autoSpawn) return
        spawnTimer -= dt
        if (spawnTimer > 0f) return
        spawnTimer = spawnInterval()
        if (monsters.size >= MAX_MONSTERS) return
        spawnMonster()
    }

    fun spawnMonster() {
        val taken = monsters.map { it.prompt }.toSet() + crates.map { it.prompt }.toSet()
        val word = drawWord(taken) ?: return
        val fromLeft = random.nextBoolean()
        monsters.add(
            Monster(
                id = nextId++,
                prompt = word.prompt,
                answers = word.answers,
                x = if (fromLeft) -0.04f else 1.04f,
                y = 0.12f + random.nextFloat() * 0.76f,
                speed = monsterSpeed() * (0.9f + random.nextFloat() * 0.25f),
                armored = random.nextFloat() < armorChance(),
                variant = random.nextInt(4),
                spawnLevel = level,
            )
        )
    }

    private fun maybeSpawnCrate(dt: Float) {
        if (!config.autoSpawn || level < 2) return
        crateTimer -= dt
        if (crateTimer > 0f) return
        crateTimer = CRATE_INTERVAL
        if (crates.size >= 2) return
        val taken = monsters.map { it.prompt }.toSet() + crates.map { it.prompt }.toSet()
        val word = drawWord(taken) ?: return
        crates.add(
            Crate(
                id = nextId++,
                prompt = word.prompt,
                answers = word.answers,
                x = 0.12f + random.nextFloat() * 0.76f,
                y = 0.12f + random.nextFloat() * 0.5f,
                ttl = CRATE_TTL,
                amount = CRATE_AMOUNT,
            )
        )
    }

    private fun setFlash(kind: FlashKind) {
        flash = kind
        flashTtl = 0.35f
    }

    private fun distanceToCannon(x: Float, y: Float): Float = hypot(CANNON_X - x, CANNON_Y - y)

    /** Test and debug helper: drop a monster on the field with a known word. */
    fun debugAddMonster(prompt: String, definition: String, x: Float, y: Float, armored: Boolean = false): Monster {
        val m = Monster(
            id = nextId++,
            prompt = prompt,
            answers = Matching.acceptedAnswers(definition),
            x = x, y = y,
            speed = monsterSpeed(),
            armored = armored,
            variant = 0,
        )
        monsters.add(m)
        return m
    }

    fun debugAddCrate(prompt: String, definition: String, amount: Int = CRATE_AMOUNT): Crate {
        val c = Crate(
            id = nextId++,
            prompt = prompt,
            answers = Matching.acceptedAnswers(definition),
            x = 0.3f, y = 0.3f, ttl = CRATE_TTL, amount = amount,
        )
        crates.add(c)
        return c
    }
}
