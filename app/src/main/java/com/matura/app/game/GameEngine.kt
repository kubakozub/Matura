package com.matura.app.game

import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.model.Direction
import com.matura.app.model.Speed
import com.matura.app.model.WordProgress
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
    /**
     * Wpis, z ktorego wzieto haslo, i kierunek TEGO wiersza - czyli wszystko, czego
     * potrzebuje Srs.key, zeby zapisac postep. Pusty identyfikator znaczy "nie wiadomo,
     * czyj to postep" (plansza testowa, wpis sprzed wersjonowania) i wtedy gra nie notuje
     * o tym slowie niczego. Kierunek NIGDY nie jest RANDOM: losowanie zapada raz, przy
     * budowie puli, bo klucz postepu z RANDOM nie istnieje.
     */
    val entryId: String = "",
    val direction: Direction = Direction.TERM_TO_DEF,
)

data class Crate(
    val id: Long,
    val prompt: String,
    val answers: List<String>,
    val x: Float,
    val y: Float,
    var ttl: Float,
    val amount: Int,
    /** Jak w [Monster]: skrzynia niesie zwykle slowo z zestawu, wiec ma i wlasciciela postepu. */
    val entryId: String = "",
    val direction: Direction = Direction.TERM_TO_DEF,
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
)

enum class FlashKind { HIT, ARMOR, MISS, CRATE, LIFE_LOST }

sealed interface SubmitResult {
    data class Fired(val monsterId: Long, val heavy: Boolean) : SubmitResult
    data class ArmorOnly(val monsterId: Long) : SubmitResult
    data class CrateTaken(val amount: Int) : SubmitResult
    data object Miss : SubmitResult
    data object Ignored : SubmitResult
}

/**
 * Co gra zaobserwowala o jednym slowie.
 *
 * CELOWO nie jest to com.matura.app.learn.Attempt, chociaz niesie te same fakty. Attempt ma
 * znacznik jednorazowosci, ktory musi byc unikalny takze MIEDZY rundami i po restarcie
 * (WordProgress.lastAttemptId przezywa zapis), a silnik gry nie ma zegara i po nowej rundzie
 * liczylby od nowa - dwie rozne proby dostalyby ten sam znacznik i druga przepadlaby jako
 * "juz przetworzona". [nr] jest wiec tylko numerem w obrebie rundy, a pelny znacznik sklada
 * warstwa zapisu, doklejajac czas. Osobny typ pilnuje, zeby nikt nie podal tego wprost do
 * Repository.recordAttempt.
 */
data class LearningEvent(
    val entryId: String,
    val direction: Direction,
    val outcome: Outcome,
    val nr: Int,
)

