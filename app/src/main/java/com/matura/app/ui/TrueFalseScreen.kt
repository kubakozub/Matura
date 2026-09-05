package com.matura.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.R
import com.matura.app.game.FlashKind
import com.matura.app.game.Matching
import com.matura.app.learn.Attempt
import com.matura.app.learn.Outcome
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.ScoreRecord
import com.matura.app.model.Speed
import com.matura.app.model.TaskKind
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordSet
import kotlin.random.Random

/**
 * TRYB "PRAWDA / FAŁSZ".
 *
 * Ekran pokazuje hasło i JEDNĄ propozycję tłumaczenia. Gracz rozstrzyga, czy propozycja
 * pasuje. Mniej więcej połowa pytań jest prawdziwa, reszta ma tłumaczenie podmienione
 * z innego hasła w zbiorze — przy czym podmianka nigdy nie może być *także* poprawna
 * (zbiory z synonimami, np. "auto" i "samochód", potrafią się nakładać), więc kandydatów
 * przesiewamy przez [Matching.acceptedAnswers].
 *
 * Cała pętla stoi na [withFrameNanos] — bez dodatkowych zależności i bez licznika, który
 * biegłby dalej po opuszczeniu ekranu.
 */

// ---- napisy ----------------------------------------------------------------

/** Lokalna tablica napisów — [Str] jest plikiem wspólnym i nie wolno go rozszerzać. */
private fun t(pl: String, en: String, lang: UiLanguage) = if (lang == UiLanguage.PL) pl else en

// ---- model pytania ---------------------------------------------------------

private data class TfQuestion(
    /**
     * Trwaly identyfikator wpisu, o ktory pytamy. Postep nauki wisi na nim, a nie na tresci —
     * pusty tylko w zestawach sprzed wersjonowania (patrz [probaPrawdaFalsz]).
     */
    val entryId: String,
    /** true = pytamy haslem, oceniana propozycja jest tlumaczenie. Stad kierunek postepu. */
    val termToDef: Boolean,
    /** Hasło pokazane u góry panelu. */
    val prompt: String,
    /** Propozycja tłumaczenia, którą ocenia gracz. */
    val shown: String,
    /** Faktycznie poprawne tłumaczenie [prompt] — pokazujemy je przy pomyłce. */
    val correct: String,
    val isTrue: Boolean,
)

private enum class TfPhase { ASK, FEEDBACK, OVER }

/**
 * Powod, dla ktorego runda sie skonczyla — steruje tekstem feedbacku, a przez
 * [probaPrawdaFalsz] takze zapisem postepu. Stad `internal`, a nie `private`: test rodzaju
 * zadania nazywa te trzy wyjscia po imieniu.
 */
internal enum class TfOutcome { RIGHT, WRONG, TIMEOUT }

/**
 * Rozstrzygniecie jednej rundy prawda/falsz przelozone na probe dla harmonogramu powtorek.
 *
 * Rodzaj zadania to [TaskKind.ROZPOZNANIE] — uczen OCENIA podana propozycje, wiec odpowiedz
 * "falsz" dowodzi tylko tego, ze odroznil dwa znaczenia, a nie ze umie przetlumaczyc haslo
 * sam. Sufit awansu (pudelko 1, "poznane") ma tu nawet mocniejsze uzasadnienie niz przy ABCD:
 * przy dwoch przyciskach zgadywanie trafia co drugi raz.
 *
 * Czysta funkcja, bo klasyfikacja jest merytoryczna i ma wlasny test (RodzajZadaniaTest);
 * w srodku ekranu bylaby sprawdzalna dopiero przez rysowanie calej gry.
 *
 * @return `null` dla wpisu bez identyfikatora — Srs.key rzuca wtedy wyjatkiem, a stary plik
 *   zapisu nie ma prawa wywrocic gry.
 */
