package com.matura.app.ui

import androidx.compose.ui.graphics.Color
import com.matura.app.model.Texture

/**
 * The asset pack's own palette: eight colours plus the outline black #101322.
 * Nothing outside this list should appear in the UI, or the pixel art stops
 * looking like it belongs to the interface around it.
 */
object Palette {
    val Ink = Color(0xFF101322)          // outline / darkest
    val Background = Color(0xFF0D0F1A)   // page
    val Surface = Color(0xFF1A1E30)      // raised panel
    val SurfaceHigh = Color(0xFF2B2F4A)  // borders, dividers
    val Dim = Color(0xFF5B6478)          // faintest text
    val Muted = Color(0xFF8F8A76)        // secondary text
    val Bone = Color(0xFFE8E4D2)         // primary text
    val OnDark = Bone
    val Accent = Color(0xFFA8E05A)       // highlight green
    val AccentDim = Color(0xFF7CC242)    // deeper green
    val Gold = Color(0xFFF2B23C)         // level, medals
    val Warning = Gold
    val Danger = Color(0xFFE0524F)       // lost life, errors
    val Purple = Color(0xFF7A5AC9)
    val Cyan = Color(0xFF4AC6E0)
}

data class FieldTheme(
    /** Fallback fill beneath the tiled background. */
    val field: Color,
    val fieldEdge: Color,
    /** Word colour over the field; highlighted words use [Palette.Accent]. */
    val hudInk: Color,
)

fun textureTheme(t: Texture): FieldTheme = when (t) {
    Texture.CLASSIC -> FieldTheme(
        field = Color(0xFF3E7A34),
        fieldEdge = Palette.Ink,
        hudInk = Palette.Bone,
    )
    Texture.NIGHT -> FieldTheme(
        field = Color(0xFF141A2E),
        fieldEdge = Palette.Ink,
        hudInk = Palette.Bone,
    )
    Texture.DESERT -> FieldTheme(
        field = Color(0xFFC2A15C),
        fieldEdge = Palette.Ink,
        hudInk = Palette.Bone,
    )
}
