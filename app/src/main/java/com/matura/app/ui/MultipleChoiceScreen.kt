package com.matura.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.R
import com.matura.app.game.FlashKind
import com.matura.app.game.Matching
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.ScoreRecord
import com.matura.app.model.Tempo
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordSet
import kotlin.random.Random

/**
 * TRYB "MULTIPLE CHOICE" — wielokrotny wybór.
 *
 * Na górze hasło, pod nim cztery kafle: jedna poprawna odpowiedź i trzy dystraktory
 * wylosowane z pozostałych haseł zestawu. Gracz stuka w kafel albo pozwala zejść
 * zegarowi — jedno i drugie kosztuje życie, jeśli odpowiedź jest zła.
 *
 * Cały plik jest samowystarczalny: napisy PL/EN idą przez lokalne [t], żeby nie
 * dokładać pól do współdzielonej klasy [Str].
 */

/** Ile haseł musi mieć zestaw, żeby dało się zbudować pytanie z czterema kaflami. */
private const val MIN_ENTRIES = 4

/** Ile kafli pokazujemy (1 poprawny + 3 dystraktory). */
private const val CHOICE_COUNT = 4

/** Punkty za trafienie, przed przemnożeniem przez mnożnik serii. */
private const val POINTS_PER_HIT = 10

/** Co ile trafień rośnie poziom. */
private const val HITS_PER_LEVEL = 10

/** Wartownik oznaczający "czas minął" zamiast indeksu wybranego kafla. */
private const val TIMED_OUT = -1

private fun t(pl: String, en: String, lang: UiLanguage) = if (lang == UiLanguage.PL) pl else en

/** Limit czasu na pytanie, w sekundach — zależny od tempa z OPCJI. */
private fun timeLimitFor(tempo: Tempo): Float = when (tempo) {
    Tempo.CALM -> 12f
    Tempo.NORMAL -> 9f
    Tempo.FAST -> 6f
}

/** Ile trwa podświetlenie odpowiedzi, zanim wskoczy kolejne pytanie. */
private fun revealTimeFor(tempo: Tempo): Float = when (tempo) {
    Tempo.CALM -> 1.7f
    Tempo.NORMAL -> 1.35f
    Tempo.FAST -> 1.05f
}

/** Mnożnik serii: 3+ => x2, 6+ => x3, 9+ => x5. */
private fun multiplierFor(streak: Int): Int = when {
    streak >= 9 -> 5
    streak >= 6 -> 3
    streak >= 3 -> 2
    else -> 1
}

@Composable
private fun multiplierBadge(multiplier: Int) = when (multiplier) {
    2 -> pixel(R.drawable.badge_x2)
    3 -> pixel(R.drawable.badge_x3)
    5 -> pixel(R.drawable.badge_x5)
    else -> null
}

/** Jedno gotowe pytanie: hasło, poprawna odpowiedź i potasowane kafle. */
private data class McQuestion(
    val prompt: String,
    val correct: String,
    val choices: List<String>,
    /** true = pytamy hasłem, odpowiedzią jest tłumaczenie. */
    val termToDef: Boolean,
) {
    val correctIndex: Int get() = choices.indexOf(correct)
}

/**
 * Buduje jedną rundę: każde hasło zestawu dokładnie raz, w losowej kolejności.
 * Nowa runda powstaje dopiero po wyczerpaniu poprzedniej, więc hasła się nie powtarzają.
 */
private fun buildRound(entries: List<Entry>, direction: Direction, rng: Random): List<McQuestion> {
    val order = entries.shuffled(rng)
    return order.map { entry ->
        val termToDef = when (direction) {
            Direction.TERM_TO_DEF -> true
            Direction.DEF_TO_TERM -> false
            Direction.RANDOM -> rng.nextBoolean()
        }
        val prompt = if (termToDef) entry.term else entry.definition
        val correct = if (termToDef) entry.definition else entry.term
        val correctKey = Matching.normalize(correct)

        // Dystraktory bierzemy z tej samej strony co odpowiedź i odrzucamy wszystko,
        // co po normalizacji jest równoważne poprawnej odpowiedzi (albo innemu kaflowi) —
        // inaczej dałoby się wskazać "złą" odpowiedź, która w istocie jest dobra.
        val used = HashSet<String>()
        used.add(correctKey)
        val distractors = ArrayList<String>(CHOICE_COUNT - 1)
        for (other in entries.shuffled(rng)) {
            if (distractors.size == CHOICE_COUNT - 1) break
            if (other === entry) continue
            val candidate = if (termToDef) other.definition else other.term
            if (candidate.isBlank()) continue
            val key = Matching.normalize(candidate)
            if (key.isEmpty() || !used.add(key)) continue
            distractors.add(candidate)
        }

        McQuestion(
            prompt = prompt,
            correct = correct,
            choices = (distractors + correct).shuffled(rng),
            termToDef = termToDef,
        )
    }
}