internal fun probaPrawdaFalsz(
    entryId: String,
    termToDef: Boolean,
    wynik: TfOutcome,
    nowMs: Long,
): Attempt? {
    if (entryId.isBlank()) return null
    val outcome = when (wynik) {
        TfOutcome.RIGHT -> Outcome.POPRAWNA
        TfOutcome.WRONG -> Outcome.BLEDNA
        // Zejscie zegara kosztuje zycie, ale nie jest bledna odpowiedzia: uczen nic nie
        // powiedzial o tym slowie. Panel odslania wtedy poprawne tlumaczenie, wiec dzien
        // zostaje oznaczony jako "z pomoca" i to samo haslo, trafione za chwile, nie awansuje.
        TfOutcome.TIMEOUT -> Outcome.POKAZANA
    }
    return Attempt(
        entryId = entryId,
        // Kierunek rozstrzygnieto przy budowie pytania — RANDOM nie ma jak tu wpasc.
        direction = if (termToDef) Direction.TERM_TO_DEF else Direction.DEF_TO_TERM,
        task = TaskKind.ROZPOZNANIE,
        outcome = outcome,
        // Znacznik jednorazowosci — jedna odpowiedz nie moze dac dwoch awansow.
        id = "$entryId#$nowMs",
    )
}

// ---- reguły ----------------------------------------------------------------

/** Limit czasu na odpowiedź; SPOKOJNA 8 s, NORMALNA 6 s, SZYBKA 4 s. */
private fun answerTime(tempo: Speed): Float = when (tempo) {
    Speed.SLOW -> 8f
    Speed.NORMAL -> 6f
    Speed.FAST -> 4f
}

/** Mnożnik serii: 3+ => x2, 6+ => x3, 9+ => x5. Kolejność warunków od najwyższego. */
private fun multiplierFor(streak: Int): Int = when {
    streak >= 9 -> 5
    streak >= 6 -> 3
    streak >= 3 -> 2
    else -> 1
}

/** Poziom rośnie co 10 poprawnych odpowiedzi. */
private fun levelFor(correctCount: Int): Int = correctCount / 10 + 1

/**
 * Losuje jedno pytanie. Zwraca `null`, gdy zbiór jest za ubogi (mniej niż 2 hasła).
 *
 * [avoidPrompt] pozwala nie powtarzać tego samego hasła dwa razy pod rząd — przy
 * bardzo małych zbiorach i tak czasem trzeba je powtórzyć, dlatego to tylko preferencja.
 */
private fun buildQuestion(
    entries: List<Entry>,
    direction: Direction,
    rnd: Random,
    avoidPrompt: String?,
): TfQuestion? {
    if (entries.size < 2) return null

    // Kierunek rozstrzygamy PRZED wyborem indeksu — przy RANDOM strona pytania musi być
    // znana już teraz, żeby anty-powtórka porównywała avoidPrompt z właściwą stroną wiersza.
    val termToDef = when (direction) {
        Direction.TERM_TO_DEF -> true
        Direction.DEF_TO_TERM -> false
        Direction.RANDOM -> rnd.nextBoolean()
    }

    // Wybór hasła: kilka podejść, żeby nie trafić od razu w to samo co poprzednio.
    var idx = rnd.nextInt(entries.size)
    if (avoidPrompt != null) {
        var tries = 0
        while (tries < 6) {
            val e = entries[idx]
            // Porównujemy stronę PYTANIA (term gdy termToDef, inaczej definition).
            val p = (if (termToDef) e.term else e.definition).trim()
            if (p != avoidPrompt) break
            idx = rnd.nextInt(entries.size)
            tries++
        }
    }
    val entry = entries[idx]

    val prompt = (if (termToDef) entry.term else entry.definition).trim()
    val correct = (if (termToDef) entry.definition else entry.term).trim()
    if (prompt.isEmpty() || correct.isEmpty()) return null

    // Około połowa pytań ma tłumaczenie prawidłowe.
    if (rnd.nextBoolean()) {
        return TfQuestion(entry.id, termToDef, prompt, correct, correct, isTrue = true)
    }

    // Podmianka: dowolne inne hasło, ale odrzucamy takie, które po normalizacji
    // dzieli choć jedną akceptowaną odpowiedź z poprawną — inaczej "fałsz" byłby prawdą.
    // Bierzemy sumę odpowiedzi WSZYSTKICH wierszy o tym samym pytaniu (hasła wieloznaczne
    // typu "ręka" -> arm/hand), a nie tylko odpowiedź z wylosowanego wiersza.
    val correctAnswers = Matching.answersForPrompt(entries, prompt, termToDef)
    val promptKey = Matching.normalize(prompt)
    for (j in entries.indices.shuffled(rnd)) {
        if (j == idx) continue
        // Wiersz, którego strona PYTANIA normalizuje się jak prompt, to inny wariant tego
        // samego hasła — jego odpowiedź jest de facto poprawna, więc nie może być fałszem
        // (inaczej "ręka"(arm) dostałaby "hand" jako rzekomy fałsz).
        val candPrompt = (if (termToDef) entries[j].term else entries[j].definition).trim()
        if (Matching.normalize(candPrompt) == promptKey) continue
        val cand = (if (termToDef) entries[j].definition else entries[j].term).trim()
        if (cand.isEmpty()) continue
        val candAnswers = Matching.acceptedAnswers(cand)
        if (candAnswers.isEmpty()) continue
        if (candAnswers.any { it in correctAnswers }) continue
        return TfQuestion(entry.id, termToDef, prompt, cand, correct, isTrue = false)
    }

    // Cały zbiór okazał się synonimami jednego pojęcia — nie da się ułożyć fałszu.
    return TfQuestion(entry.id, termToDef, prompt, correct, correct, isTrue = true)
}

