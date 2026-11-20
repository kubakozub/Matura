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
    /** Difficulty level at spawn time: picks the sprite, so it must not drift mid-life. */
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
    /**
     * Druga strona wiersza, z ktorego wzieto haslo, zapisana tak, jak stoi w zestawie:
     * z ogonkami, lacznikami i wariantami po przecinku, a nie w postaci znormalizowanej
     * z [answers]. To ja uczen ma przeczytac, gdy potworek dojdzie do dziala.
     *
     * Jedzie z potworkiem, zamiast byc szukana po [prompt], bo haslo nie wskazuje wpisu:
     * "uprzejmy" to w zestawach i kind, i polite, wiec wyszukanie po tekscie pokazaloby
     * tlumaczenie z cudzego wiersza. Pusta tylko na planszy testowej bez definicji.
     */
    val solution: String = "",
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
    /** Jak w [Monster]: druga strona wiersza w pisowni zestawu. */
    val solution: String = "",
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
    /**
     * Runda sie skonczyla. Kiedys znaczylo to zawsze utrate ostatniego zycia; odkad runda
     * skonczona zamyka sie tez sama, o powodzie mowi [completed]. Patrz [GameEngine.gameOver].
     */
    val gameOver: Boolean,
    val elapsed: Float,
    val flash: FlashKind?,
    /** Seconds left on the "new level" banner; zero when it should not be shown. */
    val levelUpBanner: Float,
    /** Slowa, ktore doszly do dziala, od pierwszego przepuszczonego: lista do powtorki po rundzie. */
    val missedWords: List<MissedWord> = emptyList(),
    /** Odpowiedz do pokazania na planszy; null, gdy nie ma czego pokazywac. */
    val reveal: Reveal? = null,
    /** Runda zamknela sie sama, bo kazde slowo dostalo poprawna odpowiedz ([GameConfig.finite]). */
    val completed: Boolean = false,
    /** Runda spokojna ([GameConfig.calm]): zyc nie ubywa, wiec ekran nie ma po co ich rysowac. */
    val calm: Boolean = false,
    /**
     * Postep rundy skonczonej: ile slow ma juz poprawna odpowiedz i ile ich jest razem.
     * Oba zera, gdy runda nie jest skonczona, wiec ekran nie musi pytac o tryb osobno.
     */
    val targetsDone: Int = 0,
    val targetsTotal: Int = 0,
)

/**
 * Slowo, ktore doszlo do dziala bez odpowiedzi, razem z tlumaczeniem, na ktore gra czekala.
 *
 * Po to, zeby runda konczyla sie lista do powtorki, a nie samym wynikiem: uczen ma zobaczyc,
 * CO przepuscil i jak to brzmi poprawnie. [solution] jest w pisowni zestawu (patrz
 * [Monster.solution]), a [times] liczy, ile razy to samo slowo przeszlo w tej rundzie.
 * [entryId] i [direction] wskazuja wpis tak samo jak w [LearningEvent]; pusty identyfikator
 * ma plansza testowa i wpis sprzed wersjonowania.
 */
data class MissedWord(
    val prompt: String,
    val solution: String,
    val entryId: String,
    val direction: Direction,
    val times: Int,
)

/**
 * Odpowiedz pokazywana na planszy tuz po tym, jak potworek doszedl do dziala.
 *
 * Bez niej tryb klasyczny niczego nie uczyl przy pierwszym kontakcie: nieznane slowo zabieralo
 * zycie i znikalo, a jego tlumaczenia nie bylo widac nigdzie, wiec zostawalo nieznane.
 * [ttl] to sekundy do zgaszenia napisu. Odlicza je [GameEngine.tick], wiec pauza zatrzymuje
 * ten zegar razem z plansza, a po koncu rundy napis zostaje taki, jaki byl w ostatniej klatce.
 *
 * Dopoki napis stoi, jego slowo nie wychodzi ponownie na plansze: odpowiedz jest do
 * przeczytania, a nie do przepisania.
 */
