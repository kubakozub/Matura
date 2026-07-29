package com.verbume.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verbume.app.R
import com.verbume.app.model.Achievement
import com.verbume.app.model.Direction
import com.verbume.app.model.Entry
import com.verbume.app.model.Options
import com.verbume.app.model.Profile
import com.verbume.app.model.SaveData
import com.verbume.app.model.Texture
import com.verbume.app.model.UiLanguage
import com.verbume.app.model.WordSet

// ---- shared input ----------------------------------------------------------

@Composable
fun FieldInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
) {
    Box(
        modifier
            .background(Palette.Ink)
            .border(2.dp, Palette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = Palette.Dim, fontSize = fontSize.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Bone, fontSize = fontSize.sp),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- start -----------------------------------------------------------------

@Composable
fun StartScreen(str: Str, profile: Profile, onPlayAnonymously: (String) -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PixelImage(pixel(R.drawable.logo_verbume), Modifier.fillMaxWidth(0.85f).height(78.dp))
        Spacer(Modifier.height(8.dp))
        PixelText(str.appTagline, color = Palette.Muted, glyphHeight = 9.dp)
        Spacer(Modifier.height(38.dp))

        Column(Modifier.fillMaxWidth()) {
            Text(str.yourName, color = Palette.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            FieldInput(name, { name = it }, placeholder = "Gracz", modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))

        PrimaryButton(str.playAnonymously, modifier = Modifier.fillMaxWidth()) {
            onPlayAnonymously(name.ifBlank { "Gracz" })
        }
        Spacer(Modifier.height(10.dp))
        MenuButton(str.signIn, enabled = false, trailingIcon = pixel(R.drawable.lock), trailing = str.soon) {}
        Spacer(Modifier.height(16.dp))
        InfoCard(str.offlineNote)
    }
}

// ---- menu ------------------------------------------------------------------

@Composable
fun MenuScreen(
    str: Str,
    profile: Profile,
    onPlay: () -> Unit,
    onSets: () -> Unit,
    onOptions: () -> Unit,
    onAchievements: () -> Unit,
    onScores: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        PixelImage(pixel(R.drawable.logo_verbume), Modifier.fillMaxWidth(0.7f).height(58.dp))
        Spacer(Modifier.height(6.dp))
        PixelText(profile.name, color = Palette.Muted, glyphHeight = 10.dp)
        Spacer(Modifier.height(22.dp))

        // Icons follow the asset pack's own menu mapping.
        MenuButton(str.play, icon = pixel(R.drawable.icon_target), onClick = onPlay)
        MenuButton(str.sets, icon = pixel(R.drawable.mode_classic), onClick = onSets)
        MenuButton(str.options, icon = pixel(R.drawable.icon_settings), onClick = onOptions)
        MenuButton(str.achievements, icon = pixel(R.drawable.medal_gold), onClick = onAchievements)
        MenuButton(str.scores, icon = pixel(R.drawable.icon_trophy), onClick = onScores)
        MenuButton(str.exit, icon = pixel(R.drawable.cross), onClick = onExit)
    }
}

// ---- set picking -----------------------------------------------------------

@Composable
fun SetPickScreen(
    str: Str,
    sets: List<WordSet>,
    onBack: () -> Unit,
    onPick: (WordSet) -> Unit,
) {
    ScreenScaffold(str.chooseSet, onBack, str.back) {
        val usable = sets.playable()
        val star = pixel(R.drawable.icon_star)
        LazyColumn(Modifier.fillMaxSize()) {
            items(usable, key = { it.id }) { set ->
                MenuButton(
                    label = set.title,
                    subtitle = "${set.entries.size} ${str.entriesLabel} · ${set.termLanguage} > ${set.definitionLanguage}",
                    icon = star,
                    trailing = if (set.builtIn) str.builtIn else null,
                    onClick = { onPick(set) },
                )
            }
            if (usable.isEmpty()) {
                item { InfoCard(str.needTwoEntries) }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
fun ModePickScreen(
    str: Str,
    set: WordSet?,
    onBack: () -> Unit,
    onClassic: () -> Unit,
) {
    ScreenScaffold(str.chooseMode, onBack, str.back) {
        val lock = pixel(R.drawable.lock)
        Column(Modifier.fillMaxSize()) {
            if (set != null) {
                PixelText(set.title, color = Palette.Bone, glyphHeight = 11.dp, maxWidthDp = 300.dp)
                Spacer(Modifier.height(12.dp))
            }
            MenuButton(
                str.classic,
                subtitle = str.classicDesc,
                icon = pixel(R.drawable.mode_classic),
                onClick = onClassic,
            )
            MenuButton("TRUE / FALSE", enabled = false, icon = pixel(R.drawable.mode_truefalse), trailingIcon = lock) {}
            MenuButton("MULTIPLE CHOICE", enabled = false, icon = pixel(R.drawable.mode_multiple), trailingIcon = lock) {}
            MenuButton("TIME ATTACK", enabled = false, icon = pixel(R.drawable.mode_timeattack), trailingIcon = lock) {}
            Spacer(Modifier.height(12.dp))
            InfoCard(str.modeLockedNote)
        }
    }
}

// ---- sets ------------------------------------------------------------------

@Composable
fun SetsScreen(
    str: Str,
    sets: List<WordSet>,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (WordSet) -> Unit,
    onDelete: (WordSet) -> Unit,
) {
    ScreenScaffold(str.sets, onBack, str.back) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.AccentDim)
                    .padding(bottom = 4.dp)
                    .background(Palette.Accent)
                    .border(3.dp, Palette.Ink)
                    .clickable { onCreate() }
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                PixelImage(pixel(R.drawable.icon_plus), Modifier.size(15.dp))
                Spacer(Modifier.width(9.dp))
                PixelText(str.createSet, color = Palette.Ink, outline = null, glyphHeight = 11.dp)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(sets, key = { it.id }) { set ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Palette.Surface)
                            .border(3.dp, Palette.SurfaceHigh)
                            .padding(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PixelText(
                                    set.title,
                                    color = Palette.Bone,
                                    glyphHeight = 11.dp,
                                    maxWidthDp = 220.dp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (set.builtIn) Pill(str.builtIn, Palette.Accent)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "${set.entries.size} ${str.entriesLabel} · ${set.timesPlayed} ${str.playedLabel} · ${set.termLanguage} > ${set.definitionLanguage}",
                                color = Palette.Muted,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(11.dp))
                            Row {
                                Row(
                                    Modifier
                                        .background(Palette.Surface)
                                        .border(3.dp, Palette.SurfaceHigh)
                                        .clickable { onEdit(set) }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PixelImage(pixel(R.drawable.icon_edit), Modifier.size(14.dp))
                                    Spacer(Modifier.width(7.dp))
                                    PixelText(str.editSet, color = Palette.Bone, glyphHeight = 10.dp)
                                }
                                if (!set.builtIn) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier
                                            .background(Palette.Surface)
                                            .border(3.dp, Palette.Danger)
                                            .clickable { onDelete(set) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            PixelImage(pixel(R.drawable.icon_trash), Modifier.size(14.dp))
                                            Spacer(Modifier.width(7.dp))
                                            PixelText(str.delete, color = Palette.Danger, glyphHeight = 10.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun SetEditorScreen(
    str: Str,
    existing: WordSet?,
    onBack: () -> Unit,
    onSave: (WordSet) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var termLang by remember { mutableStateOf(existing?.termLanguage ?: "English") }
    var defLang by remember { mutableStateOf(existing?.definitionLanguage ?: "Polski") }
    val rows = remember {
        (existing?.entries?.takeIf { it.isNotEmpty() } ?: List(5) { Entry("", "") })
            .map { it.term to it.definition }
            .toMutableStateList()
    }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(if (existing == null) str.createSet else str.editSet, onBack, str.back) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(str.setTitle, color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(5.dp))
                FieldInput(title, { title = it }, placeholder = "...", modifier = Modifier.fillMaxWidth(), fontSize = 16)
                Spacer(Modifier.height(14.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PixelText(str.terms, color = Palette.Accent, glyphHeight = 9.dp)
                        Spacer(Modifier.height(4.dp))
                        FieldInput(termLang, { termLang = it }, modifier = Modifier.fillMaxWidth(), fontSize = 12)
                    }
                    Box(
                        Modifier
                            .padding(horizontal = 7.dp)
                            .background(Palette.Surface)
                            .border(2.dp, Palette.SurfaceHigh)
                            .clickable {
                                val t = termLang; termLang = defLang; defLang = t
                                for (i in rows.indices) rows[i] = rows[i].second to rows[i].first
                            }
                            .padding(horizontal = 9.dp, vertical = 9.dp)
                    ) {
                        PixelText("<>", color = Palette.Bone, glyphHeight = 11.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        PixelText(str.definitions, color = Palette.Accent, glyphHeight = 9.dp)
                        Spacer(Modifier.height(4.dp))
                        FieldInput(defLang, { defLang = it }, modifier = Modifier.fillMaxWidth(), fontSize = 12)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(str.swapLanguages, color = Palette.Dim, fontSize = 10.sp)
                Spacer(Modifier.height(12.dp))
            }

            itemsIndexed(rows) { index, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        color = Palette.Dim,
                        fontSize = 11.sp,
                        modifier = Modifier.width(20.dp),
                    )
                    FieldInput(row.first, { rows[index] = it to rows[index].second }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    FieldInput(row.second, { rows[index] = rows[index].first to it }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .clickable { if (rows.size > 1) rows.removeAt(index) }
                            .padding(6.dp)
                    ) {
                        PixelImage(pixel(R.drawable.cross), Modifier.size(15.dp))
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                GhostButton(str.addRow) { rows.add("" to "") }
                Spacer(Modifier.height(10.dp))
                InfoCard(str.commaHint)
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.Danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(str.save, modifier = Modifier.fillMaxWidth()) {
                    val entries = rows
                        .map { Entry(it.first.trim(), it.second.trim()) }
                        .filter { it.term.isNotBlank() && it.definition.isNotBlank() }
                    if (entries.isEmpty()) {
                        error = str.needTwoEntries
                    } else {
                        onSave(
                            WordSet(
                                id = existing?.id ?: "user-${System.currentTimeMillis()}",
                                title = title.ifBlank { "Bez tytułu" },
                                termLanguage = termLang.ifBlank { "?" },
                                definitionLanguage = defLang.ifBlank { "?" },
                                entries = entries,
                                builtIn = existing?.builtIn ?: false,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                                timesPlayed = existing?.timesPlayed ?: 0,
                            )
                        )
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ---- options ---------------------------------------------------------------

@Composable
fun OptionsScreen(
    str: Str,
    options: Options,
    onBack: () -> Unit,
    onChange: (Options) -> Unit,
) {
    ScreenScaffold(str.options, onBack, str.back) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                OptionRow(
                    str.sound,
                    icon = if (options.soundEnabled) R.drawable.icon_sound_on else R.drawable.icon_sound_off,
                ) {
                    Toggle(options.soundEnabled) { onChange(options.copy(soundEnabled = it)) }
                }
                OptionRow(str.lives, icon = R.drawable.heart) {
                    Stepper(options.lives, 1, 9) { onChange(options.copy(lives = it)) }
                }
                OptionRow(str.texture, icon = R.drawable.icon_palette) {
                    Row {
                        Texture.entries.forEach { t ->
                            ChoiceChip(
                                label = when (t) {
                                    Texture.CLASSIC -> "GRASS"
                                    Texture.NIGHT -> "NIGHT"
                                    Texture.DESERT -> "SAND"
                                },
                                selected = options.texture == t,
                            ) { onChange(options.copy(texture = t)) }
                            Spacer(Modifier.width(5.dp))
                        }
                    }
                }
                OptionRow(str.language, icon = R.drawable.icon_globe) {
                    Row {
                        UiLanguage.entries.forEach { l ->
                            ChoiceChip(l.name, options.uiLanguage == l) {
                                onChange(options.copy(uiLanguage = l))
                            }
                            Spacer(Modifier.width(5.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(str.direction, color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                Column {
                    DirectionRow(str.dirTermToDef, options.direction == Direction.TERM_TO_DEF) {
                        onChange(options.copy(direction = Direction.TERM_TO_DEF))
                    }
                    DirectionRow(str.dirDefToTerm, options.direction == Direction.DEF_TO_TERM) {
                        onChange(options.copy(direction = Direction.DEF_TO_TERM))
                    }
                    DirectionRow(str.dirRandom, options.direction == Direction.RANDOM) {
                        onChange(options.copy(direction = Direction.RANDOM))
                    }
                }
                Spacer(Modifier.height(16.dp))
                InfoCard(str.soundNote)
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, icon: Int, control: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelImage(pixel(icon), Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = Palette.Bone, fontSize = 14.sp)
        }
        control()
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .background(if (on) Palette.Accent else Palette.Surface)
            .border(2.dp, Palette.Ink)
            .clickable { onChange(!on) }
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        PixelText(if (on) "ON" else "OFF", color = if (on) Palette.Ink else Palette.Muted, outline = null, glyphHeight = 9.dp)
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("-") { if (value > min) onChange(value - 1) }
        PixelText("$value", color = Palette.Bone, glyphHeight = 13.dp, modifier = Modifier.padding(horizontal = 14.dp))
        StepButton("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Palette.Surface)
            .border(2.dp, Palette.SurfaceHigh)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        PixelText(label, color = Palette.Bone, glyphHeight = 13.dp)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Palette.Accent else Palette.Surface)
            .border(2.dp, if (selected) Palette.Ink else Palette.SurfaceHigh)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        PixelText(label, color = if (selected) Palette.Ink else Palette.Muted, outline = null, glyphHeight = 8.dp)
    }
}

@Composable
private fun DirectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (selected) Palette.Surface else Color.Transparent)
            .border(2.dp, if (selected) Palette.SurfaceHigh else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            PixelImage(pixel(R.drawable.checkmark), Modifier.size(15.dp))
        } else {
            Spacer(Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Palette.Bone else Palette.Muted, fontSize = 13.sp)
    }
}

// ---- achievements ----------------------------------------------------------

@Composable
fun AchievementsScreen(str: Str, profile: Profile, onBack: () -> Unit) {
    ScreenScaffold(str.achievements, onBack, str.back) {
        val star = pixel(R.drawable.icon_star)
        val lock = pixel(R.drawable.lock)
        val isPl = str.play == "GRAJ"
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface)
                        .border(3.dp, Palette.SurfaceHigh)
                        .padding(14.dp)
                ) {
                    Column {
                        StatRow(str.gamesPlayed, "${profile.gamesPlayed}", pixel(R.drawable.icon_clock))
                        StatRow(str.totalKills, "${profile.totalKills}", pixel(R.drawable.skull))
                        StatRow(str.bestScore, "${profile.bestScore}", pixel(R.drawable.coin))
                        StatRow(str.bestStreakLabel, "${profile.bestStreak}", pixel(R.drawable.badge_x3))
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            items(Achievement.entries.toList()) { a ->
                val got = a.unlockedBy(profile)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(if (got) Palette.Surface else Palette.Surface.copy(alpha = 0.45f))
                        .border(2.dp, if (got) Palette.SurfaceHigh else Palette.Ink)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelImage(if (got) star else lock, Modifier.size(22.dp), alpha = if (got) 1f else 0.6f)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            PixelText(
                                if (isPl) a.titlePl else a.titleEn,
                                color = if (got) Palette.Bone else Palette.Dim,
                                glyphHeight = 10.dp,
                                maxWidthDp = 200.dp,
                            )
                            Text(
                                if (isPl) a.descPl else a.descEn,
                                color = Palette.Muted,
                                fontSize = 11.sp,
                            )
                        }
                        if (got) Pill(str.unlocked, Palette.Accent)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---- scores ----------------------------------------------------------------

@Composable
fun ScoresScreen(str: Str, data: SaveData, onBack: () -> Unit) {
    ScreenScaffold(str.scores, onBack, str.back) {
        val topSet = data.sets.maxByOrNull { it.timesPlayed }
        val medals = listOf(
            pixel(R.drawable.medal_gold),
            pixel(R.drawable.medal_silver),
            pixel(R.drawable.medal_bronze),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface)
                        .border(3.dp, Palette.SurfaceHigh)
                        .padding(14.dp)
                ) {
                    Column {
                        StatRow(str.gamesPlayed, "${data.profile.gamesPlayed}", pixel(R.drawable.icon_clock))
                        StatRow(str.bestScore, "${data.profile.bestScore}", pixel(R.drawable.crown))
                        StatRow(
                            str.topSet,
                            if (topSet != null && topSet.timesPlayed > 0) topSet.title else "-",
                            pixel(R.drawable.icon_trophy),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                PixelText(str.bestRuns, color = Palette.Accent, glyphHeight = 10.dp)
                Spacer(Modifier.height(7.dp))
            }
            if (data.scores.isEmpty()) {
                item { InfoCard(str.noScores) }
            }
            itemsIndexed(data.scores.sortedByDescending { it.score }) { i, s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(Palette.Surface)
                        .border(2.dp, if (i == 0) Palette.Gold else Palette.SurfaceHigh)
                        .padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (i < 3) {
                        PixelImage(medals[i], Modifier.size(24.dp))
                    } else {
                        PixelText("${i + 1}", color = Palette.Dim, glyphHeight = 9.dp, modifier = Modifier.width(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.setTitle, color = Palette.Bone, fontSize = 13.sp)
                        Text(
                            "LVL ${s.level} · ${str.killsLabel} ${s.kills} · x${s.bestStreak}",
                            color = Palette.Muted,
                            fontSize = 10.sp,
                        )
                    }
                    PixelText("${s.score}", color = Palette.Gold, glyphHeight = 13.dp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