data class GameConfig(
    val lives: Int = 3,
    val direction: Direction = Direction.TERM_TO_DEF,
    /** Player-chosen tempo; scales the whole monster speed curve. */
    val speed: Speed = Speed.NORMAL,
    /** Test seam: with automatic waves off, a test controls exactly what is on the field. */
    val autoSpawn: Boolean = true,
    /**
     * Historia nauki slow (klucz sklada Srs.key). Zwykle DANE, wstrzykniete przez wolajacego -
     * silnik nie siega do zapisu i dzieki temu zostaje testowalny bez Androida.
     *
     * PUSTA MAPA znaczy "gra o rekord": slowa leca losowo, dokladnie jak przed wprowadzeniem
     * harmonogramu. Mapa niepusta znaczy "runda realizuje plan powtorek". Rozroznienie jest
     * widoczne dla ucznia (podpis na planszy), bo inaczej dobor slow zmienialby sie sam z
     * siebie i wygladalby na kaprys losowania.
     */
    val progress: Map<String, WordProgress> = emptyMap(),
    /**
     * Dzien harmonogramu (Srs.dayOf) w chwili startu rundy. Znaczy cokolwiek tylko razem
     * z [progress]. Ustalany RAZ, na starcie: gdyby doba zmienila sie w trakcie rundy,
     * polowa planu stalaby sie nagle niezalegla.
     */
    val today: Int = 0,
    /**
     * Ile NOWYCH slow wolno wprowadzic w jednej rundzie.
     *
     * Bez sufitu dluga runda przy swiezym zestawie pokazalaby uczniowi kilkadziesiat nowych
     * hasel raz i nigdy do nich nie wrocila - a to jest dokladne przeciwienstwo powtorek.
     * Dziesiec to tyle, ile przy trzech zyciach mniej wiecej mozna zobaczyc kilka razy;
     * reszta nowych czeka na kolejna runde (i tak trafi do planu, tylko na jego koniec).
     */
    val newWordsPerRound: Int = 10,
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
        /**
         * Potwory suna poziomo na stalej wysokosci, a dlugie etykiety sa dociagane do ekranu,
         * wiec dwa potwory na zblizonym y = napisy zlepione w kase (widoczne na dziale "Charakter").
         * Rozdzielamy je na stale TORY z gwarantowanym odstepem pionowym; MAX = liczba torow,
         * wiec przy spawnie zawsze jest wolny tor i etykiety nigdy sie nie nakladaja.
         */
        val LANES = listOf(0.14f, 0.32f, 0.50f, 0.68f, 0.86f)
        const val MAX_MONSTERS = 5
        const val DEATH_FRAME_TIME = 0.35f
        const val LEVEL_BANNER_TIME = 1.6f
        /** Walk cycle: the pack asks for the base frame and `_f2` to alternate ~every 200 ms. */
        const val WALK_FRAME_TIME = 0.2f

        /**
         * Jak blisko hasla musi byc chybiona odpowiedz, zeby uznac ja za pomylke WLASNIE
         * przy tym hasle (patrz missTarget). 0,6 to mniej wiecej "co trzeci znak inny" -
         * przepuszcza literowke i przekrecona koncowke, odrzuca inne slowo z planszy.
         */
        private const val MISS_SIMILARITY = 0.6
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
        // Krzywa mnoznika wspolna z 3 nowymi trybami i zestawem odznak (badge_x2/x3/x5,
        // brak x4): 3/6/9 -> x2/x3/x5. Osiagniecie COMBO_X5 (bestStreak>=9) jest z nia zgodne.
        get() = when {
            streak >= 9 -> 5
            streak >= 6 -> 3
            streak >= 3 -> 2
            else -> 1
        }

    /**
     * Jedno grywalne haslo: co stoi nad potworkiem, co gra przyjmuje jako odpowiedz i CZYJ
     * to postep. Kierunek jest tu juz rozstrzygniety (nigdy RANDOM), bo klucz postepu bez
     * kierunku nie istnieje - patrz Srs.key.
     */
    private data class Playable(
        val entryId: String,
        val direction: Direction,
        val prompt: String,
        val answers: List<String>,
    )

    private val playable: List<Playable> = buildPlayable()

    private fun buildPlayable(): List<Playable> {
        val out = mutableListOf<Playable>()
        for (e in set.entries) {
            // RANDOM to ustawienie "losuj kierunek", a nie kierunek. Losujemy go dla KAZDEGO
            // wiersza osobno i na cala runde: tak samo widzi to plansza (jedno slowo ma jedna
            // strone od poczatku do konca) i tak samo zapis postepu, ktory inaczej trafialby
            // pod klucz odrzucany przez Srs.key. Zapisanie kierunku CALEJ rundy byloby falszem
            // - w trybie losowym co drugi wiersz ma go odwrotny.
            val kierunek = when (config.direction) {
                Direction.RANDOM ->
                    if (random.nextBoolean()) Direction.TERM_TO_DEF else Direction.DEF_TO_TERM
                else -> config.direction
            }
            val wprzod = kierunek == Direction.TERM_TO_DEF
            out.add(
                Playable(
                    entryId = e.id,
                    direction = kierunek,
                    prompt = (if (wprzod) e.term else e.definition).trim(),
                    answers = Matching.acceptedAnswers(if (wprzod) e.definition else e.term),
                )
            )
        }
        return out.filter { it.prompt.isNotBlank() && it.answers.isNotEmpty() }
    }

    val isPlayable: Boolean get() = playable.isNotEmpty()

    // ---- plan rundy --------------------------------------------------------

    /**
     * Kolejnosc, w jakiej slowa maja wychodzic na plansze - albo null, gdy runda jest GRA
     * O REKORD (wolajacy nie podal historii, patrz [GameConfig.progress]).
     *
     * Plan powstaje RAZ, przy tworzeniu silnika. Uczen ma grac wedlug tego, co bylo zalegle
     * na starcie rundy, a nie wedlug stanu, ktory sam zmienil dwa potworki wczesniej - inaczej
     * dobrze odpowiedziane slowo wypadaloby z planszy w srodku fali i kolejnosc zmienialaby
     * sie pod reka.
     *
     * Kolejnosc bierze sie wprost z harmonogramu:
     *  1. zalegle powtorki, najpilniejsze na przodzie (patrz [potrzeba]),
     *  2. nowe slowa, ale najwyzej [GameConfig.newWordsPerRound],
     *  3. to, co jeszcze nie jest zalegle (w tym utrwalone), od najblizszego terminu,
     *  4. nowe ponad limit - dopiero na samym koncu, gdy runda okaze sie bardzo dluga.
     *
     * Plan jest KOLEJKA CYKLICZNA, wiec dlugiej rundzie nigdy nie zabraknie celow, a slowo
     * z poczatku wraca dopiero po przejsciu calej puli (to jest cala "roznorodnosc": kolejka
     * gwarantuje ja mocniej niz losowanie, ktore potrafi wyciagnac to samo slowo trzy razy
     * pod rzad).
     */
    private val plan: List<Playable>? = buildPlan()

    /** Pozycja w [plan]; osobne pole, bo plan jest niezmienny przez cala runde. */
    private var planIdx = 0

    /** Czy runda realizuje plan powtorek, a nie losowanie dla wyniku. Ekran to podpisuje. */
    val plannedLearning: Boolean get() = plan != null

    private fun buildPlan(): List<Playable>? {
        if (config.progress.isEmpty() || playable.isEmpty()) return null
        val zalegle = mutableListOf<Playable>()
        val nowe = mutableListOf<Playable>()
        val czekajace = mutableListOf<Playable>()
        // Tasujemy PRZED podzialem i sortowaniem: sortowanie w Kotlinie jest stabilne, wiec
        // slowa o tej samej pilnosci wychodza za kazdym razem w innej kolejnosci, a kolejnosc
        // miedzy roznymi pilnosciami zostaje nienaruszona.
        for (c in playable.shuffled(random)) {
            val p = progressOf(c)
            when {
                p.box == Srs.NO_BOX -> nowe.add(c)
                Srs.isDue(p, config.today) -> zalegle.add(c)
                else -> czekajace.add(c)
            }
        }
        return zalegle.sortedByDescending { potrzeba(progressOf(it)) } +
            nowe.take(config.newWordsPerRound) +
            czekajace.sortedBy { progressOf(it).dueDay } +
            nowe.drop(config.newWordsPerRound)
    }

    /** Stan nauki slowa. Wpis bez identyfikatora (sprzed wersjonowania) liczy sie jak nowy. */
    private fun progressOf(cel: Playable): WordProgress =
        if (cel.entryId.isBlank()) WordProgress()
        else Srs.progressOf(config.progress, cel.entryId, cel.direction)

    /**
     * Jak pilne jest slowo w kolejce zaleglych. Najpierw to, przy ktorym uczen potrzebowal
     * dzis pomocy (pomylka, podpowiedz, potworek, ktory doszedl do dziala), potem to, na
     * ktorym potyka sie najczesciej, a na koncu to, ktore stoi najnizej w pudelkach.
     * Liczniki obcinamy, zeby jedno slowo z dluga historia nie zajelo calej rundy.
     */
    private fun potrzeba(p: WordProgress): Int =
        (if (p.assistedDay == config.today) 8 else 0) +
            min(p.mistakes, 4) * 2 + min(p.hints, 4) + (Srs.MAX_BOX - p.box)

    /**
     * Slowo na nowego potworka albo na skrzynie.
     *
     * Trzy rzeczy trzymaja sie tu razem i zadnej nie wolno zgubic: na planszy nie moga stac
     * dwa te same napisy (strzal nie mialby jednoznacznego celu), pula mniejsza od planszy
     * musi dalej wystawiac cele (stad zgoda na powtorzenie, gdy nie ma juz czego wybrac),
     * a przegladanie planu ma STALA dlugosc - zadnego "losuj, dopoki nie trafisz wolnego".
     */
    private fun nextTarget(): Playable? {
        if (playable.isEmpty()) return null
        val zajete = monsters.map { it.prompt }.toSet() + crates.map { it.prompt }.toSet()
        val kolejka = plan ?: run {
            // GRA O REKORD: dobor jednostajny, dokladnie jak przed harmonogramem.
            val choices = playable.filter { it.prompt !in zajete }.ifEmpty { playable }
            return choices[random.nextInt(choices.size)]
        }
        for (k in kolejka.indices) {
            val i = (planIdx + k) % kolejka.size
            if (kolejka[i].prompt !in zajete) {
                planIdx = (i + 1) % kolejka.size
                return kolejka[i]
            }
        }
        // Cala pula stoi juz na planszy (pula mniejsza niz plansza). Powtarzamy biezace slowo
        // zamiast nie wystawic nic - tak samo, jak robil to dobor losowy.
        val cel = kolejka[planIdx]
        planIdx = (planIdx + 1) % kolejka.size
        return cel
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
            // ODPOWIEDZ ZALICZONA. Notujemy w chwili odpowiedzi, a nie trafienia: pancerz,
            // lot pocisku i to, czy potworek dozyje uderzenia, sa sprawa gry - o wiedzy ucznia
            // rozstrzyga sam moment, w ktorym poprawne haslo trafilo na plansze.
            note(target.entryId, target.direction, Outcome.POPRAWNA)
            return SubmitResult.Fired(target.id, heavy)
        }

        val crate = crates.firstOrNull { Matching.isExactMatch(answer, it.answers) }
        if (crate != null) {
            crates.remove(crate)
            heavyAmmo += crate.amount
            setFlash(FlashKind.CRATE)
            // Skrzynia to takie samo pytanie jak potworek, tylko nagroda jest inna.
            note(crate.entryId, crate.direction, Outcome.POPRAWNA)
            return SubmitResult.CrateTaken(crate.amount)
        }

        // ODPOWIEDZ BLEDNA - o ile w ogole wiadomo, czego dotyczyla (patrz [missTarget]).
        missTarget(answer)?.let { note(it.entryId, it.direction, Outcome.BLEDNA) }
        streak = 0
        setFlash(FlashKind.MISS)
        return SubmitResult.Miss
    }

    // ---- nauka -------------------------------------------------------------

    private val learning = mutableListOf<LearningEvent>()
    private var learningNr = 0

    /**
     * Oddaje i CZYSCI zdarzenia nauki uzbierane od poprzedniego wywolania.
     *
     * Odbieranie, a nie oddzwanianie, bo silnik ma zostac czysta symulacja: warstwa zapisu
     * sama decyduje, kiedy dotknac dysku, i nie robi tego w srodku klatki. Kolejka jest
     * oprozniana, wiec ta sama proba nie ma jak wyjsc dwa razy.
     */
    fun takeLearningEvents(): List<LearningEvent> {
        if (learning.isEmpty()) return emptyList()
        val out = learning.toList()
        learning.clear()
        return out
    }

    private fun note(entryId: String, direction: Direction, outcome: Outcome) {
        // Bez identyfikatora nie ma czego zapisac: Srs.key rzuca wyjatkiem, a zgadywanie
        // wlasciciela postepu z tresci hasla to dokladnie ten blad, ktory naprawia Entry.id.
        if (entryId.isBlank()) return
        learning.add(LearningEvent(entryId, direction, outcome, ++learningNr))
    }

    /**
     * Do ktorego slowa NA PEWNO mierzyla chybiona odpowiedz - albo null, gdy tego nie wiadomo.
     *
     * W tym trybie uczen nie odpowiada na jedno pytanie: na planszy stoi kilka hasel naraz
     * i wolno napisac odpowiedz na dowolne z nich. Samo chybienie nie mowi wiec, ktore slowo
     * uczen probowal przetlumaczyc, a pomylka dopisana do niewlasciwego slowa cofa jego
     * pudelko i psuje harmonogram komus, kto to slowo umial. Zapisujemy ja tylko wtedy, gdy
     * wpis jest BLISKI dokladnie jednemu haslu z planszy - czyli widac literowke albo
     * przekrecona koncowke, a nie zgadywanie na chybil trafil. W kazdym innym przypadku
     * milczymy: brak danych jest uczciwszy niz dane o niewlasciwym slowie.
     *
     * Droga glosowa nie trafia tu nigdy: VoiceMatch przepuszcza tylko odpowiedzi, ktore juz
     * leza na planszy, wiec przekrecona transkrypcja konczy sie cisza, a nie pomylka.
     */
    private fun missTarget(typed: String): Playable? {
        val t = Matching.normalize(typed)
        if (t.isEmpty()) return null
        val plansza = (
            monsters.map { Playable(it.entryId, it.direction, it.prompt, it.answers) } +
                crates.map { Playable(it.entryId, it.direction, it.prompt, it.answers) }
            )
            .filter { it.entryId.isNotBlank() }
            .distinctBy { it.entryId }
        val bliskie = plansza.filter { c -> c.answers.any { VoiceMatch.sim(t, it) >= MISS_SIMILARITY } }
        return bliskie.singleOrNull()
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
                // POTWOREK, KTORY DOSZEDL DO DZIALA, nie dowodzi, ze uczen nie zna hasla:
                // mogl pisac inne slowo, moglo zabraknac sekundy, mogl walczyc z klawiatura.
                // Zapisujemy POKAZANA - jedyny wynik rdzenia, ktory znaczy "tego dnia uczen
                // potrzebowal przy tym slowie pomocy": nie liczy pomylki, nie cofa pudelka,
                // nie przesuwa terminu, ale blokuje awans tego dnia, wiec slowo wraca do
                // spokojnego sprawdzenia zamiast awansowac z rozpedu.
                // Gdy pocisk juz leci (incoming > 0), uczen odpowiedzial i ma to zapisane
                // jako POPRAWNA - druga notatka o tym samym slowie byla by falszem.
                if (m.incoming == 0) note(m.entryId, m.direction, Outcome.POKAZANA)
                monsters.remove(m)
                projectiles.removeAll { it.targetId == m.id }
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

    /**
     * Wybiera wolny TOR (patrz [LANES]) — taki, ktorego nie zajmuje zaden obecny potwor ani
     * skrzynia. Dzieki temu etykiety nigdy nie laduja na tym samym pasie. Gdy (awaryjnie) nie
     * ma wolnego, bierze tor najdalszy od zajetych, zeby i tak maksymalnie je rozsunac.
     */
    private fun freeLaneY(): Float {
        val taken = monsters.map { it.y } + crates.map { it.y }
        val free = LANES.filter { lane -> taken.none { kotlin.math.abs(it - lane) < 0.09f } }
        if (free.isNotEmpty()) return free[random.nextInt(free.size)]
        return LANES.maxByOrNull { lane -> taken.minOfOrNull { kotlin.math.abs(it - lane) } ?: 1f }!!
    }

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
        val cel = nextTarget() ?: return
        val fromLeft = random.nextBoolean()
        monsters.add(
            Monster(
                id = nextId++,
                prompt = cel.prompt,
                answers = cel.answers,
                x = if (fromLeft) -0.04f else 1.04f,
                y = freeLaneY(),
                speed = monsterSpeed() * (0.9f + random.nextFloat() * 0.25f),
                armored = random.nextFloat() < armorChance(),
                variant = random.nextInt(4),
                spawnLevel = level,
                entryId = cel.entryId,
                direction = cel.direction,
            )
        )
    }

    private fun maybeSpawnCrate(dt: Float) {
        if (!config.autoSpawn || level < 2) return
        crateTimer -= dt
        if (crateTimer > 0f) return
        crateTimer = CRATE_INTERVAL
        if (crates.size >= 2) return
        // Skrzynia niesie zwykle slowo z zestawu, wiec bierze je z tej samej kolejki co
        // potworki - amunicja jest nagroda za odpowiedz, a nie osobnym zyciem slownika.
        val cel = nextTarget() ?: return
        crates.add(
            Crate(
                id = nextId++,
                prompt = cel.prompt,
                answers = cel.answers,
                x = 0.12f + random.nextFloat() * 0.76f,
                y = freeLaneY(),
                ttl = CRATE_TTL,
                amount = CRATE_AMOUNT,
                entryId = cel.entryId,
                direction = cel.direction,
            )
        )
    }

    private fun setFlash(kind: FlashKind) {
        flash = kind
        flashTtl = 0.35f
    }

    private fun distanceToCannon(x: Float, y: Float): Float = hypot(CANNON_X - x, CANNON_Y - y)

    /**
     * Test and debug helper: drop a monster on the field with a known word.
     *
     * Bez [entryId] potworek nie nalezy do zadnego wpisu i gra nie notuje o nim niczego -
     * dokladnie tego chca starsze testy, ktore stawiaja plansze recznie.
     */
    fun debugAddMonster(
        prompt: String,
        definition: String,
        x: Float,
        y: Float,
        armored: Boolean = false,
        entryId: String = "",
        direction: Direction = Direction.TERM_TO_DEF,
    ): Monster {
        val m = Monster(
            id = nextId++,
            prompt = prompt,
            answers = Matching.acceptedAnswers(definition),
            x = x, y = y,
            speed = monsterSpeed(),
            armored = armored,
            variant = 0,
            entryId = entryId,
            direction = direction,
        )
        monsters.add(m)
        return m
    }

    fun debugAddCrate(
        prompt: String,
        definition: String,
        amount: Int = CRATE_AMOUNT,
        entryId: String = "",
        direction: Direction = Direction.TERM_TO_DEF,
    ): Crate {
        val c = Crate(
            id = nextId++,
            prompt = prompt,
            answers = Matching.acceptedAnswers(definition),
            x = 0.3f, y = 0.3f, ttl = CRATE_TTL, amount = amount,
            entryId = entryId,
            direction = direction,
        )
        crates.add(c)
        return c
    }
}