@Composable
fun MultipleChoiceScreen(
    set: WordSet,
    options: Options,
    str: Str,
    playerName: String,
    onExit: () -> Unit,
    onFinished: (ScoreRecord) -> Unit,
) {
    // Puste wiersze z edytora zestawów nie nadają się ani na pytanie, ani na dystraktor.
    val playable = remember(set.id, set.entries) {
        set.entries.filter { it.term.isNotBlank() && it.definition.isNotBlank() }
    }

    if (playable.size < MIN_ENTRIES) {
        NotEnoughEntries(
            lang = options.uiLanguage,
            have = playable.size,
            backLabel = str.back,
            onExit = onExit,
        )
    } else {
        McGame(
            set = set,
            entries = playable,
            options = options,
            str = str,
            playerName = playerName,
            onExit = onExit,
            onFinished = onFinished,
        )
    }
}

/** Zestaw za mały na ten tryb — zamiast crasha elegancki komunikat i wyjście. */
@Composable
private fun NotEnoughEntries(
    lang: UiLanguage,
    have: Int,
    backLabel: String,
    onExit: () -> Unit,
) {
    ScreenScaffold(
        title = t("ZA MAŁO HASEŁ", "NOT ENOUGH", lang),
        onBack = onExit,
        backLabel = backLabel,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PixelImage(pixel(R.drawable.lock), Modifier.size(52.dp))
            }
            Spacer(Modifier.height(16.dp))
            InfoCard(
                t(
                    "MULTIPLE CHOICE potrzebuje co najmniej $MIN_ENTRIES haseł z tłumaczeniem — " +
                        "trzy z nich robią za błędne odpowiedzi. Ten zestaw ma ich $have. " +
                        "Dopisz brakujące wiersze w ZESTAWACH i wróć.",
                    "MULTIPLE CHOICE needs at least $MIN_ENTRIES complete rows — three of them " +
                        "become the wrong answers. This set has $have. Add the missing rows " +
                        "under SETS and come back.",
                    lang,
                )
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                label = t("DO MENU", "TO MENU", lang),
                modifier = Modifier.fillMaxWidth(),
                onClick = onExit,
            )
        }
    }
}

