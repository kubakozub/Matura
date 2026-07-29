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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything is square-edged and hard-bordered so it sits with the pixel art. */
val PixelFamily = FontFamily.Monospace

@Composable
fun ScreenScaffold(
    title: String?,
    onBack: (() -> Unit)?,
    backLabel: String = "Back",
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier
                        .background(Palette.Surface)
                        .border(2.dp, Palette.SurfaceHigh)
                        .clickable { onBack() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("< $backLabel", color = Palette.Bone, fontSize = 13.sp, fontFamily = PixelFamily)
                }
                Spacer(Modifier.width(12.dp))
            }
            if (title != null) {
                Text(
                    title,
                    color = Palette.Accent,
                    fontSize = 18.sp,
                    fontFamily = PixelFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun MenuButton(
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: String? = null,
    icon: ImageBitmap? = null,
    trailingIcon: ImageBitmap? = null,
    onClick: () -> Unit,
) {
    val ink = if (enabled) Palette.Bone else Palette.Dim
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (enabled) Palette.Surface else Palette.Surface.copy(alpha = 0.5f))
            .border(3.dp, Palette.SurfaceHigh)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (icon != null) {
                PixelImage(icon, Modifier.size(26.dp), alpha = if (enabled) 1f else 0.45f)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = ink,
                    fontSize = 15.sp,
                    fontFamily = PixelFamily,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = Palette.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                PixelImage(trailingIcon, Modifier.size(20.dp))
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(trailing, color = Palette.Muted, fontSize = 11.sp, fontFamily = PixelFamily)
            }
        }
    }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .background(Palette.AccentDim)
            .padding(bottom = 4.dp)
            .background(if (enabled) Palette.Accent else Palette.Dim)
            .border(3.dp, Palette.Ink)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Palette.Ink,
            fontWeight = FontWeight.Bold,
            fontFamily = PixelFamily,
            fontSize = 14.sp,
        )
    }
}

@Composable
fun GhostButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(Palette.Surface)
            .border(3.dp, Palette.SurfaceHigh)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.Bone, fontSize = 13.sp, fontFamily = PixelFamily)
    }
}

@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .border(2.dp, Palette.SurfaceHigh)
            .padding(13.dp)
    ) {
        Text(text, color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
fun StatRow(label: String, value: String, icon: ImageBitmap? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                PixelImage(icon, Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(label, color = Palette.Muted, fontSize = 13.sp)
        }
        Text(value, color = Palette.Bone, fontSize = 14.sp, fontFamily = PixelFamily, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .background(Palette.Ink)
            .border(2.dp, color)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontFamily = PixelFamily, fontWeight = FontWeight.Bold)
    }
}
