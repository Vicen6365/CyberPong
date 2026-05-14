package com.cyberpong.app

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import android.view.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    // Scale
    private var W = 800f; private var H = 500f; private var s = 1f
    private var pw = 12f; private var ph = 100f; private var br = 9f
    private var pm = 30f; private var sb = 6f

    // Thread-safe state
    @Volatile private var state = 0 // 0=title,1=serving,2=playing,3=point,4=gameover
    @Volatile private var touchY = 0f

    private var playerScore = 0; private var aiScore = 0; private val winScore = 7
    private var difficulty = 0f; private var rallyTime = 0f; private var afterPointTimer = 0f

    // Ball
    private data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float)
    private var ball = Ball(0f, 0f, 0f, 0f)
    private val trail = Array(12) { FloatArray(3) } // x,y,life; recycled
    private var trailHead = 0
    private var ballCurve = 0f; private var ballInvisible = false; private var ballSlow = false

    // Paddles
    private var playerY = 0f; private var aiY = 0f
    private var playerGiant = false; private var aiMini = false
    private var playerGiantTimer = 0f; private var aiMiniTimer = 0f

    // Power-ups
    private enum class PUType { CURVE, GIANT, MINI, SLOWMO, INVISIBLE }
    private var puActive = false; private var puType = PUType.CURVE
    private var puX = 0f; private var puY = 0f; private var puR = 0f
    private var currentPU: PUType? = null; private var currentPUTimer = 0f
    private var nextPUSpawn = 0L

    // Particles - pre-allocated arrays
    private val partsX = FloatArray(80); private val partsY = FloatArray(80)
    private val partsVX = FloatArray(80); private val partsVY = FloatArray(80)
    private val partsLife = FloatArray(80); private val partsDecay = FloatArray(80)
    private val partsColor = IntArray(80); private val partsR = FloatArray(80)
    private var partsCount = 0

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; isFakeBoldText = true; isAntiAlias = true
    }
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; isFakeBoldText = true; isAntiAlias = true
    }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG) // shadow paint for text

    // Threading
    @Volatile private var running = false
    private var loopThread: Thread? = null

    // Sound - pooled + async
    private val sr = 22050
    private var bgTrack: AudioTrack? = null
    @Volatile private var bgRun = false
    private var bgThread: Thread? = null
    private val soundQueue = ArrayDeque<ShortArray>(4)
    private val soundLock = Any()
    @Volatile private var soundThreadRunning = false
    private var soundThread: Thread? = null
    // Pre-generated sound buffers
    private lateinit var hitSounds: Array<ShortArray>
    private lateinit var scoreSound: ShortArray
    private lateinit var winSounds: Array<ShortArray>
    private lateinit var powerupSounds: Array<ShortArray>

    // Grid cache
    private val gridLines = mutableListOf<Pair<Float,Float>>()

    init {
        holder.addCallback(this)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
        // Pre-generate sounds
        hitSounds = arrayOf(genTone(880f,0.06f,0.25f), genTone(660f,0.06f,0.25f), genTone(440f,0.06f,0.25f))
        scoreSound = genTone(330f,0.15f,0.25f)
        winSounds = arrayOf(genTone(523f,0.18f,0.25f), genTone(659f,0.18f,0.25f),
            genTone(784f,0.18f,0.25f), genTone(1047f,0.3f,0.3f))
        powerupSounds = arrayOf(genTone(1200f,0.08f,0.2f), genTone(1500f,0.08f,0.2f), genTone(1800f,0.08f,0.2f))

        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchY = event.y
                    if (state == 0) { startGame(); return@setOnTouchListener true }
                    if (state == 1) { serve(); return@setOnTouchListener true }
                    if (state == 4) { startGame(); return@setOnTouchListener true }
                }
                MotionEvent.ACTION_MOVE -> { touchY = event.y }
            }
            true
        }
    }

    private fun recalc(w: Float, h: Float) {
        W = w; H = h
        s = min(W / 800f, H / 500f)
        pw = max(8f, 12f * s); ph = max(50f, 100f * s)
        br = max(5f, 9f * s); pm = max(15f, 30f * s); sb = max(3f, 6f * s)
        // Pre-calculate grid lines
        gridLines.clear()
        var i = 0f; while (i < W) { gridLines.add(i to 0f); gridLines.add(i to H); i += 40f }
        i = 0f; while (i < H) { gridLines.add(0f to i); gridLines.add(W to i); i += 40f }
    }

    private fun startGame() {
        state = 1; playerScore = 0; aiScore = 0; difficulty = 0f; rallyTime = 0f
        playerY = H / 2f; aiY = H / 2f
        ball = Ball(W / 2f, H / 2f, 0f, 0f)
        trailHead = 0; partsCount = 0
        puActive = false; currentPU = null; playerGiant = false; aiMini = false
        ballCurve = 0f; ballInvisible = false; ballSlow = false
        nextPUSpawn = SystemClock.elapsedRealtime() + 8000 + (Math.random() * 4000).toLong()
        startSoundThread()
        playBg(true)
    }

    private fun serve() {
        state = 2
        val dir = if (Math.random() > 0.5) 1f else -1f
        val angle = (Math.random().toFloat() - 0.5f) * 0.8f
        val spd = sb * 1.2f
        ball.x = W / 2f; ball.y = H / 2f
        ball.vx = dir * cos(angle) * spd
        ball.vy = sin(angle) * spd
        if (abs(ball.vy) < 0.3f) ball.vy = if (ball.vy > 0) 0.3f else -0.3f
        rallyTime = 0f; difficulty = 0f; trailHead = 0
    }

    private fun update(dt: Float) {
        if (state != 2 && state != 3) return

        if (state == 3) {
            afterPointTimer -= dt
            if (afterPointTimer <= 0f) {
                state = 1
                playerY = H / 2f; aiY = H / 2f
                ball = Ball(W / 2f, H / 2f, 0f, 0f)
                trailHead = 0; playerGiant = false; aiMini = false
                ballCurve = 0f; ballInvisible = false; ballSlow = false
                currentPU = null
                nextPUSpawn = SystemClock.elapsedRealtime() + 6000 + (Math.random() * 4000).toLong()
            }
            return
        }

        rallyTime += dt; difficulty = min(90f, (rallyTime / 10f) * 18f)

        // Power-up spawn
        if (!puActive && currentPU == null && SystemClock.elapsedRealtime() > nextPUSpawn) {
            val types = PUType.values(); val t = types[(Math.random() * types.size).toInt()]
            puX = W / 2f + ((Math.random().toFloat() - 0.5f) * W * 0.35f)
            puY = H / 2f + ((Math.random().toFloat() - 0.5f) * H * 0.25f)
            puR = max(14f, 22f * s); puActive = true; puType = t
            nextPUSpawn = SystemClock.elapsedRealtime() + 8000 + (Math.random() * 4000).toLong()
        }

        // Current PU timer
        if (currentPU != null) {
            currentPUTimer -= dt
            if (currentPUTimer <= 0f) {
                when (currentPU) { PUType.GIANT -> playerGiant = false
                    PUType.MINI -> aiMini = false; PUType.SLOWMO -> ballSlow = false
                    PUType.INVISIBLE -> ballInvisible = false; PUType.CURVE -> ballCurve = 0f; else -> {} }
                currentPU = null
            }
        }

        // Move ball
        val sm = if (ballSlow) 0.4f else 1f
        ball.x += ball.vx * sm; ball.y += ball.vy * sm

        // Curve
        if (ballCurve != 0f) ball.vy += ballCurve * 0.15f * s * sm

        // Trail
        val tIdx = (trailHead % 12)
        trail[tIdx][0] = ball.x; trail[tIdx][1] = ball.y; trail[tIdx][2] = 1f
        trailHead++
        for (j in 0 until min(trailHead, 12)) {
            val idx = (trailHead - 1 - j) % 12
            trail[idx][2] = 1f - j * 0.083f // life decreasing
        }

        // Walls
        if (ball.y - br < 0f) { ball.y = br; ball.vy = -ball.vy; playHit(2) }
        if (ball.y + br > H) { ball.y = H - br; ball.vy = -ball.vy; playHit(2) }

        // Power-up collection
        if (puActive) {
            val dx = ball.x - puX; val dy = ball.y - puY
            if (dx*dx + dy*dy < (br + puR).pow(2)) {
                puActive = false; val side = if (ball.vx > 0f) 1 else 0
                currentPU = puType; currentPUTimer = 5f
                spawnParts(puX, puY, Color.YELLOW, 12, 3f)
                playPowerUp()
                when (puType) { PUType.CURVE -> if (side == 0) ballCurve = if (Math.random() > 0.5) 1f else -1f
                    PUType.GIANT -> if (side == 0) { playerGiant = true; playerGiantTimer = 5f }
                    PUType.MINI -> if (side == 1) { aiMini = true; aiMiniTimer = 5f }
                    PUType.SLOWMO -> if (side == 0) ballSlow = true
                    PUType.INVISIBLE -> if (side == 0) ballInvisible = true }
            }
        }

        // Player paddle collision
        val pph = if (playerGiant) ph * 2f else ph
        val pTop = playerY - pph/2f; val pBot = playerY + pph/2f
        if (ball.x - br <= pm + pw/2f && ball.x + br >= pm - pw/2f &&
            ball.y + br > pTop && ball.y - br < pBot && ball.vx < 0f) {
            val hitPos = (ball.y - playerY) / (pph/2f)
            val angle = hitPos * PI.toFloat() / 3f
            val spd = sqrt(ball.vx * ball.vx + ball.vy * ball.vy) * 1.03f
            ball.vx = cos(angle) * spd; ball.vy = sin(angle) * spd
            ball.x = pm + pw / 2f + br + 1f; ballCurve = 0f
            spawnParts(pm + pw / 2f, ball.y, Color.rgb(0,255,204), 8, 2.5f)
            playHit(0)
        }

        // AI paddle collision
        val aah = if (aiMini) ph * 0.5f else ph
        val aTop = aiY - aah/2f; val aBot = aiY + aah/2f
        if (ball.x + br >= W - pm - pw/2f && ball.x - br <= W - pm + pw/2f &&
            ball.y + br > aTop && ball.y - br < aBot && ball.vx > 0f) {
            val hitPos = (ball.y - aiY) / (aah/2f)
            val angle = hitPos * PI.toFloat() / 3f
            val spd = sqrt(ball.vx * ball.vx + ball.vy * ball.vy) * 1.03f
            ball.vx = -cos(angle) * spd; ball.vy = sin(angle) * spd
            ball.x = W - pm - pw / 2f - br - 1f
            spawnParts(W - pm - pw / 2f, ball.y, Color.rgb(255,51,102), 8, 2.5f)
            playHit(1)
        }

        // Scoring
        if (ball.x - br < 0f) { aiScore++; afterPoint(aiScore >= winScore) }
        if (ball.x + br > W) { playerScore++; afterPoint(playerScore >= winScore) }

        // AI
        val aiTargetY: Float
        if (ball.vx > 0f) {
            val off = max(0.1f, 1f - difficulty / 100f) * H * 0.15f
            aiTargetY = ball.y + ((Math.random().toFloat() - 0.5f) * off)
        } else aiTargetY = H/2f + ((Math.random().toFloat() - 0.5f) * H * 0.1f)
        val maxSpd = max(2f, 5f + difficulty / 10f) * s
        val diff = aiTargetY - aiY
        if (abs(diff) > 3f) aiY += sign(diff) * min(abs(diff), maxSpd)
        aiY = max(aah/2f, min(H - aah/2f, aiY))

        // Player paddle
        val pSpd = max(3f, 8f * s)
        val pDiff = touchY - playerY
        if (abs(pDiff) > 2f) playerY += sign(pDiff) * min(abs(pDiff), pSpd)
        playerY = max(pph/2f, min(H - pph/2f, playerY))

        // Particles - update in place
        var pi = 0
        while (pi < partsCount) {
            partsX[pi] += partsVX[pi]; partsY[pi] += partsVY[pi]
            partsLife[pi] -= partsDecay[pi]
            if (partsLife[pi] <= 0f) {
                // Remove by swapping with last
                partsCount--
                if (pi < partsCount) {
                    partsX[pi] = partsX[partsCount]; partsY[pi] = partsY[partsCount]
                    partsVX[pi] = partsVX[partsCount]; partsVY[pi] = partsVY[partsCount]
                    partsLife[pi] = partsLife[partsCount]; partsDecay[pi] = partsDecay[partsCount]
                    partsColor[pi] = partsColor[partsCount]; partsR[pi] = partsR[partsCount]
                }
            } else pi++
        }
    }

    private fun afterPoint(winner: Boolean) {
        state = 3; afterPointTimer = 1.5f
        if (winner) { state = 4; playWin() }
        playScore()
        spawnParts(if (playerScore > aiScore) W else 0f, ball.y,
            if (playerScore > aiScore) Color.rgb(0,255,204) else Color.rgb(255,51,102), 20, 5f)
    }

    private fun spawnParts(x: Float, y: Float, color: Int, count: Int, spdMul: Float) {
        val n = min(count, 80 - partsCount)
        for (i in 0 until n) {
            val a = (Math.random().toFloat()) * PI.toFloat() * 2f
            val spd = (Math.random().toFloat() * 4f * s + 1f * s) * spdMul
            partsX[partsCount] = x; partsY[partsCount] = y
            partsVX[partsCount] = cos(a) * spd; partsVY[partsCount] = sin(a) * spd
            partsLife[partsCount] = 1f; partsDecay[partsCount] = 0.012f + (Math.random().toFloat()*0.02f)
            partsColor[partsCount] = color; partsR[partsCount] = (Math.random().toFloat()*2.5f*s + 1f*s)
            partsCount++
        }
    }

