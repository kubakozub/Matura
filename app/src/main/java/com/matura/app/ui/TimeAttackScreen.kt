package com.matura.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.R
import com.matura.app.game.FlashKind
import com.matura.app.game.Matching
import com.matura.app.learn.Attempt
import com.matura.app.learn.Outcome
import com.matura.app.model.Direction
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.ScoreRecord
import com.matura.app.model.Speed
import com.matura.app.model.TaskKind
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordSet
import kotlin.math.ceil
import kotlin.random.Random

/**
 * TRYB "NA CZAS" (TIME ATTACK).
 *
 * Jeden wielki zegar na całą rozgrywkę — życia z [Options] nie obowiązują, walutą jest czas.
 * Gracz wpisuje tłumaczenie ekranową klawiaturą [GameKeyboard] i zatwierdza enterem:
 *   - trafienie  -> +10 x mnożnik serii, zielony błysk, BONUS +2 s, nowe hasło,
 *   - pomyłka    -> czerwony błysk, seria wyzerowana, KARA -3 s,
 *   - POMIŃ      -> KARA -5 s (i zerwana seria, bo łańcuch poprawnych odpowiedzi się urywa).
 *
 * Gra kończy się dokładnie wtedy, gdy zegar dobije do zera.
 *
 * @param onAttempt wynik JEDNEGO hasla dla harmonogramu powtorek (patrz [probaWpisania]).
 *   Ekran nie zna repozytorium i nie zaklada wlasnego — druga instancja
 *   [com.matura.app.data.Repository] ma wlasny bufor, wiec zapis wyniku gry nadpisalby postep
 *   z calej rozgrywki. Wolajacy podaje `repo.recordAttempt(proba, System.currentTimeMillis())`;
 *   domyslne nic-nie-rob zostawia tryb takim, jaki byl.
 */
