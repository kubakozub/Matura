package com.matura.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.matura.app.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap font from the asset pack (`font_8x8.png`, documented in `assets/font-layout.md`).
 *
 * Sheet geometry, taken verbatim from that document: 16x16 cells laid out 16 columns by
 * 6 rows, 93 glyphs, glyph field 5x7 at offset (5,4) inside its cell, glyph index
 * `row * 16 + col`, advance 6 px proportional. Diacritics live at y 1..3 and ogonki at
 * y 11..13, both inside the cell, so 16 px is a safe line height.
 *
 * The sheet is a single colour, so text is tinted with [ColorFilter.tint] and the
 * in-game outline is the same string drawn once at (+1,+1) underneath, as the pack
 * prescribes.
 */
class PixelFont(private val sheet: ImageBitmap) {

    companion object {
        const val CELL = 16
        const val OFFSET_X = 5
        const val OFFSET_Y = 4
        const val GLYPH_W = 5
        const val GLYPH_H = 7
        const val ADVANCE = 6
        const val ADVANCE_MONO = 8
        const val LINE_HEIGHT = 16

        /** Glyph order straight out of font-layout.md, row by row. */
        private const val ORDER =
            "ABCDEFGHIJKLMNOP" +
            "QRSTUVWXYZabcdef" +
            "ghijklmnopqrstuv" +
            "wxyz0123456789ĄĆ" +
            "ĘŁŃÓŚŹŻąćęłńóśźż" +
            ".,!?:/-_×∞ +'"

        private val INDEX: Map<Char, Int> =
            ORDER.withIndex().associate { (i, c) -> c to i }

        /**
         * Characters the sheet does not carry, mapped to the closest one it does.
         * Set titles use "·" and prose uses typographic dashes and quotes; without
         * this they would silently turn into blanks.
         */
        private val SUBSTITUTE: Map<Char, Char> = mapOf(
            '·' to '-', '•' to '-', '—' to '-', '–' to '-', '‑' to '-',
            '„' to ',', '”' to '\'', '“' to '\'', '"' to '\'', '\'' to '\'',
            '‚' to ',', '’' to '\'', '‘' to '\'',
            '(' to '/', ')' to '/', '[' to '/', ']' to '/',
            ';' to ',', '>' to '/', '<' to '/', '=' to '-',
            '&' to '-', '%' to '/', '#' to '-', '@' to '-', '*' to 'x',
            '∙' to '-', '…' to '.', '\t' to ' ',
        )

        val SUPPORTED: Set<Char> = ORDER.toSet()
    }

    private fun glyphIndex(c: Char): Int {
        INDEX[c]?.let { return it }
        SUBSTITUTE[c]?.let { s -> INDEX[s]?.let { return it } }
        // last resort: strip an unknown accent by falling back to the bare letter
        INDEX[c.lowercaseChar()]?.let { return it }
        INDEX[c.uppercaseChar()]?.let { return it }
        return INDEX[' ']!!
    }

    fun advance(mono: Boolean) = if (mono) ADVANCE_MONO else ADVANCE

    /** Ink width of [text] in pixels at [scale]. */
    fun widthPx(text: String, scale: Float, mono: Boolean = false): Float {
        if (text.isEmpty()) return 0f
        return (text.length * advance(mono) - (advance(mono) - GLYPH_W)) * scale
    }

    fun lineHeightPx(scale: Float) = LINE_HEIGHT * scale

    /**
     * Draws one line. [x] and [y] are the top-left of the *ink*, not of the cell, so
     * text lines up with other elements without the caller subtracting bearings.
     */
    fun DrawScope.draw(
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        color: Color,
        mono: Boolean = false,
    ) {
        val cell = (CELL * scale).roundToInt()
        val step = advance(mono) * scale
        val tint = ColorFilter.tint(color, BlendMode.SrcIn)
        var penX = x - OFFSET_X * scale
        val top = (y - OFFSET_Y * scale).roundToInt()
        for (ch in text) {
            val i = glyphIndex(ch)
            if (ch != ' ') {
                val col = i % 16
                val row = i / 16
                drawImage(
                    image = sheet,
                    srcOffset = IntOffset(col * CELL, row * CELL),
                    srcSize = IntSize(CELL, CELL),
                    dstOffset = IntOffset(penX.roundToInt(), top),
                    dstSize = IntSize(cell, cell),
                    colorFilter = tint,
                    filterQuality = FilterQuality.None,
                )
            }
            penX += step
        }
    }

