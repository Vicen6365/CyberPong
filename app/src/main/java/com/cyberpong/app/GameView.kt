package com.cyberpong.app

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    private data class Trail(val x: Float, val y: Float, var life: Float)
    private val trail = mutableListOf<Trail>()
    private var ballCurve = 0f; private var ballInvisible = false; private var ballSlow = false

    // Paddles
    private var playerY = 0f; private var aiY = 0f
    private var playerGiant = false; private var aiMini = false
    private var aiTargetY = 0f
    private var playerGiantTimer = 0f; private var aiMiniTimer = 0f

    // Power-ups
    private enum class PUType { CURVE, GIANT, MINI, SLOWMO, INVISIBLE }
    private var puActive = false; private var puType = PUType.CURVE
    private var puX = 0f; private var puY = 0f; private var puR = 0f
    private var currentPU: PUType? = null; private var currentPUTimer = 0f
    private var nextPUSpawn = 0L

    // Particles
    private data class Part(var x: Float, var y: Float, var vx: Float, var vy: Float,
                            var life: Float, var decay: Float, val color: Int, val r: Float)
    private val parts = mutableListOf<Part>()

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; isFakeBoldText = true
    }
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; isFakeBoldText = true
    }

    // Loop
    @Volatile private var running = false
    private var loopThread: Thread? = null

    // Sound
    private val sr = 22050
    private var bgTrack: AudioTrack? = null
    @Volatile private var bgRun = false
    private var bgThread: Thread? = null

    init {
        holder.addCallback(this)
        // Software layer required for BlurMaskFilter (glow effects)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true

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
    }

    private fun startGame() {
        state = 1; playerScore = 0; aiScore = 0; difficulty = 0f; rallyTime = 0f
        playerY = H / 2f; aiY = H / 2f
        ball = Ball(W / 2f, H / 2f, 0f, 0f)
        trail.clear(); parts.clear()
        puActive = false; currentPU = null; playerGiant = false; aiMini = false
        ballCurve = 0f; ballInvisible = false; ballSlow = false
        nextPUSpawn = SystemClock.elapsedRealtime() + 8000 + (Math.random() * 4000).toLong()
        playBg(true)
    }

    private fun serve() {
        state = 2
        val dir = if (Math.random() > 0.5) 1f else -1f
        val angle = (Math.random() - 0.5) * 0.8
        val spd = sb * 1.2f
        ball.x = W / 2f; ball.y = H / 2f
        ball.vx = dir * cos(angle).toFloat() * spd
        ball.vy = sin(angle).toFloat() * spd
        if (abs(ball.vy) < 0.3f) ball.vy = if (ball.vy > 0) 0.3f else -0.3f
        rallyTime = 0f; difficulty = 0f
        trail.clear()
    }

    private fun pointScored(winner: Boolean) {
        state = 3; afterPointTimer = 1.5f
        if (winner) { state = 4; playWin() }
    }

    private fun update(dt: Float) {
        if (state != 2 && state != 3) return

        if (state == 3) {
            afterPointTimer -= dt
            if (afterPointTimer <= 0f) {
                state = 1
                playerY = H / 2f; aiY = H / 2f
                ball = Ball(W / 2f, H / 2f, 0f, 0f)
                trail.clear(); playerGiant = false; aiMini = false
                ballCurve = 0f; ballInvisible = false; ballSlow = false
                currentPU = null
                nextPUSpawn = SystemClock.elapsedRealtime() + 8000 + (Math.random() * 4000).toLong()
            }
            return
        }

        rallyTime += dt; difficulty = min(90f, (rallyTime / 10f) * 15f)

        // Power-up spawn
        if (!puActive && currentPU == null && SystemClock.elapsedRealtime() > nextPUSpawn) {
            val types = PUType.values(); val t = types[(Math.random() * types.size).toInt()]
            puX = W / 2f + ((Math.random() - 0.5) * W * 0.35f).toFloat()
            puY = H / 2f + ((Math.random() - 0.5) * H * 0.25f).toFloat()
            puR = max(14f, 22f * s); puActive = true; puType = t
            nextPUSpawn = SystemClock.elapsedRealtime() + 8000 + (Math.random() * 4000).toLong()
        }

        // Current PU timer
        if (currentPU != null) {
            currentPUTimer -= dt
            if (currentPUTimer <= 0f) {
                when (currentPU) { PUType.GIANT -> playerGiant = false
                    PUType.MINI -> aiMini = false
                    PUType.SLOWMO -> ballSlow = false
                    PUType.INVISIBLE -> ballInvisible = false
                    PUType.CURVE -> ballCurve = 0f }
                currentPU = null
            }
        }

        // Move ball
        val sm = if (ballSlow) 0.4f else 1f
        ball.x += ball.vx * sm; ball.y += ball.vy * sm

        // Curve
        if (ballCurve != 0f) ball.vy += ballCurve * 0.15f * s * sm

        // Trail
        trail.add(Trail(ball.x, ball.y, 1f))
        if (trail.size > 12) trail.removeAt(0)
        trail.forEach { it.life -= 0.08f }

        // Walls
        if (ball.y - br < 0f) { ball.y = br; ball.vy = -ball.vy; playHit(2) }
        if (ball.y + br > H) { ball.y = H - br; ball.vy = -ball.vy; playHit(2) }

        // Power-up collision
        if (puActive) {
            val dx = ball.x - puX; val dy = ball.y - puY
            if (dx*dx + dy*dy < (br + puR).pow(2)) {
                puActive = false; val side = if (ball.vx > 0f) 1 else 0
                currentPU = puType; currentPUTimer = 5f
                spawnParts(puX, puY, Color.YELLOW, 25, 4f)
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
            spawnParts(pm + pw / 2f, ball.y, Color.rgb(0,255,204), 15, 3f)
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
            spawnParts(W - pm - pw / 2f, ball.y, Color.rgb(255,51,102), 15, 3f)
            playHit(1)
        }

        // Scoring
        if (ball.x - br < 0f) { aiScore++; playScore(); afterPoint(aiScore >= winScore) }
        if (ball.x + br > W) { playerScore++; playScore(); afterPoint(playerScore >= winScore) }

        // AI
        val dir = if (ball.vx > 0f) 1 else -1
        if (dir < 0) {
            val off = (1f - difficulty / 100f) * H * 0.15f
            aiTargetY = ball.y + ((Math.random() - 0.5) * off).toFloat()
        } else aiTargetY = H/2f + ((Math.random() - 0.5) * H * 0.1f).toFloat()
        val maxSpd = max(2f, 5f + difficulty / 10f) * s
        val diff = aiTargetY - aiY
        if (abs(diff) > 3f) aiY += sign(diff) * min(abs(diff), maxSpd)
        aiY = max(aah/2f, min(H - aah/2f, aiY))

        // Player paddle
        val pSpd = max(3f, 8f * s)
        val pDiff = touchY - playerY
        if (abs(pDiff) > 2f) playerY += sign(pDiff) * min(abs(pDiff), pSpd)
        playerY = max(pph/2f, min(H - pph/2f, playerY))

        // Particles
        val it = parts.iterator()
        while (it.hasNext()) { val p = it.next(); p.x += p.vx; p.y += p.vy
            p.life -= p.decay; if (p.life <= 0f) it.remove() }
    }

    private fun afterPoint(winner: Boolean) {
        state = 3; afterPointTimer = 1.5f
        if (winner) { state = 4; playWin() }
        playScore()
        spawnParts(if (playerScore > aiScore) W else 0f, ball.y,
            if (playerScore > aiScore) Color.rgb(0,255,204) else Color.rgb(255,51,102), 40, 6f)
    }

    private fun spawnParts(x: Float, y: Float, color: Int, count: Int, spdMul: Float) {
        for (i in 0 until count) {
            val a = (Math.random() * PI * 2).toFloat()
            val spd = (Math.random() * 4f * s + 1f * s) * spdMul
            parts.add(Part(x, y, cos(a.toDouble()).toFloat()*spd, sin(a.toDouble()).toFloat()*spd, 1f,
                0.012f + (Math.random()*0.02f).toFloat(), color, (Math.random()*3f*s + 1f*s)))
        }
    }

    private fun render(c: Canvas) {
        c.drawColor(Color.rgb(5, 5, 16))

        // Grid
        paint.color = Color.argb(30, 30, 30, 80); paint.strokeWidth = 1f
        var i = 0f; while (i < W) { c.drawLine(i, 0f, i, H, paint); i += 40f }
        i = 0f; while (i < H) { c.drawLine(0f, i, W, i, paint); i += 40f }

        // Center line
        paint.color = Color.argb(60, 100, 200, 255); paint.strokeWidth = 2f
        paint.pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
        c.drawLine(W / 2f, 0f, W / 2f, H, paint); paint.pathEffect = null

        // Scores
        glowText(c, "$playerScore", W / 2f - 60f, 50f, Color.rgb(0,255,204), min(48f,48f*s))
        glowText(c, "$aiScore", W / 2f + 60f, 50f, Color.rgb(255,51,102), min(48f,48f*s))

        // Ball trail
        trail.forEach { t ->
            if (t.life > 0f) {
                paint.color = Color.argb((t.life * 80f).toInt().coerceIn(0,80), 255, 255, 255)
                c.drawCircle(t.x, t.y, br * t.life * 0.6f, paint)
            }
        }

        // Ball
        if (!ballInvisible || state != 2) {
            paint.color = Color.WHITE; paint.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(ball.x, ball.y, br, paint); paint.maskFilter = null
        }

        // Paddles
        neonRect(c, pm - pw/2f, playerY - (if(playerGiant) ph*2f else ph)/2f, pw, if(playerGiant) ph*2f else ph, Color.rgb(0,255,204))
        neonRect(c, W - pm - pw/2f, aiY - (if(aiMini) ph*0.5f else ph)/2f, pw, if(aiMini) ph*0.5f else ph, Color.rgb(255,51,102))

        // Particles
        parts.forEach { p ->
            paint.alpha = (max(0f, p.life) * 255f).toInt(); paint.color = p.color
            paint.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(p.x, p.y, p.r, paint)
        }
        paint.alpha = 255; paint.maskFilter = null

        // Power-up
        if (puActive) {
            val pulse = 1f + sin(SystemClock.elapsedRealtime()/200f)*0.15f
            val col = puCol(puType)
            paint.color = col; paint.maskFilter = BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(puX, puY, puR*pulse, paint); paint.maskFilter = null
            tp.textSize = puR * pulse; tp.color = Color.WHITE; tp.maskFilter = null
            c.drawText(puLbl(puType), puX, puY + puR * pulse * 0.35f, tp)
        }

        // Current PU indicator
        if (currentPU != null) {
            val lbl = puLbl(currentPU!!); val col = puCol(currentPU!!)
            tp.color = col; tp.alpha = (128 + (sin(SystemClock.elapsedRealtime()/300f)*0.3f*127f).toInt()).coerceIn(0,255)
            tp.textSize = 12f * s; tp.maskFilter = null
            c.drawText("$lbl ${ceil(currentPUTimer).toInt()}s", W/2f, H/2f + 60f, tp)
            tp.alpha = 255
        }

        // Title
        if (state == 0) {
            c.drawColor(Color.argb(180, 5, 5, 16))
            glowText(c, "CYBER PONG", W/2f, H/2f - 40f, Color.rgb(0,255,204), min(50f,50f*s))
            tp.color = Color.argb(128, 100, 200, 255); tp.textSize = min(16f,18f*s); tp.maskFilter = null
            if ((SystemClock.elapsedRealtime()/400L).toInt()%2==0) c.drawText("TAP TO START", W/2f, H/2f+30f, tp)
            tp.color = Color.argb(60, 100, 200, 255); tp.textSize = min(10f,12f*s)
            c.drawText("FIRST TO 7 WINS", W/2f, H/2f+60f, tp)
            paint.color = Color.argb(60, 0, 255, 204); paint.strokeWidth = 1f; paint.maskFilter = null
            c.drawLine(W/2f-150f, H/2f-90f, W/2f+150f, H/2f-90f, paint)
            c.drawLine(W/2f-150f, H/2f+90f, W/2f+150f, H/2f+90f, paint)
        }

        // Serving
        if (state == 1) {
            tp.color = Color.argb(200, 0, 255, 204); tp.textSize = min(18f,20f*s); tp.maskFilter = null
            c.drawText("TAP TO SERVE", W/2f, H/2f+80f, tp)
            ball.y = H/2f + sin(SystemClock.elapsedRealtime()/300f)*15f; ball.x = W/2f
            paint.color = Color.WHITE; paint.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(ball.x, ball.y, br, paint); paint.maskFilter = null
        }

        // Game over
        if (state == 4) {
            c.drawColor(Color.argb(150, 0, 0, 0))
            val w = playerScore >= winScore; val clr = if(w) Color.rgb(0,255,204) else Color.rgb(255,51,102)
            glowText(c, if(w)"YOU WIN!" else "YOU LOSE", W/2f, H/2f-30f, clr, min(40f,40f*s))
            glowText(c, "$playerScore - $aiScore", W/2f, H/2f+20f, Color.WHITE, min(28f,28f*s))
            if ((SystemClock.elapsedRealtime()/400L).toInt()%2==0) {
                tp.color = Color.argb(153, 100, 200, 255); tp.textSize = min(14f,16f*s); tp.maskFilter = null
                c.drawText("TAP TO PLAY AGAIN", W/2f, H/2f+65f, tp) }
        }

        // Difficulty
        if (state == 2 && difficulty > 0f) {
            tp.color = Color.argb(40, 255, 255, 255); tp.textSize = 9f*s; tp.maskFilter = null
            val bars = (difficulty/10f).toInt()
            if(bars>0) c.drawText("\u25B6".repeat(bars), W/2f, H-10f, tp)
        }
    }

    private fun glowText(c: Canvas, t: String, x: Float, y: Float, color: Int, size: Float) {
        gp.textSize = size; gp.textAlign = Paint.Align.CENTER; gp.color = color
        gp.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(t, x, y, gp)
        gp.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(t, x, y, gp); gp.maskFilter = null
    }

    private fun neonRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color; paint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        val r = min(w, h) * 0.2f
        val path = Path().apply { addRoundRect(RectF(x, y, x+w, y+h), r, r, Path.Direction.CW) }
        c.drawPath(path, paint); paint.maskFilter = null
    }

    private fun puLbl(t: PUType) = when(t) { PUType.CURVE->"~"; PUType.GIANT->"<>"
        PUType.MINI->"><"; PUType.SLOWMO->"<<"; PUType.INVISIBLE->"?" }
    private fun puCol(t: PUType) = when(t) { PUType.CURVE->Color.rgb(255,107,53)
        PUType.GIANT->Color.rgb(57,255,20); PUType.MINI->Color.rgb(255,0,85)
        PUType.SLOWMO->Color.rgb(0,212,255); PUType.INVISIBLE->Color.rgb(170,102,255) }

    // ===== SOUND =====
    private fun genTone(freq: Float, dur: Float, vol: Float): ShortArray {
        val n = (sr * dur).toInt(); val b = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat()/sr; val e = exp(-3f*t/dur)
            b[i] = (sin(2f*PI*freq*t)*Short.MAX_VALUE*vol*e).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()
        }
        return b
    }

    private fun playBuf(buf: ShortArray) {
        try {
            val at = AudioTrack.Builder().setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size*2).build()
            at.write(buf, 0, buf.size); at.play()
            Thread.sleep((buf.size.toFloat()/sr*1000).toLong())
            at.release()
        } catch (_: Exception) {}
    }

    private fun playHit(who: Int) { playBuf(genTone(floatArrayOf(880f,660f,440f)[who],0.08f,0.3f)) }
    private fun playScore() { playBuf(genTone(330f,0.2f,0.3f)) }
    private fun playWin() {
        floatArrayOf(523f,659f,784f,1047f).forEach { playBuf(genTone(it,0.25f,0.3f)); sleepMs(80) }
    }
    private fun playPowerUp() {
        floatArrayOf(1200f,1500f,1800f).forEach { playBuf(genTone(it,0.1f,0.25f)); sleepMs(50) }
    }

    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch(_: Exception) {} }

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
                val buf = ShortArray(bufSize); var ph = 0f; var lph = 0f
                while (bgRun && bgTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    for (i in buf.indices) {
                        val t = i.toFloat()/sr
                        val lfo = (sin(2f*PI*0.3f*t+lph)*0.5f+0.5f)
                        val saw = 2f*((55f*t+ph)%1f)-1f
                        buf[i] = (saw*2000f*lfo).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()
                    }
                    ph += 55f*buf.size/sr; lph += 0.3f*buf.size/sr
                    try { bgTrack?.write(buf,0,buf.size) } catch(_:Exception) { break }
                }
            }; bgThread!!.start()
            } catch(_: Exception) {}
        } else if (!on) { bgRun = false; try { bgThread?.join(300); bgTrack?.stop(); bgTrack?.release() } catch(_:Exception){}; bgTrack = null }
    }

    // ===== SURFACE =====
    override fun surfaceCreated(holder: SurfaceHolder) { recalc(width.toFloat(),height.toFloat()); running = true
        loopThread = Thread {
            var last = SystemClock.elapsedRealtime()
            while (running) {
                val now = SystemClock.elapsedRealtime(); val dt = min((now-last)/1000f,0.05f); last = now
                update(dt)
                holder.lockCanvas()?.let { c -> try { render(c) } finally { holder.unlockCanvasAndPost(c) } }
                val e = SystemClock.elapsedRealtime()-now; if(e<16) sleepMs(16-e)
            }
        }; loopThread!!.start() }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) { recalc(w.toFloat(),h.toFloat()) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { running = false; bgRun = false
        try { bgThread?.join(300); bgTrack?.release() } catch(_:Exception){}; bgTrack = null }
}
