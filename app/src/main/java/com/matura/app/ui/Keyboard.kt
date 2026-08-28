package com.matura.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp

/**
 * Klawiatura gry — JEDNA rodzina sprite'ów dla wszystkich klawiszy.
 *
 * Każdy klawisz ma stan normalny i wciśnięty (sprite `*_pressed`), więc litery,
 * spacja, enter, backspace i strzałki wyglądają i zachowują się identycznie.
 * Litery rysowane są czcionką `font_8x8` na pustym klawiszu `key_cap`.
 *
 * Sprite'y mają 16 px wysokości: 14 px korpusu + 2 px twardego cienia u dołu,
 * dlatego zawartość klawisza (litera) jest wyśrodkowana w górnych 14/16 wysokości.
 */
@Composable
fun GameKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cap = pixel(com.matura.app.R.drawable.key_cap)
    val capPressed = pixel(com.matura.app.R.drawable.key_cap_pressed)
    val backspace = pixel(com.matura.app.R.drawable.key_backspace)
    val backspacePressed = pixel(com.matura.app.R.drawable.key_backspace_pressed)
    val enter = pixel(com.matura.app.R.drawable.key_enter)
    val enterPressed = pixel(com.matura.app.R.drawable.key_enter_pressed)
    val space = pixel(com.matura.app.R.drawable.key_space)
    val spacePressed = pixel(com.matura.app.R.drawable.key_space_pressed)
    val arrows = pixel(com.matura.app.R.drawable.key_arrows)
    val arrowsPressed = pixel(com.matura.app.R.drawable.key_arrows_pressed)

    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            .padding(horizontal = 3.dp, vertical = 5.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(46.dp)) {
            "QWERTYUIOP".forEach { LetterKey(it, cap, capPressed, onChar) }
        }
        Row(Modifier.fillMaxWidth().height(46.dp)) {
            Box(Modifier.weight(0.5f))
            "ASDFGHJKL".forEach { LetterKey(it, cap, capPressed, onChar) }
            Box(Modifier.weight(0.5f))
        }
        Row(Modifier.fillMaxWidth().height(46.dp)) {
            SpriteKey(backspace, backspacePressed, weight = 1.6f, onClick = onBackspace)
            "ZXCVBNM".forEach { LetterKey(it, cap, capPressed, onChar) }
            SpriteKey(enter, enterPressed, weight = 1.6f, onClick = onEnter)
        }
        // Dolny pasek: SPACJA na całą szerokość (poza strzałkami). Wagi 93:28 odpowiadają
        // proporcjom sprite'ów, więc piksele nie są rozciągane nierówno.
        Row(Modifier.fillMaxWidth().height(50.dp)) {
            SpriteKey(space, spacePressed, weight = 93f, onClick = onSpace)
            ArrowKey(arrows, arrowsPressed, weight = 28f, onLeft = onLeft, onRight = onRight)
        }
    }
}

/** Klawisz litery: sprite klawisza + glif z czcionki pikselowej. */
@Composable
private fun RowScope.LetterKey(
    c: Char,
    cap: ImageBitmap,
    capPressed: ImageBitmap,
    onChar: (Char) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(1.dp)
            .clickable(interactionSource = interaction, indication = null) {
                onChar(c.lowercaseChar())
            },
        contentAlignment = Alignment.Center,
    ) {
        PixelImage(if (pressed) capPressed else cap, Modifier.fillMaxSize())
        // Litera siedzi na korpusie klawisza (górne 14/16 sprite'a), nie na cieniu.
        Box(
            Modifier.fillMaxSize().padding(bottom = if (pressed) 0.dp else 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            PixelText(c.toString(), color = Palette.Ink, outline = null, glyphHeight = 13.dp)
        }
    }
}

/** Klawisz funkcyjny — glif jest już wypalony w sprite'cie. */
@Composable
private fun RowScope.SpriteKey(
    sprite: ImageBitmap,
    spritePressed: ImageBitmap,
    weight: Float,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(1.dp)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PixelImage(if (pressed) spritePressed else sprite, Modifier.fillMaxSize())
    }
}

/**
 * Sprite pokazuje obie strzałki, więc wizualnie to jeden klawisz, ale każda połowa
 * jest osobnym celem dotyku.
 */
@Composable
private fun RowScope.ArrowKey(
    sprite: ImageBitmap,
    spritePressed: ImageBitmap,
    weight: Float,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelImage(if (pressed) spritePressed else sprite, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = interaction, indication = null) { onLeft() }
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = interaction, indication = null) { onRight() }
            )
        }
    }
}