data class Reveal(
    val prompt: String,
    val solution: String,
    val ttl: Float,
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
     * PUSTA MAPA znaczy "gra o rekord": o kolejnosci slow decyduje tasowanie (worek w
     * [GameEngine]), a nie harmonogram. Mapa niepusta znaczy "runda realizuje plan powtorek".
     * Rozroznienie jest widoczne dla ucznia (podpis na planszy), bo inaczej dobor slow
     * zmienialby sie sam z siebie i wygladalby na kaprys losowania.
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
    /**
     * RUNDA SPOKOJNA: potworek, ktory doszedl do dziala, nie zabiera zycia i nie konczy rundy.
     *
     * Dla rund, w ktorych uczen dopiero poznaje slowa (dzienna sesja, cwiczenie trudnych slow).
     * Kara za nieznajomosc hasla widzianego pierwszy raz uczy tylko unikania nowych hasel,
     * a "dziewiec zyc na zapas" i tak konczylo sie ekranem KONIEC GRY. Cala reszta zostaje:
     * seria sie zeruje, do harmonogramu idzie POKAZANA, slowo trafia na liste
     * [GameEngine.missedWords], a jego tlumaczenie na plansze ([Reveal]).
     *
     * Spokojna znaczy tez: bez pancerzy, bez skrzyn i bez rosnacego tempa. [GameEngine.level]
     * stoi wtedy na 1 i to z niego wynika cala reszta.
     */
    val calm: Boolean = false,
    /**
     * RUNDA SKONCZONA: zamyka sie sama, gdy kazde grywalne slowo zestawu dostalo w niej choc
     * jedna poprawna odpowiedz (patrz [GameEngine.completed]).
     *
     * Dzienna sesja i cwiczenie trudnych slow startowaly dotad zwykla runde bez konca: uczen
     * musial wyjsc z niej sam, nie wiedzac, czy przerobil juz wszystko. W rundzie skonczonej
     * na plansze wychodza tylko slowa, ktore jeszcze czekaja na odpowiedz; slowo, ktore doszlo
     * do dziala, czeka dalej i wraca.
     *
     * Niezalezna od [calm]: runda skonczona z zyciami dalej potrafi skonczyc sie wczesniej,
     * ich utrata.
     */
    val finite: Boolean = false,
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
        // potworek dochodzil do dzialka, zanim dalo sie dokonczyc pisanie:
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
         * Ile sekund plansza pokazuje poprawna odpowiedz po dojsciu potworka do dziala ([Reveal]).
         * Trzy sekundy to czas na przeczytanie pary slow bez odrywania sie od nastepnego potworka.
         */
        const val REVEAL_TIME = 3.0f

        /**
         * Najdluzszy wpis, jaki przyjmuje silnik. Odpowiedz dluzsza niz tyle znakow nie da sie
         * wpisac zadna klawiatura, wiec pyta o te liczbe takze ten, kto uklada runde, ktora
         * konczy sie dopiero odpowiedziami.
         */
        const val MAX_TYPED = 40

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

    /**
     * Runda sie skonczyla: silnik nie przyjmuje juz ani czasu, ani odpowiedzi.
     *
     * Nazwa zostala z czasow, gdy jedynym koncem byla utrata ostatniego zycia. Runda skonczona
     * ([GameConfig.finite]) zamyka sie takze sama i wtedy to pole TEZ jest prawda, zeby kazdy
     * straznik pytajacy o koniec rundy (klawiatura, mikrofon, zapis wyniku) dzialal bez zmian.
     * DLACZEGO runda sie skonczyla, mowi [completed].
     */
    var gameOver: Boolean = false
        private set

    /**
     * Runda skonczona zamknela sie sama, bo kazde slowo dostalo poprawna odpowiedz. Nigdy bez
     * [gameOver]; samo [gameOver] znaczy, ze skonczyly sie zycia.
     */
    var completed: Boolean = false
        private set
    var elapsed: Float = 0f
        private set

    /**
     * Poziom trudnosci. W rundzie spokojnej ([GameConfig.calm]) stoi na 1 przez cala gre.
     * To jedno miejsce wystarcza, bo wszystko, co ma w niej nie rosnac, liczy sie z poziomu:
     * predkosc potworkow, odstep fal, pancerze (od 3), skrzynie (od 2) i punkty za trafienie.
     */
    val level: Int get() = if (config.calm) 1 else 1 + kills / KILLS_PER_LEVEL

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
        /** Druga strona wiersza w pisowni zestawu, patrz [Monster.solution]. */
        val solution: String,
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
            val haslo = (if (wprzod) e.term else e.definition).trim()
            val rozwiazanie = (if (wprzod) e.definition else e.term).trim()
            // WSPOLNE HASLO, WSPOLNE ODPOWIEDZI. Napis nad potworkiem nie mowi, z ktorego wiersza
            // powstal: "reka" to w jednym zestawie i arm, i hand, wiec kazde poprawne tlumaczenie
            // tego hasla ma trafic, a nie tylko to z wlasnego wiersza. Bierzemy sume po wszystkich
            // wierszach zestawu o tym samym hasle (ta sama regula co w pozostalych trybach gry),
            // liczona dla kierunku TEGO wiersza. Proba i tak idzie do wpisu, z ktorego powstal
            // potworek. Wlasne odpowiedzi stoja pierwsze i sa dodane osobno: haslo z samych znakow
            // spoza liter i cyfr normalizuje sie do pustego, suma go nie widzi, a wiersz ma zostac
            // grywalny.
            val odpowiedzi = LinkedHashSet(Matching.acceptedAnswers(rozwiazanie))
            odpowiedzi.addAll(Matching.answersForPrompt(set.entries, haslo, wprzod))
            out.add(
                Playable(
                    entryId = e.id,
                    direction = kierunek,
                    prompt = haslo,
                    answers = odpowiedzi.toList(),
                    solution = rozwiazanie,
                )
            )
        }
        return out.filter { it.prompt.isNotBlank() && it.answers.isNotEmpty() }
    }

    val isPlayable: Boolean get() = playable.isNotEmpty()

    /**
     * Tozsamosc slowa w obrebie rundy: wpis i kierunek, czyli to samo, pod czym harmonogram
     * trzyma postep. Wpis bez identyfikatora (plansza testowa, zestaw sprzed wersjonowania)
     * rozpoznajemy po hasle razem z rozwiazaniem. Samo haslo nie wystarcza w zadnym z tych
     * przypadkow: "uprzejmy" to i kind, i polite, wiec klucz po tekscie hasla zlalby dwa wpisy
     * w jeden wiersz listy do powtorki i w jedno slowo do zaliczenia.
     */
    private fun kluczSlowa(entryId: String, direction: Direction, prompt: String, solution: String): String =
        if (entryId.isNotBlank()) entryId + '\u001F' + direction.name
        else '\u0000' + prompt + '\u0000' + solution

    private fun klucz(c: Playable) = kluczSlowa(c.entryId, c.direction, c.prompt, c.solution)
    private fun klucz(m: Monster) = kluczSlowa(m.entryId, m.direction, m.prompt, m.solution)
    private fun klucz(c: Crate) = kluczSlowa(c.entryId, c.direction, c.prompt, c.solution)

    // ---- runda skonczona ---------------------------------------------------

    /**
     * Slowa, ktore w rundzie skonczonej ([GameConfig.finite]) czekaja jeszcze na pierwsza
     * poprawna odpowiedz, jako klucze z [kluczSlowa]. W kazdej innej rundzie zbior jest pusty.
     *
     * Slowo schodzi stad w chwili ODPOWIEDZI, tak samo jak zapis POPRAWNA: o wiedzy rozstrzyga
     * moment, w ktorym poprawne haslo trafilo na plansze, a nie lot pocisku. Potworek, ktory
     * doszedl do dziala bez odpowiedzi, niczego stad nie zdejmuje, wiec jego slowo wraca.
     */
    private val pending: MutableSet<String> = LinkedHashSet<String>().apply {
        if (config.finite) playable.forEach { add(klucz(it)) }
    }

    /** Ile slow ma do zaliczenia runda skonczona; zero w kazdej innej. */
    val targetsTotal: Int = pending.size

    /** Ile z nich dostalo juz poprawna odpowiedz. */
    val targetsDone: Int get() = targetsTotal - pending.size

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
            when {
                // NOWE jest tylko slowo, o ktorym harmonogram nie wie nic. Pytanie o pudelko
                // (box == NO_BOX) to za malo: po pomylce na swiezym slowie Srs zostawia je
                // w pudelku 0, ale z policzona pomylka, dniem pomocy i terminem na dzis. Takie
                // slowo ma wrocic jako pilna zaleglosc, a liczone jako nowe stawalo w losowym
                // miejscu puli nowych i potrafilo wypasc poza ich limit, czyli nie wrocic
                // w tej rundzie wcale. Ta sama regula co w Sesja.zaplanuj.
                !maHistorie(c) -> nowe.add(c)
                Srs.isDue(progressOf(c), config.today) -> zalegle.add(c)
                else -> czekajace.add(c)
            }
        }
        return zalegle.sortedByDescending { potrzeba(progressOf(it)) } +
            nowe.take(config.newWordsPerRound) +
            czekajace.sortedBy { progressOf(it).dueDay } +
            nowe.drop(config.newWordsPerRound)
    }

    /** Czy harmonogram ma o tym slowie jakikolwiek zapis. Wpis bez identyfikatora nie ma go nigdy. */
    private fun maHistorie(cel: Playable): Boolean =
        cel.entryId.isNotBlank() && config.progress.containsKey(Srs.key(cel.entryId, cel.direction))

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
     *
     * Runda skonczona ([GameConfig.finite]) zmienia druga z nich: wychodza tylko slowa, ktore
     * czekaja jeszcze na odpowiedz, a gdy kazde takie stoi juz na planszy, nie wychodzi nic.
     * Powtorzenie "zeby plansza nie stala pusta" wystawialoby tu slowo juz zaliczone albo
     * drugi egzemplarz tego, nad ktorym uczen wlasnie mysli, i runda nie mialaby konca.
     */
    private fun nextTarget(): Playable? {
        if (playable.isEmpty()) return null
        val zajete = monsters.map { it.prompt }.toSet() + crates.map { it.prompt }.toSet()
        val kolejka = plan ?: return zWorka(zajete)
        for (k in kolejka.indices) {
            val i = (planIdx + k) % kolejka.size
            if (doWystawienia(kolejka[i], zajete)) {
                planIdx = (i + 1) % kolejka.size
                return kolejka[i]
            }
        }
        if (config.finite) return null
        // Poza plansza zostalo tylko slowo, ktorego odpowiedz wlasnie na niej stoi. Czekamy, az
        // zgasnie, zamiast dublowac inny napis: to slowo zaraz bedzie mialo co wystawic.
        if (kolejka.any { it.prompt !in zajete }) return null
        // Cala pula stoi juz na planszy (pula mniejsza niz plansza). Powtarzamy biezace slowo
        // zamiast nie wystawic nic - tak samo, jak robil to dobor losowy.
        val cel = kolejka[planIdx]
        planIdx = (planIdx + 1) % kolejka.size
        return cel
    }

    /**
     * Czy slowo moze teraz wyjsc: jego napis nie stoi na planszy, w rundzie skonczonej jeszcze
     * czeka na odpowiedz, a plansza nie pokazuje wlasnie jego tlumaczenia.
     *
     * Ostatni warunek pilnuje, zeby odpowiedz byla nauka, a nie sciaga. Fala wychodzi co 3,8 s,
     * a odpowiedz stoi 3 s ([REVEAL_TIME]), wiec slowo, ktore jako jedyne moglo wyjsc (koniec
     * kazdej rundy skonczonej, pierwsza sesja z piecioma slowami), wracalo na plansze pod
     * wlasnym tlumaczeniem i uczen je po prostu przepisywal.
     */
    private fun doWystawienia(cel: Playable, zajete: Set<String>): Boolean {
        if (cel.prompt in zajete) return false
        val klucz = klucz(cel)
        return klucz != odkrywane && (!config.finite || klucz in pending)
    }

    // ---- gra o rekord: worek -----------------------------------------------

    /**
     * Kolejka rundy BEZ planu: przetasowany zestaw, z ktorego slowa wychodza bez zwracania.
     *
     * Dobor jednostajny ze zwracaniem potrafil wystawic jedno haslo trzy razy, zanim inne
     * wyszlo raz. A gra o rekord to pierwsza runda kazdego nowego gracza (nie ma jeszcze
     * historii, wiec nie ma i planu), czyli ta, w ktorej kazde slowo powinno dostac swoja
     * szanse. Worek daje kazdemu slowu jedno wyjscie na obieg i tasuje sie na nowo dopiero
     * po wyczerpaniu: slowa dalej wracaja w trakcie rozgrywki, tylko rowno, po kolei.
     */
    private val worek = ArrayList<Playable>()

    /** Haslo wystawione ostatnio w rundzie bez planu. Nowy worek nie moze sie od niego zaczac. */
    private var ostatnieHaslo: String? = null

    /** Z czego tasuje sie worek: caly zestaw, a w rundzie skonczonej tylko slowa, ktore czekaja. */
    private fun pulaWorka(): List<Playable> =
        if (config.finite) playable.filter { klucz(it) in pending } else playable

    private fun zWorka(zajete: Set<String>): Playable? {
        // W rundzie skonczonej slowo zaliczone juz nie wraca, wiec nie zalega tez w worku.
        if (config.finite) worek.removeAll { klucz(it) !in pending }
        // Wychodzi pierwszy wiersz worka, ktory wyjsc MOZE. Te, ktore teraz nie moga, zostaja
        // na swoim miejscu i czekaja: dotyczy to zwlaszcza wiersza o tym samym hasle co potworek
        // na planszy ("reka" jako arm i jako hand). Nie stanie obok niego, ale gdy tylko haslo
        // sie zwolni, jest pierwszy w kolejce, a nie na jej koncu.
        var i = worek.indexOfFirst { doWystawienia(it, zajete) }
        if (i < 0) {
            // Worek sie WYCZERPAL: jest pusty albo zostaly w nim same wiersze, ktore wyjsc nie
            // moga. Nie czekamy na nie z pusta plansza, nowy obieg zaczyna sie od razu.
            val pula = pulaWorka()
            if (pula.none { doWystawienia(it, zajete) }) {
                // Nie ma czego wystawic. Runda skonczona wtedy czeka. Runda bez konca czeka
                // tylko na slowo, ktorego odpowiedz stoi wlasnie na planszy (zaraz zgasnie),
                // a gdy na planszy stoi juz wszystko, godzi sie na drugi taki sam napis, bo
                // zestaw mniejszy od planszy musi dalej wystawiac cele.
                if (config.finite || pula.any { it.prompt !in zajete }) return null
                return pula[random.nextInt(pula.size)].also { ostatnieHaslo = it.prompt }
            }
            nowyWorek(pula, zajete)
            i = worek.indexOfFirst { doWystawienia(it, zajete) }
        }
        val cel = worek.removeAt(i)
        ostatnieHaslo = cel.prompt
        return cel
    }

    /** Tasuje [pula] w nowy worek. Wolajacy sprawdzil, ze jest w niej slowo, ktore moze wyjsc. */
    private fun nowyWorek(pula: List<Playable>, zajete: Set<String>) {
        // ZALEGLE Z POPRZEDNIEGO OBIEGU: wiersze, ktore nie wyszly, bo przez caly czas stal na
        // planszy ich blizniak o tym samym hasle. Obieg im sie nalezy, wiec przechodza na czolo
        // nowego worka i wyjda, gdy tylko haslo sie zwolni. Inaczej z pary arm i hand ("reka")
        // przy wolniejszej grze wychodzil w kazdym obiegu tylko jeden wiersz, a potrafil to byc
        // dwa razy z rzedu ten sam. Wiersz, ktorego WLASNY potworek stoi na planszy, zalegly nie
        // jest: swoje wyjscie wlasnie ma.
        val naPlanszy = HashSet<String>()
        monsters.forEach { naPlanszy.add(klucz(it)) }
        crates.forEach { naPlanszy.add(klucz(it)) }
        val zalegle = worek
            .filter { it.prompt in zajete && klucz(it) !in naPlanszy }
            .distinctBy { klucz(it) }
        // Slowa, ktore teraz wyjsc nie moga (stoja na planszy albo plansza pokazuje wlasnie ich
        // odpowiedz), ida na koniec nowego worka. Nowy obieg zaczyna sie zwykle wtedy, gdy
        // ostatnie potworki poprzedniego jeszcze ida; gdyby ich slowa lezaly w losowym miejscu
        // worka, wracalyby na plansze w chwili, w ktorej uczen dopiero co je widzial. Tak samo
        // dziala kolejka planu: slowo pominiete czeka tam na nastepny obieg.
        val (wolne, czekajace) = pula.shuffled(random).partition { doWystawienia(it, zajete) }
        val swiezy = wolne.toMutableList()
        // Nowy worek nie zaczyna sie haslem, ktorym skonczyl sie poprzedni: na styku dwoch
        // obiegow to samo slowo wyszloby dwa razy pod rzad.
        if (swiezy[0].prompt == ostatnieHaslo) {
            val inne = swiezy.indices.filter { swiezy[it].prompt != ostatnieHaslo }
            if (inne.isNotEmpty()) {
                val drugie = inne[random.nextInt(inne.size)]
                val glowa = swiezy[0]
                swiezy[0] = swiezy[drugie]
                swiezy[drugie] = glowa
            }
        }
        worek.clear()
        worek.addAll(zalegle)
        worek.addAll(swiezy)
        worek.addAll(czekajace)
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
        reveal = reveal,
        completed = completed,
        calm = config.calm,
        targetsDone = targetsDone,
        targetsTotal = targetsTotal,
    )

    // ---- input -------------------------------------------------------------

    fun type(ch: Char) {
        if (gameOver) return
        if (typed.length >= MAX_TYPED) return
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
        typed = value.take(MAX_TYPED)
        cursor = typed.length
    }

    /** Monsters whose answer starts with what is currently typed: these get highlighted. */
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
            pending.remove(klucz(target))
            return SubmitResult.Fired(target.id, heavy)
        }

        val crate = crates.firstOrNull { Matching.isExactMatch(answer, it.answers) }
        if (crate != null) {
            crates.remove(crate)
            heavyAmmo += crate.amount
            setFlash(FlashKind.CRATE)
            // Skrzynia to takie samo pytanie jak potworek, tylko nagroda jest inna.
            note(crate.entryId, crate.direction, Outcome.POPRAWNA)
            pending.remove(klucz(crate))
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
            monsters.map { Playable(it.entryId, it.direction, it.prompt, it.answers, it.solution) } +
                crates.map { Playable(it.entryId, it.direction, it.prompt, it.answers, it.solution) }
            )
            .filter { it.entryId.isNotBlank() }
            .distinctBy { it.entryId }
        val bliskie = plansza.filter { c -> c.answers.any { VoiceMatch.sim(t, it) >= MISS_SIMILARITY } }
        return bliskie.singleOrNull()
    }

    // ---- slowa przepuszczone -----------------------------------------------

    // Kolejnosc wstawiania to kolejnosc, w jakiej uczen przepuscil slowa po raz pierwszy;
    // powtorne przepuszczenie podmienia wartosc pod tym samym kluczem i miejsca nie zmienia.
    private val missed = LinkedHashMap<String, MissedWord>()

    /**
     * Slowa, ktore doszly do dziala bez odpowiedzi, od pierwszego przepuszczonego. To samo slowo
     * przepuszczone ponownie podbija [MissedWord.times], zamiast dopisywac kolejny wiersz.
     *
     * Lista jest skladana na nowo tylko przy przepuszczeniu, a nie przy kazdym odczycie: migawka
     * bierze ja co klatke, a zmienia sie kilka razy na runde.
     */
    var missedWords: List<MissedWord> = emptyList()
        private set

    /** Odpowiedz pokazywana teraz na planszy; nowsza zastepuje starsza. Wychodzi w migawce. */
    private var reveal: Reveal? = null

    /**
     * Slowo, ktorego odpowiedz stoi teraz na planszy, jako klucz z [kluczSlowa]; null razem
     * z [reveal]. Dopoki napis nie zgasnie, to slowo nie wychodzi ponownie, patrz [doWystawienia].
     */
    private var odkrywane: String? = null

    /**
     * Potworek doszedl do dziala, zanim uczen na niego odpowiedzial: slowo idzie na liste do
     * powtorki, a jego tlumaczenie na plansze.
     */
    private fun przepuszczone(m: Monster) {
        // Bez rozwiazania nie ma czego pokazac ani powtarzac (plansza testowa z pusta definicja).
        if (m.solution.isBlank()) return
        val klucz = klucz(m)
        val bylo = missed[klucz]
        missed[klucz] = bylo?.copy(times = bylo.times + 1)
            ?: MissedWord(m.prompt, m.solution, m.entryId, m.direction, 1)
        missedWords = missed.values.toList()
        reveal = Reveal(m.prompt, m.solution, REVEAL_TIME)
        odkrywane = klucz
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
        // Odpowiedz gasnie PRZED ruchem potworkow, tak samo jak blysk: napis ustawiony w tej
        // klatce ma przed soba pelne REVEAL_TIME, a nie czas pomniejszony o klatke.
        reveal = reveal?.let { r ->
            val zostalo = r.ttl - dt
            if (zostalo > 0f) r.copy(ttl = zostalo) else null
        }
        if (reveal == null) odkrywane = null

        moveProjectiles(dt)
        moveMonsters(dt)
        ageCrates(dt)
        maybeSpawnMonster(dt)
        maybeSpawnCrate(dt)
        maybeComplete()
    }

    /**
     * Runda skonczona zamyka sie, gdy nie czeka juz zadne slowo, a z planszy zeszly potworki
     * i pociski.
     *
     * Na pusta plansze czekamy, bo ostatnia odpowiedz to jeszcze nie koniec: pocisk ma doleciec,
     * a trafienie, punkty i seria naleza do tej rundy. Zycia maja pierwszenstwo: jesli ostatnie
     * przepadlo w tej samej klatce, runda konczy sie ich utrata (zostaje samo [gameOver]).
     * Zestaw bez ani jednego grywalnego slowa niczego nie konczy, bo nic sie w nim nie odbylo.
     */
    private fun maybeComplete() {
        if (!config.finite || gameOver || targetsTotal == 0) return
        if (pending.isNotEmpty() || monsters.isNotEmpty() || projectiles.isNotEmpty()) return
        completed = true
        gameOver = true
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
                // Z tego samego powodu takie slowo nie jest ani pozycja na liscie do powtorki,
                // ani odpowiedzia do pokazania: uczen wlasnie ja napisal.
                if (m.incoming == 0) {
                    note(m.entryId, m.direction, Outcome.POKAZANA)
                    przepuszczone(m)
                }
                monsters.remove(m)
                projectiles.removeAll { it.targetId == m.id }
            }
            streak = 0
            // Blysk zostaje takze w rundzie spokojnej: to jedyny sygnal dla ekranu (dzwiek,
            // drgniecie planszy), ze potworek doszedl do dziala i ze jest odpowiedz do przeczytania.
            setFlash(FlashKind.LIFE_LOST)
            // RUNDA SPOKOJNA nie odbiera zyc, wiec nie ma tez jak skonczyc sie przegrana.
            if (!config.calm) {
                lives -= reached.size
                if (lives <= 0) {
                    lives = 0
                    gameOver = true
                }
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
        val przed = monsters.size
        spawnMonster()
        // Nic nie wyszlo, bo jedyne slowo, ktore moglo, czeka na zgaszenie swojej odpowiedzi.
        // Fala nie przepada wtedy na caly odstep: rusza w klatce, w ktorej napis gasnie, wiec
        // slowo wraca najpozniej po tym samym czasie co dotad, tylko juz bez sciagi nad glowa.
        val r = reveal
        if (r != null && monsters.size == przed && czekaNaZgaszenie()) spawnTimer = min(spawnTimer, r.ttl)
    }

    /** Czy slowo z pokazywana odpowiedzia wyszloby teraz na plansze, gdyby nie ona. */
    private fun czekaNaZgaszenie(): Boolean {
        val klucz = odkrywane ?: return false
        if (config.finite && klucz !in pending) return false
        val zajete = monsters.map { it.prompt }.toSet() + crates.map { it.prompt }.toSet()
        return playable.any { klucz(it) == klucz && it.prompt !in zajete }
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
                solution = cel.solution,
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
                solution = cel.solution,
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
            solution = definition.trim(),
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
            solution = definition.trim(),
        )
        crates.add(c)
        return c
    }
}
