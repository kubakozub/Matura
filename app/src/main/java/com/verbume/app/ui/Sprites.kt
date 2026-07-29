package com.verbume.app.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.verbume.app.R
import com.verbume.app.model.Texture
import kotlin.math.roundToInt

/**
 * Pixel-art sprites from the Verbume asset pack.
 *
 * The pack ships 1x (16x16 base grid) for the game and 4x for previews, and states
 * plainly: scale nearest-neighbour only. So the 1x files live in `drawable-nodpi`,
 * where Android will not resample them on load, and every draw call passes
 * [FilterQuality.None]. Any smoothing here would turn crisp pixel art to mush.
 */
class Sprites(
    val turretBase: ImageBitmap,
    val turretBarrel: ImageBitmap,
    val muzzleFlash: ImageBitmap,
    val bulletBasic: ImageBitmap,
    val bulletMid: ImageBitmap,
    val bulletHeavy: ImageBitmap,
    val crateAmmo: ImageBitmap,
    val armorPlate: ImageBitmap,
    val hitFlash: ImageBitmap,
    val explosion: ImageBitmap,
    val heart: ImageBitmap,
    val heartEmpty: ImageBitmap,
    val coin: ImageBitmap,
    val iconTarget: ImageBitmap,
    val badgeX2: ImageBitmap,
    val badgeX3: ImageBitmap,
    val badgeX5: ImageBitmap,
    val cloud: ImageBitmap,
    val rock: ImageBitmap,
    val tileGrass: ImageBitmap,
    val tileNight: ImageBitmap,
    val tileSand: ImageBitmap,
    val slime: ImageBitmap,
    val slimePurple: ImageBitmap,
    val slimeRed: ImageBitmap,
    val bat: ImageBitmap,
    val spider: ImageBitmap,
    val ghost: ImageBitmap,
    val brute: ImageBitmap,
    val boss: ImageBitmap,
) {
    fun tile(t: Texture): ImageBitmap = when (t) {
        Texture.CLASSIC -> tileGrass
        Texture.NIGHT -> tileNight
        Texture.DESERT -> tileSand
    }

    fun badge(multiplier: Int): ImageBitmap? = when {
        multiplier >= 5 -> badgeX5
        multiplier >= 3 -> badgeX3
        multiplier >= 2 -> badgeX2
        else -> null
    }

    /**
     * The pack describes the colour variants as difficulty tiers, so the body is
     * chosen purely by the level a monster spawned at. Armour is never baked into
     * the body — it is the separate [armorPlate] overlay, which the pack calls
     * "pancerz (nakładka)". Keeping it separate means losing the armour is visible
     * on every monster type, not just one.
     */
    fun monster(level: Int, variant: Int): ImageBitmap = when {
        level <= 2 -> slime
        level <= 4 -> if (variant % 2 == 0) slime else slimePurple
        level <= 6 -> if (variant % 2 == 0) slimePurple else slimeRed
        level <= 8 -> if (variant % 2 == 0) slimeRed else bat
        level <= 10 -> if (variant % 2 == 0) bat else spider
        level <= 12 -> if (variant % 2 == 0) spider else ghost
        else -> if (variant % 2 == 0) brute else boss
    }
}