@Composable
fun TimeAttackScreen(
    set: WordSet,
    options: Options,
    str: Str,
    playerName: String,
    onExit: () -> Unit,
    onFinished: (ScoreRecord) -> Unit,
    /**
     * BEZ WARTOSCI DOMYSLNEJ — celowo. Gdy ten parametr mial domyslne nic-nie-rob, App.kt
     * po prostu go nie podawal: kompilacja i testy byly zielone, a postep nauki nie zapisywal
     * sie w ogole. Wymagany parametr sprawia, ze kazde nowe wywolanie ekranu musi rozstrzygnac,
     * co zrobic z proba — a zapomnienie konczy sie bledem kompilacji, nie cicha strata danych.
     */
    onAttempt: (Attempt) -> Unit,
) {
    val lang = options.uiLanguage

    // Sprzętowy/gestowy „wstecz" wychodzi z trybu (bez zapisu wyniku) — spójnie z ikoną wyjścia.
    BackHandler { onExit() }

    // Licznik rund: każde "JESZCZE RAZ" podbija go o 1, a że wszystkie stany gry są na nim
    // kluczowane przez remember(round), restart czyści planszę bez ręcznego zerowania pól.
    var round by remember { mutableIntStateOf(0) }

    // Świeże ziarno na każdą rundę — deterministyczny set.id.hashCode()+round dawał
    // dokładnie te same pytania w każdej rozgrywce. System.nanoTime() losuje pulę od nowa.
    val seed = remember(round) { System.nanoTime().toInt() }

    // Pula pytań jest tasowana raz na rundę; kierunek TERM_TO_DEF / DEF_TO_TERM / RANDOM
    // rozstrzygamy w momencie budowania puli, tak samo jak robi to silnik trybu klasycznego.
    val questions = remember(set.id, options.direction, round, seed) {
        buildQuestions(set, options.direction, seed = seed)
    }

    // --- zestaw bez użytecznych haseł: elegancki komunikat zamiast wywrotki ---------------
    if (questions.isEmpty()) {
        EmptySetNotice(str = str, lang = lang, onExit = onExit)
        return
    }

    val totalTime = timeBudgetFor(options.speed)

    var qIndex by remember(round) { mutableIntStateOf(0) }
    var typed by remember(round) { mutableStateOf("") }
    var cursor by remember(round) { mutableIntStateOf(0) }
    var score by remember(round) { mutableIntStateOf(0) }
    var streak by remember(round) { mutableIntStateOf(0) }
    var bestStreak by remember(round) { mutableIntStateOf(0) }
    var correct by remember(round) { mutableIntStateOf(0) }
    var skipped by remember(round) { mutableIntStateOf(0) }
    var timeLeft by remember(round) { mutableFloatStateOf(totalTime) }
    var finished by remember(round) { mutableStateOf(false) }
    var reported by remember(round) { mutableStateOf(false) }

    // Krótkotrwałe sprzężenie zwrotne: barwa błysku, etykieta zmiany czasu ("+2s"),
    // oraz podpowiedź z poprawną odpowiedzią po pomyłce/pominięciu. Każde ma własne ttl.
    var flashColor by remember(round) { mutableStateOf<Color?>(null) }
    var flashTtl by remember(round) { mutableFloatStateOf(0f) }
    var delta by remember(round) { mutableStateOf("") }
    var deltaColor by remember(round) { mutableStateOf(Palette.Accent) }
    var deltaTtl by remember(round) { mutableFloatStateOf(0f) }
    var hint by remember(round) { mutableStateOf("") }
    var hintTtl by remember(round) { mutableFloatStateOf(0f) }
    // Licznik pomyłek na BIEŻĄCYM haśle — pełną odpowiedź pokazujemy dopiero po 2. błędzie.
    var wrongCount by remember(round) { mutableIntStateOf(0) }

    val sfx = remember { Sfx() }
    DisposableEffect(Unit) { onDispose { sfx.release() } }

    val question = questions[qIndex % questions.size]

    // --- zegar: jeden pomiar na klatkę, zatrzymany po końcu gry ----------------------------
    LaunchedEffect(round, finished) {
        if (finished) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // dt przycięte do 50 ms — po powrocie z tła gracz nie traci całej rundy.
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                    timeLeft -= dt
                    if (flashTtl > 0f) flashTtl -= dt
                    if (deltaTtl > 0f) deltaTtl -= dt
                    if (hintTtl > 0f) hintTtl -= dt
                    if (timeLeft <= 0f) {
                        timeLeft = 0f
                        // Haslo, na ktorym zastal ucznia koniec czasu, NIE trafia do
                        // harmonogramu: uczen nie zdazyl odpowiedziec, a wyczerpanie budzetu
                        // calej gry nie mowi nic o tym jednym slowie.
                        finished = true
                    }
                }
                last = now
            }
        }
    }

    // onFinished dokładnie raz na rundę — strażnikiem jest `reported` kluczowane na round.
    LaunchedEffect(round, finished) {
        if (finished && !reported) {
            reported = true
            onFinished(
                ScoreRecord(
                    playerName = playerName,
                    setTitle = set.title,
                    score = score,
                    level = 1 + correct / 10,
                    bestStreak = bestStreak,
                    kills = correct,
                    playedAt = System.currentTimeMillis(),
                    // Jawny tryb — spójnie z pozostałymi trybami zapisującymi wynik.
                    mode = GameMode.TIME_ATTACK,
                )
            )
        }
    }

    /** Przechodzi do kolejnego hasła i czyści pole wpisywania oraz podpowiedź. */
    fun nextQuestion() {
        qIndex = (qIndex + 1) % questions.size
        typed = ""
        cursor = 0
        // Podpowiedź i licznik pomyłek dotyczą poprzedniego hasła — zerujemy je przy zmianie.
        hint = ""
        hintTtl = 0f
        wrongCount = 0
    }

    fun adjustTime(seconds: Float, label: String, color: Color) {
        // Sufit czasu = totalTime: bonusy nie mogą nabić zegara ponad budżet rundy.
        timeLeft = (timeLeft + seconds).coerceIn(0f, totalTime)
        delta = label
        deltaColor = color
        deltaTtl = 1.1f
    }

    /** Zapis jednej proby do harmonogramu; osobno od punktacji, bo to dwie rozne rachuby. */
    fun zapiszProbe(q: TaQuestion, wynik: TaWynik) {
        probaWpisania(q.entryId, q.termToDef, wynik, System.currentTimeMillis())?.let(onAttempt)
    }

    fun submit() {
        if (finished || typed.isBlank()) return
        if (Matching.isExactMatch(typed, question.answers)) {
            zapiszProbe(question, TaWynik.TRAFIONE)
            streak += 1
            if (streak > bestStreak) bestStreak = streak
            correct += 1
            score += POINTS_PER_HIT * multiplierFor(streak)
            flashColor = Palette.Accent
            flashTtl = 0.22f
            sfx.play(FlashKind.HIT, options.soundEnabled)
            // Jednoelementowy zestaw dostawałby bonus w kółko za to samo hasło (perpetuum mobile) —
            // przy jednym pytaniu nie doładowujemy czasu.
            if (questions.size > 1) {
                adjustTime(BONUS_HIT, "+${BONUS_HIT.toInt()}s", Palette.Accent)
            }
            nextQuestion()
        } else {
            zapiszProbe(question, TaWynik.POMYLKA)
            streak = 0
            flashColor = Palette.Danger
            flashTtl = 0.22f
            sfx.play(FlashKind.MISS, options.soundEnabled)
            adjustTime(PENALTY_MISS, "${PENALTY_MISS.toInt()}s", Palette.Danger)
            // Pomyłka nie zabiera hasła — gracz może poprawić literówkę, kosztem tykającego zegara.
            // Pełną odpowiedź odsłaniamy dopiero po 2. błędzie na tym samym haśle, żeby tryb
            // nie zmienił się w przepisywanie; po 1. błędzie zostaje sam czerwony błysk.
            wrongCount += 1
            if (wrongCount >= 2) {
                hint = question.reveal
                hintTtl = 2.0f
            }
        }
    }

    fun skip() {
        if (finished) return
        zapiszProbe(question, TaWynik.POMINIETE)
        skipped += 1
        streak = 0
        flashColor = Palette.Gold
        flashTtl = 0.22f
        sfx.play(FlashKind.LIFE_LOST, options.soundEnabled)
        adjustTime(PENALTY_SKIP, "${PENALTY_SKIP.toInt()}s", Palette.Gold)
        // Zapamiętujemy poprawną odpowiedź PRZED zmianą hasła — nextQuestion() czyści hint,
        // więc podpowiedź ustawiamy dopiero PO nim, inaczej nigdy by się nie pokazała.
        val reveal = question.reveal
        nextQuestion()
        hint = reveal
        hintTtl = 2.0f
    }

    // --- ekran końcowy --------------------------------------------------------------------
    if (finished) {
        ResultScreen(
            lang = lang,
            str = str,
            score = score,
            correct = correct,
            skipped = skipped,
            bestStreak = bestStreak,
            level = 1 + correct / 10,
            onAgain = { round += 1 },
            onMenu = onExit,
        )
        return
    }

    // --- rozgrywka ------------------------------------------------------------------------
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
    ) {
        TimeAttackHud(
            timeLeft = timeLeft,
            totalTime = totalTime,
            score = score,
            streak = streak,
            delta = delta,
            deltaColor = deltaColor,
            deltaVisible = deltaTtl > 0f,
            lang = lang,
            onQuit = onExit,
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            PromptPanel(
                prompt = question.prompt,
                hint = if (hintTtl > 0f) hint else null,
                lang = lang,
            )
            // Błysk kładziemy na cały obszar hasła — czytelny sygnał, a nie ruszamy układu.
            val fc = flashColor
            if (fc != null && flashTtl > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(fc.copy(alpha = 0.22f * (flashTtl / 0.22f).coerceIn(0f, 1f)))
                )
            }
        }

        AnswerField(typed = typed, cursor = cursor)

        SkipBar(lang = lang, onSkip = { skip() })

        GameKeyboard(
            opisy = opisyKlawiszy(str),
            onChar = { c ->
                if (typed.length < MAX_INPUT) {
                    val safe = cursor.coerceIn(0, typed.length)
                    typed = typed.substring(0, safe) + c + typed.substring(safe)
                    cursor = safe + 1
                    sfx.key(options.soundEnabled)
                }
            },
            onBackspace = {
                val safe = cursor.coerceIn(0, typed.length)
                if (safe > 0) {
                    typed = typed.substring(0, safe - 1) + typed.substring(safe)
                    cursor = safe - 1
                }
            },
            onEnter = { submit() },
            onSpace = {
                // Wiodąca spacja i podwójne spacje tylko śmiecą w polu — blokujemy je od razu.
                val safe = cursor.coerceIn(0, typed.length)
                if (typed.length < MAX_INPUT && safe > 0 && typed.getOrNull(safe - 1) != ' ') {
                    typed = typed.substring(0, safe) + ' ' + typed.substring(safe)
                    cursor = safe + 1
                }
            },
            onLeft = { cursor = (cursor - 1).coerceAtLeast(0) },
            onRight = { cursor = (cursor + 1).coerceAtMost(typed.length) },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Stałe trybu
// ---------------------------------------------------------------------------------------

private const val POINTS_PER_HIT = 10
private const val BONUS_HIT = 2f
private const val PENALTY_MISS = -3f
private const val PENALTY_SKIP = -5f
private const val MAX_INPUT = 40

/** Budżet czasu na całą grę zależy wyłącznie od ustawienia tempa. */
private fun timeBudgetFor(tempo: Speed): Float = when (tempo) {
    Speed.SLOW -> 120f
    Speed.NORMAL -> 90f
    Speed.FAST -> 60f
}

/** Mnożnik serii — progi te same, co odznaki badge_x2 / x3 / x5. */
private fun multiplierFor(streak: Int): Int = when {
    streak >= 9 -> 5
    streak >= 6 -> 3
    streak >= 3 -> 2
    else -> 1
}

private fun badgeFor(multiplier: Int): Int? = when (multiplier) {
    5 -> R.drawable.badge_x5
    3 -> R.drawable.badge_x3
    2 -> R.drawable.badge_x2
    else -> null
}

/** Lokalne napisy tego ekranu — klasa [Str] jest wspólna, więc jej nie dotykamy. */
private fun t(pl: String, en: String, lang: UiLanguage) = if (lang == UiLanguage.PL) pl else en

private data class TaQuestion(
    /**
     * Trwaly identyfikator wpisu — postep nauki wisi na nim, nie na tresci hasla.
     * Pusty w zestawach sprzed wersjonowania (patrz [probaWpisania]).
     */
    val entryId: String,
    /** true = pokazujemy haslo, uczen wpisuje tlumaczenie. Stad kierunek postepu. */
    val termToDef: Boolean,
    val prompt: String,
    val answers: List<String>,
    /** Surowa poprawna odpowiedź — pokazywana jako podpowiedź po pomyłce lub pominięciu. */
    val reveal: String,
)

/** Trzy wyjscia z jednego hasla w tym trybie. Nazwane, bo od nich zalezy zapis postepu. */
internal enum class TaWynik { TRAFIONE, POMYLKA, POMINIETE }

/**
 * Rozstrzygniecie jednego hasla w trybie NA CZAS przelozone na probe dla harmonogramu.
 *
 * Rodzaj zadania to [TaskKind.PRZYPOMNIENIE] i to jest tu rzecz najwazniejsza: uczen nie
 * wybiera sposrod podanych, tylko WPISUJE odpowiedz z klawiatury — razem z pisownia. Tylko
 * takie zadanie moze wyniesc slowo ponad pudelko 1, bo tylko ono dowodzi samodzielnego
 * przypomnienia. Presja czasu tego nie zmienia: sprawdzamy to samo, tylko szybciej.
 *
 * Czysta funkcja, zeby klasyfikacje dalo sie sprawdzic testem bez rysowania gry.
 *
 * @return `null` dla wpisu bez identyfikatora — Srs.key rzuca wtedy wyjatkiem, a stary plik
 *   zapisu nie ma prawa wywrocic gry.
 */
internal fun probaWpisania(
    entryId: String,
    termToDef: Boolean,
    wynik: TaWynik,
    nowMs: Long,
): Attempt? {
    if (entryId.isBlank()) return null
    val outcome = when (wynik) {
        TaWynik.TRAFIONE -> Outcome.POPRAWNA
        TaWynik.POMYLKA -> Outcome.BLEDNA
        // POMIN to nie jest bledna odpowiedz (uczen zadnej nie podal), ale nie jest tez
        // zdarzeniem bez sladu: ekran odslania wtedy poprawne tlumaczenie na dwie sekundy.
        // Bez oznaczenia dnia jako "z pomoca" to samo haslo, wpisane przy kolejnym obiegu puli
        // z pamieci ekranu, awansowaloby slowo — czyli aplikacja twierdzilaby, ze uczen je umie,
        // dokladnie po tym, jak sam powiedzial, ze nie umie.
        TaWynik.POMINIETE -> Outcome.POKAZANA
    }
    return Attempt(
        entryId = entryId,
        // Kierunek rozlosowano przy budowie puli — RANDOM nie ma jak tu wpasc.
        direction = if (termToDef) Direction.TERM_TO_DEF else Direction.DEF_TO_TERM,
        task = TaskKind.PRZYPOMNIENIE,
        outcome = outcome,
        // Znacznik jednorazowosci — jedna odpowiedz nie moze dac dwoch awansow.
        id = "$entryId#$nowMs",
    )
}

private fun buildQuestions(set: WordSet, direction: Direction, seed: Int): List<TaQuestion> {
    val random = Random(seed)
    val out = ArrayList<TaQuestion>(set.entries.size)
    for (e in set.entries) {
        val termToDef = TaQuestion(
            e.id, true, e.term.trim(), Matching.acceptedAnswers(e.definition), e.definition.trim(),
        )
        val defToTerm = TaQuestion(
            e.id, false, e.definition.trim(), Matching.acceptedAnswers(e.term), e.term.trim(),
        )
        out += when (direction) {
            Direction.TERM_TO_DEF -> termToDef
            Direction.DEF_TO_TERM -> defToTerm
            Direction.RANDOM -> if (random.nextBoolean()) termToDef else defToTerm
        }
    }
    // Wiersz bez hasła albo bez akceptowalnej odpowiedzi nie nadaje się na pytanie.
    return out.filter { it.prompt.isNotBlank() && it.answers.isNotEmpty() }.shuffled(random)
}

// ---------------------------------------------------------------------------------------
// Kawałki UI
// ---------------------------------------------------------------------------------------

@Composable
private fun TimeAttackHud(
    timeLeft: Float,
    totalTime: Float,
    score: Int,
    streak: Int,
    delta: String,
    deltaColor: Color,
    deltaVisible: Boolean,
    lang: UiLanguage,
    onQuit: () -> Unit,
) {
    val seconds = ceil(timeLeft).toInt().coerceAtLeast(0)
    // Ostatnie 10 sekund: zegar czerwienieje i pulsuje w rytm półsekundowy.
    val low = timeLeft <= 10f
    val blinkOn = !low || ((timeLeft * 2f).toInt() % 2 == 0)
    val clockColor = when {
        low && blinkOn -> Palette.Danger
        low -> Palette.Gold
        else -> Palette.Bone
    }
    val multiplier = multiplierFor(streak)

    // Ciaśniejszy pionowy padding (6.dp zamiast 8.dp) — na 360x640 dp HUD brakowało wysokości.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Wyjście w trakcie gry — bez zapisu wyniku, celowo małe, żeby nie kusiło.
            Box(
                Modifier
                    .background(Palette.Ink)
                    .border(2.dp, Palette.SurfaceHigh)
                    .clickable { onQuit() }
                    .padding(horizontal = 7.dp, vertical = 6.dp)
            ) {
                PixelImage(pixel(R.drawable.icon_back), Modifier.size(13.dp))
            }

            Spacer(Modifier.width(10.dp))

            PixelImage(pixel(R.drawable.icon_clock), Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            PixelText("$seconds", color = clockColor, glyphHeight = 18.dp)
            Spacer(Modifier.width(3.dp))
            PixelText("s", color = Palette.Dim, glyphHeight = 9.dp)

            Spacer(Modifier.weight(1f))

            // Etykieta zmiany czasu ("+2s" / "-3s") miga tuż obok punktów.
            if (deltaVisible && delta.isNotEmpty()) {
                PixelText(delta, color = deltaColor, glyphHeight = 12.dp)
                Spacer(Modifier.width(8.dp))
            }
            PixelImage(pixel(R.drawable.coin), Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            PixelText("$score", color = Palette.Gold, glyphHeight = 14.dp)
        }

        Spacer(Modifier.height(8.dp))

        // Pasek czasu: prosty prostokąt z wypełnieniem — zostaje ostry przy każdej skali.
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Palette.Ink)
                .border(2.dp, Palette.SurfaceHigh)
        ) {
            val fraction = (timeLeft / totalTime).coerceIn(0f, 1f)
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(if (low) Palette.Danger else Palette.Accent)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(R.drawable.icon_star), Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            PixelText(
                t("SERIA $streak", "STREAK $streak", lang),
                color = if (streak > 0) Palette.Cyan else Palette.Dim,
                glyphHeight = 9.dp,
            )
            Spacer(Modifier.weight(1f))
            val badge = badgeFor(multiplier)
            if (badge != null) {
                PixelImage(pixel(badge), Modifier.size(24.dp))
            } else {
                PixelText("x1", color = Palette.Dim, glyphHeight = 9.dp)
            }
        }
    }
}

