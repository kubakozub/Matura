package com.matura.app.ui

import androidx.activity.compose.BackHandler
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
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.WordSet

sealed interface Screen {
    data object Start : Screen
    data object Menu : Screen
    data object SetPick : Screen
    data class ModePick(val setId: String) : Screen
    data class Play(val setId: String) : Screen
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
                onPick = { set -> push(Screen.ModePick(set.id)) },
            )

            is Screen.ModePick -> ModePickScreen(
                str = str,
                set = data.sets.firstOrNull { it.id == s.setId },
                onBack = { pop() },
                onClassic = { replaceTop(Screen.Play(s.setId)) },
            )

            is Screen.Play -> {
                val set = data.sets.firstOrNull { it.id == s.setId }
                if (set == null) {
                    pop()
                } else {
                    PlayScreen(
                        set = set,
                        options = data.options,
                        str = str,
                        playerName = data.profile.name,
                        onExit = { stack = listOf(Screen.Start, Screen.Menu) },
                        onFinished = { record ->
                            repo.recordGame(record, set.id)
                            refresh()
                        },
                    )
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
    filter { set -> set.entries.any { it.term.isNotBlank() && it.definition.isNotBlank() } }
