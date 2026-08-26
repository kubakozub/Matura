package com.matura.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.matura.app.game.Crate
import com.matura.app.game.FlashKind
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.GameSnapshot
import com.matura.app.game.MissedWord
import com.matura.app.game.SubmitResult
import com.matura.app.model.Options
import com.matura.app.model.ScoreRecord
import com.matura.app.model.WordSet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val engine = remember(set.id, restarts, options.lives, options.direction, options.speed) {
        GameEngine(
            set = set,
            config = GameConfig(
                lives = options.lives,
                direction = options.direction,
                speed = options.speed,
            ),
        )
    }
    var snap by remember(engine) { mutableStateOf(engine.snapshot()) }
    var highlighted by remember(engine) { mutableStateOf(emptySet<Long>()) }
    var highlightedCrates by remember(engine) { mutableStateOf(emptySet<Long>()) }
    var paused by remember(engine) { mutableStateOf(false) }
    var reported by remember(engine) { mutableStateOf(false) }
    var muzzle by remember(engine) { mutableFloatStateOf(0f) }

    val sprites = rememberSprites()
    val font = rememberPixelFont()
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

    fun submit() {
        if (paused) return
        val r = engine.submit()
        if (r is SubmitResult.Fired) muzzle = 0.11f
    }

    // Android 16 ignoruje android:screenOrientation na ekranach od 600dp w gore, wiec gra
    // musi przezyc okno w poziomie. Ulozone jedno pod drugim, plansza schodzi do paska:
    // na telefonie 891x411dp sama klawiatura zjada ponad polowe wysokosci. Obok siebie
    // obie polowy zachowuja uzyteczny ksztalt.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        val obokSiebie = maxWidth > maxHeight

        val keyboard: @Composable () -> Unit = {
            GameKeyboard(
                onChar = { c -> if (!paused) { engine.type(c); sfx.key(options.soundEnabled) } },
                onBackspace = { if (!paused) engine.backspace() },
                onEnter = { submit() },
                onSpace = { if (!paused) engine.type(' ') },
                onLeft = { engine.cursorLeft() },
                onRight = { engine.cursorRight() },
            )
        }

        if (obokSiebie) {
            Row(Modifier.fillMaxSize()) {
                PlayField(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    snap = snap, highlighted = highlighted, highlightedCrates = highlightedCrates,
                    theme = theme, sprites = sprites, font = font, options = options,
                    muzzle = muzzle, playable = engine.isPlayable, paused = paused, str = str,
                    onTogglePause = { paused = !paused },
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    AnswerBar(snap.typed, snap.cursor, font)
                    keyboard()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PlayField(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    snap = snap, highlighted = highlighted, highlightedCrates = highlightedCrates,
                    theme = theme, sprites = sprites, font = font, options = options,
                    muzzle = muzzle, playable = engine.isPlayable, paused = paused, str = str,
                    onTogglePause = { paused = !paused },
                )
                AnswerBar(snap.typed, snap.cursor, font)
                keyboard()
            }
        }

        // Podsumowanie idzie na caly ekran, nie na sama plansze - w poziomie plansza to
        // polowa szerokosci i lista do powtorki nie miescilaby sie w niej.
        if (snap.gameOver) {
            GameOverOverlay(snap, str, sprites, font, onAgain = { restarts++ }, onMenu = onExit)
        }
    }
}

/** The playfield with its HUD overlays; the same block in both layouts. */
@Composable
private fun PlayField(
    modifier: Modifier,
    snap: GameSnapshot,
    highlighted: Set<Long>,
    highlightedCrates: Set<Long>,
    theme: FieldTheme,
    sprites: Sprites,
    font: PixelFont,
    options: Options,
    muzzle: Float,
    playable: Boolean,
    paused: Boolean,
    str: Str,
    onTogglePause: () -> Unit,
) {
    Box(
        modifier
            .padding(5.dp)
            .border(3.dp, Palette.Ink)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawField(snap, highlighted, highlightedCrates, theme, sprites, font, options, muzzle)
        }

        if (!playable) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .background(Palette.Ink)
                        .border(3.dp, Palette.SurfaceHigh)
                        .padding(14.dp)
                ) {
                    PixelText(
                        str.emptySetWarning,
                        color = Palette.Danger,
                        glyphHeight = 9.dp,
                        maxWidthDp = 240.dp,
                        font = font,
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 5.dp)
                .background(Palette.Ink.copy(alpha = 0.75f))
                .border(2.dp, Palette.SurfaceHigh)
                .clickable { onTogglePause() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(com.matura.app.R.drawable.icon_pause), Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                PixelText(
                    if (paused) str.resume else str.pause,
                    color = Palette.Bone,
                    glyphHeight = 8.dp,
                    font = font,
                )
            }
        }
    }
}

