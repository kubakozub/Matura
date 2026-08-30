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
import com.matura.app.data.Repository
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.WordSet

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
                onPlay = { push(Screen.SetPick) },
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
                    val finish: (com.matura.app.model.ScoreRecord) -> Unit = { record ->
                        repo.recordGame(record.copy(mode = s.mode), set.id)
                        refresh()
                    }
                    when (s.mode) {
                        GameMode.CLASSIC -> PlayScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                        )
                        GameMode.TRUE_FALSE -> TrueFalseScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                        )
                        GameMode.MULTIPLE_CHOICE -> MultipleChoiceScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                        )
                        GameMode.TIME_ATTACK -> TimeAttackScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                        )
                    }
                }
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

            Screen.Options -> OptionsScreen(
                str = str,
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
