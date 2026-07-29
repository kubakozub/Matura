package com.matura.app.ui

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
import com.matura.app.R
import com.matura.app.game.GameEngine
import com.matura.app.model.Texture
import kotlin.math.roundToInt

/**
 * Pixel-art sprites from the original asset pack.
 *
 * The pack ships 1x (16x16 base grid) for the game and 4x for previews, and states
 * plainly: scale nearest-neighbour only. So the 1x files live in `drawable-nodpi`,
 * where Android will not resample them on load, and every draw call passes
 * [FilterQuality.None]. Any smoothing here would turn crisp pixel art to mush.
 */

/** The three frames the pack ships per monster: idle, second walk frame, death. */
class MonsterArt(val idle: ImageBitmap, val walk: ImageBitmap, val death: ImageBitmap)

class Sprites(
    val turretBase: ImageBitmap,
    val turretBarrel: ImageBitmap,
    val muzzleFlash: ImageBitmap,
    val bulletBasic: ImageBitmap,
    val bulletMid: ImageBitmap,
    val bulletHeavy: ImageBitmap,
    val crateAmmo: ImageBitmap,
    val armorPlate: ImageBitmap,
    val armorPlateLarge: ImageBitmap,
    val hitFlash: ImageBitmap,
    val explosion: ImageBitmap,
    val bannerLevelUp: ImageBitmap,
    val heart: ImageBitmap,
    val heartEmpty: ImageBitmap,
    val coin: ImageBitmap,
    val iconTarget: ImageBitmap,
    val badgeX2: ImageBitmap,
    val badgeX3: ImageBitmap,
    val badgeX5: ImageBitmap,
    private val tilesGrass: List<ImageBitmap>,
    private val tilesNight: List<ImageBitmap>,
    private val tilesSand: List<ImageBitmap>,
    private val monsters: List<MonsterArt>,
) {
    fun tiles(t: Texture): List<ImageBitmap> = when (t) {
        Texture.CLASSIC -> tilesGrass
        Texture.NIGHT -> tilesNight
        Texture.DESERT -> tilesSand
    }

    fun badge(multiplier: Int): ImageBitmap? = when {
        multiplier >= 5 -> badgeX5
        multiplier >= 3 -> badgeX3
        multiplier >= 2 -> badgeX2
        else -> null
    }

    /**
     * The pack describes the colour variants as difficulty tiers, so the body is chosen
     * purely by the level a monster spawned at. Armour is never baked into the body —
     * it is the separate `armor_plate` overlay, which the pack calls "pancerz (nakładka)".
     */
    fun art(level: Int, variant: Int): MonsterArt {
        val tier = when {
            level <= 2 -> 0
            level <= 4 -> if (variant % 2 == 0) 0 else 1
            level <= 6 -> if (variant % 2 == 0) 1 else 2
            level <= 8 -> if (variant % 2 == 0) 2 else 3
            level <= 10 -> if (variant % 2 == 0) 3 else 4
            level <= 12 -> if (variant % 2 == 0) 4 else 5
            else -> if (variant % 2 == 0) 6 else 7
        }
        return monsters[tier]
    }

    /** Alternates the two walk frames; [phase] staggers monsters so they do not march in lockstep. */
    fun walkFrame(art: MonsterArt, elapsed: Float, phase: Int): ImageBitmap {
        val step = (elapsed / GameEngine.WALK_FRAME_TIME).toInt() + phase
        return if (step % 2 == 0) art.idle else art.walk
    }

    /** Bigger plate for the 20x20 boss, which the standard 14x8 one looks lost on. */
    fun armorFor(level: Int, variant: Int): ImageBitmap =
        if (art(level, variant) === monsters[7]) armorPlateLarge else armorPlate
}