@Composable
private fun AnswerBar(typed: String, cursor: Int, font: PixelFont) {
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
            if (safe > 0) {
                PixelText(
                    typed.substring(0, safe),
                    color = Palette.Ink,
                    outline = null,
                    glyphHeight = 17.dp,
                    font = font,
                )
            }
            PixelText("_", color = Palette.AccentDim, outline = null, glyphHeight = 17.dp, font = font)
            if (safe < typed.length) {
                PixelText(
                    typed.substring(safe),
                    color = Palette.Ink,
                    outline = null,
                    glyphHeight = 17.dp,
                    font = font,
                )
            }
        }
    }
}

@Composable
internal fun GameOverOverlay(
    snap: GameSnapshot,
    str: Str,
    sprites: Sprites,
    font: PixelFont,
    onAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.86f)),
    ) {
        // Przyciski musza byc widoczne zawsze — to jedyna droga wyjscia z tego ekranu.
        // Na telefonie w poziomie okno ma 411 dp, a caly uklad z pelna lista potrzebuje
        // wiecej, wiec cos musi ustapic i moze to byc wylacznie lista. Dlatego dostaje
        // `weight(1f, fill = false)`: bierze to, co zostanie po elementach o stalej
        // wysokosci, i ani piksela wiecej. Zadnych stalych liczbowych — pierwsze podejscie
        // odejmowalo zgadniete 300 dp i bylo za male, bo font bitmapowy zajmuje wiecej,
        // niz wynika z samego `glyphHeight`.
        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PixelImage(pixel(com.matura.app.R.drawable.skull), Modifier.size(60.dp))
            Spacer(Modifier.height(12.dp))
            PixelText(str.gameOver, color = Palette.Danger, glyphHeight = 18.dp, font = font)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(sprites.coin, Modifier.size(26.dp))
                Spacer(Modifier.width(9.dp))
                PixelText("${snap.score}", color = Palette.Gold, glyphHeight = 26.dp, font = font)
            }
            Spacer(Modifier.height(14.dp))
            PixelText(
                "LVL ${snap.level}  ${str.killsLabel} ${snap.kills}  x${snap.bestStreak}",
                color = Palette.Muted,
                glyphHeight = 9.dp,
                font = font,
            )
            Spacer(Modifier.height(16.dp))
            ReviewList(snap.missedWords, str, font, Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                PrimaryButton(str.playAgain, onClick = onAgain)
                Spacer(Modifier.width(12.dp))
                GhostButton(str.toMenu, onClick = onMenu)
            }
        }
    }
}

/**
 * The words that reached the cannon, each with the answer spelled out in full — hyphens,
 * diacritics and all. A tester asked for exactly this: a list of what got through
 * "wraz z ich tlumaczeniem", to repeat straight after the run instead of hunting for the
 * words in the set. The list scrolls inside a fixed height, so a long run can never push
 * the buttons off the bottom of the screen.
 */