@Composable
private fun McGame(
    set: WordSet,
    entries: List<Entry>,
    options: Options,
    str: Str,
    playerName: String,
    onExit: () -> Unit,
    onFinished: (ScoreRecord) -> Unit,
) {
    val lang = options.uiLanguage
    val maxLives = options.lives.coerceAtLeast(1)
    val timeLimit = timeLimitFor(options.tempo)
    val revealTime = revealTimeFor(options.tempo)

    // Każdy restart ("JESZCZE RAZ") zwiększa runId, co resetuje wszystkie remembery poniżej.
    var runId by remember { mutableIntStateOf(0) }

    val rng = remember(runId) { Random(System.nanoTime()) }
    var round by remember(runId) { mutableStateOf(buildRound(entries, options.direction, rng)) }
    var index by remember(runId) { mutableIntStateOf(0) }
    var asked by remember(runId) { mutableIntStateOf(0) }

    var lives by remember(runId) { mutableIntStateOf(maxLives) }
    var score by remember(runId) { mutableIntStateOf(0) }
    var streak by remember(runId) { mutableIntStateOf(0) }
    var bestStreak by remember(runId) { mutableIntStateOf(0) }
    var hits by remember(runId) { mutableIntStateOf(0) }

    // null = gracz jeszcze nie odpowiedział; TIMED_OUT = zegar zszedł; inaczej indeks kafla.
    var revealed by remember(runId) { mutableStateOf<Int?>(null) }
    var lastGain by remember(runId) { mutableIntStateOf(0) }
    var gameOver by remember(runId) { mutableStateOf(false) }
    var reported by remember(runId) { mutableStateOf(false) }
    var timeLeft by remember(runId) { mutableFloatStateOf(timeLimit) }

    val sfx = remember { Sfx() }
    DisposableEffect(Unit) { onDispose { sfx.release() } }

    val question = round.getOrNull(index)
    val level = 1 + hits / HITS_PER_LEVEL

    /** Rozstrzyga pytanie: [choice] to indeks kafla albo [TIMED_OUT]. */
    fun resolve(choice: Int) {
        if (revealed != null || gameOver || question == null) return
        val correct = choice >= 0 && choice == question.correctIndex
        revealed = choice
        asked += 1
        if (correct) {
            streak += 1
            if (streak > bestStreak) bestStreak = streak
            hits += 1
            lastGain = POINTS_PER_HIT * multiplierFor(streak)
            score += lastGain
            sfx.play(FlashKind.HIT, options.soundEnabled)
        } else {
            streak = 0
            lastGain = 0
            lives = (lives - 1).coerceAtLeast(0)
            sfx.play(FlashKind.LIFE_LOST, options.soundEnabled)
        }
    }

    /** Po podświetleniu: albo koniec gry, albo kolejne pytanie (nowa runda, gdy zestaw się wyczerpał). */
    fun advance() {
        if (lives <= 0) {
            gameOver = true
            return
        }
        val next = index + 1
        if (next >= round.size) {
            round = buildRound(entries, options.direction, rng)
            index = 0
        } else {
            index = next
        }
        revealed = null
        timeLeft = timeLimit
    }

    // Zegar pytania. Efekt startuje od nowa przy każdym pytaniu i zamiera na czas podświetlenia.
    LaunchedEffect(runId, index, revealed, gameOver) {
        if (gameOver || revealed != null || question == null) return@LaunchedEffect
        timeLeft = timeLimit
        var last = 0L
        while (timeLeft > 0f) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
                    timeLeft = (timeLeft - dt).coerceAtLeast(0f)
                }
                last = now
            }
        }
        resolve(TIMED_OUT)
    }

    // Pauza na podświetlenie poprawnej/błędnej odpowiedzi, po niej kolejne pytanie.
    LaunchedEffect(runId, index, revealed) {
        if (revealed == null) return@LaunchedEffect
        var waited = 0f
        var last = 0L
        while (waited < revealTime) {
            withFrameNanos { now ->
                if (last != 0L) {
                    waited += ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
                }
                last = now
            }
        }
        advance()
    }

    // Wynik zgłaszamy dokładnie raz na rozgrywkę.
    LaunchedEffect(runId, gameOver) {
        if (gameOver && !reported) {
            reported = true
            onFinished(
                ScoreRecord(
                    playerName = playerName,
                    setTitle = set.title,
                    score = score,
                    level = level,
                    bestStreak = bestStreak,
                    kills = hits,
                    playedAt = System.currentTimeMillis(),
                    mode = GameMode.MULTIPLE_CHOICE,
                )
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        // Szerokość dostępna na tekst w panelu: ekran minus marginesy ekranu i panelu.
        val textWidth: Dp = (maxWidth - 56.dp).coerceIn(140.dp, 420.dp)

        if (gameOver) {
            McGameOver(
                lang = lang,
                str = str,
                setTitle = set.title,
                score = score,
                level = level,
                hits = hits,
                bestStreak = bestStreak,
                onAgain = { runId += 1 },
                onMenu = onExit,
            )
        } else if (question != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                McHud(
                    lang = lang,
                    lives = lives,
                    maxLives = maxLives,
                    score = score,
                    level = level,
                    streak = streak,
                    backLabel = str.back,
                    onExit = onExit,
                )
                Spacer(Modifier.height(10.dp))
                McTimerBar(timeLeft = timeLeft, limit = timeLimit)
                Spacer(Modifier.height(12.dp))

                McPrompt(
                    question = question,
                    set = set,
                    lang = lang,
                    textWidth = textWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Spacer(Modifier.height(10.dp))
                McFeedback(
                    lang = lang,
                    revealed = revealed,
                    question = question,
                    gain = lastGain,
                    multiplier = multiplierFor(streak),
                )
                Spacer(Modifier.height(6.dp))

                question.choices.forEachIndexed { i, choice ->
                    McChoiceTile(
                        letter = ('A' + i).toString(),
                        text = choice,
                        state = tileState(revealed, i, question.correctIndex),
                        enabled = revealed == null,
                        onClick = { resolve(i) },
                    )
                    Spacer(Modifier.height(7.dp))
                }

                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelText(
                        t("PYTANIE", "QUESTION", lang) + " ${asked + if (revealed == null) 1 else 0}",
                        color = Palette.Dim,
                        glyphHeight = 8.dp,
                    )
                    PixelText(
                        t("SERIA", "STREAK", lang) + " $streak",
                        color = if (streak >= 3) Palette.Accent else Palette.Dim,
                        glyphHeight = 8.dp,
                    )
                }
            }
        }
    }
}