@Composable
fun rememberSprites(): Sprites {
    val turretBase = ImageBitmap.imageResource(R.drawable.turret_base)
    val turretBarrel = ImageBitmap.imageResource(R.drawable.turret_barrel)
    val muzzleFlash = ImageBitmap.imageResource(R.drawable.muzzle_flash)
    val bulletBasic = ImageBitmap.imageResource(R.drawable.bullet_basic)
    val bulletMid = ImageBitmap.imageResource(R.drawable.bullet_mid)
    val bulletHeavy = ImageBitmap.imageResource(R.drawable.bullet_heavy)
    val crateAmmo = ImageBitmap.imageResource(R.drawable.crate_ammo)
    val armorPlate = ImageBitmap.imageResource(R.drawable.armor_plate)
    val hitFlash = ImageBitmap.imageResource(R.drawable.hit_flash)
    val explosion = ImageBitmap.imageResource(R.drawable.explosion)
    val heart = ImageBitmap.imageResource(R.drawable.heart)
    val heartEmpty = ImageBitmap.imageResource(R.drawable.heart_empty)
    val coin = ImageBitmap.imageResource(R.drawable.coin)
    val iconTarget = ImageBitmap.imageResource(R.drawable.icon_target)
    val badgeX2 = ImageBitmap.imageResource(R.drawable.badge_x2)
    val badgeX3 = ImageBitmap.imageResource(R.drawable.badge_x3)
    val badgeX5 = ImageBitmap.imageResource(R.drawable.badge_x5)
    val cloud = ImageBitmap.imageResource(R.drawable.cloud)
    val rock = ImageBitmap.imageResource(R.drawable.rock)
    val tileGrass = ImageBitmap.imageResource(R.drawable.tile_grass)
    val tileNight = ImageBitmap.imageResource(R.drawable.tile_night)
    val tileSand = ImageBitmap.imageResource(R.drawable.tile_sand)
    val slime = ImageBitmap.imageResource(R.drawable.slime)
    val slimePurple = ImageBitmap.imageResource(R.drawable.slime_purple)
    val slimeRed = ImageBitmap.imageResource(R.drawable.slime_red)
    val bat = ImageBitmap.imageResource(R.drawable.bat)
    val spider = ImageBitmap.imageResource(R.drawable.spider)
    val ghost = ImageBitmap.imageResource(R.drawable.ghost)
    val brute = ImageBitmap.imageResource(R.drawable.brute)
    val boss = ImageBitmap.imageResource(R.drawable.boss)

    return remember(turretBase) {
        Sprites(
            turretBase, turretBarrel, muzzleFlash,
            bulletBasic, bulletMid, bulletHeavy,
            crateAmmo, armorPlate, hitFlash, explosion,
            heart, heartEmpty, coin, iconTarget,
            badgeX2, badgeX3, badgeX5,
            cloud, rock,
            tileGrass, tileNight, tileSand,
            slime, slimePurple, slimeRed, bat, spider, ghost, brute, boss,
        )
    }
}

// ---- draw helpers ----------------------------------------------------------

/**
 * Fills the whole draw area with a repeating tile.
 *
 * Tile size and offsets are integers and the step equals the drawn size exactly —
 * rounding each tile independently leaves one-pixel gaps that show up as a grid of
 * dark seams across the field.
 */
fun DrawScope.drawTiledBackground(tile: ImageBitmap, approxSize: Float) {
    val step = approxSize.roundToInt().coerceAtLeast(8)
    val w = size.width.toInt()
    val h = size.height.toInt()
    var ty = 0
    while (ty < h) {
        var tx = 0
        while (tx < w) {
            drawImage(
                image = tile,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(tile.width, tile.height),
                dstOffset = IntOffset(tx, ty),
                dstSize = IntSize(step, step),
                filterQuality = FilterQuality.None,
            )
            tx += step
        }
        ty += step
    }
}

/** Draws a sprite centred on (cx, cy), scaled to [height] px, aspect preserved. */
fun DrawScope.drawSprite(
    sprite: ImageBitmap,
    cx: Float,
    cy: Float,
    height: Float,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    val scale = height / sprite.height
    val w = (sprite.width * scale).roundToInt().coerceAtLeast(1)
    val h = height.roundToInt().coerceAtLeast(1)
    drawImage(
        image = sprite,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(sprite.width, sprite.height),
        dstOffset = IntOffset((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt()),
        dstSize = IntSize(w, h),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = FilterQuality.None,
    )
}

/**
 * Draws a sprite rotated about a pivot, with the sprite's left edge at the pivot —
 * how the pack expects the turret barrel to be mounted on its base.
 */
fun DrawScope.drawSpriteRotatedFromPivot(
    sprite: ImageBitmap,
    pivotX: Float,
    pivotY: Float,
    height: Float,
    angleDeg: Float,
    inset: Float = 0f,
) {
    val scale = height / sprite.height
    val w = (sprite.width * scale).roundToInt().coerceAtLeast(1)
    val h = height.roundToInt().coerceAtLeast(1)
    rotate(degrees = angleDeg, pivot = androidx.compose.ui.geometry.Offset(pivotX, pivotY)) {
        drawImage(
            image = sprite,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sprite.width, sprite.height),
            dstOffset = IntOffset((pivotX - inset).roundToInt(), (pivotY - h / 2f).roundToInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.None,
        )
    }
}

/** Image composable that never smooths — for sprites inside normal Compose layouts. */
@Composable
fun PixelImage(
    sprite: ImageBitmap,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    Image(
        bitmap = sprite,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = FilterQuality.None,
    )
}

@Composable
fun pixel(id: Int): ImageBitmap = ImageBitmap.imageResource(id)