/**
 * Czy dla [entry] w kierunku [termToDef] da się w ogóle ułożyć fałszywą podmiankę.
 * Ta sama logika odsiewu co w [buildQuestion] (suma odpowiedzi po prompcie + pomijanie
 * wierszy o tym samym pytaniu) — służy do wcześniejszego odfiltrowania niegrywalnych
 * wierszy, żeby cichy fallback `isTrue = true` nie był osiągalny w normalnej rozgrywce.
 */
private fun hasFalseCandidate(entries: List<Entry>, entry: Entry, termToDef: Boolean): Boolean {
    val prompt = (if (termToDef) entry.term else entry.definition).trim()
    val correct = (if (termToDef) entry.definition else entry.term).trim()
    if (prompt.isEmpty() || correct.isEmpty()) return false
    val correctAnswers = Matching.answersForPrompt(entries, prompt, termToDef)
    val promptKey = Matching.normalize(prompt)
    for (other in entries) {
        if (other === entry) continue
        val candPrompt = (if (termToDef) other.term else other.definition).trim()
        if (Matching.normalize(candPrompt) == promptKey) continue
        val cand = (if (termToDef) other.definition else other.term).trim()
        if (cand.isEmpty()) continue
        val candAnswers = Matching.acceptedAnswers(cand)
        if (candAnswers.isEmpty()) continue
        // Grywalny fałsz = kandydat, który NIE dzieli żadnej poprawnej odpowiedzi.
        if (candAnswers.none { it in correctAnswers }) return true
    }
    return false
}

// ---- ekran -----------------------------------------------------------------

/**
 * @param onAttempt wynik JEDNEJ rundy dla harmonogramu powtorek. Ekran nie zna repozytorium
 *   i nie zaklada wlasnego — druga instancja [com.matura.app.data.Repository] ma wlasny bufor,
 *   wiec zapis wyniku gry nadpisalby postep z calej rozgrywki. Wolajacy podaje
 *   `repo.recordAttempt(proba, System.currentTimeMillis())`; domyslne nic-nie-rob zostawia
 *   tryb takim, jaki byl.
 */