@Composable
private fun ReviewList(
    missed: List<MissedWord>,
    str: Str,
    font: PixelFont,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 300.dp)
            .background(Palette.Ink)
            .border(2.dp, Palette.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        PixelText(str.reviewTitle, color = Palette.Accent, glyphHeight = 9.dp, font = font)
        Spacer(Modifier.height(9.dp))
        if (missed.isEmpty()) {
            PixelText(str.reviewClean, color = Palette.Muted, glyphHeight = 8.dp, font = font)
            return@Column
        }
        // Na wysokim ekranie lista konczy sie na wlasnej tresci, najwyzej 136 dp;
        // na niskim przycina ja `weight` rodzica, a przewijanie zostawia dostep do reszty.
        Column(
            Modifier
                .heightIn(max = 136.dp)
                .verticalScroll(rememberScrollState())
        ) {
            for (word in missed) {
                val repeats = if (word.times > 1) "  x${word.times}" else ""
                PixelText(
                    word.prompt + repeats,
                    color = Palette.Bone,
                    glyphHeight = 8.dp,
                    maxWidthDp = 272.dp,
                    font = font,
                )
                PixelText(
                    word.solution,
                    color = Palette.Muted,
                    glyphHeight = 8.dp,
                    maxWidthDp = 272.dp,
                    font = font,
                )
                Spacer(Modifier.height(8.dp))
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
    sprites: Sprites,
    font: PixelFont,
    options: Options,
    muzzle: Float,
) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val fs = (unit * 0.0065f).roundToInt().coerceAtLeast(1).toFloat()

    drawRect(color = theme.field)
    clipRect {
        drawTiledBackground(sprites.tiles(options.texture), (unit * 0.11f).coerceAtLeast(24f))
    }

    // A hit knocks the whole field about a little; keeps the camera alive without animation.
    val shake = if (snap.flash == FlashKind.LIFE_LOST) unit * 0.006f else 0f

    translate(
        left = if (shake > 0f) (snap.elapsed * 97f % 2f - 1f) * shake else 0f,
        top = if (shake > 0f) (snap.elapsed * 61f % 2f - 1f) * shake else 0f,
    ) {
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

        // corpses first, so a fresh kill sits behind whatever walks over it
        for (c in snap.corpses) {
            val art = sprites.art(c.spawnLevel, c.variant)
            drawSprite(
                art.death, px(c.x), py(c.y), unit * 0.105f,
                alpha = (c.ttl / GameEngine.DEATH_FRAME_TIME).coerceIn(0f, 1f),
            )
        }

        for (c in snap.crates) {
            val x = px(c.x)
            val y = py(c.y)
            val hgt = unit * 0.085f
            val blink = if (c.ttl < 4f) (if ((c.ttl * 6f).toInt() % 2 == 0) 0.4f else 1f) else 1f
            drawSprite(sprites.crateAmmo, x, y, hgt, alpha = blink)
            drawCentredLabel(
                font, c.prompt, x, y - hgt * 0.62f, fs * 0.85f,
                if (c.id in highlightedCrates) Palette.Accent else theme.hudInk,
                clampToCanvas = true,
            )
        }

        for (m in snap.monsters) {
            val x = px(m.x)
            val y = py(m.y)
            val hgt = unit * 0.105f
            val art = sprites.art(m.spawnLevel, m.variant)
            drawSprite(sprites.walkFrame(art, snap.elapsed, (m.id % 2).toInt()), x, y, hgt)
            if (m.armored) {
                drawSprite(sprites.armorFor(m.spawnLevel, m.variant), x, y + hgt * 0.14f, hgt * 0.30f)
            }
            if (m.hitFlash > 0f) {
                drawSprite(sprites.hitFlash, x, y, hgt * 1.05f, alpha = (m.hitFlash / 0.18f).coerceIn(0f, 1f))
            }
            drawCentredLabel(
                font, m.prompt, x, y - hgt * 0.62f, fs,
                if (m.id in highlighted) Palette.Accent else theme.hudInk,
                clampToCanvas = true,
            )
        }

        drawTurret(cx, cy, unit, aimAngle, sprites, muzzle)

        for (p in snap.projectiles) {
            val target = snap.monsters.firstOrNull { it.id == p.targetId } ?: continue
            val x = px(p.x)
            val y = py(p.y)
            val a = Math.toDegrees(
                atan2((py(target.y) - y).toDouble(), (px(target.x) - x).toDouble())
            ).toFloat()
            val sprite = if (p.heavy) sprites.bulletMid else sprites.bulletBasic
            val hgt = unit * (if (p.heavy) 0.048f else 0.038f)
            rotate(degrees = a, pivot = Offset(x, y)) { drawSprite(sprite, x, y, hgt) }
        }

        drawHud(snap, w, h, unit, fs, sprites, font)

        if (snap.levelUpBanner > 0f) {
            val alpha = (snap.levelUpBanner / GameEngine.LEVEL_BANNER_TIME).coerceIn(0f, 1f)
            val bh = unit * 0.075f
            val by = h * 0.28f
            drawSprite(sprites.bannerLevelUp, w / 2f, by, bh, alpha = alpha)
            drawCentredLabel(font, "LVL ${snap.level}", w / 2f, by + bh * 0.18f, fs, Palette.Ink, outline = null)
        }
    }
}

/**
 * Centres one line of bitmap text horizontally on [cx], with its baseline box above [bottomY].
 *
 * Monsters enter from just outside the canvas, so a label centred on the sprite would hang off
 * the edge — until 0.7.0 a long word like "disciplined" read as "plined" for the first couple of
 * seconds, which is exactly the time the player needs to start typing. The label is therefore
 * kept inside the canvas; only the sprite walks in from off-screen.
 */
private fun DrawScope.drawCentredLabel(
    font: PixelFont,
    text: String,
    cx: Float,
    bottomY: Float,
    scale: Float,
    color: Color,
    outline: Color? = Palette.Ink,
    clampToCanvas: Boolean = false,
) {
    val width = font.widthPx(text, scale)
    var x = cx - width / 2f
    if (clampToCanvas) {
        val margin = 2f * scale
        x = when {
            width + 2 * margin >= size.width -> (size.width - width) / 2f   // dłuższe niż ekran
            else -> x.coerceIn(margin, size.width - width - margin)
        }
    }
    drawPixelText(
        font = font,
        text = text,
        x = x,
        y = bottomY - PixelFont.GLYPH_H * scale,
        scale = scale,
        color = color,
        outline = outline,
    )
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

private fun DrawScope.drawHud(
    snap: GameSnapshot,
    w: Float,
    h: Float,
    unit: Float,
    fs: Float,
    sprites: Sprites,
    font: PixelFont,
) {
    val pad = unit * 0.030f
    val iconH = unit * 0.048f

    // lives, top-left
    for (i in 0 until snap.maxLives) {
        val sprite = if (i < snap.lives) sprites.heart else sprites.heartEmpty
        drawSprite(sprite, pad + iconH / 2f + i * (iconH + unit * 0.012f), pad + iconH / 2f, iconH)
    }

    // ammo, top-right: heavy count over the mid bullet, then the endless basic round
    val midY = pad + iconH / 2f
    val textTop = midY - PixelFont.GLYPH_H * fs / 2f
    var rx = w - pad
    val inf = font.widthPx("∞", fs)
    rx -= inf
    drawPixelText(font, "∞", rx, textTop, fs, Palette.Bone)
    rx -= unit * 0.016f + iconH * 0.8f
    drawSprite(sprites.bulletBasic, rx + iconH * 0.4f, midY, iconH * 0.8f)
    rx -= unit * 0.016f
    val slash = font.widthPx("/", fs)
    rx -= slash
    drawPixelText(font, "/", rx, textTop, fs, Palette.Muted)
    rx -= unit * 0.016f
    val heavy = "${snap.heavyAmmo}"
    rx -= font.widthPx(heavy, fs)
    drawPixelText(font, heavy, rx, textTop, fs, Palette.Bone)
    rx -= unit * 0.016f + iconH
    drawSprite(sprites.bulletMid, rx + iconH / 2f, midY, iconH)

    // score, bottom-left
    val bottomMid = h - pad - iconH / 2f
    val bottomTop = bottomMid - PixelFont.GLYPH_H * fs / 2f
    drawSprite(sprites.coin, pad + iconH / 2f, bottomMid, iconH * 0.85f)
    val score = "${snap.score}"
    val scoreX = pad + iconH + unit * 0.012f
    drawPixelText(font, score, scoreX, bottomTop, fs, Palette.Bone)
    sprites.badge(snap.multiplier)?.let { badge ->
        drawSprite(
            badge,
            scoreX + font.widthPx(score, fs) + unit * 0.014f + iconH * 0.5f,
            bottomMid,
            iconH * 0.85f,
        )
    }

    // level, bottom-right
    val lvl = "LVL ${snap.level}"
    val lx = w - pad - font.widthPx(lvl, fs)
    drawPixelText(font, lvl, lx, bottomTop, fs, Palette.Gold)
    drawSprite(sprites.iconTarget, lx - unit * 0.014f - iconH * 0.45f, bottomMid, iconH * 0.8f)
}