/** Local helper is not an option here: `imageResource` is composable, so it needs its own function. */
@Composable
private fun monsterArt(idle: Int, walk: Int, death: Int) = MonsterArt(
    ImageBitmap.imageResource(idle),
    ImageBitmap.imageResource(walk),
    ImageBitmap.imageResource(death),
)

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
    val armorPlateLarge = ImageBitmap.imageResource(R.drawable.armor_plate_large)
    val hitFlash = ImageBitmap.imageResource(R.drawable.hit_flash)
    val explosion = ImageBitmap.imageResource(R.drawable.explosion)
    val banner = ImageBitmap.imageResource(R.drawable.banner_levelup)
    val heart = ImageBitmap.imageResource(R.drawable.heart)
    val heartEmpty = ImageBitmap.imageResource(R.drawable.heart_empty)
    val coin = ImageBitmap.imageResource(R.drawable.coin)
    val iconTarget = ImageBitmap.imageResource(R.drawable.icon_target)
    val badgeX2 = ImageBitmap.imageResource(R.drawable.badge_x2)
    val badgeX3 = ImageBitmap.imageResource(R.drawable.badge_x3)
    val badgeX5 = ImageBitmap.imageResource(R.drawable.badge_x5)

    val grass = listOf(
        ImageBitmap.imageResource(R.drawable.tile_grass),
        ImageBitmap.imageResource(R.drawable.tile_grass_2),
        ImageBitmap.imageResource(R.drawable.tile_grass_3),
    )
    val night = listOf(
        ImageBitmap.imageResource(R.drawable.tile_night),
        ImageBitmap.imageResource(R.drawable.tile_night_2),
        ImageBitmap.imageResource(R.drawable.tile_night_3),
    )
    val sand = listOf(
        ImageBitmap.imageResource(R.drawable.tile_sand),
        ImageBitmap.imageResource(R.drawable.tile_sand_2),
        ImageBitmap.imageResource(R.drawable.tile_sand_3),
    )

    val monsters = listOf(
        monsterArt(R.drawable.slime, R.drawable.slime_f2, R.drawable.slime_death),
        monsterArt(R.drawable.slime_purple, R.drawable.slime_purple_f2, R.drawable.slime_purple_death),
        monsterArt(R.drawable.slime_red, R.drawable.slime_red_f2, R.drawable.slime_red_death),
        monsterArt(R.drawable.bat, R.drawable.bat_f2, R.drawable.bat_death),
        monsterArt(R.drawable.spider, R.drawable.spider_f2, R.drawable.spider_death),
        monsterArt(R.drawable.ghost, R.drawable.ghost_f2, R.drawable.ghost_death),
        monsterArt(R.drawable.brute, R.drawable.brute_f2, R.drawable.brute_death),
        monsterArt(R.drawable.boss, R.drawable.boss_f2, R.drawable.boss_death),
    )

    return remember(turretBase) {
        Sprites(
            turretBase, turretBarrel, muzzleFlash,
            bulletBasic, bulletMid, bulletHeavy,
            crateAmmo, armorPlate, armorPlateLarge, hitFlash, explosion, banner,
            heart, heartEmpty, coin, iconTarget,
            badgeX2, badgeX3, badgeX5,
            grass, night, sand, monsters,
        )
    }
}

// ---- draw helpers ----------------------------------------------------------

/**
 * Fills the whole draw area with repeating tiles, mixing the variants the pack ships so
 * the ground does not read as one stamp repeated.
 *
 * Tile size and offsets are integers and the step equals the drawn size exactly —
 * rounding each tile independently leaves one-pixel gaps that show up as a grid of
 * dark seams across the field.
 */
fun DrawScope.drawTiledBackground(tiles: List<ImageBitmap>, approxSize: Float) {
    if (tiles.isEmpty()) return
    val step = approxSize.roundToInt().coerceAtLeast(8)
    val w = size.width.toInt()
    val h = size.height.toInt()
    var row = 0
    var ty = 0
    while (ty < h) {
        var col = 0
        var tx = 0
        while (tx < w) {
            // deterministic scatter: same cell always gets the same variant, no flicker
            val pick = ((col * 7 + row * 13) xor (col * row * 3)) % tiles.size
            val tile = tiles[if (pick < 0) -pick else pick]
            drawImage(
                image = tile,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(tile.width, tile.height),
                dstOffset = IntOffset(tx, ty),
                dstSize = IntSize(step, step),
                filterQuality = FilterQuality.None,
            )
            tx += step
            col++
        }
        ty += step
        row++
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