@Composable
fun TrueFalseScreen(
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

    // Sprzętowy/gestowy "wstecz" wychodzi do menu tak samo jak przycisk w nagłówku.
    BackHandler { onExit() }

    // Grywalne są tylko wiersze wypełnione po obu stronach, dla których w danym kierunku
    // da się w ogóle ułożyć fałszywą podmiankę. Zestaw samych synonimów zostaje odsiany tu,
    // dzięki czemu cichy fallback isTrue=true w buildQuestion nie jest osiągalny.
    val usable = remember(set.id, set.entries, options.direction) {
        // Wiersz grywalny = obie strony niepuste PO NORMALIZACJI (jak w MultipleChoice); wiersz
        // z samej interpunkcji dawał pusty klucz i psuł odsiew fałszów.
        val complete = set.entries.filter {
            Matching.normalize(it.term).isNotEmpty() && Matching.normalize(it.definition).isNotEmpty()
        }

        // hasFalseCandidate liczy kandydatów na fałsz po CAŁEJ liście, ale buildQuestion losuje
        // z przefiltrowanej listy — wiersz mógł więc być "grywalny" dzięki kandydatowi, który sam
        // wypadł z odsiewu, i cichy fallback isTrue=true stawał się osiągalny. Filtrujemy więc do
        // PUNKTU STAŁEGO: powtarzamy odsiew na już zawężonej liście, aż przestanie się kurczyć.
        fun playable(pool: List<Entry>, entry: Entry): Boolean = when (options.direction) {
            Direction.TERM_TO_DEF -> hasFalseCandidate(pool, entry, termToDef = true)
            Direction.DEF_TO_TERM -> hasFalseCandidate(pool, entry, termToDef = false)
            // RANDOM losuje kierunek per pytanie, więc wiersz musi mieć fałsz w OBU stronach.
            Direction.RANDOM ->
                hasFalseCandidate(pool, entry, termToDef = true) &&
                    hasFalseCandidate(pool, entry, termToDef = false)
        }

        var current = complete
        while (true) {
            val next = current.filter { playable(current, it) }
            if (next.size == current.size) break
            current = next
        }
        current
    }

    // Do zbudowania fałszywej pary trzeba minimum dwóch grywalnych haseł — inaczej komunikat.
    if (usable.size < 2) {
        NotEnoughEntries(str, lang, onExit)
        return
    }

    var restarts by remember { mutableIntStateOf(0) }

    val maxLives = options.lives.coerceIn(1, 9)
    val limit = answerTime(options.speed)

    val rnd = remember(restarts) { Random(System.nanoTime()) }
    var score by remember(restarts) { mutableIntStateOf(0) }
    var lives by remember(restarts) { mutableIntStateOf(maxLives) }
    var streak by remember(restarts) { mutableIntStateOf(0) }
    var bestStreak by remember(restarts) { mutableIntStateOf(0) }
    var correctCount by remember(restarts) { mutableIntStateOf(0) }
    var qIndex by remember(restarts) { mutableIntStateOf(0) }
    var phase by remember(restarts) { mutableStateOf(TfPhase.ASK) }
    var outcome by remember(restarts) { mutableStateOf(TfOutcome.RIGHT) }
    var gained by remember(restarts) { mutableIntStateOf(0) }
    var leveledUp by remember(restarts) { mutableStateOf(false) }
    var timeLeft by remember(restarts) { mutableFloatStateOf(limit) }
    var reported by remember(restarts) { mutableStateOf(false) }
    var question by remember(restarts) {
        mutableStateOf(buildQuestion(usable, options.direction, rnd, null))
    }

    val sfx = remember { Sfx() }
    DisposableEffect(Unit) { onDispose { sfx.release() } }

    val level = levelFor(correctCount)

    /** Zamyka rundę: zapisuje wynik i przechodzi w fazę feedbacku. */
    fun settle(result: TfOutcome) {
        if (phase != TfPhase.ASK) return
        outcome = result
        // Postep nauki liczy sie inaczej niz punkty: strata zycia za zejscie zegara zostaje,
        // ale harmonogram nie dostaje z tego bledu (patrz probaPrawdaFalsz).
        question?.let { q ->
            probaPrawdaFalsz(q.entryId, q.termToDef, result, System.currentTimeMillis())
                ?.let(onAttempt)
        }
        if (result == TfOutcome.RIGHT) {
            val before = levelFor(correctCount)
            streak += 1
            if (streak > bestStreak) bestStreak = streak
            correctCount += 1
            gained = 10 * multiplierFor(streak)
            score += gained
            leveledUp = levelFor(correctCount) > before
            sfx.play(if (leveledUp) FlashKind.CRATE else FlashKind.HIT, options.soundEnabled)
        } else {
            streak = 0
            gained = 0
            leveledUp = false
            lives -= 1
            sfx.play(FlashKind.LIFE_LOST, options.soundEnabled)
        }
        phase = TfPhase.FEEDBACK
    }

    /** Po feedbacku: albo koniec gry, albo nowe pytanie i pełny pasek czasu. */
    fun advance() {
        if (lives <= 0) {
            phase = TfPhase.OVER
            return
        }
        val next = buildQuestion(usable, options.direction, rnd, question?.prompt)
        if (next == null) {
            phase = TfPhase.OVER
            return
        }
        question = next
        qIndex += 1
        timeLeft = limit
        phase = TfPhase.ASK
    }

    // Odliczanie czasu na odpowiedź. Restart licznika przy każdej zmianie pytania/fazy.
    LaunchedEffect(restarts, qIndex, phase) {
        if (phase != TfPhase.ASK) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // dt przycięte, żeby zgubiona klatka nie zjadła od razu pół sekundy
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                    timeLeft = (timeLeft - dt).coerceAtLeast(0f)
                }
                last = now
            }
            if (timeLeft <= 0f) {
                settle(TfOutcome.TIMEOUT)
                break
            }
        }
    }

    // Krótka pauza z zielonym / czerwonym znakiem, zanim wskoczy kolejne pytanie.
    LaunchedEffect(restarts, qIndex, phase) {
        if (phase != TfPhase.FEEDBACK) return@LaunchedEffect
        val hold = if (outcome == TfOutcome.RIGHT) 0.75f else 1.35f
        var last = 0L
        var elapsed = 0f
        while (elapsed < hold) {
            withFrameNanos { now ->
                if (last != 0L) {
                    elapsed += ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                }
                last = now
            }
        }
        advance()
    }

    // Wynik zgłaszany DOKŁADNIE RAZ na przebieg — pilnuje tego flaga `reported`.
    LaunchedEffect(restarts, phase) {
        if (phase == TfPhase.OVER && !reported) {
            reported = true
            onFinished(
                ScoreRecord(
                    playerName = playerName,
                    setTitle = set.title,
                    score = score,
                    level = level,
                    bestStreak = bestStreak,
                    kills = correctCount,
                    playedAt = System.currentTimeMillis(),
                    mode = GameMode.TRUE_FALSE,
                )
            )
        }
    }

    ScreenScaffold(
        title = t("PRAWDA / FAŁSZ", "TRUE / FALSE", lang),
        onBack = onExit,
        backLabel = str.back,
    ) {
        if (phase == TfPhase.OVER) {
            TfGameOver(
                score = score,
                level = level,
                correctCount = correctCount,
                bestStreak = bestStreak,
                lang = lang,
                onAgain = { restarts += 1 },
                onMenu = onExit,
            )
            return@ScreenScaffold
        }

        val q = question ?: return@ScreenScaffold

        Column(Modifier.fillMaxSize()) {
            TfHud(
                lives = lives,
                maxLives = maxLives,
                score = score,
                level = level,
                streak = streak,
                lang = lang,
            )
            Spacer(Modifier.height(10.dp))

            // Pasek czasu barwi się ostrzegawczo, gdy zostaje mniej niż połowa.
            val fraction = if (limit <= 0f) 0f else timeLeft / limit
            TimeBar(
                fraction = fraction,
                color = when {
                    fraction > 0.5f -> Palette.Accent
                    fraction > 0.25f -> Palette.Gold
                    else -> Palette.Danger
                },
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f)) {
                QuestionPanel(q = q, phase = phase, outcome = outcome, gained = gained, leveledUp = leveledUp, lang = lang)
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                AnswerButton(
                    label = t("PRAWDA", "TRUE", lang),
                    icon = pixel(R.drawable.checkmark),
                    face = Palette.Accent,
                    shadow = Palette.AccentDim,
                    enabled = phase == TfPhase.ASK,
                    modifier = Modifier.weight(1f),
                    onClick = { settle(if (q.isTrue) TfOutcome.RIGHT else TfOutcome.WRONG) },
                )
                Spacer(Modifier.width(10.dp))
                AnswerButton(
                    label = t("FAŁSZ", "FALSE", lang),
                    icon = pixel(R.drawable.cross),
                    face = Palette.Danger,
                    shadow = Palette.Ink,
                    enabled = phase == TfPhase.ASK,
                    modifier = Modifier.weight(1f),
                    onClick = { settle(if (q.isTrue) TfOutcome.WRONG else TfOutcome.RIGHT) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---- części ekranu ---------------------------------------------------------

@Composable
private fun TfHud(
    lives: Int,
    maxLives: Int,
    score: Int,
    level: Int,
    streak: Int,
    lang: UiLanguage,
) {
    val heart = pixel(R.drawable.heart)
    val heartEmpty = pixel(R.drawable.heart_empty)
    val multiplier = multiplierFor(streak)

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until maxLives) {
                PixelImage(if (i < lives) heart else heartEmpty, Modifier.size(17.dp))
                Spacer(Modifier.width(3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(R.drawable.coin), Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            PixelText("$score", color = Palette.Gold, glyphHeight = 13.dp)
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Pill("LVL $level", Palette.Cyan)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Odznaka mnożnika pojawia się dopiero od serii 3
            val badge: ImageBitmap? = when (multiplier) {
                5 -> pixel(R.drawable.badge_x5)
                3 -> pixel(R.drawable.badge_x3)
                2 -> pixel(R.drawable.badge_x2)
                else -> null
            }
            if (badge != null) {
                PixelImage(badge, Modifier.size(22.dp))
                Spacer(Modifier.width(7.dp))
            }
            PixelText(
                "${t("SERIA", "STREAK", lang)} $streak",
                color = if (streak >= 3) Palette.Accent else Palette.Muted,
                glyphHeight = 9.dp,
            )
        }
    }
}

@Composable
private fun TimeBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(15.dp)
            .background(Palette.Ink)
            .border(2.dp, Palette.SurfaceHigh)
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color)
        )
    }
}