private fun render(c: Canvas) {
        c.drawColor(Color.rgb(4, 4, 14))

        // Grid - batched
        if (gridLines.size >= 4) {
            paint.color = Color.argb(25, 30, 30, 80); paint.strokeWidth = 1f
            paint.maskFilter = null
            for (j in 0 until gridLines.size step 2) {
                c.drawLine(gridLines[j].first, gridLines[j].second,
                    gridLines[j+1].first, gridLines[j+1].second, paint)
            }
        }

        // Center line
        paint.color = Color.argb(50, 100, 200, 255); paint.strokeWidth = 2f
        paint.pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
        c.drawLine(W / 2f, 0f, W / 2f, H, paint); paint.pathEffect = null

        // Scores
        val scoreSize = min(56f, 56f * s)
        val scoreY = 52f
        // Clean text with shadow (no blur)
        tp.textSize = scoreSize; tp.textAlign = Paint.Align.CENTER
        sp.textSize = scoreSize; sp.textAlign = Paint.Align.CENTER; sp.color = Color.argb(30, 0, 0, 0)

        tp.color = Color.rgb(0, 255, 204)
        c.drawText("$playerScore", W / 2f - 80f + 1f, scoreY + 1f, sp) // shadow
        c.drawText("$playerScore", W / 2f - 80f, scoreY, tp)
        tp.color = Color.rgb(255, 51, 102)
        c.drawText("$aiScore", W / 2f + 80f + 1f, scoreY + 1f, sp)
        c.drawText("$aiScore", W / 2f + 80f, scoreY, tp)

        // Ball trail
        val trailLen = min(trailHead, 12)
        for (j in 0 until trailLen) {
            val idx = (trailHead - 1 - j) % 12
            if (trail[idx][2] > 0f) {
                paint.color = Color.argb((trail[idx][2] * 60f).toInt().coerceIn(0, 60), 200, 200, 255)
                paint.maskFilter = null
                c.drawCircle(trail[idx][0], trail[idx][1], br * trail[idx][2] * 0.5f, paint)
            }
        }

        // Ball glow (subtle)
        if (!ballInvisible || state != 2) {
            paint.color = Color.argb(60, 200, 200, 255)
            paint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(ball.x, ball.y, br * 1.5f, paint)
            paint.color = Color.WHITE; paint.maskFilter = null
            c.drawCircle(ball.x, ball.y, br, paint)
        }

        // Paddles - clean neon rects
        neonRect(c, pm - pw/2f, playerY - (if(playerGiant) ph*2f else ph)/2f,
            pw, if(playerGiant) ph*2f else ph, Color.rgb(0,255,204))
        neonRect(c, W - pm - pw/2f, aiY - (if(aiMini) ph*0.5f else ph)/2f,
            pw, if(aiMini) ph*0.5f else ph, Color.rgb(255,51,102))

        // Particles
        paint.maskFilter = null
        for (pi in 0 until partsCount) {
            paint.alpha = (max(0f, partsLife[pi]) * 200f).toInt().coerceIn(0, 200)
            paint.color = partsColor[pi]
            paint.maskFilter = if (partsR[pi] > 2f)
                BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL) else null
            c.drawCircle(partsX[pi], partsY[pi], partsR[pi], paint)
        }
        paint.alpha = 255; paint.maskFilter = null

        // Power-up
        if (puActive) {
            val pulse = 1f + sin(SystemClock.elapsedRealtime() / 200f) * 0.15f
            val col = puCol(puType)
            paint.color = Color.argb(100, Color.red(col), Color.green(col), Color.blue(col))
            paint.maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(puX, puY, puR * pulse * 1.3f, paint)
            paint.maskFilter = null
            paint.color = col; c.drawCircle(puX, puY, puR * pulse, paint)
            // Icon
            drawPUIcon(c, puX, puY, puR * pulse * 0.55f, puType)
        }

        // Current PU indicator
        if (currentPU != null) {
            val lbl = puLbl(currentPU!!); val col = puCol(currentPU!!)
            tp.alpha = (140 + (sin(SystemClock.elapsedRealtime()/300f)*0.3f*115f).toInt()).coerceIn(0,255)
            tp.color = col; tp.textSize = 11f * s
            c.drawText("$lbl ${ceil(currentPUTimer).toInt()}s", W/2f + 1f, H/2f + 65f + 1f, sp)
            c.drawText("$lbl ${ceil(currentPUTimer).toInt()}s", W/2f, H/2f + 65f, tp)
            tp.alpha = 255
        }

        // Title
        if (state == 0) {
            c.drawColor(Color.argb(190, 4, 4, 14))
            val ts = min(52f, 52f * s)
            sp.textSize = ts; sp.textAlign = Paint.Align.CENTER; sp.color = Color.argb(30, 0, 0, 0)
            tp.textSize = ts; tp.textAlign = Paint.Align.CENTER
            tp.color = Color.rgb(0, 230, 190)
            c.drawText("CYBER PONG", W/2f + 2f, H/2f - 40f + 2f, sp)
            c.drawText("CYBER PONG", W/2f, H/2f - 40f, tp)
            tp.textSize = min(17f, 18f * s); tp.color = Color.argb(120, 100, 200, 255)
            if ((SystemClock.elapsedRealtime()/400L).toInt()%2==0)
                c.drawText("TAP TO START", W/2f, H/2f+30f, tp)
            tp.textSize = min(10f, 12f * s); tp.color = Color.argb(50, 100, 200, 255)
            c.drawText("FIRST TO 7 WINS", W/2f, H/2f+55f, tp)
            // Decorative lines
            paint.color = Color.argb(50, 0, 255, 204); paint.strokeWidth = 1f
            c.drawLine(W/2f-170f, H/2f-90f, W/2f+170f, H/2f-90f, paint)
            c.drawLine(W/2f-170f, H/2f+90f, W/2f+170f, H/2f+90f, paint)
        }

        // Serving
        if (state == 1) {
            tp.textSize = min(18f, 20f * s); tp.color = Color.argb(200, 0, 255, 204)
            c.drawText("TAP TO SERVE", W/2f, H/2f+80f, tp)
            ball.y = H/2f + sin(SystemClock.elapsedRealtime()/300f)*15f; ball.x = W/2f
            paint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            paint.color = Color.argb(60, 200, 200, 255)
            c.drawCircle(ball.x, ball.y, br*1.5f, paint)
            paint.color = Color.WHITE; paint.maskFilter = null
            c.drawCircle(ball.x, ball.y, br, paint)
        }

        // Game over
        if (state == 4) {
            c.drawColor(Color.argb(140, 0, 0, 0))
            val w = playerScore >= winScore; val clr = if(w) Color.rgb(0,230,190) else Color.rgb(255,51,102)
            val gs = min(42f, 42f * s)
            sp.textSize = gs; sp.textAlign = Paint.Align.CENTER
            tp.textSize = gs; tp.textAlign = Paint.Align.CENTER
            tp.color = clr
            c.drawText(if(w)"YOU WIN!" else "YOU LOSE", W/2f+1f, H/2f-30f+1f, sp)
            c.drawText(if(w)"YOU WIN!" else "YOU LOSE", W/2f, H/2f-30f, tp)
            val ss = min(30f, 30f*s)
            tp.textSize = ss; tp.color = Color.WHITE
            c.drawText("$playerScore - $aiScore", W/2f+1f, H/2f+20f+1f, sp)
            c.drawText("$playerScore - $aiScore", W/2f, H/2f+20f, tp)
            if ((SystemClock.elapsedRealtime()/400L).toInt()%2==0) {
                tp.textSize = min(14f, 16f * s); tp.color = Color.argb(150, 100, 200, 255)
                c.drawText("TAP TO PLAY AGAIN", W/2f, H/2f+65f, tp)
            }
        }

        // Difficulty indicator
        if (state == 2 && difficulty > 0f) {
            tp.textSize = 9f * s; tp.color = Color.argb(35, 255, 255, 255)
            val bars = (difficulty/10f).toInt()
            if(bars>0) c.drawText("\u25B6".repeat(bars), W/2f, H-8f, tp)
        }
    }

    // Draw clean text with shadow instead of blur
    private fun cleanText(c: Canvas, t: String, x: Float, y: Float, color: Int, size: Float, align: Paint.Align) {
        sp.textSize = size; sp.textAlign = align; sp.color = Color.argb(25, 0, 0, 0)
        tp.textSize = size; tp.textAlign = align; tp.color = color
        c.drawText(t, x + 1.5f, y + 1.5f, sp)
        c.drawText(t, x, y, tp)
    }

    // Power-up icon drawing
    private fun drawPUIcon(c: Canvas, cx: Float, cy: Float, r: Float, t: PUType) {
        paint.color = Color.WHITE; paint.strokeWidth = max(2f, 2.5f * r / 12f)
        paint.maskFilter = null
        when (t) {
            PUType.CURVE -> {
                paint.style = Paint.Style.STROKE
                val path = Path(); path.moveTo(cx - r, cy + r * 0.3f)
                path.quadTo(cx - r * 0.3f, cy - r, cx, cy)
                path.quadTo(cx + r * 0.3f, cy + r, cx + r, cy - r * 0.3f)
                c.drawPath(path, paint)
            }
            PUType.GIANT -> {
                paint.style = Paint.Style.FILL
                val h = r * 1.2f; val w = r * 0.35f
                c.drawRect(cx - r*0.5f - w, cy - h/2f, cx - r*0.5f, cy + h/2f, paint)
                c.drawRect(cx + r*0.5f, cy - h/2f, cx + r*0.5f + w, cy + h/2f, paint)
                // Arrow tips
                val tri = Path()
                tri.moveTo(cx - r*0.5f, cy - h*0.4f)
                tri.lineTo(cx - r - 3f, cy)
                tri.lineTo(cx - r*0.5f, cy + h*0.4f)
                tri.close(); c.drawPath(tri, paint)
                tri.rewind()
                tri.moveTo(cx + r*0.5f, cy - h*0.4f)
                tri.lineTo(cx + r + 3f, cy)
                tri.lineTo(cx + r*0.5f, cy + h*0.4f)
                tri.close(); c.drawPath(tri, paint)
            }
            PUType.MINI -> {
                paint.style = Paint.Style.FILL
                val h = r * 0.7f; val w = r * 0.35f
                c.drawRect(cx - r*0.3f - w, cy - h/2f, cx - r*0.3f, cy + h/2f, paint)
                c.drawRect(cx + r*0.3f, cy - h/2f, cx + r*0.3f + w, cy + h/2f, paint)
                // Inner arrows
                val tri = Path()
                tri.moveTo(cx + r*0.3f, cy - h*0.35f)
                tri.lineTo(cx + r + 3f, cy)
                tri.lineTo(cx + r*0.3f, cy + h*0.35f)
                tri.close(); c.drawPath(tri, paint)
                tri.rewind()
                tri.moveTo(cx - r*0.3f, cy - h*0.35f)
                tri.lineTo(cx - r - 3f, cy)
                tri.lineTo(cx - r*0.3f, cy + h*0.35f)
                tri.close(); c.drawPath(tri, paint)
            }
            PUType.SLOWMO -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                // Two small triangles
                val tri = Path()
                val ts = r * 0.5f
                tri.moveTo(cx - ts*0.6f, cy - ts)
                tri.lineTo(cx - ts*0.6f, cy + ts)
                tri.lineTo(cx + ts*0.4f, cy)
                tri.close(); c.drawPath(tri, paint)
                tri.rewind()
                tri.moveTo(cx + ts*0.2f, cy - ts)
                tri.lineTo(cx + ts*0.2f, cy + ts)
                tri.lineTo(cx + ts*1.2f, cy)
                tri.close(); c.drawPath(tri, paint)
            }
            PUType.INVISIBLE -> {
                paint.style = Paint.Style.STROKE
                paint.color = Color.argb(140, 255, 255, 255)
                c.drawCircle(cx, cy, r * 0.7f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(100, 255, 255, 255)
                c.drawCircle(cx, cy, r * 0.4f, paint)
                // Eye dots
                paint.color = Color.WHITE
                c.drawCircle(cx - r*0.15f, cy - r*0.1f, r*0.06f, paint)
                c.drawCircle(cx + r*0.15f, cy - r*0.1f, r*0.06f, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun neonRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        val r = min(w, h) * 0.2f
        paint.color = color; paint.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        val path = Path().apply { addRoundRect(RectF(x, y, x+w, y+h), r, r, Path.Direction.CW) }
        c.drawPath(path, paint)
        paint.maskFilter = null
        paint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
        paint.strokeWidth = 2f; paint.style = Paint.Style.STROKE
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun puLbl(t: PUType) = when(t) { PUType.CURVE->"CURVE"; PUType.GIANT->"GIANT"
        PUType.MINI->"SHRINK"; PUType.SLOWMO->"SLOW"; PUType.INVISIBLE->"GHOST" }
    private fun puCol(t: PUType) = when(t) { PUType.CURVE->Color.rgb(255,107,53)
        PUType.GIANT->Color.rgb(57,255,20); PUType.MINI->Color.rgb(255,0,85)
        PUType.SLOWMO->Color.rgb(0,212,255); PUType.INVISIBLE->Color.rgb(170,102,255) }

    // ===== SOUND =====
    private fun genTone(freq: Float, dur: Float, vol: Float): ShortArray {
        val n = (sr * dur).toInt(); val b = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat()/sr; val e = exp(-4f*t/dur)
            // Triangle wave (softer than sine)
            val phase = (freq * t) % 1f
            val wave = if (phase < 0.5f) 4f*phase - 1f else 3f - 4f*phase
            b[i] = (wave * Short.MAX_VALUE * vol * e).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return b
    }

    // Non-blocking sound: queue and play in background thread
    private fun playHit(who: Int) { queueSound(hitSounds[who]) }
    private fun playScore() { queueSound(scoreSound) }
    private fun playWin() { winSounds.forEach { queueSound(it) } }
    private fun playPowerUp() { powerupSounds.forEach { queueSound(it) } }

    private fun queueSound(buf: ShortArray) {
        synchronized(soundLock) {
            if (soundQueue.size < 6) soundQueue.addLast(buf)
        }
    }

    private fun startSoundThread() {
        if (soundThreadRunning) return
        soundThreadRunning = true
        soundThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            while (soundThreadRunning) {
                var buf: ShortArray? = null
                synchronized(soundLock) { buf = soundQueue.pollFirst() }
                if (buf != null) {
                    try {
                        val at = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(buf.size*2).build()
                        at.write(buf, 0, buf.size); at.play()
                        Thread.sleep((buf.size.toFloat()/sr*1000).toLong())
                        at.release()
                    } catch (_: Exception) {}
                } else {
                    try { Thread.sleep(10) } catch (_: Exception) { break }
                }
            }
        }; soundThread!!.start()
    }

    private fun stopSoundThread() {
        soundThreadRunning = false
        try { soundThread?.join(500) } catch (_: Exception) {}
        soundThread = null
    }

    private fun playBg(on: Boolean) {
        if (on && bgTrack == null) {
            try {
                val bufSize = sr * 2
                bgTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufSize*2).setTransferMode(AudioTrack.MODE_STREAM).build()
                bgTrack!!.play(); bgRun = true
                bgThread = Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    val buf = ShortArray(bufSize); var ph = 0f; var lph = 0f
                    while (bgRun && bgTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        for (i in buf.indices) {
                            val t = i.toFloat()/sr
                            val lfo = (sin(2f*PI.toFloat()*0.3f*t+lph)*0.5f+0.5f) * 0.3f
                            val wave = 2f*((55f*t+ph)%1f)-1f
                            buf[i] = (wave * 1500f * lfo).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        ph += 55f*buf.size/sr; lph += 0.3f*buf.size/sr
                        try { bgTrack?.write(buf,0,buf.size) } catch(_:Exception) { break }
                    }
                }; bgThread!!.start()
            } catch(_: Exception) { Log.w("CyberPong", "BG audio unavailable") }
        } else if (!on) {
            bgRun = false
            try { bgThread?.join(300); bgTrack?.stop(); bgTrack?.release() } catch(_:Exception){}
            bgTrack = null
        }
    }

    // ===== SURFACE =====
    override fun surfaceCreated(h: SurfaceHolder) {
        recalc(width.toFloat(), height.toFloat()); running = true
        loopThread = Thread {
            val dtBuffer = FloatArray(4); var dtIdx = 0
            var last = SystemClock.elapsedRealtime()
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val rawDt = (now - last) / 1000f; last = now
                // Clamp and smooth delta time
                dtBuffer[dtIdx % 4] = min(rawDt, 0.05f); dtIdx++
                val dt = dtBuffer.take(min(dtIdx,4)).average().toFloat()
                update(dt)
                holder.lockCanvas()?.let { c ->
                    try { render(c) } finally { holder.unlockCanvasAndPost(c) }
                }
                val elapsed = SystemClock.elapsedRealtime() - now
                if (elapsed < 16) {
                    try { Thread.sleep(16 - elapsed) } catch (_: Exception) {}
                }
            }
        }; loopThread!!.start()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h: Int) {
        recalc(w.toFloat(), h.toFloat())
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false; bgRun = false; soundThreadRunning = false
        try { loopThread?.join(500) } catch(_:Exception){}
        try { bgThread?.join(300); bgTrack?.release() } catch(_:Exception){}
        bgTrack = null; loopThread = null; bgThread = null; soundThread = null
    }
}
