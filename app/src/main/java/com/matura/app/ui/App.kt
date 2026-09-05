package com.matura.app.ui

import androidx.activity.compose.BackHandler
import com.matura.app.data.BuiltInSets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.matura.app.data.Repository
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.WordSet
import com.matura.app.learn.Attempt
import com.matura.app.learn.Srs
import com.matura.app.audio.Pronunciation
import com.matura.app.learn.Clock
import com.matura.app.learn.RodzajKroku
import com.matura.app.learn.Sesja
import com.matura.app.model.Direction

sealed interface Screen {
    data object Start : Screen
    data object Menu : Screen
    data object SetPick : Screen
    data class ModePick(val setId: String) : Screen
    data class Play(val setId: String, val mode: GameMode) : Screen
    data object Sets : Screen
    data class Editor(val setId: String?) : Screen
    data object Options : Screen
    data object Achievements : Screen
    data object Scores : Screen
    data object Privacy : Screen
    data object VoiceTest : Screen
    data object TrudneSlowa : Screen
    data object DzisiejszaSesja : Screen
}

@Composable
fun MaturaApp(repo: Repository, onExitApp: () -> Unit) {
    var data by remember { mutableStateOf(repo.load()) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Start)) }
    var mixSet by remember { mutableStateOf<com.matura.app.model.WordSet?>(null) }

    val screen = stack.last()
    val str = remember(data.options.uiLanguage) { Str(data.options.uiLanguage) }

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replaceTop(s: Screen) { stack = stack.dropLast(1) + s }
    fun refresh() { data = repo.load() }

    // Dzien harmonogramu dla calej aplikacji. Liczony raz przy wejsciu — przekroczenie polnocy
    // UTC w trakcie jednej sesji nie ma przestawiac list pod palcami ucznia.
    val dzisiaj = remember { Srs.dayOf(System.currentTimeMillis()) }
    // Klasa Pronunciation istniala juz wczesniej, ale nie miala ANI JEDNEGO wolajacego —
    // "Trudne slowa" sa pierwszym ekranem, ktory czyta haslo na glos. Instancja zyje tak dlugo
    // jak aplikacja i zwalnia zasoby przy wyjsciu z kompozycji.
    val kontekst = LocalContext.current
    val wymowa = remember(kontekst) { Pronunciation(kontekst) }
    DisposableEffect(wymowa) { onDispose { wymowa.release() } }
    val zegar = remember { Clock { System.currentTimeMillis() } }
    // Runda uruchomiona z codziennej sesji jest SPOKOJNA: brief wymaga wariantu bez utraty zyc
    // przy nauce nowych slow. Flaga zyje tu, bo tylko App sklada opcje dla ekranu gry.
    var rundaSpokojna by remember { mutableStateOf(false) }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            // Jedyne miejsce, ktore odsuwa tresc od chromu systemu - stad w dol
            // insety sa juz skonsumowane i ekrany moga liczyc wysokosc normalnie.
            // Pasek stanu: bez tego zegarek systemowy lezal na sercach i amunicji.
            // Pasek nawigacji: bez tego dolny przycisk w MENU, OPCJACH, SETS i
            // edytorze wpadal pod pasek gestow - klawiatura radzila sobie sama,
            // reszta ekranow nie.
            // IME: StartScreen i edytor maja prawdziwe pola tekstowe, wiec przy
            // otwartej klawiaturze systemowej tresc musi sie podniesc.
            // Wyciecie w ekranie: w poziomie sprite'y nie wchodza pod kamere.
            .safeDrawingPadding()
    ) {
        when (val s = screen) {
            Screen.Start -> StartScreen(
                str = str,
                profile = data.profile,
                onPlayAnonymously = { name ->
                    repo.setProfile(data.profile.copy(name = name, anonymous = true))
                    refresh()
                    replaceTop(Screen.Menu)
                },
            )

            Screen.Menu -> MenuScreen(
                str = str,
                profile = data.profile,
                onDaily = { rundaSpokojna = false; push(Screen.DzisiejszaSesja) },
                onHardWords = { push(Screen.TrudneSlowa) },
                onPlay = { rundaSpokojna = false; push(Screen.SetPick) },
                onSets = { push(Screen.Sets) },
                onOptions = { push(Screen.Options) },
                onAchievements = { push(Screen.Achievements) },
                onScores = { push(Screen.Scores) },
                onExit = onExitApp,
            )

            Screen.SetPick -> SetPickScreen(
                str = str,
                sets = data.sets,
                onBack = { pop() },
                onPick = { set ->
                    // Zestaw losowy nie jest zapisany w danych - trzymamy wylosowana
                    // partie tutaj, zeby ModePick i Play widzialy DOKLADNIE ta sama.
                    if (set.id == BuiltInSets.MIX_ID) mixSet = set
                    push(Screen.ModePick(set.id))
                },
            )

            is Screen.ModePick -> ModePickScreen(
                str = str,
                set = if (s.setId == BuiltInSets.MIX_ID) mixSet else data.sets.firstOrNull { it.id == s.setId },
                onBack = { pop() },
                onPickMode = { mode -> replaceTop(Screen.Play(s.setId, mode)) },
            )

            is Screen.Play -> {
                val set = if (s.setId == BuiltInSets.MIX_ID) mixSet else data.sets.firstOrNull { it.id == s.setId }
                if (set == null) {
                    pop()
                } else {
                    // Wszystkie tryby maja ta sama sygnature ekranu; tryb doklejamy
                    // do rekordu tutaj, wiec same ekrany nie musza go znac.
                    val exit: () -> Unit = { stack = listOf(Screen.Menu) }
                    // JEDNO wejscie zapisu proby dla wszystkich czterech trybow. Ekrany dostaja
                    // zwykla lambde, wiec zadne z nich nie siega do repozytorium samo i kazde
                    // zostaje testowalne bez Androida.
                    //
                    // refresh() po zapisie jest CELOWO pominiete: przeladowanie SaveData w trakcie
                    // rundy podmienialoby `data.options` i mape postepu, a te sa kluczami remember
                    // w ekranach gry — czyli kazda poprawna odpowiedz kasowalaby trwajaca gre.
                    // Ekran wyniku i tak czyta swiezy stan, bo finish() wola refresh().
                    val zapiszProbe: (Attempt) -> Unit = { proba ->
                        repo.recordAttempt(proba, System.currentTimeMillis())
                    }
                    // Dzien harmonogramu ustalamy RAZ, przy wejsciu do rundy. Gdyby liczyl sie na
                    // biezaco, przekroczenie polnocy UTC w trakcie gry przestawiloby plan rundy.
                    val dzisiaj = Srs.dayOf(System.currentTimeMillis())
                    val finish: (com.matura.app.model.ScoreRecord) -> Unit = { record ->
                        repo.recordGame(record.copy(mode = s.mode), set.id)
                        refresh()
                    }
                    // Spokojna runda z codziennej sesji dostaje zapas zyc, zeby nauka nowego
                    // slowa nie konczyla sie przegrana. Reszta ustawien bez zmian.
                    val opcjeRundy =
                        if (rundaSpokojna) data.options.copy(lives = maxOf(data.options.lives, 9))
                        else data.options
                    when (s.mode) {
                        GameMode.CLASSIC -> PlayScreen(
                            set = set, options = opcjeRundy, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            // Gra moze sama wlaczyc klawiature telefonu (awaryjna droga glosu);
                            // zapis ta sama droga co z ekranu OPCJE, wiec zostaje na kolejne gry.
                            onOptionsChange = { o: Options -> repo.setOptions(o); refresh() },
                            progress = data.progress,
                            today = dzisiaj,
                            onAttempt = zapiszProbe,
                        )
                        GameMode.TRUE_FALSE -> TrueFalseScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            onAttempt = zapiszProbe,
                        )
                        GameMode.MULTIPLE_CHOICE -> MultipleChoiceScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            onAttempt = zapiszProbe,
                        )
                        GameMode.TIME_ATTACK -> TimeAttackScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            onAttempt = zapiszProbe,
                        )
                    }
                }
            }

            Screen.TrudneSlowa -> TrudneSlowaScreen(
                teksty = TrudneTeksty(
                    tytul = str.hardWords,
                    wroc = str.back,
                    wszystkieDzialy = str.filterAll,
                    mojeZestawy = str.ownSets,
                    kierunekHasloZnaczenie = str.dirTermToDef,
                    kierunekZnaczenieHaslo = str.dirDefToTerm,
                    etykietaLiczby = str.hardWordsCount,
                    powodZalegle = str.hardReasonDue,
                    powodCofniete = str.hardReasonBackToStart,
                    powodPomylki = str.hardReasonMistakes,
                    powodPodpowiedzi = str.hardReasonHints,
                    etykietaPomylki = str.hardCountMistakes,
                    etykietaPodpowiedzi = str.hardCountHints,
                    etykietaPoTerminie = str.hardOverdueDays,
                    terminDzis = str.hardDueToday,
                    wymowa = str.hardListen,
                    cwicz = str.hardPractice,
                    cwiczWszystkie = str.hardPracticeAll,
                    pustoBrakHistorii = str.hardEmptyNoHistory,
                    pustoNicTrudnego = str.hardEmptyNothingHard,
                ),
                zestawy = data.sets,
                postep = data.progress,
                dzis = dzisiaj,
                onBack = { pop() },
                onWymowa = { slowo -> wymowa.play(slowo, data.options.soundEnabled) },
                onCwicz = { trudne ->
                    // Jedno slowo do poprawki gramy na mieszance zbudowanej wokol niego:
                    // plansza potrzebuje kilku hasel, zeby w ogole miec z czego losowac.
                    mixSet = BuiltInSets.randomMix().let { mix ->
                        mix.copy(entries = (listOf(trudne.entry) + mix.entries).distinct().take(20))
                    }
                    rundaSpokojna = true
                    push(Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))
                },
                onCwiczWszystkie = { lista ->
                    mixSet = BuiltInSets.randomMix().copy(entries = lista.map { it.entry }.take(20))
                    rundaSpokojna = true
                    push(Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))
                },
            )

            Screen.DzisiejszaSesja -> {
                // Kierunek losowy to USTAWIENIE, nie kierunek — sesja musi go rozstrzygnac,
                // zanim ulozy plan (Sesja.zaplanuj wprost tego pilnuje).
                val kierunek = if (data.options.direction == Direction.RANDOM) {
                    Direction.TERM_TO_DEF
                } else {
                    data.options.direction
                }
                val idki = remember(data.sets) {
                    data.sets.flatMap { it.entries }.map { it.id }.filter { it.isNotBlank() }
                }
                val plan = remember(data.progress, idki, kierunek, dzisiaj) {
                    Sesja.zaplanuj(data.progress, idki, kierunek, dzisiaj)
                }
                var stanSesji by remember(plan) {
                    mutableStateOf(Sesja.wznow(data.sesja, plan, zegar))
                }
                DzisiejszaSesjaScreen(
                    jezyk = data.options.uiLanguage,
                    plan = plan,
                    stan = stanSesji,
                    podsumowanie = Sesja.podsumuj(plan, stanSesji, data.progress, dzisiaj),
                    czasNaKoniec = Sesja.czasNaKoniec(stanSesji, plan, zegar),
                    wznowiona = data.sesja != null,
                    // Przerwanie ZAPISUJE stan: brief wymaga, zeby przerwa nie kasowala nauki.
                    onPrzerwij = { repo.setSesja(stanSesji); refresh(); pop() },
                    onKrok = { krok ->
                        if (krok.rodzaj == RodzajKroku.POWTORKA || krok.rodzaj == RodzajKroku.GRA) {
                            val cele = krok.cele.toSet()
                            val wpisy = data.sets.flatMap { it.entries }.filter { it.id in cele }
                            mixSet = BuiltInSets.randomMix().copy(entries = wpisy.take(20))
                            rundaSpokojna = krok.spokojny
                            repo.setSesja(stanSesji)
                            push(Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))
                        } else {
                            stanSesji = Sesja.dalej(stanSesji, plan)
                            repo.setSesja(stanSesji)
                        }
                    },
                    onDalej = {
                        stanSesji = Sesja.dalej(stanSesji, plan)
                        repo.setSesja(stanSesji)
                    },
                    onZakoncz = {
                        stanSesji = Sesja.zakoncz(stanSesji, plan)
                        // Skonczona sesja przestaje byc "przerwana" — inaczej jutro proponowaloby
                        // sie wznowienie czegos, co juz sie domknelo.
                        repo.setSesja(null)
                        refresh()
                        pop()
                    },
                )
            }

            Screen.Sets -> SetsScreen(
                str = str,
                sets = data.sets,
                onBack = { pop() },
                onCreate = { push(Screen.Editor(null)) },
                onEdit = { set -> push(Screen.Editor(set.id)) },
                onDelete = { set -> repo.deleteSet(set.id); refresh() },
            )

            is Screen.Editor -> SetEditorScreen(
                str = str,
                existing = s.setId?.let { id -> data.sets.firstOrNull { it.id == id } },
                onBack = { pop() },
                onSave = { set -> repo.upsertSet(set); refresh(); pop() },
            )

            Screen.Privacy -> PrivacyScreen(str = str, onBack = { pop() })

            // Ekran diagnostyczny: przechodzi cala matryce silnikow mowy i mowi, ktora
            // konfiguracja dziala na TYM telefonie. Wchodzi sie z OPCJI.
            Screen.VoiceTest -> VoiceTestScreen(str = str, onBack = { pop() })

            Screen.Options -> OptionsScreen(
                str = str,
                onPrivacy = { push(Screen.Privacy) },
                onVoiceTest = { push(Screen.VoiceTest) },
                options = data.options,
                onBack = { pop() },
                onChange = { o: Options -> repo.setOptions(o); refresh() },
            )

            Screen.Achievements -> AchievementsScreen(
                str = str,
                profile = data.profile,
                onBack = { pop() },
            )

            Screen.Scores -> ScoresScreen(
                str = str,
                data = data,
                onBack = { pop() },
            )
        }
    }
}

internal fun SaveData.defaultProfile(): Profile = profile

internal fun List<WordSet>.playable(): List<WordSet> =
    // Wiersz jest grywalny tylko, gdy OBIE strony po normalizacji nie sa puste — inaczej
    // silnik dostalby haslo bez akceptowanej odpowiedzi (samo interpunkcja) i utknal.
    filter { set ->
        set.entries.any {
            com.matura.app.game.Matching.normalize(it.term).isNotEmpty() &&
                com.matura.app.game.Matching.normalize(it.definition).isNotEmpty()
        }
    }
