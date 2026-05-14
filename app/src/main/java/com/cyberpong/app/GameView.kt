package com.cyberpong.app

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.view.*
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

    // Trail - fixed array
    private val trailX = FloatArray(12); private val trailY = FloatArray(12); private val trailL = FloatArray(12)
    private var trailHead = 0
    private var ballCurve = 0f; private var ballInvisible = false; private var ballSlow = false

    // Paddles
    private var playerY = 0f; private var aiY = 0f
    private var playerGiant = false; private var aiMini = false

    // Power-ups
    private enum class PUType { CURVE, GIANT, MINI, SLOWMO, INVISIBLE }
    private var puActive = false; private var puType = PUType.CURVE
    private var puX = 0f; private var puY = 0f; private var puR = 0f
    private var currentPU: PUType? = null; private var currentPUTimer = 0f
    private var nextPUSpawn = 0L

    // Particles - fixed arrays, no allocations
    private val pX = FloatArray(80); private val pY = FloatArray(80)
    private val pVX = FloatArray(80); private val pVY = FloatArray(80)
    private val pLife = FloatArray(80); private val pDecay = FloatArray(80)
    private val pColor = IntArray(80); private val pRad = FloatArray(80)
    private var pCount = 0

    // Cached Canvas objects (reuse, never allocate in render loop)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; isFakeBoldText = true; isAntiAlias = true
    }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(25, 0, 0, 0); isAntiAlias = true
    }
    private val bgPaint = Paint()
    private val rectF = RectF()
    private val path = Path()
    private val triPath = Path()
    private val dashPE = DashPathEffect(floatArrayOf(8f, 12f), 0f)

    // Runtime
    @Volatile private var running = false
    private var loopThread: Thread? = null

    // Sound - SoundPool for SFX (zero allocation per hit), AudioTrack for bg
    private var soundPool: SoundPool? = null
    private var sidHitPlayer = 0; private var sidHitAI = 0; private var sidHitWall = 0
    private var sidScore = 0; private var sidWin1 = 0; private var sidWin2 = 0
    private var sidWin3 = 0; private var sidWin4 = 0; private var sidPower1 = 0
    private var sidPower2 = 0; private var sidPower3 = 0
    @Volatile private var soundReady = false
    private var bgTrack: AudioTrack? = null; @Volatile private var bgRun = false
    private var bgThread: Thread? = null

    // Grid cache
    private val gridX1 = FloatArray(80); private val gridY1 = FloatArray(80)
    private val gridX2 = FloatArray(80); private val gridY2 = FloatArray(80)
    private var gridCount = 0

    init {
        holder.addCallback(this)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
        initSound()

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

    private fun initSound() {
        try {
            soundPool = SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            sidHitPlayer = loadTone(880f, 0.06f, 0.3f)
            sidHitAI = loadTone(660f, 0.06f, 0.3f)
            sidHitWall = loadTone(440f, 0.06f, 0.2f)
            sidScore = loadTone(330f, 0.15f, 0.25f)
            sidWin1 = loadTone(523f, 0.2f, 0.3f); sidWin2 = loadTone(659f, 0.2f, 0.3f)
            sidWin3 = loadTone(784f, 0.2f, 0.3f); sidWin4 = loadTone(1047f, 0.25f, 0.35f)
            sidPower1 = loadTone(1200f, 0.1f, 0.25f); sidPower2 = loadTone(1500f, 0.1f, 0.25f)
            sidPower3 = loadTone(1800f, 0.1f, 0.25f)
            soundReady = true
        } catch (_: Exception) {}
    }

    private fun loadTone(freq: Float, dur: Float, vol: Float): Int {
        val sr = 22050; val n = (sr * dur).toInt()
        val buf = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toFloat()/sr; val e = exp(-4f*t/dur)
            val phase = (freq * t) % 1f
            val wave = if (phase < 0.5f) 4f*phase - 1f else 3f - 4f*phase
            val s = (wave * Short.MAX_VALUE * vol * e).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buf[i*2] = (s.toInt() and 0xFF).toByte()
            buf[i*2+1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        return soundPool!!.load(buf, 0, buf.size, 1)
    }

    private fun playHit(who: Int) {
        if (!soundReady) return
        when (who) { 0 -> soundPool?.play(sidHitPlayer, 0.5f, 0.5f, 0, 0, 1f)
            1 -> soundPool?.play(sidHitAI, 0.5f, 0.5f, 0, 0, 1f)
            else -> soundPool?.play(sidHitWall, 0.3f, 0.3f, 0, 0, 1f) }
    }
    private fun playScore() { if(soundReady) soundPool?.play(sidScore, 0.5f, 0.5f, 0, 0, 1f) }
    private fun playWin() {
        if(!soundReady) return
        soundPool?.play(sidWin1, 0.5f, 0.5f, 0, 0, 1f)
        Thread { try { Thread.sleep(120)
            soundPool?.play(sidWin2, 0.5f, 0.5f, 0, 0, 1f); Thread.sleep(120)
            soundPool?.play(sidWin3, 0.5f, 0.5f, 0, 0, 1f); Thread.sleep(120)
            soundPool?.play(sidWin4, 0.5f, 0.5f, 0, 0, 1f)
        } catch(_:Exception){} }.start()
    }
    private fun playPowerUp() {
        if(!soundReady) return
        soundPool?.play(sidPower1, 0.5f, 0.5f, 0, 0, 1f)
        Thread { try { Thread.sleep(60)
            soundPool?.play(sidPower2, 0.5f, 0.5f, 0, 0, 1f); Thread.sleep(60)
            soundPool?.play(sidPower3, 0.5f, 0.5f, 0, 0, 1f)
        } catch(_:Exception){} }.start()
    }

    private fun recalc(w: Float, h: Float) {
        W = w; H = h
        s = min(W / 800f, H / 500f)
        pw = max(8f, 12f * s); ph = max(50f, 100f * s)
        br = max(5f, 9f * s); pm = max(15f, 30f * s); sb = max(3f, 6f * s)
        gridCount = 0
        var i = 0f; while (i < W) {
            gridX1[gridCount] = i; gridY1[gridCount] = 0f
            gridX2[gridCount] = i; gridY2[gridCount] = H; gridCount++
            i += 40f }
        i = 0f; while (i < H) {
            gridX1[gridCount] = 0f; gridY1[gridCount] = i
            gridX2[gridCount] = W; gridY2[gridCount] = i; gridCount++
            i += 40f }
    }

    private fun startGame() {
        state = 1; playerScore = 0; aiScore = 0; difficulty = 0f; rallyTime = 0f
        playerY = H / 2f; aiY = H / 2f
        ball = Ball(W / 2f, H / 2f, 0f, 0f); trailHead = 0; pCount = 0
        puActive = false; currentPU = null; playerGiant = false; aiMini = false
        ballCurve = 0f; ballInvisible = false; ballSlow = false
        nextPUSpawn = SystemClock.elapsedRealtime() + 8000 + (Math.random() * 4000).toLong()
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
                state = 1; playerY = H / 2f; aiY = H / 2f
                ball = Ball(W / 2f, H / 2f, 0f, 0f); trailHead = 0
                playerGiant = false; aiMini = false
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

        // Ball physics
        val sm = if (ballSlow) 0.4f else 1f
        ball.x += ball.vx * sm; ball.y += ball.vy * sm
        if (ballCurve != 0f) ball.vy += ballCurve * 0.15f * s * sm

        // Trail
        trailX[trailHead % 12] = ball.x; trailY[trailHead % 12] = ball.y; trailL[trailHead % 12] = 1f
        trailHead++

        // Walls
        if (ball.y - br < 0f) { ball.y = br; ball.vy = -ball.vy; playHit(2) }
        if (ball.y + br > H) { ball.y = H - br; ball.vy = -ball.vy; playHit(2) }

        // Power-up collect
        if (puActive) {
            val dx = ball.x - puX; val dy = ball.y - puY
            if (dx*dx + dy*dy < (br + puR).pow(2)) {
                puActive = false; val side = if (ball.vx > 0f) 1 else 0
                currentPU = puType; currentPUTimer = 5f
                spawnParts(puX, puY, Color.YELLOW, 10, 2f)
                playPowerUp()
                when (puType) { PUType.CURVE -> if (side == 0) ballCurve = if (Math.random() > 0.5) 1f else -1f
                    PUType.GIANT -> if (side == 0) playerGiant = true
                    PUType.MINI -> if (side == 1) aiMini = true
                    PUType.SLOWMO -> if (side == 0) ballSlow = true
                    PUType.INVISIBLE -> if (side == 0) ballInvisible = true }
            }
        }

        // Player paddle
        val pph = if (playerGiant) ph * 2f else ph
        val pTop = playerY - pph/2f; val pBot = playerY + pph/2f
        if (ball.x - br <= pm + pw/2f && ball.x + br >= pm - pw/2f &&
            ball.y + br > pTop && ball.y - br < pBot && ball.vx < 0f) {
            val hitPos = (ball.y - playerY) / (pph/2f)
            val angle = hitPos * PI.toFloat() / 3f
            val spd = sqrt(ball.vx * ball.vx + ball.vy * ball.vy) * 1.03f
            ball.vx = cos(angle) * spd; ball.vy = sin(angle) * spd
            ball.x = pm + pw / 2f + br + 1f; ballCurve = 0f
            spawnParts(pm + pw / 2f, ball.y, Color.rgb(0,255,204), 6, 2f)
            playHit(0)
        }

        // AI paddle
        val aah = if (aiMini) ph * 0.5f else ph
        val aTop = aiY - aah/2f; val aBot = aiY + aah/2f
        if (ball.x + br >= W - pm - pw/2f && ball.x - br <= W - pm + pw/2f &&
            ball.y + br > aTop && ball.y - br < aBot && ball.vx > 0f) {
            val hitPos = (ball.y - aiY) / (aah/2f)
            val angle = hitPos * PI.toFloat() / 3f
            val spd = sqrt(ball.vx * ball.vx + ball.vy * ball.vy) * 1.03f
            ball.vx = -cos(angle) * spd; ball.vy = sin(angle) * spd
            ball.x = W - pm - pw / 2f - br - 1f
            spawnParts(W - pm - pw / 2f, ball.y, Color.rgb(255,51,102), 6, 2f)
            playHit(1)
        }

        // Score
        if (ball.x - br < 0f) { aiScore++; afterPoint(aiScore >= winScore) }
        if (ball.x + br > W) { playerScore++; afterPoint(playerScore >= winScore) }

        // AI movement
        val aiTargetY: Float
        if (ball.vx > 0f) {
            val off = max(0.1f, 1f - difficulty / 100f) * H * 0.15f
            aiTargetY = ball.y + ((Math.random().toFloat() - 0.5f) * off)
        } else aiTargetY = H/2f + ((Math.random().toFloat() - 0.5f) * H * 0.1f)
        val maxSpd = max(2f, 5f + difficulty / 10f) * s
        val diff = aiTargetY - aiY
        if (abs(diff) > 3f) aiY += sign(diff) * min(abs(diff), maxSpd)
        aiY = max(aah/2f, min(H - aah/2f, aiY))

        // Player movement
        val pSpd = max(3f, 8f * s)
        val pDiff = touchY - playerY
        if (abs(pDiff) > 2f) playerY += sign(pDiff) * min(abs(pDiff), pSpd)
        playerY = max(pph/2f, min(H - pph/2f, playerY))

        // Update particles (swap-remove)
        var pi = 0
        while (pi < pCount) {
            pX[pi] += pVX[pi]; pY[pi] += pVY[pi]
            pLife[pi] -= pDecay[pi]
            if (pLife[pi] <= 0f) {
                pCount--
                if (pi < pCount) {
                    pX[pi] = pX[pCount]; pY[pi] = pY[pCount]
                    pVX[pi] = pVX[pCount]; pVY[pi] = pVY[pCount]
                    pLife[pi] = pLife[pCount]; pDecay[pi] = pDecay[pCount]
                    pColor[pi] = pColor[pCount]; pRad[pi] = pRad[pCount]
                }
            } else pi++
        }
    }

    private fun afterPoint(winner: Boolean) {
        state = 3; afterPointTimer = 1.5f
        if (winner) { state = 4; playWin() }
        playScore()
        spawnParts(if (playerScore > aiScore) W else 0f, ball.y,
            if (playerScore > aiScore) Color.rgb(0,255,204) else Color.rgb(255,51,102), 16, 4f)
    }

    private fun spawnParts(x: Float, y: Float, color: Int, count: Int, spdMul: Float) {
        val n = min(count, 80 - pCount)
        for (i in 0 until n) {
            val a = (Math.random().toFloat()) * PI.toFloat() * 2f
            val spd = (Math.random().toFloat() * 4f * s + 1f * s) * spdMul
            pX[pCount] = x; pY[pCount] = y
            pVX[pCount] = cos(a) * spd; pVY[pCount] = sin(a) * spd
            pLife[pCount] = 1f; pDecay[pCount] = 0.012f + (Math.random().toFloat()*0.02f)
            pColor[pCount] = color; pRad[pCount] = (Math.random().toFloat()*2.5f*s + 1f*s)
            pCount++
        }
    }

    private fun render(c: Canvas) {
        c.drawColor(Color.rgb(4, 4, 14))

        // Grid
        if (gridCount > 0) {
            bgPaint.color = Color.argb(25, 30, 30, 80); bgPaint.strokeWidth = 1f
            for (j in 0 until gridCount)
                c.drawLine(gridX1[j], gridY1[j], gridX2[j], gridY2[j], bgPaint)
        }

        // Center line
        bgPaint.color = Color.argb(50, 100, 200, 255); bgPaint.strokeWidth = 2f
        bgPaint.pathEffect = dashPE
        c.drawLine(W / 2f, 0f, W / 2f, H, bgPaint); bgPaint.pathEffect = null

        // Scores - no glow, clean shadow
        val scoreSize = min(60f, 60f * s); val scoreY = 50f
        sp.textSize = scoreSize; sp.typeface = Typeface.MONOSPACE
        tp.textSize = scoreSize; tp.typeface = Typeface.MONOSPACE
        tp.textAlign = Paint.Align.CENTER; sp.textAlign = Paint.Align.CENTER
        sp.color = Color.argb(35, 0, 0, 0)
        tp.color = Color.rgb(0, 255, 204)
        c.drawText("$playerScore", W/2f - 80f + 1.5f, scoreY + 1.5f, sp)
        c.drawText("$playerScore", W/2f - 80f, scoreY, tp)
        tp.color = Color.rgb(255, 51, 102)
        c.drawText("$aiScore", W/2f + 80f + 1.5f, scoreY + 1.5f, sp)
        c.drawText("$aiScore", W/2f + 80f, scoreY, tp)

        // Trail
        val tLen = min(trailHead, 12)
        for (j in 0 until tLen) {
            val idx = (trailHead - 1 - j) % 12
            val l = 1f - j * 0.085f
            if (l > 0f) {
                bgPaint.color = Color.argb((l * 50f).toInt().coerceIn(0, 50), 150, 150, 200)
                c.drawCircle(trailX[idx], trailY[idx], br * l * 0.4f, bgPaint)
            }
        }

        // Ball glow
        if (!ballInvisible || state != 2) {
            bgPaint.color = Color.argb(50, 180, 180, 255)
            bgPaint.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(ball.x, ball.y, br * 1.3f, bgPaint)
            bgPaint.color = Color.WHITE; bgPaint.maskFilter = null
            c.drawCircle(ball.x, ball.y, br, bgPaint)
        }

        // Paddles
        neonRect(c, pm - pw/2f, playerY - (if(playerGiant) ph*2f else ph)/2f,
            pw, if(playerGiant) ph*2f else ph, Color.rgb(0,255,204))
        neonRect(c, W - pm - pw/2f, aiY - (if(aiMini) ph*0.5f else ph)/2f,
            pw, if(aiMini) ph*0.5f else ph, Color.rgb(255,51,102))

        // Particles
        for (pi in 0 until pCount) {
            val a = (max(0f, pLife[pi]) * 200f).toInt().coerceIn(0, 200)
            bgPaint.alpha = a; bgPaint.color = pColor[pi]
            bgPaint.maskFilter = if (pRad[pi] > 2f) glow4 else null
            c.drawCircle(pX[pi], pY[pi], pRad[pi], bgPaint)
        }
        bgPaint.alpha = 255; bgPaint.maskFilter = null

        // Power-up
        if (puActive) {
            val pulse = 1f + sin(SystemClock.elapsedRealtime() / 200f) * 0.15f
            val col = puCol(puType)
            val pr = puR * pulse; val pcx = puX; val pcy = puY
            bgPaint.color = Color.argb(80, Color.red(col), Color.green(col), Color.blue(col))
            bgPaint.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(pcx, pcy, pr * 1.2f, bgPaint); bgPaint.maskFilter = null
            bgPaint.color = col; c.drawCircle(pcx, pcy, pr, bgPaint)
            drawPUIcon(c, pcx, pcy, pr * 0.5f, puType, col)
        }

        // Current PU indicator
        if (currentPU != null) {
            val lbl = puLbl(currentPU!!); val col = puCol(currentPU!!)
            tp.textSize = 11f * s; tp.typeface = Typeface.MONOSPACE
            tp.color = col; tp.textAlign = Paint.Align.CENTER
            sp.textSize = 11f * s; sp.typeface = Typeface.MONOSPACE
            sp.color = Color.argb(25, 0, 0, 0); sp.textAlign = Paint.Align.CENTER
            val puAlpha = (140 + (sin(SystemClock.elapsedRealtime()/300f)*0.3f*115f).toInt()).coerceIn(0,255)
            tp.color = Color.argb(puAlpha, Color.red(col), Color.green(col), Color.blue(col))
            c.drawText("$lbl ${ceil(currentPUTimer).toInt()}s", W/2f, H/2f + 65f, tp)
            tp.alpha = 255
        }

        // Title
        if (state == 0) {
            c.drawColor(Color.argb(190, 4, 4, 14))
            val ts = min(54f, 54f * s)
            sp.textSize = ts; sp.typeface = Typeface.MONOSPACE; sp.color = Color.argb(25, 0, 0, 0)
            tp.textSize = ts; tp.typeface = Typeface.MONOSPACE; tp.color = Color.rgb(0, 230, 190)
            tp.textAlign = Paint.Align.CENTER; sp.textAlign = Paint.Align.CENTER
            c.drawText("CYBER PONG", W/2f + 2f, H/2f - 40f + 2f, sp)
            c.drawText("CYBER PONG", W/2f, H/2f - 40f, tp)
            tp.textSize = min(18f, 18f * s); tp.color = Color.argb(120, 100, 200, 255)
            if ((SystemClock.elapsedRealtime()/400L).toInt()%2==0)
                c.drawText("TAP TO START", W/2f, H/2f+30f, tp)
            tp.textSize = min(10f, 12f * s); tp.color = Color.argb(45, 100, 200, 255)
            c.drawText("FIRST TO 7 WINS", W/2f, H/2f+55f, tp)
            bgPaint.color = Color.argb(45, 0, 255, 204); bgPaint.strokeWidth = 1f
            c.drawLine(W/2f-170f, H/2f-90f, W/2f+170f, H/2f-90f, bgPaint)
            c.drawLine(W/2f-170f, H/2f+90f, W/2f+170f, H/2f+90f, bgPaint)
        }

        // Serving
        if (state == 1) {
            tp.textSize = min(19f, 20f * s); tp.color = Color.argb(200, 0, 255, 204)
            tp.textAlign = Paint.Align.CENTER
            c.drawText("TAP TO SERVE", W/2f, H/2f+80f, tp)
            ball.y = H/2f + sin(SystemClock.elapsedRealtime()/300f)*15f
            bgPaint.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
            bgPaint.color = Color.argb(50, 180, 180, 255)
            c.drawCircle(W/2f, ball.y, br*1.3f, bgPaint)
            bgPaint.color = Color.WHITE; bgPaint.maskFilter = null
            c.drawCircle(W/2f, ball.y, br, bgPaint)
        }

        // Game over
        if (state == 4) {
            c.drawColor(Color.argb(140, 0, 0, 0))
            val w = playerScore >= winScore; val clr = if(w) Color.rgb(0,230,190) else Color.rgb(255,51,102)
            val gs = min(44f, 44f * s)
            sp.textSize = gs; sp.typeface = Typeface.MONOSPACE; sp.color = Color.argb(25, 0, 0, 0)
            tp.textSize = gs; tp.typeface = Typeface.MONOSPACE; tp.color = clr
            tp.textAlign = Paint.Align.CENTER; sp.textAlign = Paint.Align.CENTER
            val t1 = if(w) "YOU WIN!" else "YOU LOSE"
            c.drawText(t1, W/2f+1.5f, H/2f-30f+1.5f, sp)
            c.drawText(t1, W/2f, H/2f-30f, tp)
            val ss = min(32f, 32f*s)
            tp.textSize = ss; tp.color = Color.WHITE
            c.drawText("$playerScore - $aiScore", W/2f+1.5f, H/2f+20f+1.5f, sp)
            c.drawText("$playerScore - $aiScore", W/2f, H/2f+20f, tp)
            if ((SystemClock.elapsedRealtime()/400L).toInt()%2==0) {
                tp.textSize = min(15f, 16f * s); tp.color = Color.argb(150, 100, 200, 255)
                c.drawText("TAP TO PLAY AGAIN", W/2f, H/2f+65f, tp)
            }
        }

        // Difficulty
        if (state == 2 && difficulty > 0f) {
            tp.textSize = 10f * s; tp.color = Color.argb(35, 255, 255, 255)
            tp.textAlign = Paint.Align.CENTER
            val bars = (difficulty/10f).toInt()
            if(bars>0) c.drawText("\u25B6".repeat(bars), W/2f, H-8f, tp)
        }
    }

    // Reusable BlurMaskFilter instances
    private val glow4 = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    private val glow6 = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    private val glow10 = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    private val glow14 = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)

    private fun neonRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        val r = min(w, h) * 0.2f
        rectF.set(x, y, x+w, y+h); path.rewind()
        path.addRoundRect(rectF, r, r, Path.Direction.CW)
        bgPaint.color = color; bgPaint.maskFilter = glow6
        c.drawPath(path, bgPaint)
        bgPaint.maskFilter = null
        bgPaint.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        bgPaint.strokeWidth = 1.5f; bgPaint.style = Paint.Style.STROKE
        c.drawPath(path, bgPaint)
        bgPaint.style = Paint.Style.FILL
    }

    private fun drawPUIcon(c: Canvas, cx: Float, cy: Float, r: Float, t: PUType, col: Int) {
        val ip = bgPaint
        when (t) {
            PUType.CURVE -> {
                ip.color = Color.argb(200, 255, 255, 255)
                ip.strokeWidth = max(2f, 2.5f * r / 12f)
                ip.style = Paint.Style.STROKE; path.rewind()
                path.moveTo(cx - r, cy + r * 0.3f)
                path.quadTo(cx - r * 0.3f, cy - r, cx, cy)
                path.quadTo(cx + r * 0.3f, cy + r, cx + r, cy - r * 0.3f)
                c.drawPath(path, ip); ip.style = Paint.Style.FILL
            }
            PUType.GIANT -> {
                ip.color = Color.WHITE; val h = r * 1.2f; val w2 = r * 0.3f
                c.drawRect(cx - r*0.5f - w2, cy - h/2f, cx - r*0.5f, cy + h/2f, ip)
                c.drawRect(cx + r*0.5f, cy - h/2f, cx + r*0.5f + w2, cy + h/2f, ip)
                triPath.rewind()
                triPath.moveTo(cx - r*0.5f, cy - h*0.4f)
                triPath.lineTo(cx - r - 3f, cy)
                triPath.lineTo(cx - r*0.5f, cy + h*0.4f)
                triPath.close(); c.drawPath(triPath, ip)
                triPath.rewind()
                triPath.moveTo(cx + r*0.5f, cy - h*0.4f)
                triPath.lineTo(cx + r + 3f, cy)
                triPath.lineTo(cx + r*0.5f, cy + h*0.4f)
                triPath.close(); c.drawPath(triPath, ip)
            }
            PUType.MINI -> {
                ip.color = Color.WHITE; val h = r * 0.7f; val w2 = r * 0.3f
                c.drawRect(cx - r*0.3f - w2, cy - h/2f, cx - r*0.3f, cy + h/2f, ip)
                c.drawRect(cx + r*0.3f, cy - h/2f, cx + r*0.3f + w2, cy + h/2f, ip)
                triPath.rewind()
                triPath.moveTo(cx + r*0.3f, cy - h*0.35f)
                triPath.lineTo(cx + r + 3f, cy)
                triPath.lineTo(cx + r*0.3f, cy + h*0.35f)
                triPath.close(); c.drawPath(triPath, ip)
                triPath.rewind()
                triPath.moveTo(cx - r*0.3f, cy - h*0.35f)
                triPath.lineTo(cx - r - 3f, cy)
                triPath.lineTo(cx - r*0.3f, cy + h*0.35f)
                triPath.close(); c.drawPath(triPath, ip)
            }
            PUType.SLOWMO -> {
                ip.color = Color.WHITE; ip.style = Paint.Style.FILL
                val ts = r * 0.5f
                triPath.rewind()
                triPath.moveTo(cx - ts*0.6f, cy - ts)
                triPath.lineTo(cx - ts*0.6f, cy + ts)
                triPath.lineTo(cx + ts*0.4f, cy)
                triPath.close(); c.drawPath(triPath, ip)
                triPath.rewind()
                triPath.moveTo(cx + ts*0.2f, cy - ts)
                triPath.lineTo(cx + ts*0.2f, cy + ts)
                triPath.lineTo(cx + ts*1.2f, cy)
                triPath.close(); c.drawPath(triPath, ip)
                ip.style = Paint.Style.FILL
            }
            PUType.INVISIBLE -> {
                ip.color = Color.argb(140, 255, 255, 255); ip.style = Paint.Style.STROKE
                ip.strokeWidth = max(1.5f, 2f * r / 12f)
                c.drawCircle(cx, cy, r * 0.7f, ip)
                ip.style = Paint.Style.FILL
                ip.color = Color.argb(80, 255, 255, 255)
                c.drawCircle(cx, cy, r * 0.35f, ip)
                ip.color = Color.WHITE; ip.alpha = 255
                c.drawCircle(cx - r*0.15f, cy - r*0.1f, r*0.06f, ip)
                c.drawCircle(cx + r*0.15f, cy - r*0.1f, r*0.06f, ip)
            }
        }
    }

    private fun puLbl(t: PUType) = when(t) { PUType.CURVE->"CURVE"; PUType.GIANT->"GIANT"
        PUType.MINI->"SHRINK"; PUType.SLOWMO->"SLOW"; PUType.INVISIBLE->"GHOST" }
    private fun puCol(t: PUType) = when(t) { PUType.CURVE->Color.rgb(255,107,53)
        PUType.GIANT->Color.rgb(57,255,20); PUType.MINI->Color.rgb(255,0,85)
        PUType.SLOWMO->Color.rgb(0,212,255); PUType.INVISIBLE->Color.rgb(170,102,255) }

    // ===== SOUND =====
    private fun playBg(on: Boolean) {
        if (on && bgTrack == null) {
            try {
                val sr = 22050; val bufSize = sr * 2
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
                        try { bgTrack?.write(buf, 0, buf.size) } catch(_:Exception) { break }
                    }
                }; bgThread!!.start()
            } catch(_: Exception) {}
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
            var last = SystemClock.elapsedRealtime()
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val dt = min((now - last) / 1000f, 0.05f); last = now
                update(dt)
                holder.lockCanvas()?.let { c ->
                    try { render(c) } finally { holder.unlockCanvasAndPost(c) }
                }
                val elapsed = SystemClock.elapsedRealtime() - now
                if (elapsed < 16) { try { Thread.sleep(16 - elapsed) } catch(_: Exception) {} }
            }
        }; loopThread!!.start()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h: Int) { recalc(w.toFloat(), h.toFloat()) }
    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false; bgRun = false
        try { loopThread?.join(500) } catch(_:Exception){}
        try { bgThread?.join(300); bgTrack?.release() } catch(_:Exception){}
        try { soundPool?.release() } catch(_:Exception){}
        bgTrack = null; loopThread = null; bgThread = null; soundPool = null
    }
}