@Composable
private fun QuestionPanel(
    q: TfQuestion,
    phase: TfPhase,
    outcome: TfOutcome,
    gained: Int,
    leveledUp: Boolean,
    lang: UiLanguage,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            .padding(14.dp)
    ) {
        // BoxWithConstraints daje realną szerokość panelu, więc zawijanie tekstu
        // bitmapowego działa tak samo na 360 dp i na tablecie.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inner = maxWidth
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PixelText(
                    t("CZY TO ZNACZY?", "DOES IT MEAN?", lang),
                    color = Palette.Dim,
                    glyphHeight = 8.dp,
                    maxWidthDp = inner,
                )
                Spacer(Modifier.height(14.dp))

                PixelText(
                    q.prompt,
                    color = Palette.Bone,
                    glyphHeight = glyphFor(q.prompt, big = true),
                    maxWidthDp = inner,
                )

                Spacer(Modifier.height(12.dp))
                // Cienka linia rozdzielająca hasło od ocenianej propozycji
                Box(
                    Modifier
                        .width(70.dp)
                        .height(2.dp)
                        .background(Palette.SurfaceHigh)
                )
                Spacer(Modifier.height(12.dp))

                PixelText(
                    q.shown,
                    color = Palette.Accent,
                    glyphHeight = glyphFor(q.shown, big = false),
                    maxWidthDp = inner,
                )
            }

            if (phase == TfPhase.FEEDBACK) {
                Feedback(q = q, outcome = outcome, gained = gained, leveledUp = leveledUp, lang = lang, inner = inner)
            }
        }
    }
}

