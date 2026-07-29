package com.verbume.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verbume.app.game.Crate
import com.verbume.app.game.FlashKind
import com.verbume.app.game.GameConfig
import com.verbume.app.game.GameEngine
import com.verbume.app.game.GameSnapshot
import com.verbume.app.game.Monster
import com.verbume.app.game.SubmitResult
import com.verbume.app.model.Options
import com.verbume.app.model.ScoreRecord
import com.verbume.app.model.WordSet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** The pack is built on a 16x16 grid; a chunky mono face sits closest to its lettering. */
private val PixelFont = FontFamily.Monospace

@Composable
fun PlayScreen(
    set: WordSet,
    options: Options,
    str: Str,
    playerName: String,
    onExit: () -> Unit,
    onFinished: (ScoreRecord) -> Unit,
) {
    var restarts by remember { mutableIntStateOf(0) }
    val engine = remember(set.id, restarts, options.lives, options.direction) {
        GameEngine(
            set = set,
            config = GameConfig(lives = options.lives, direction = options.direction),
        )
    }
    var snap by remember(engine) { mutableStateOf(engine.snapshot()) }
    var highlighted by remember(engine) { mutableStateOf(emptySet<Long>()) }
    var highlightedCrates by remember(engine) { mutableStateOf(emptySet<Long>()) }
    var paused by remember(engine) { mutableStateOf(false) }
    var reported by remember(engine) { mutableStateOf(false) }
    var muzzle by remember(engine) { mutableFloatStateOf(0f) }

    val sprites = rememberSprites()
    val sfx = remember { Sfx() }
    DisposableEffect(Unit) { onDispose { sfx.release() } }

    var lastFlash by remember(engine) { mutableStateOf<FlashKind?>(null) }

    LaunchedEffect(engine, paused) {
        if (paused) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                    engine.tick(dt)
                    if (muzzle > 0f) muzzle -= dt
                }
                last = now
                snap = engine.snapshot()
                highlighted = engine.highlightedIds()
                highlightedCrates = engine.highlightedCrateIds()
            }
        }
    }

    LaunchedEffect(snap.flash) {
        val f = snap.flash
        if (f != null && f != lastFlash) sfx.play(f, options.soundEnabled)
        lastFlash = f
    }

    LaunchedEffect(snap.gameOver) {
        if (snap.gameOver && !reported) {
            reported = true
            onFinished(
                ScoreRecord(
                    playerName = playerName,
                    setTitle = set.title,
                    score = snap.score,
                    level = snap.level,
                    bestStreak = snap.bestStreak,
                    kills = snap.kills,
                    playedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    val theme = textureTheme(options.texture)
    val measurer = rememberTextMeasurer()

    fun submit() {
        if (paused) return
        val r = engine.submit()
        if (r is SubmitResult.Fired) muzzle = 0.11f
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(5.dp)
                .border(3.dp, Palette.Ink)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawField(snap, highlighted, highlightedCrates, theme, measurer, sprites, options, muzzle)
            }

            if (!engine.isPlayable) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        str.emptySetWarning,
                        color = Palette.Danger,
                        fontSize = 15.sp,
                        fontFamily = PixelFont,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Palette.Ink)
                            .border(3.dp, Palette.SurfaceHigh)
                            .padding(16.dp),
                    )
                }
            }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 5.dp)
                    .background(Palette.Ink.copy(alpha = 0.75f))
                    .border(2.dp, Palette.SurfaceHigh)
                    .clickable { paused = !paused }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    if (paused) str.resume else str.pause,
                    color = Palette.Bone,
                    fontSize = 11.sp,
                    fontFamily = PixelFont,
                )
            }

            if (snap.gameOver) {
                GameOverOverlay(snap, str, sprites, onAgain = { restarts++ }, onMenu = onExit)
            }
        }

        AnswerBar(snap.typed, snap.cursor)

        GameKeyboard(
            sprites = sprites,
            onChar = { c -> if (!paused) { engine.type(c); sfx.key(options.soundEnabled) } },
            onBackspace = { if (!paused) engine.backspace() },
            onEnter = { submit() },
            onSpace = { if (!paused) engine.type(' ') },
            onLeft = { engine.cursorLeft() },
            onRight = { engine.cursorRight() },
        )
    }
}