/** Wizualny stan kafla po odsłonięciu odpowiedzi. */
private enum class TileState { IDLE, CORRECT, WRONG, FADED }

private fun tileState(revealed: Int?, index: Int, correctIndex: Int): TileState = when {
    revealed == null -> TileState.IDLE
    index == correctIndex -> TileState.CORRECT
    index == revealed -> TileState.WRONG
    else -> TileState.FADED
}

@Composable
private fun McHud(
    lang: UiLanguage,
    lives: Int,
    maxLives: Int,
    score: Int,
    level: Int,
    streak: Int,
    backLabel: String,
    onExit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier
                    .background(Palette.Surface)
                    .border(2.dp, Palette.SurfaceHigh)
                    .clickable { onExit() }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelImage(pixel(R.drawable.icon_back), Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                PixelText(backLabel, color = Palette.Bone, glyphHeight = 8.dp)
            }
            Spacer(Modifier.width(8.dp))
            // Przy dużej liczbie żyć rysowanie serduszek nie mieści się na 360dp — wtedy licznik.
            if (maxLives <= 6) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until maxLives) {
                        PixelImage(
                            if (i < lives) pixel(R.drawable.heart) else pixel(R.drawable.heart_empty),
                            Modifier.size(17.dp),
                        )
                        if (i < maxLives - 1) Spacer(Modifier.width(3.dp))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelImage(pixel(R.drawable.heart), Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    PixelText("$lives/$maxLives", color = Palette.Bone, glyphHeight = 9.dp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(R.drawable.coin), Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                PixelText("$score", color = Palette.Gold, glyphHeight = 13.dp)
                val badge = multiplierBadge(multiplierFor(streak))
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    PixelImage(badge, Modifier.size(19.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(R.drawable.icon_target), Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                PixelText(t("POZ", "LVL", lang) + " $level", color = Palette.Cyan, glyphHeight = 9.dp)
            }
        }
    }
}

@Composable
private fun McTimerBar(timeLeft: Float, limit: Float) {
    val fraction = if (limit <= 0f) 0f else (timeLeft / limit).coerceIn(0f, 1f)
    val fill = when {
        fraction > 0.5f -> Palette.Accent
        fraction > 0.25f -> Palette.Gold
        else -> Palette.Danger
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PixelImage(pixel(R.drawable.icon_clock), Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(14.dp)
                .background(Palette.Ink)
                .border(2.dp, Palette.SurfaceHigh)
                .padding(2.dp)
        ) {
            // Pasek maleje płynnie; przy zerze zostaje pusta ramka.
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(fill)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        PixelText(
            "${kotlin.math.ceil(timeLeft.toDouble()).toInt()}",
            color = fill,
            glyphHeight = 10.dp,
        )
    }
}

@Composable
private fun McPrompt(
    question: McQuestion,
    set: WordSet,
    lang: UiLanguage,
    textWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val fromLang = if (question.termToDef) set.termLanguage else set.definitionLanguage
    val toLang = if (question.termToDef) set.definitionLanguage else set.termLanguage
    Box(
        modifier
            .heightIn(min = 96.dp)
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Pill("$fromLang / $toLang", Palette.Cyan)
            Spacer(Modifier.height(12.dp))
            // Krótkie hasła wyglądają lepiej czcionką bitmapową; dłuższe zdania czyta się
            // wygodniej systemową — stąd próg długości zamiast jednego stylu na wszystko.
            if (question.prompt.length <= 20) {
                PixelText(
                    question.prompt,
                    color = Palette.Bone,
                    glyphHeight = 17.dp,
                    maxWidthDp = textWidth,
                )
            } else {
                Text(
                    question.prompt,
                    color = Palette.Bone,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            PixelText(
                t("WYBIERZ TŁUMACZENIE", "PICK THE TRANSLATION", lang),
                color = Palette.Dim,
                glyphHeight = 7.dp,
                maxWidthDp = textWidth,
            )
        }
    }
}

/** Pasek zwrotki pod hasłem — ma stałą wysokość, żeby kafle nie skakały po odpowiedzi. */
@Composable
private fun McFeedback(
    lang: UiLanguage,
    revealed: Int?,
    question: McQuestion,
    gain: Int,
    multiplier: Int,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            revealed == null -> Unit
            revealed == question.correctIndex -> Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(R.drawable.checkmark), Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                PixelText(t("DOBRZE", "CORRECT", lang), color = Palette.Accent, glyphHeight = 10.dp)
                Spacer(Modifier.width(9.dp))
                // Czcionka bitmapowa nie ma glifu "+" (podmienia go na "-"), więc zysk punktów
                // pokazujemy monetą zamiast znakiem plus.
                PixelImage(pixel(R.drawable.coin), Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                PixelText("$gain", color = Palette.Gold, glyphHeight = 10.dp)
                if (multiplier > 1) {
                    Spacer(Modifier.width(7.dp))
                    multiplierBadge(multiplier)?.let { PixelImage(it, Modifier.size(17.dp)) }
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(R.drawable.cross), Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                PixelText(
                    if (revealed == TIMED_OUT) t("CZAS MINĄŁ", "TIME UP", lang)
                    else t("ŹLE", "WRONG", lang),
                    color = Palette.Danger,
                    glyphHeight = 10.dp,
                )
            }
        }
    }
}

@Composable
private fun McChoiceTile(
    letter: String,
    text: String,
    state: TileState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fill = when (state) {
        TileState.IDLE -> Palette.Surface
        TileState.CORRECT -> Palette.Accent
        TileState.WRONG -> Palette.Danger
        TileState.FADED -> Palette.Surface
    }
    val borderColor = when (state) {
        TileState.IDLE -> Palette.SurfaceHigh
        TileState.CORRECT, TileState.WRONG -> Palette.Ink
        TileState.FADED -> Palette.SurfaceHigh
    }
    val ink: Color = when (state) {
        TileState.IDLE -> Palette.Bone
        TileState.CORRECT, TileState.WRONG -> Palette.Ink
        TileState.FADED -> Palette.Dim
    }
    val badgeFill = when (state) {
        TileState.CORRECT, TileState.WRONG -> Palette.Ink
        else -> Palette.SurfaceHigh
    }
    val badgeInk = when (state) {
        TileState.CORRECT -> Palette.Accent
        TileState.WRONG -> Palette.Danger
        TileState.FADED -> Palette.Dim
        else -> Palette.Bone
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(fill)
            .border(3.dp, borderColor)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(badgeFill),
                contentAlignment = Alignment.Center,
            ) {
                PixelText(letter, color = badgeInk, outline = null, glyphHeight = 10.dp)
            }
            Spacer(Modifier.width(11.dp))
            Text(
                text,
                color = ink,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state == TileState.CORRECT || state == TileState.WRONG) {
                Spacer(Modifier.width(8.dp))
                PixelImage(
                    if (state == TileState.CORRECT) pixel(R.drawable.checkmark) else pixel(R.drawable.cross),
                    Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun McGameOver(
    lang: UiLanguage,
    str: Str,
    setTitle: String,
    score: Int,
    level: Int,
    hits: Int,
    bestStreak: Int,
    onAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PixelImage(pixel(R.drawable.skull), Modifier.size(54.dp))
        Spacer(Modifier.height(12.dp))
        PixelText(str.gameOver, color = Palette.Danger, glyphHeight = 17.dp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(R.drawable.coin), Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            PixelText("$score", color = Palette.Gold, glyphHeight = 26.dp)
        }
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .border(3.dp, Palette.Ink)
                .padding(horizontal = 13.dp, vertical = 6.dp)
        ) {
            StatRow(
                label = t("Tryb", "Mode", lang),
                value = "MULTIPLE CHOICE",
                icon = pixel(R.drawable.mode_multiple),
            )
            StatRow(
                label = t("Zestaw", "Set", lang),
                value = setTitle,
                icon = pixel(R.drawable.icon_star),
                stacked = true,
            )
            StatRow(
                label = str.level,
                value = "$level",
                icon = pixel(R.drawable.icon_target),
            )
            StatRow(
                label = t("Trafienia", "Correct answers", lang),
                value = "$hits",
                icon = pixel(R.drawable.checkmark),
            )
            StatRow(
                label = str.bestStreakLabel,
                value = "x$bestStreak",
                icon = pixel(R.drawable.icon_trophy),
            )
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton(
            label = t("JESZCZE RAZ", "PLAY AGAIN", lang),
            modifier = Modifier.fillMaxWidth(),
            onClick = onAgain,
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            label = t("DO MENU", "TO MENU", lang),
            modifier = Modifier.fillMaxWidth(),
            onClick = onMenu,
        )
        Spacer(Modifier.height(16.dp))
    }
}