    /** Greedy word wrap to [maxWidth] pixels. Never splits a word unless it alone overflows. */
    fun wrap(text: String, maxWidth: Float, scale: Float, mono: Boolean = false): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (widthPx(candidate, scale, mono) <= maxWidth || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word)
                }
            }
            out.add(line.toString())
        }
        return out
    }
}

/** Draws [text] with the pack's prescribed outline: the same string at (+1,+1) underneath. */
fun DrawScope.drawPixelText(
    font: PixelFont,
    text: String,
    x: Float,
    y: Float,
    scale: Float,
    color: Color,
    outline: Color? = Palette.Ink,
    mono: Boolean = false,
) {
    with(font) {
        if (outline != null) draw(text, x + scale, y + scale, scale, outline, mono)
        draw(text, x, y, scale, color, mono)
    }
}

@Composable
fun rememberPixelFont(): PixelFont {
    val sheet = ImageBitmap.imageResource(R.drawable.font_8x8)
    return remember(sheet) { PixelFont(sheet) }
}

/**
 * Integer pixel scale for a requested glyph height. Pixel art only stays crisp at whole
 * multiples, so this rounds rather than using the exact density figure.
 */
@Composable
fun pixelScaleFor(glyphHeight: Dp): Float {
    val density = LocalDensity.current
    return with(density) {
        max(1, (glyphHeight.toPx() / PixelFont.GLYPH_H).roundToInt()).toFloat()
    }
}

/**
 * Text drawn with the bitmap font inside a normal Compose layout.
 *
 * [maxWidthDp] enables word wrapping; without it the text is a single line. Long prose
 * still reads better in the system font, so this is meant for labels, headings and
 * anything short enough to control.
 *
 * @param opis nazwa dla czytnika ekranu. Domyslnie sam [text], bo napis rysowany po Canvas
 *   nie trafia do semantyki i bez tego TalkBack czytal z przyciskow MENU samo "przycisk" —
 *   6 z 8 pozycji nie mialo ZADNEJ nazwy, a ostatnia nieopisana pozycja to WYJSCIE.
 *   `null` znaczy "glif czysto dekoracyjny, etykiete niesie rodzic" i sluzy do tego, zeby
 *   czytnik nie odczytywal tej samej rzeczy dwa razy.
 */
@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Bone,
    outline: Color? = Palette.Ink,
    glyphHeight: Dp = 11.dp,
    mono: Boolean = false,
    maxWidthDp: Dp? = null,
    opis: String? = text,
    font: PixelFont = rememberPixelFont(),
) {
    val scale = pixelScaleFor(glyphHeight)
    val density = LocalDensity.current
    val maxWidthPx = maxWidthDp?.let { with(density) { it.toPx() } }
    val lines = remember(text, scale, maxWidthPx, mono) {
        if (maxWidthPx != null) font.wrap(text, maxWidthPx, scale, mono) else listOf(text)
    }
    val widthPx = lines.maxOfOrNull { font.widthPx(it, scale, mono) } ?: 0f
    val lineH = font.lineHeightPx(scale)
    val heightPx = lineH * lines.size
    val w = with(density) { (widthPx + scale * 2).toDp() }
    val h = with(density) { heightPx.toDp() }

    // Semantyka siedzi w JEDNYM miejscu, a nie przy kazdym z 92 wywolan: klikalny rodzic
    // (przycisk menu, klawisz, plakietka) scala semantyke dzieci, wiec opis napisu staje sie
    // nazwa przycisku. Dopisywanie go recznie przy kazdym wywolaniu skonczylo sie tym, ze
    // przez czternascie wersji nie dopisal go nikt.
    val semantyka = if (opis != null) Modifier.semantics { contentDescription = opis } else Modifier

    Canvas(modifier.then(semantyka).then(Modifier.size(w, h))) {
        lines.forEachIndexed { i, line ->
            drawPixelText(
                font = font,
                text = line,
                x = 0f,
                y = i * lineH + (lineH - PixelFont.GLYPH_H * scale) / 2f,
                scale = scale,
                color = color,
                outline = outline,
                mono = mono,
            )
        }
    }
}