/** Dobiera wysokość glifu do długości napisu, żeby długie hasła nie zalewały panelu. */
private fun glyphFor(text: String, big: Boolean): Dp = when {
    text.length <= 12 -> if (big) 24.dp else 19.dp
    text.length <= 24 -> if (big) 18.dp else 15.dp
    text.length <= 44 -> if (big) 14.dp else 12.dp
    else -> if (big) 11.dp else 10.dp
}

@Composable
private fun Feedback(
    q: TfQuestion,
    outcome: TfOutcome,
    gained: Int,
    leveledUp: Boolean,
    lang: UiLanguage,
    inner: Dp,
) {
    val right = outcome == TfOutcome.RIGHT
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Surface.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PixelImage(
                pixel(if (right) R.drawable.checkmark else R.drawable.cross),
                Modifier.size(52.dp),
            )
            Spacer(Modifier.height(12.dp))
            PixelText(
                when (outcome) {
                    TfOutcome.RIGHT -> t("DOBRZE!", "CORRECT!", lang)
                    TfOutcome.WRONG -> t("ŹLE!", "WRONG!", lang)
                    TfOutcome.TIMEOUT -> t("ZA WOLNO!", "TOO SLOW!", lang)
                },
                color = if (right) Palette.Accent else Palette.Danger,
                glyphHeight = 17.dp,
                maxWidthDp = inner,
            )
            if (right && gained > 0) {
                Spacer(Modifier.height(9.dp))
                PixelText("+$gained", color = Palette.Gold, glyphHeight = 13.dp)
            }
            if (!right) {
                // Przy pomyłce od razu pokazujemy właściwe tłumaczenie — to jest nauka słówek,
                // a nie tylko punktacja.
                Spacer(Modifier.height(10.dp))
                Text(
                    "${t("Poprawnie", "Correct", lang)}: ${q.correct}",
                    color = Palette.Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(inner),
                )
            }
            if (leveledUp) {
                Spacer(Modifier.height(12.dp))
                PixelImage(pixel(R.drawable.banner_levelup), Modifier.width(inner.coerceAtMost(190.dp)))
            }
        }
    }
}

