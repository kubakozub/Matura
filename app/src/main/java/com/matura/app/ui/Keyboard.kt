package com.matura.app.ui

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
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyBackspace = pixel(com.matura.app.R.drawable.key_backspace)
    val keyEnter = pixel(com.matura.app.R.drawable.key_enter)
    val keySpace = pixel(com.matura.app.R.drawable.key_space)
    val keyArrows = pixel(com.matura.app.R.drawable.key_arrows)

    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            // Tester zglosil, ze spacja "jest minimalnie za nisko" i trudno w nia
            // trafic — zjadal ja pasek gestow. Odsuniecie od paska nawigacji robi
            // teraz safeDrawingPadding() w MaturaApp, wspolnie dla wszystkich
            // ekranow; tutaj zostaje tylko luz pod dolnym wierszem, ktory i tak
            // jest wyzszy niz reszta klawiatury.
            .padding(horizontal = 3.dp)
            .padding(top = 5.dp, bottom = 9.dp)
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
        Row(Modifier.fillMaxWidth().height(SPACE_ROW_HEIGHT)) {
            SpriteKey(keySpace, weight = 6.6f, onClick = onSpace)
            ArrowKey(keyArrows, weight = 2.4f, onLeft = onLeft, onRight = onRight)
        }
    }
}

/**
 * The bottom row is deliberately taller than the letter rows: it is the row closest to
 * the edge of the screen and the one testers missed most often.
 */
private val SPACE_ROW_HEIGHT = 56.dp

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
        PixelText(c.toString(), color = Palette.Ink, outline = null, glyphHeight = 13.dp)
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
