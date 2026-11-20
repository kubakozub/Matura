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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Nazwy klawiszy dla czytnika ekranu.
 *
 * Litery czyta sie same (glif idzie przez PixelText, a ten od 0.9.18 wnosi wlasna
 * semantyke), ale backspace, enter, spacja i strzalki maja glif WYPALONY w bitmapie
 * sprite'a, a PixelImage ustawia contentDescription = null. Te piec klawiszy nie ma wiec
 * skad wziac nazwy i musi ja dostac parametrem — inaczej caly dolny i trzeci rzad to dla
 * TalkBacka "przycisk, przycisk, przycisk".
 *
 * Nazwy ida przez [Str], jak kazdy tekst widziany przez ucznia — inaczej uczen z angielskim
 * interfejsem slyszalby klawisze po polsku. Wolajacy sklada je funkcja [opisyKlawiszy].
 */
data class OpisyKlawiszy(
    val backspace: String,
    val enter: String,
    val spacja: String,
    val kursorWLewo: String,
    val kursorWPrawo: String,
) {
    companion object {
        /** Patrz UWAGA w KDoc klasy: do czasu wpisow w Strings.kt. */
        /**
         * Awaryjny komplet na wypadek wywolania bez [Str] (podglady Compose, testy).
         * Ekrany gry podaja wersje z [opisyKlawiszy], czyli w jezyku interfejsu.
         */
        val DOMYSLNE = OpisyKlawiszy(
            backspace = "Backspace",
            enter = "Enter",
            spacja = "Spacja",
            kursorWLewo = "Kursor w lewo",
            kursorWPrawo = "Kursor w prawo",
        )
    }
}

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
    opisy: OpisyKlawiszy = OpisyKlawiszy.DOMYSLNE,
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
            .background(Palette.Surface)
            .border(3.dp, Palette.Ink)
            // Odsuniecie od paska nawigacji robi safeDrawingPadding() w MaturaApp
            // (wspolnie dla wszystkich ekranow) — tutaj zostaje tylko luz pod
            // dolnym wierszem, ktory i tak jest wyzszy niz reszta klawiatury.
            .padding(horizontal = 3.dp)
            .padding(top = 5.dp, bottom = 9.dp)
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
            SpriteKey(backspace, backspacePressed, weight = 1.6f, opis = opisy.backspace, onClick = onBackspace)
            "ZXCVBNM".forEach { LetterKey(it, cap, capPressed, onChar) }
            SpriteKey(enter, enterPressed, weight = 1.6f, opis = opisy.enter, onClick = onEnter)
        }
        // Dolny pasek: SPACJA na całą szerokość (poza strzałkami). Wagi 93:28 odpowiadają
        // proporcjom sprite'ów, więc piksele nie są rozciągane nierówno.
        Row(Modifier.fillMaxWidth().height(SPACE_ROW_HEIGHT)) {
            SpriteKey(space, spacePressed, weight = 93f, opis = opisy.spacja, onClick = onSpace)
            ArrowKey(
                arrows,
                arrowsPressed,
                weight = 28f,
                opisLewo = opisy.kursorWLewo,
                opisPrawo = opisy.kursorWPrawo,
                onLeft = onLeft,
                onRight = onRight,
            )
        }
    }
}

/**
 * Dolny rzad jest celowo wyzszy niz rzedy liter: lezy najblizej krawedzi ekranu
 * i to w niego testerzy najczesciej nie trafiali.
 */
private val SPACE_ROW_HEIGHT = 56.dp

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
            Modifier.fillMaxSize().padding(top = if (pressed) 5.dp else 0.dp, bottom = if (pressed) 0.dp else 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            PixelText(c.toString(), color = Palette.Ink, outline = null, glyphHeight = 13.dp)
        }
    }
}

/**
 * Klawisz funkcyjny — glif jest już wypalony w sprite'cie.
 *
 * @param opis nazwa dla czytnika ekranu; sprite nie niesie zadnego tekstu, wiec bez niej
 *   klawisz jest niemy (patrz [OpisyKlawiszy]).
 */
@Composable
private fun RowScope.SpriteKey(
    sprite: ImageBitmap,
    spritePressed: ImageBitmap,
    weight: Float,
    opis: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(1.dp)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .semantics { contentDescription = opis },
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
    opisLewo: String,
    opisPrawo: String,
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
                    .semantics { contentDescription = opisLewo }
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = interaction, indication = null) { onRight() }
                    .semantics { contentDescription = opisPrawo }
            )
        }
    }
}

/** Etykiety klawiszy w jezyku interfejsu — jedno miejsce dla wszystkich ekranow z klawiatura. */
fun opisyKlawiszy(str: Str) = OpisyKlawiszy(
    backspace = str.keyBackspace,
    enter = str.keyEnter,
    spacja = str.keySpace,
    kursorWLewo = str.keyCursorLeft,
    kursorWPrawo = str.keyCursorRight,
)