@Composable
private fun AnswerBar(typed: String, cursor: Int) {
    // The design puts the answer on a bone panel with ink lettering and a green caret.
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Palette.Bone)
            .border(3.dp, Palette.Ink),
        contentAlignment = Alignment.Center,
    ) {
        val safe = cursor.coerceIn(0, typed.length)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (typed.isNotEmpty()) {
                Text(
                    typed.substring(0, safe),
                    color = Palette.Ink,
                    fontSize = 26.sp,
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "_",
                color = Palette.AccentDim,
                fontSize = 26.sp,
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
            )
            if (safe < typed.length) {
                Text(
                    typed.substring(safe),
                    color = Palette.Ink,
                    fontSize = 26.sp,
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun GameOverOverlay(
    snap: GameSnapshot,
    str: Str,
    sprites: Sprites,
    onAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PixelImage(sprites.explosion, Modifier.size(72.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                str.gameOver,
                color = Palette.Danger,
                fontSize = 26.sp,
                fontFamily = PixelFont,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(sprites.coin, Modifier.size(28.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    "${snap.score}",
                    color = Palette.Gold,
                    fontSize = 40.sp,
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text("${str.level} ${snap.level}", color = Palette.Muted, fontSize = 13.sp, fontFamily = PixelFont)
                Spacer(Modifier.width(14.dp))
                Text("${str.killsLabel} ${snap.kills}", color = Palette.Muted, fontSize = 13.sp, fontFamily = PixelFont)
                Spacer(Modifier.width(14.dp))
                Text("${str.bestStreakLabel} ${snap.bestStreak}", color = Palette.Muted, fontSize = 13.sp, fontFamily = PixelFont)
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                PrimaryButton(str.playAgain, onClick = onAgain)
                Spacer(Modifier.width(12.dp))
                GhostButton(str.toMenu, onClick = onMenu)
            }
        }
    }
}

// ---- drawing ---------------------------------------------------------------

private fun DrawScope.drawField(
    snap: GameSnapshot,
    highlighted: Set<Long>,
    highlightedCrates: Set<Long>,
    theme: FieldTheme,
    measurer: TextMeasurer,
    sprites: Sprites,
    options: Options,
    muzzle: Float,
) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)

    drawRect(color = theme.field)
    clipRect {
        drawTiledBackground(sprites.tile(options.texture), (unit * 0.11f).coerceAtLeast(24f))
    }

    fun px(x: Float) = x * w
    fun py(y: Float) = y * h

    val cx = px(GameEngine.CANNON_X)
    val cy = py(GameEngine.CANNON_Y)

    val aim = snap.monsters
        .filter { highlighted.isEmpty() || it.id in highlighted }
        .minByOrNull { hypot(px(it.x) - cx, py(it.y) - cy) }
    val aimAngle = if (aim != null) {
        Math.toDegrees(atan2((py(aim.y) - cy).toDouble(), (px(aim.x) - cx).toDouble())).toFloat()
    } else 0f

    drawCrates(snap.crates, highlightedCrates, ::px, ::py, unit, measurer, sprites, theme)
    drawMonsters(snap.monsters, highlighted, ::px, ::py, unit, measurer, sprites, theme)
    drawTurret(cx, cy, unit, aimAngle, sprites, muzzle)
    drawProjectiles(snap, ::px, ::py, unit, sprites)
    drawHud(snap, w, h, unit, measurer, sprites)
}

private fun DrawScope.drawTurret(
    cx: Float,
    cy: Float,
    unit: Float,
    angleDeg: Float,
    sprites: Sprites,
    muzzle: Float,
) {
    val barrelH = unit * 0.036f
    val baseH = unit * 0.072f
    // Barrel first so the base sits over its mounting end, as in the design.
    drawSpriteRotatedFromPivot(
        sprite = sprites.turretBarrel,
        pivotX = cx, pivotY = cy,
        height = barrelH,
        angleDeg = angleDeg,
        inset = unit * 0.012f,
    )
    if (muzzle > 0f) {
        val a = Math.toRadians(angleDeg.toDouble())
        val reach = unit * 0.062f
        drawSprite(
            sprites.muzzleFlash,
            cx + (cos(a) * reach).toFloat(),
            cy + (sin(a) * reach).toFloat(),
            unit * 0.045f,
            alpha = (muzzle / 0.11f).coerceIn(0f, 1f),
        )
    }
    drawSprite(sprites.turretBase, cx, cy, baseH)
}

private fun DrawScope.drawMonsters(
    monsters: List<Monster>,
    highlighted: Set<Long>,
    px: (Float) -> Float,
    py: (Float) -> Float,
    unit: Float,
    measurer: TextMeasurer,
    sprites: Sprites,
    theme: FieldTheme,
) {
    for (m in monsters) {
        val x = px(m.x)
        val y = py(m.y)
        val hgt = unit * 0.105f
        val lit = m.id in highlighted

        drawSprite(sprites.monster(m.spawnLevel, m.variant), x, y, hgt)
        // Armour sits over the body's chest and simply disappears when knocked off.
        if (m.armored) {
            drawSprite(sprites.armorPlate, x, y + hgt * 0.14f, hgt * 0.30f)
        }
        if (m.hitFlash > 0f) {
            drawSprite(sprites.hitFlash, x, y, hgt * 1.05f, alpha = (m.hitFlash / 0.18f).coerceIn(0f, 1f))
        }

        // Word above the monster, ink drop-shadow like the design's text-shadow.
        val ink = measurer.measure(
            AnnotatedString(m.prompt),
            style = TextStyle(color = Palette.Ink, fontSize = 15.sp, fontFamily = PixelFont, fontWeight = FontWeight.Bold),
        )
        val face = measurer.measure(
            AnnotatedString(m.prompt),
            style = TextStyle(
                color = if (lit) Palette.Accent else theme.hudInk,
                fontSize = 15.sp,
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
            ),
        )
        val tx = x - face.size.width / 2f
        val ty = y - hgt * 0.62f - face.size.height
        drawText(ink, topLeft = Offset(tx + 3f, ty + 3f))
        drawText(face, topLeft = Offset(tx, ty))
    }
}

private fun DrawScope.drawCrates(
    crates: List<Crate>,
    highlighted: Set<Long>,
    px: (Float) -> Float,
    py: (Float) -> Float,
    unit: Float,
    measurer: TextMeasurer,
    sprites: Sprites,
    theme: FieldTheme,
) {
    for (c in crates) {
        val x = px(c.x)
        val y = py(c.y)
        val hgt = unit * 0.085f
        val lit = c.id in highlighted
        val blink = if (c.ttl < 4f) 0.5f else 1f

        drawSprite(sprites.crateAmmo, x, y, hgt, alpha = blink)

        val ink = measurer.measure(
            AnnotatedString(c.prompt),
            style = TextStyle(color = Palette.Ink, fontSize = 13.sp, fontFamily = PixelFont, fontWeight = FontWeight.Bold),
        )
        val face = measurer.measure(
            AnnotatedString(c.prompt),
            style = TextStyle(
                color = if (lit) Palette.Accent else theme.hudInk,
                fontSize = 13.sp,
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
            ),
        )
        val tx = x - face.size.width / 2f
        val ty = y - hgt * 0.62f - face.size.height
        drawText(ink, topLeft = Offset(tx + 3f, ty + 3f))
        drawText(face, topLeft = Offset(tx, ty))
    }
}

private fun DrawScope.drawProjectiles(
    snap: GameSnapshot,
    px: (Float) -> Float,
    py: (Float) -> Float,
    unit: Float,
    sprites: Sprites,
) {
    for (p in snap.projectiles) {
        val target = snap.monsters.firstOrNull { it.id == p.targetId } ?: continue
        val x = px(p.x)
        val y = py(p.y)
        val a = Math.toDegrees(
            atan2((py(target.y) - y).toDouble(), (px(target.x) - x).toDouble())
        ).toFloat()
        val sprite = if (p.heavy) sprites.bulletMid else sprites.bulletBasic
        val hgt = unit * (if (p.heavy) 0.048f else 0.038f)
        rotate(degrees = a, pivot = Offset(x, y)) {
            drawSprite(sprite, x, y, hgt)
        }
    }
}

private fun DrawScope.drawHud(
    snap: GameSnapshot,
    w: Float,
    h: Float,
    unit: Float,
    measurer: TextMeasurer,
    sprites: Sprites,
) {
    val pad = unit * 0.030f
    val iconH = unit * 0.048f

    fun label(text: String, size: Int, color: androidx.compose.ui.graphics.Color) = measurer.measure(
        AnnotatedString(text),
        style = TextStyle(color = color, fontSize = size.sp, fontFamily = PixelFont, fontWeight = FontWeight.Bold),
    )

    fun shadowed(text: String, size: Int, color: androidx.compose.ui.graphics.Color, x: Float, y: Float) {
        val ink = label(text, size, Palette.Ink)
        drawText(ink, topLeft = Offset(x + 3f, y + 3f))
        drawText(label(text, size, color), topLeft = Offset(x, y))
    }

    // lives, top-left — filled hearts then empty ones
    for (i in 0 until snap.maxLives) {
        val sprite = if (i < snap.lives) sprites.heart else sprites.heartEmpty
        drawSprite(sprite, pad + iconH / 2f + i * (iconH + unit * 0.012f), pad + iconH / 2f, iconH)
    }

    // ammo, top-right: heavy count over the mid bullet, then the endless basic round
    val heavyText = label("${snap.heavyAmmo}", 16, Palette.Bone)
    val slash = label("/", 16, Palette.Muted)
    val infinity = label("∞", 18, Palette.Bone)
    var rx = w - pad
    rx -= infinity.size.width; drawText(infinity, topLeft = Offset(rx, pad))
    rx -= unit * 0.008f
    rx -= iconH * 0.8f
    drawSprite(sprites.bulletBasic, rx + iconH * 0.4f, pad + iconH * 0.42f, iconH * 0.8f)
    rx -= unit * 0.010f
    rx -= slash.size.width; drawText(slash, topLeft = Offset(rx, pad))
    rx -= unit * 0.010f
    rx -= heavyText.size.width; drawText(heavyText, topLeft = Offset(rx, pad))
    rx -= unit * 0.008f
    rx -= iconH
    drawSprite(sprites.bulletMid, rx + iconH / 2f, pad + iconH * 0.42f, iconH)

    // score, bottom-left: coin, number, and the streak badge when one is active
    val scoreY = h - pad - iconH
    drawSprite(sprites.coin, pad + iconH / 2f, scoreY + iconH / 2f, iconH * 0.85f)
    val scoreLabel = label("${snap.score}", 17, Palette.Bone)
    shadowed("${snap.score}", 17, Palette.Bone, pad + iconH + unit * 0.012f, scoreY + iconH * 0.18f)
    sprites.badge(snap.multiplier)?.let { badge ->
        drawSprite(
            badge,
            pad + iconH + unit * 0.024f + scoreLabel.size.width + iconH * 0.6f,
            scoreY + iconH / 2f,
            iconH * 0.85f,
        )
    }

    // level, bottom-right: crosshair plus LVL n in gold
    val lvl = label("LVL ${snap.level}", 17, Palette.Gold)
    val lx = w - pad - lvl.size.width
    shadowed("LVL ${snap.level}", 17, Palette.Gold, lx, scoreY + iconH * 0.18f)
    drawSprite(sprites.iconTarget, lx - unit * 0.014f - iconH * 0.45f, scoreY + iconH / 2f, iconH * 0.8f)
}