@Composable
private fun AnswerButton(
    label: String,
    icon: ImageBitmap,
    face: Color,
    shadow: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(shadow)
            .padding(bottom = 4.dp)
            .background(if (enabled) face else Palette.Dim)
            .border(3.dp, Palette.Ink)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PixelImage(icon, Modifier.size(22.dp), alpha = if (enabled) 1f else 0.5f)
            Spacer(Modifier.height(7.dp))
            PixelText(label, color = Palette.Ink, outline = null, glyphHeight = 11.dp)
        }
    }
}

@Composable
private fun TfGameOver(
    score: Int,
    level: Int,
    correctCount: Int,
    bestStreak: Int,
    lang: UiLanguage,
    onAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            // Przewijanie, żeby na niskich ekranach statystyki i przyciski się mieściły (jak w McGameOver).
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PixelImage(pixel(R.drawable.skull), Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        PixelText(t("KONIEC GRY", "GAME OVER", lang), color = Palette.Danger, glyphHeight = 18.dp)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(R.drawable.coin), Modifier.size(26.dp))
            Spacer(Modifier.width(9.dp))
            PixelText("$score", color = Palette.Gold, glyphHeight = 26.dp)
        }
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .border(2.dp, Palette.Ink)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            StatRow(t("Poziom", "Level", lang), "$level", icon = pixel(R.drawable.icon_star))
            StatRow(t("Trafne odpowiedzi", "Correct answers", lang), "$correctCount", icon = pixel(R.drawable.icon_target))
            StatRow(t("Najdłuższa seria", "Best streak", lang), "$bestStreak", icon = pixel(R.drawable.icon_trophy))
        }

        Spacer(Modifier.height(22.dp))
        PrimaryButton(t("JESZCZE RAZ", "PLAY AGAIN", lang), Modifier.fillMaxWidth(), onClick = onAgain)
        Spacer(Modifier.height(10.dp))
        GhostButton(t("DO MENU", "TO MENU", lang), Modifier.fillMaxWidth(), onClick = onMenu)
    }
}

/** Zbiór z jednym (albo zerem) użytecznym hasłem nie pozwala ułożyć fałszywej pary. */
@Composable
private fun NotEnoughEntries(str: Str, lang: UiLanguage, onExit: () -> Unit) {
    ScreenScaffold(
        title = t("PRAWDA / FAŁSZ", "TRUE / FALSE", lang),
        onBack = onExit,
        backLabel = str.back,
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            PixelImage(pixel(R.drawable.lock), Modifier.size(44.dp))
            Spacer(Modifier.height(16.dp))
            PixelText(t("ZA MAŁO HASEŁ", "NOT ENOUGH ENTRIES", lang), color = Palette.Gold, glyphHeight = 13.dp)
            Spacer(Modifier.height(14.dp))
            InfoCard(
                t(
                    "Ten tryb podmienia tłumaczenia między hasłami, więc potrzebuje co najmniej dwóch " +
                        "wypełnionych wierszy. Uzupełnij zestaw w ZESTAWY i wróć.",
                    "This mode swaps translations between entries, so it needs at least two complete rows. " +
                        "Fill the set in under SETS and come back.",
                    lang,
                )
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(t("DO MENU", "TO MENU", lang), Modifier.fillMaxWidth(), onClick = onExit)
        }
    }
}