@Composable
private fun PromptPanel(prompt: String, hint: String?, lang: UiLanguage) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Szerokość zawijania liczona z realnej szerokości panelu, więc długie hasła
        // mieszczą się także na wąskim (360 dp) ekranie.
        val textWidth = maxWidth - 36.dp
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .border(3.dp, Palette.Ink)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelText(
                t("PRZETŁUMACZ", "TRANSLATE", lang),
                color = Palette.Dim,
                glyphHeight = 8.dp,
            )
            Spacer(Modifier.height(12.dp))
            PixelText(
                prompt.uppercase(),
                color = Palette.Bone,
                glyphHeight = 17.dp,
                maxWidthDp = textWidth,
            )
            if (hint != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelImage(pixel(R.drawable.cross), Modifier.size(13.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        hint,
                        color = Palette.Danger,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerField(typed: String, cursor: Int) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
    ) {
        val textWidth = maxWidth - 24.dp
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.Bone)
                .border(3.dp, Palette.Ink)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Kursor wstawiamy w tekst jako zwykły znak — dzięki temu zawijanie liczy się
            // dla całej zawartości pola i nic nie ucieka poza panel.
            val safe = cursor.coerceIn(0, typed.length)
            val display = typed.substring(0, safe) + "_" + typed.substring(safe)
            PixelText(
                display,
                color = Palette.Ink,
                outline = null,
                glyphHeight = 16.dp,
                maxWidthDp = textWidth,
            )
        }
    }
}

