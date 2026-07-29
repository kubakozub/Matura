package com.verbume.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The restricted keyboard from the design document — A-Z, space, backspace and a
 * cursor block, nothing else. Letter caps are drawn to match the pack's key styling
 * (bone face, ink outline, a hard shadow underneath); the four function keys are
 * sprites from the pack, which already include their own key cap.
 */
@Composable
fun GameKeyboard(
    sprites: Sprites,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyBackspace = pixel(com.verbume.app.R.drawable.key_backspace)
    val keyEnter = pixel(com.verbume.app.R.drawable.key_enter)
    val keySpace = pixel(com.verbume.app.R.drawable.key_space)
    val keyArrows = pixel(com.verbume.app.R.drawable.key_arrows)

    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            .padding(horizontal = 3.dp, vertical = 5.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            "QWERTYUIOP".forEach { LetterKey(it, onChar) }
        }
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            Box(Modifier.weight(0.5f))
            "ASDFGHJKL".forEach { LetterKey(it, onChar) }
            Box(Modifier.weight(0.5f))
        }
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            SpriteKey(keyBackspace, weight = 1.6f, onClick = onBackspace)
            "ZXCVBNM".forEach { LetterKey(it, onChar) }
            SpriteKey(keyEnter, weight = 1.6f, onClick = onEnter)
        }
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            Box(Modifier.weight(0.7f))
            SpriteKey(keySpace, weight = 5.5f, onClick = onSpace)
            ArrowKey(keyArrows, weight = 2.2f, onLeft = onLeft, onRight = onRight)
            Box(Modifier.weight(0.7f))
        }
    }
}

@Composable
private fun RowScope.LetterKey(c: Char, onChar: (Char) -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(2.dp)
            .background(Palette.Muted)          // hard shadow, like the pack's key caps
            .padding(bottom = 4.dp)
            .background(Palette.Bone)
            .border(2.dp, Palette.Ink)
            .clickable { onChar(c.lowercaseChar()) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            c.toString(),
            color = Palette.Ink,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RowScope.SpriteKey(sprite: ImageBitmap, weight: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(2.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PixelImage(sprite, Modifier.fillMaxSize().padding(1.dp))
    }
}

/**
 * The pack ships one sprite showing both arrows, so it stays one key visually while
 * each half is its own tap target.
 */
@Composable
private fun RowScope.ArrowKey(
    sprite: ImageBitmap,
    weight: Float,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelImage(sprite, Modifier.fillMaxSize().padding(1.dp))
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().clickable { onLeft() })
            Box(Modifier.weight(1f).fillMaxHeight().clickable { onRight() })
        }
    }
}
