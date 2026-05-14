package com.cyberpong.app

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class GameView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile private var state = 0; @Volatile private var touchY = 0f
    private var playerScore = 0; private var aiScore = 0
    private var difficulty = 0f; private var rallyTime = 0f; private var afterPointTimer = 0f
    private var playerY = 0f; private var aiY = 0f
    private var ballX = 0f; private var ballY = 0f; private var ballVX = 0f; private var ballVY = 0f
    private var playerGiant = false; private var aiMini = false
    private var ballCurve = 0f; private var ballInvisible = false; private var ballSlow = false
    private var currentPUType = 0; private var currentPUDur = 0f

    private var W = 0; private var H = 0; private var s = 1f
    private var pw = 12f; private var ph = 100f; private var br = 9f; private var pm = 30f; private var sb = 6f
    private var quadProg = 0; private var aPos = 0; private var uColor = 0; private var uMat = 0
    private var texProg = 0; private var aTPos = 0; private var aTTex = 0; private var uTCol = 0; private var uTMat = 0; private var uTSam = 0

    private val rv = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
    private val ri = shortArrayOf(0,1,2, 1,3,2)
    private val rvb = ByteBuffer.allocateDirect(rv.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(rv).also{it.flip()}
    private val rib = ByteBuffer.allocateDirect(ri.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().put(ri).also{it.flip()}

    private var textTex = 0; private var texW = 0; private var texH = 0
    private val fontChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!-.~><O"

    data class Part(var x:Float,var y:Float,var vx:Float,var vy:Float,var life:Float,var dec:Float,var r:Float,var cr:Float,var cg:Float,var cb:Float)
    data class Trail(var x:Float,var y:Float)
    private val parts=mutableListOf<Part>(); private val trail=mutableListOf<Trail>()

    // Power-ups
    private val puN=arrayOf("CURVE","GIANT","MINI","SLOW","GHOST")
    private val puC=intArrayOf(Color.rgb(255,107,53),Color.rgb(57,255,20),Color.rgb(255,0,85),Color.rgb(0,212,255),Color.rgb(170,102,255))
    private var puA=false;private var puT=0;private var puX=0f;private var puY=0f;private var puR=0f;private var puNext=0L

    // Sound
    private val sB=mutableListOf<ShortArray>();private val sAT=arrayOfNulls<AudioTrack>(4)
    @Volatile private var sOk=false;private var sN=0
    private var bAT:AudioTrack?=null;@Volatile private var bRun=false;private var bTh:Thread?=null

    init{setRenderer(this);renderMode=RENDERMODE_CONTINUOUSLY;sndInit()
        setOnTouchListener{_,e->when(e.action){MotionEvent.ACTION_DOWN->{touchY=e.y
            if(state==0||state==4){start();true}else if(state==1){serve();true}else false}
        MotionEvent.ACTION_MOVE->{touchY=e.y;true} else->false}} }

    // === SOUND ===
    private fun sndInit(){try{val sr=22050
        for(i in 0..3)sAT[i]=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes((sr*0.3f).toInt()*2).build()
        sB.addAll(listOf(tn(880f,0.06f,0.3f),tn(660f,0.06f,0.3f),tn(440f,0.06f,0.2f),tn(330f,0.15f,0.25f),
            tn(523f,0.2f,0.3f),tn(659f,0.2f,0.3f),tn(784f,0.2f,0.3f),tn(1047f,0.25f,0.35f),
            tn(1200f,0.1f,0.25f),tn(1500f,0.1f,0.25f),tn(1800f,0.1f,0.25f)));sOk=true}catch(_:Exception){}}
    private fun tn(f:Float,d:Float,v:Float):ShortArray{val sr=22050;val n=(sr*d).toInt();val b=ShortArray(n)
        for(i in 0 until n){val t=i.toFloat()/sr;val e=exp(-4f*t/d);val p=(f*t)%1f;val w=if(p<0.5f)4f*p-1f else 3f-4f*p
            b[i]=(w*Short.MAX_VALUE*v*e).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()};return b}
    private fun pb(b:ShortArray){if(!sOk)return;val idx=sN++ and 3;val at=sAT[idx]?:return
        try{at.stop();at.flush();at.write(b,0,b.size);at.play()}catch(_:Exception){}}
    private fun ph(w:Int){pb(sB[w])};private fun ps(){pb(sB[3])};private fun pw(){for(i in 4..7)pb(sB[i])};private fun pp(){for(i in 8..10)pb(sB[i])}
    private fun bg(on:Boolean){if(on&&bAT==null)try{val sr=22050;val bs=sr*2
        bAT=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bs*2).setTransferMode(AudioTrack.MODE_STREAM).build()
        bAT?.play();bRun=true;bTh=Thread{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val buf=ShortArray(bs);var ph=0f;var lph=0f
            while(bRun&&bAT?.playState==AudioTrack.PLAYSTATE_PLAYING){
                for(i in buf.indices){val l=(sin(2f*PI*lph)*0.5f+0.5f)*0.3f
                    buf[i]=(1500f*l*2f*((55f*i.toFloat()/sr+ph)%1f)-1f).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()}
                ph+=55f*buf.size/sr;lph+=0.3f*buf.size/sr;try{bAT?.write(buf,0,buf.size)}catch(_:Exception){break}}}
        bTh?.start()}catch(_:Exception){}else if(!on){bRun=false
            try{bTh?.join(300);bAT?.stop();bAT?.release()}catch(_:Exception){};bAT=null} }

    // === GAME ===
    private fun start(){state=1;playerScore=0;aiScore=0;difficulty=0f;rallyTime=0f;trail.clear();parts.clear()
        playerY=H/2f;aiY=H/2f;ballX=W/2f;ballY=H/2f;ballVX=0f;ballVY=0f
        playerGiant=false;aiMini=false;ballCurve=0f;ballInvisible=false;ballSlow=false
        puA=false;currentPUDur=0f;puNext=SystemClock.elapsedRealtime()+8000+(Math.random()*4000).toLong();bg(true)}
    private fun serve(){state=2;val d=if(Math.random()>0.5)1f else-1f;val a=(Math.random().toFloat()-0.5f)*0.8f;val sp=sb*1.2f
        ballX=W/2f;ballY=H/2f;ballVX=d*cos(a)*sp;ballVY=sin(a)*sp
        if(abs(ballVY)<0.3f)ballVY=if(ballVY>0)0.3f else-0.3f;rallyTime=0f;difficulty=0f}
    private fun afterPt(w:Boolean){state=3;afterPointTimer=1.5f;if(w){state=4;pw()};ps()
        val col=if(playerScore>aiScore) floatArrayOf(0f,1f,0.8f) else floatArrayOf(1f,0.2f,0.4f)
        for(i in 0 until 20){val a=Math.random().toFloat()*PI.toFloat()*2f;val sp=(Math.random().toFloat()*4f*s+1f*s)*4f
            parts.add(Part(ballX,ballY,cos(a)*sp,sin(a)*sp,1f,0.015f+Math.random().toFloat()*0.02f,Math.random().toFloat()*2.5f*s+1f*s,col[0],col[1],col[2])) } }

    // === RENDERER ===
    override fun onSurfaceCreated(gl:GL10?,cfg:EGLConfig?){
        GLES20.glClearColor(0.015f,0.015f,0.055f,1f)
        GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val vs="attribute vec2 aP;uniform mat4 uM;void main(){gl_Position=uM*vec4(aP,0.0,1.0);}"
        val fs="precision mediump float;uniform vec4 uC;void main(){gl_FragColor=uC;}"
        val tvs="attribute vec2 aP;attribute vec2 aT;uniform mat4 uM;varying vec2 vT;void main(){vT=aT;gl_Position=uM*vec4(aP,0.0,1.0);}"
        val tfs="precision mediump float;uniform vec4 uC;uniform sampler2D uS;varying vec2 vT;void main(){gl_FragColor=texture2D(uS,vT)*uC;}"
        quadProg=cs(vs,fs);texProg=cs(tvs,tfs)
        aPos=GLES20.glGetAttribLocation(quadProg,"aP");uColor=GLES20.glGetUniformLocation(quadProg,"uC");uMat=GLES20.glGetUniformLocation(quadProg,"uM")
        aTPos=GLES20.glGetAttribLocation(texProg,"aP");aTTex=GLES20.glGetAttribLocation(texProg,"aT");uTCol=GLES20.glGetUniformLocation(texProg,"uC");uTMat=GLES20.glGetUniformLocation(texProg,"uM");uTSam=GLES20.glGetUniformLocation(texProg,"uS")
        genFont() }
    private fun cs(vs:String,fs:String):Int{val v=GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);GLES20.glShaderSource(v,vs);GLES20.glCompileShader(v)
        val f=GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);GLES20.glShaderSource(f,fs);GLES20.glCompileShader(f)
        val p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){W=w;H=h;GLES20.glViewport(0,0,w,h);s=min(w/800f,h/500f)
        pw=max(8f,12f*s);ph=max(50f,100f*s);br=max(5f,9f*s);pm=max(15f,30f*s);sb=max(3f,6f*s);puR=max(14f,22f*s)
        playerY=H/2f;aiY=H/2f;start()}

    private var lastUpdate=0L
    override fun onDrawFrame(gl:GL10?){if(W==0||H==0)return;update();GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);draw()}

    private fun genFont(){
        val pt=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.MONOSPACE;isFakeBoldText=true;textSize=48f;color=Color.WHITE}
        val r=Rect();pt.getTextBounds(fontChars,0,fontChars.length,r)
        texW=r.width()+4;texH=r.height()+4
        val bmp=Bitmap.createBitmap(texW,texH,Bitmap.Config.ALPHA_8)
        val cv=Canvas(bmp);cv.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);cv.drawText(fontChars,2f,-r.top.toFloat()+2f,pt)
        val tid=IntArray(1);GLES20.glGenTextures(1,tid,0);textTex=tid[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val buf=ByteBuffer.allocateDirect(bmp.rowBytes*bmp.height);bmp.copyPixelsToBuffer(buf);buf.flip()
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_ALPHA,texW,texH,0,GLES20.GL_ALPHA,GLES20.GL_UNSIGNED_BYTE,buf);bmp.recycle()
    }

    // === UPDATE ===
    private fun update(){val now=SystemClock.elapsedRealtime()
        if(lastUpdate==0L){lastUpdate=now;return}
        val dt=min((now-lastUpdate)/1000f,0.05f);lastUpdate=now
        if(state!=2&&state!=3)return
        if(state==3){afterPointTimer-=dt
            if(afterPointTimer<=0f){state=1;playerY=H/2f;aiY=H/2f;ballX=W/2f;ballY=H/2f;ballVX=0f;ballVY=0f;trail.clear()
                playerGiant=false;aiMini=false;ballCurve=0f;ballInvisible=false;ballSlow=false;currentPUDur=0f
                puNext=SystemClock.elapsedRealtime()+6000+(Math.random()*4000).toLong()};return}
        rallyTime+=dt;difficulty=min(90f,(rallyTime/10f)*18f)
        if(!puA&&currentPUDur<=0f&&SystemClock.elapsedRealtime()>puNext){puT=(Math.random()*puN.size).toInt()
            puX=W/2f+((Math.random().toFloat()-0.5f)*W*0.35f);puY=H/2f+((Math.random().toFloat()-0.5f)*H*0.25f);puA=true
            puNext=SystemClock.elapsedRealtime()+8000+(Math.random()*4000).toLong()}
        if(currentPUDur>0f){currentPUDur-=dt
            if(currentPUDur<=0f){when(currentPUType){1->playerGiant=false;2->aiMini=false;3->ballSlow=false;4->ballInvisible=false;0->ballCurve=0f};currentPUDur=0f}}
        val sm=if(ballSlow)0.4f else 1f;ballX+=ballVX*sm;ballY+=ballVY*sm
        if(ballCurve!=0f)ballY+=ballCurve*0.15f*s*sm
        trail.add(Trail(ballX,ballY));if(trail.size>12)trail.removeAt(0)
        if(ballY-br<0f){ballY=br;ballVY=-ballVY;ph(2)}
        if(ballY+br>H){ballY=H.toFloat()-br;ballVY=-ballVY;ph(2)}
        // PU collect
        if(puA){val dx=ballX-puX;val dy=ballY-puY
            if(dx*dx+dy*dy<(br+puR).pow(2)){puA=false;pp();currentPUType=puT;currentPUDur=5f
                for(i in 0 until 12){val a=Math.random().toFloat()*PI.toFloat()*2f;val sp=(Math.random().toFloat()*3f*s+1f*s)*2f
                    parts.add(Part(puX,puY,cos(a)*sp,sin(a)*sp,1f,0.015f+Math.random().toFloat()*0.02f,Math.random().toFloat()*2f*s+1f*s,1f,1f,0f))}
                val side=if(ballVX>0f)1 else 0
                when(puT){0->if(side==0)ballCurve=if(Math.random()>0.5)1f else-1f;1->if(side==0)playerGiant=true;2->if(side==1)aiMini=true;3->if(side==0)ballSlow=true;4->if(side==0)ballInvisible=true}}}
        // Player hit
        val pph=if(playerGiant)ph*2f else ph
        if(ballX-pm-pw/2f<=br&&ballX+br>=pm-pw/2f&&ballY+br>playerY-pph/2f&&ballY-br<playerY+pph/2f&&ballVX<0f){
            val hp=(ballY-playerY)/(pph/2f);val ang=hp*PI.toFloat()/3f;val sp=sqrt(ballVX*ballVX+ballVY*ballVY)*1.03f
            ballVX=cos(ang)*sp;ballVY=sin(ang)*sp;ballX=pm+pw/2f+br+1f;ballCurve=0f;ph(0)
            for(i in 0 until 6){val a=Math.random().toFloat()*PI.toFloat()*2f;val s=(Math.random().toFloat()*3f*s+1f*s)*2f
                parts.add(Part(pm+pw/2f,ballY,cos(a)*s,sin(a)*s,1f,0.015f+Math.random().toFloat()*0.02f,Math.random().toFloat()*2f*s+1f*s,0f,1f,0.8f))}}
        // AI hit
        val aah=if(aiMini)ph*0.5f else ph
        if(ballX+br>=W.toFloat()-pm-pw/2f&&ballX-br<=W.toFloat()-pm+pw/2f&&ballY+br>aiY-aah/2f&&ballY-br<aiY+aah/2f&&ballVX>0f){
            val hp=(ballY-aiY)/(aah/2f);val ang=hp*PI.toFloat()/3f;val sp=sqrt(ballVX*ballVX+ballVY*ballVY)*1.03f
            ballVX=-cos(ang)*sp;ballVY=sin(ang)*sp;ballX=W.toFloat()-pm-pw/2f-br-1f;ph(1)
            for(i in 0 until 6){val a=Math.random().toFloat()*PI.toFloat()*2f;val s=(Math.random().toFloat()*3f*s+1f*s)*2f
                parts.add(Part(W.toFloat()-pm-pw/2f,ballY,cos(a)*s,sin(a)*s,1f,0.015f+Math.random().toFloat()*0.02f,Math.random().toFloat()*2f*s+1f*s,1f,0.2f,0.4f))}}
        // Score
        if(ballX-br<0f){aiScore++;afterPt(aiScore>=7)}
        if(ballX+br>W){playerScore++;afterPt(playerScore>=7)}
        // AI
        val at=if(ballVX>0f)ballY+((Math.random().toFloat()-0.5f)*max(0.1f,1f-difficulty/100f)*H*0.15f)
            else H/2f+((Math.random().toFloat()-0.5f)*H*0.1f)
        val asp=max(2f,5f+difficulty/10f)*s;val dd=at-aiY
        if(abs(dd)>3f)aiY+=sign(dd)*min(abs(dd),asp)
        aiY=max(aah/2f,min(H.toFloat()-aah/2f,aiY))
        // Player
        val psp=max(3f,8f*s);val pd=touchY-playerY
        if(abs(pd)>2f)playerY+=sign(pd)*min(abs(pd),psp)
        playerY=max(pph/2f,min(H.toFloat()-pph/2f,playerY))
        // Particles
        for(i in parts.indices.reversed()){val p=parts[i];p.x+=p.vx;p.y+=p.vy;p.life-=p.dec;if(p.life<=0f)parts.removeAt(i)}
    }

    // === DRAW ===
    private fun draw(){
        // Grid (static)
        GLES20.glUseProgram(quadProg);GLES20.glUniform4f(uColor,0.01f,0.01f,0.03f,0.08f)
        var i=0f;val sx=2f/W;val sy=2f/H
        GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,0,rvb);GLES20.glEnableVertexAttribArray(aPos)
        while(i<W){val m=floatArrayOf(0f,0f,0f,0f,0f,sy,0f,0f,0f,0f,1f,0f,-1f+sx*i,-1f,0f,1f)
            m[0]=sx*0.5f
            GLES20.glUniformMatrix4fv(uMat,1,false,m,0);GLES20.glDrawElements(GLES20.GL_LINES,2,GLES20.GL_UNSIGNED_SHORT,rib);i+=60f}
        i=0f;while(i<H){val m=floatArrayOf(sx,0f,0f,0f,0f,0f,0f,0f,0f,0f,1f,0f,-1f,-1f+sy*i,0f,1f)
            m[5]=sy*0.5f
            GLES20.glUniformMatrix4fv(uMat,1,false,m,0);GLES20.glDrawElements(GLES20.GL_LINES,2,GLES20.GL_UNSIGNED_SHORT,rib);i+=60f}
        // Center line dashes
        GLES20.glUniform4f(uColor,0.1f,0.2f,0.4f,0.3f)
        i=0f;while(i<H){val m=floatArrayOf(sx*1.5f,0f,0f,0f,0f,sy*4f,0f,0f,0f,0f,1f,0f,-1f+sx*W/2f,-1f+sy*(i+2f),0f,1f)
            GLES20.glUniformMatrix4fv(uMat,1,false,m,0);GLES20.glDrawElements(GLES20.GL_TRIANGLES,6,GLES20.GL_UNSIGNED_SHORT,rib);i+=16f}
        GLES20.glDisableVertexAttribArray(aPos)

        // Scores via font texture
        drawT(if(playerScore<10)"0$playerScore" else"$playerScore",W/2f-100f*H/500f,50f*H/500f,min(60f,60f*s),0f,1f,0.8f,1f)
        drawT(if(aiScore<10)"0$aiScore" else"$aiScore",W/2f+40f*H/500f,50f*H/500f,min(60f,60f*s),1f,0.2f,0.4f,1f)

        // Trail
        for((j,t) in trail.withIndex()){val l=1f-j*0.085f;if(l>0f)drC(t.x,t.y,br*l*0.4f,0.15f*l,0.15f*l,0.2f*l,l*0.12f)}
        // Ball
        if(!ballInvisible||state!=2){drC(ballX,ballY,br*2.5f,0.08f,0.08f,0.15f,0.12f);drC(ballX,ballY,br*1.5f,0.6f,0.6f,0.8f,0.3f)
            drR(ballX-br*0.5f,ballY-br*0.5f,br,br,1f,1f,1f,1f)}
        // Paddles
        val pph=if(playerGiant)ph*2f else ph;val aah=if(aiMini)ph*0.5f else ph
        drR(pm-pw/2f,playerY-pph/2f,pw,pph,0f,1f,0.8f,0.6f);drR(pm-pw/2f-3f,playerY-pph/2f-3f,pw+6f,pph+6f,0f,0.6f,0.5f,0.06f)
        drR(W.toFloat()-pm-pw/2f,aiY-aah/2f,pw,aah,1f,0.2f,0.4f,0.6f);drR(W.toFloat()-pm-pw/2f-3f,aiY-aah/2f-3f,pw+6f,aah+6f,0.6f,0.1f,0.25f,0.06f)
        // Particles
        for(p in parts){val aa=(max(0f,p.life)*0.7f).coerceIn(0f,0.7f)
            drC(p.x,p.y,p.r*1.2f,p.cr,p.cg,p.cb,aa*0.2f);drC(p.x,p.y,p.r,p.cr,p.cg,p.cb,aa)}
        // PU
        if(puA){val pl=1f+sin(SystemClock.elapsedRealtime()/200f)*0.15f;val i=puT
            val cr=Color.red(puC[i])/255f;val cg=Color.green(puC[i])/255f;val cb=Color.blue(puC[i])/255f
            drC(puX,puY,puR*pl*1.5f,cr,cg,cb,0.08f);drC(puX,puY,puR*pl,cr,cg,cb,0.6f)
            drawT(puN[i].take(1),puX-5f,puY-10f,16f*H/500f,1f,1f,1f,0.9f)}
        if(currentPUDur>0f){val ci=currentPUType;val ca=(0.5f+sin(SystemClock.elapsedRealtime()/300f)*0.3f).coerceIn(0.2f,0.8f)
            drawT("${puN[ci]} ${ceil(currentPUDur).toInt()}s",W/2f-55f*H/500f,H/2f+70f,14f*H/500f,Color.red(puC[ci])/255f,Color.green(puC[ci])/255f,Color.blue(puC[ci])/255f,ca)}
        // Overlays
        when(state){0->{drawT("CYBER PONG",W/2f-130f*H/500f,H/2f-50f,55f*H/500f,0f,0.9f,0.75f,1f)
            if((SystemClock.elapsedRealtime()/400L).toInt()%2==0)drawT("TAP TO START",W/2f-70f*H/500f,H/2f+20f,18f*H/500f,0.4f,0.8f,1f,0.5f)}
            1->{drawT("TAP TO SERVE",W/2f-65f*H/500f,H/2f+70f,20f*H/500f,0f,1f,0.8f,0.5f)
                val by=H/2f+sin(SystemClock.elapsedRealtime()/300f)*15f
                drC(W/2f,by,br*2.5f,0.08f,0.08f,0.15f,0.1f);drC(W/2f,by,br*1.5f,0.6f,0.6f,0.8f,0.3f);drR(W/2f-br*0.5f,by-br*0.5f,br,br,1f,1f,1f,1f)}
            4->{val w=playerScore>=7;val cr=if(w)0f else 1f;val cg=if(w)0.9f else 0.2f;val cb=if(w)0.75f else 0.4f
                drawT(if(w)"YOU WIN!"else"YOU LOSE",W/2f-90f*H/500f,H/2f-40f,44f*H/500f,cr,cg,cb,1f)
                drawT("$playerScore - $aiScore",W/2f-50f*H/500f,H/2f+10f,28f*H/500f,1f,1f,1f,1f)
                if((SystemClock.elapsedRealtime()/400L).toInt()%2==0)drawT("TAP TO PLAY AGAIN",W/2f-95f*H/500f,H/2f+60f,16f*H/500f,0.4f,0.8f,1f,0.5f)} }
        if(state==2&&difficulty>0f){val bars=(difficulty/10f).toInt();if(bars>0)drawT("■".repeat(bars),W/2f-bars*5f,H.toFloat()-12f,10f*H/500f,0.3f,0.3f,0.3f,0.3f)}
    }

    // Drawing helpers
    private fun drR(x:Float,y:Float,w:Float,h:Float,r:Float,g:Float,b:Float,a:Float){
        GLES20.glUseProgram(quadProg);GLES20.glUniform4f(uColor,r,g,b,a)
        val sx=2f/W;val sy=2f/H;val m=floatArrayOf(sx*w*0.5f,0f,0f,0f,0f,sy*h*0.5f,0f,0f,0f,0f,1f,0f,-1f+sx*x+sx*w*0.5f,-1f+sy*y+sy*h*0.5f,0f,1f)
        GLES20.glUniformMatrix4fv(uMat,1,false,m,0)
        GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,0,rvb);GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES,6,GLES20.GL_UNSIGNED_SHORT,rib);GLES20.glDisableVertexAttribArray(aPos)
    }
    private fun drC(x:Float,y:Float,r:Float,cr:Float,cg:Float,cb:Float,ca:Float)=drR(x-r,y-r,r*2f,r*2f,cr,cg,cb,ca)

    // Text rendering with font texture
    private val tc4=FloatArray(4);private val tBuf=ByteBuffer.allocateDirect(24*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private fun drawT(txt:String,x:Float,y:Float,sz:Float,r:Float,g:Float,b:Float,a:Float){
        if(textTex==0)return;val cw=sz*0.6f;val sx=2f/W;val sy=2f/H
        val pt=Paint().apply{typeface=Typeface.MONOSPACE;textSize=48f}
        GLES20.glUseProgram(texProg);GLES20.glUniform4f(uTCol,r,g,b,a);GLES20.glUniform1i(uTSam,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textTex)
        var cx=x
        for(ch in txt){val idx=fontChars.indexOf(ch);if(idx<0){cx+=cw*0.5f;continue}
            val cwTex=pt.measureText(ch.toString())
            val u0=(fontChars.substring(0,idx).let{pt.measureText(it)+2f})/texW.toFloat()
            val u1=(fontChars.substring(0,idx+1).let{pt.measureText(it)+2f})/texW.toFloat()
            val v0=0f;val v1=1f
            val cW2=cw*0.55f;val cH2=sz;val m=floatArrayOf(sx*cW2*0.5f,0f,0f,0f,0f,sy*cH2*0.5f,0f,0f,0f,0f,1f,0f,-1f+sx*cx+sx*cW2*0.5f,-1f+sy*y+sy*cH2*0.5f,0f,1f)
            tBuf.clear();val t=tBuf
            t.put(-1f);t.put(-1f);t.put(u0);t.put(v1)
            t.put(1f);t.put(-1f);t.put(u1);t.put(v1)
            t.put(-1f);t.put(1f);t.put(u0);t.put(v0)
            t.put(1f);t.put(-1f);t.put(u1);t.put(v1)
            t.put(1f);t.put(1f);t.put(u1);t.put(v0)
            t.put(-1f);t.put(1f);t.put(u0);t.put(v0)
            t.flip()
            GLES20.glVertexAttribPointer(aTPos,2,GLES20.GL_FLOAT,false,24,t);GLES20.glEnableVertexAttribArray(aTPos)
            t.position(2);GLES20.glVertexAttribPointer(aTTex,2,GLES20.GL_FLOAT,false,24,t);GLES20.glEnableVertexAttribArray(aTTex)
            GLES20.glUniformMatrix4fv(uTMat,1,false,m,0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GLES20.glDisableVertexAttribArray(aTPos);GLES20.glDisableVertexAttribArray(aTTex)
            cx+=cw+cw*0.03f }
    }

    override fun onDetachedFromWindow(){super.onDetachedFromWindow()
        bRun=false;try{bTh?.join(300);bAT?.stop();bAT?.release()}catch(_:Exception){}
        sAT.forEach{try{it?.stop();it?.release()}catch(_:Exception){}} }
}