@Composable
private fun SkipBar(lang: UiLanguage, onSkip: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(R.drawable.icon_target), Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                t("ENTER = zatwierdź", "ENTER = submit", lang),
                color = Palette.Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .background(Palette.Surface)
                .border(3.dp, Palette.SurfaceHigh)
                .clickable { onSkip() }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(t("POMIŃ", "SKIP", lang), color = Palette.Bone, glyphHeight = 10.dp)
                Spacer(Modifier.width(8.dp))
                PixelText("-5s", color = Palette.Danger, glyphHeight = 9.dp)
            }
        }
    }
}

@Composable
private fun ResultScreen(
    lang: UiLanguage,
    str: Str,
    score: Int,
    correct: Int,
    skipped: Int,
    bestStreak: Int,
    level: Int,
    onAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    // Medal wyłącznie z liczby trafień — czytelna nagroda, bez zależności od profilu.
    val medal: ImageBitmap? = when {
        correct >= 25 -> pixel(R.drawable.medal_gold)
        correct >= 15 -> pixel(R.drawable.medal_silver)
        correct >= 8 -> pixel(R.drawable.medal_bronze)
        else -> null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            // Przewijanie: na niskich ekranach medal + statystyki wypychały przyciski
            // DO MENU / JESZCZE RAZ poza widok — teraz da się do nich doscrollować.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PixelImage(medal ?: pixel(R.drawable.icon_clock), Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        PixelText(
            t("KONIEC CZASU", "TIME'S UP", lang),
            color = Palette.Danger,
            glyphHeight = 18.dp,
            maxWidthDp = 300.dp,
        )
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(R.drawable.coin), Modifier.size(26.dp))
            Spacer(Modifier.width(9.dp))
            PixelText("$score", color = Palette.Gold, glyphHeight = 26.dp)
        }

        Spacer(Modifier.height(20.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .border(3.dp, Palette.Ink)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            StatRow(
                label = t("Poprawne odpowiedzi", "Correct answers", lang),
                value = "$correct",
                icon = pixel(R.drawable.checkmark),
            )
            StatRow(
                label = t("Pominięte", "Skipped", lang),
                value = "$skipped",
                icon = pixel(R.drawable.cross),
            )
            StatRow(
                label = str.bestStreakLabel,
                value = "x$bestStreak",
                icon = pixel(R.drawable.icon_star),
            )
            StatRow(
                label = str.level,
                value = "$level",
                icon = pixel(R.drawable.icon_trophy),
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(t("JESZCZE RAZ", "PLAY AGAIN", lang), onClick = onAgain)
            Spacer(Modifier.width(12.dp))
            GhostButton(t("DO MENU", "TO MENU", lang), onClick = onMenu)
        }
    }
}

@Composable
private fun EmptySetNotice(str: Str, lang: UiLanguage, onExit: () -> Unit) {
    ScreenScaffold(
        title = t("NA CZAS", "TIME ATTACK", lang),
        onBack = onExit,
        backLabel = str.back,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(R.drawable.lock), Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                PixelText(
                    t("BRAK HASEŁ", "NO ENTRIES", lang),
                    color = Palette.Danger,
                    glyphHeight = 12.dp,
                )
            }
            Spacer(Modifier.height(12.dp))
            InfoCard(
                t(
                    "Ten zestaw nie ma ani jednego kompletnego hasła. Dodaj przynajmniej jeden " +
                        "wiersz z hasłem i tłumaczeniem, a potem wróć tu na wyścig z czasem.",
                    "This set has no complete entry. Add at least one row with a term and its " +
                        "translation, then come back and race the clock.",
                    lang,
                )
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton(t("DO MENU", "TO MENU", lang), onClick = onExit)
        }
    }
}
