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

    private var W=800f; private var H=500f; private var s=1f
    private var pw=12f; private var ph=100f; private var br=9f; private var pm=30f; private var sb=6f

    @Volatile private var state=0; @Volatile private var touchY=0f
    private var pScore=0; private var aScore=0; private val win=7
    private var diff=0f; private var rTime=0f; private var apTimer=0f

    private var bx=0f; private var by=0f; private var bvx=0f; private var bvy=0f
    private val tX=FloatArray(12); private val tY=FloatArray(12); private val tL=FloatArray(12)
    private var tH=0; private var ballC=0f; private var ballI=false; private var ballS=false

    private var padY=0f; private var aiY=0f; private var giant=false; private var mini=false
    private var puA=false; private var puT=0; private var puX=0f; private var puY=0f; private var puR=0f
    private var cuPU=-1; private var cuTimer=0f; private var spawnPU=0L

    private val puN=arrayOf("CURVE","GIANT","MINI","SLOW","GHOST")
    private val puC=intArrayOf(Color.rgb(255,107,53),Color.rgb(57,255,20),Color.rgb(255,0,85),Color.rgb(0,212,255),Color.rgb(170,102,255))

    // Menu settings
    private var aiDiff=1 // 0=easy 1=medium 2=expert
    private var pIdx=0 // player color index
    private val pal=intArrayOf(Color.rgb(0,200,160),Color.rgb(57,255,20),Color.rgb(255,107,53),
        Color.rgb(0,212,255),Color.rgb(255,0,170),Color.rgb(170,102,255))
    private val pCol get()=pal[pIdx];private val aCol get()=pal[(pIdx+3)%pal.size]

    // Particles (pooled arrays)
    private val arrX=FloatArray(80); private val arrY=FloatArray(80)
    private val arrVX=FloatArray(80); private val arrVY=FloatArray(80)
    private val arrL=FloatArray(80); private val arrD=FloatArray(80)
    private val arrCR=IntArray(80); private val arrCG=IntArray(80); private val arrCB=IntArray(80)
    private val arrRad=FloatArray(80); private var pCnt=0

    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.MONOSPACE;isFakeBoldText=true;textAlign=Paint.Align.CENTER}

    @Volatile private var running=false; private var loop:Thread?=null

    // Sound pools
    private val sr=22050; private val sBuf=Array(4){genTone(0f,0.05f,0f)}
    private val sAT=arrayOfNulls<AudioTrack>(4); private var sI=0
    private var bAT:AudioTrack?=null; @Volatile private var bRun=false; private var bTh:Thread?=null

    init{
        holder.addCallback(this)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable=true
        for(i in 0..3) try{sAT[i]=AudioTrack.Builder().setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes((sr*0.3f).toInt()*2).build()}catch(_:Exception){}
        setOnTouchListener{_,e->when(e.action){MotionEvent.ACTION_DOWN->{touchY=e.y
            if(state==0){menuTap(e.x,e.y);true}else{when(state){4->start();1->serve()};true}}
        MotionEvent.ACTION_MOVE->{touchY=e.y;true} else->false}}
    }

    private fun rcalc(w:Float,hh:Float){W=w;H=hh;s=min(w/800f,hh/500f)
        pw=max(8f,12f*s);ph=max(50f,100f*s);br=max(5f,9f*s);pm=max(15f,30f*s);sb=max(3f,6f*s);puR=max(14f,22f*s)}

    private fun menuTap(x:Float,y:Float){
        val cx=W/2f;val bw=min(140f,110f*s);val bh=min(60f,42f*s);val gap=min(16f,12f*s)
        val bx=cx-(bw*3f+gap*2f)/2f;val by=170f*s
        // Difficulty buttons
        for(i in 0..2){val l=bx+i*(bw+gap)
            if(x>l&&x<l+bw&&y>by&&y<by+bh){aiDiff=i;return}}
        // Color swatches
        val cs=min(36f,30f*s);val cg=min(24f,18f*s);val cRow=(pal.size+1)/2
        val cw=cRow*(cs+cg)-cg;val cy=260f*s
        for(i in pal.indices){val row=i/2;val col=i%2
            val cl=cx-cw/2f+row*(cs+cg);val ct=cy+col*(cs+cg)
            if((x-cl).pow(2)+(y-ct).pow(2)<cs*cs){pIdx=i;return}}
        // Start button
        val sW=min(180f,150f*s);val sH=min(60f,48f*s);val sX=cx-sW/2f;val sY=350f*s
        if(x>sX&&x<sX+sW&&y>sY&&y<sY+sH){start()}
    }

    private fun start(){state=1;pScore=0;aScore=0;diff=0f;rTime=0f;padY=H/2f;aiY=H/2f
        bx=W/2f;by=H/2f;bvx=0f;bvy=0f;tH=0;pCnt=0;giant=false;mini=false;ballC=0f;ballI=false;ballS=false
        puA=false;cuPU=-1;spawnPU=SystemClock.elapsedRealtime()+8000+(Math.random()*4000).toLong();bg(true)}

    private fun serve(){state=2;val d=if(Math.random()>0.5)1f else-1f;val a=(Math.random().toFloat()-0.5f)*0.8f;val sp=sb*1.2f
        bx=W/2f;by=H/2f;bvx=d*cos(a.toDouble()).toFloat()*sp;bvy=sin(a.toDouble()).toFloat()*sp
        if(abs(bvy)<0.3f)bvy=if(bvy>0)0.3f else-0.3f;rTime=0f;diff=0f;tH=0}

    private fun ap(w:Boolean){state=3;apTimer=1.5f;if(w){state=4;plw()};pls()
        val col=if(pScore>aScore) intArrayOf(Color.red(pCol),Color.green(pCol),Color.blue(pCol))
            else intArrayOf(Color.red(aCol),Color.green(aCol),Color.blue(aCol))
        spawnP(col,20,6f)}

    private fun spawnP(col:IntArray,cnt:Int,sm:Float){
        val n=min(cnt,80-pCnt)
        for(i in 0 until n){val a=Math.random().toDouble()*PI*2.0;val sp=(Math.random().toFloat()*4f*s+1f*s)*sm
            arrX[pCnt]=bx;arrY[pCnt]=by;arrVX[pCnt]=(cos(a)*sp).toFloat();arrVY[pCnt]=(sin(a)*sp).toFloat();arrL[pCnt]=1f;arrD[pCnt]=0.012f+Math.random().toFloat()*0.02f
            arrCR[pCnt]=col[0];arrCG[pCnt]=col[1];arrCB[pCnt]=col[2];arrRad[pCnt]=Math.random().toFloat()*3f*s+1f*s;pCnt++}}

    private fun genTone(f:Float,d:Float,v:Float):ShortArray{
        val n=(sr*d).toInt();val b=ShortArray(n)
        for(i in 0 until n){val t=i.toFloat()/sr;val e=exp(-3f*t/d)
            b[i]=(sin(2f*PI*f*t)*Short.MAX_VALUE*v*e).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()}
        return b}

    private fun playBuf(b:ShortArray){val idx=sI++ and 3;val at=sAT[idx]?:return
        try{at.stop();at.flush();at.write(b,0,b.size);at.play()}catch(_:Exception){}}
    private fun ph(who:Int){sBuf[who]=genTone(floatArrayOf(880f,660f,440f)[who],0.06f,0.3f);playBuf(sBuf[who])}
    private fun pls(){sBuf[3]=genTone(330f,0.15f,0.25f);playBuf(sBuf[3])}
    private fun plw(){for(i in 4..7)playBuf(genTone(floatArrayOf(523f,659f,784f,1047f)[i-4],0.2f,0.3f))}
    private fun ppu(){for(i in 8..10)playBuf(genTone(floatArrayOf(1200f,1500f,1800f)[i-8],0.1f,0.25f))}

    private fun bg(on:Boolean){
        if(on&&bAT==null)try{val bs=sr*2
            bAT=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bs*2).setTransferMode(AudioTrack.MODE_STREAM).build()
            bAT?.play();bRun=true;bTh=Thread{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val buf=ShortArray(bs);var ph=0f;var lph=0f
                while(bRun&&bAT?.playState==AudioTrack.PLAYSTATE_PLAYING){for(i in buf.indices){val t=i.toFloat()/sr;val l=(sin(2f*PI*lph)*0.5f+0.5f)*0.3f
                        buf[i]=(1500f*l*2f*((55f*t+ph)%1f)-1f).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()}
                    ph+=55f*buf.size/sr;lph+=0.3f*buf.size/sr;try{bAT?.write(buf,0,buf.size)}catch(_:Exception){break}}}
            bTh?.start()}catch(_:Exception){}else if(!on){bRun=false
            try{bTh?.join(300);bAT?.stop();bAT?.release()}catch(_:Exception){};bAT=null}}

    // === UPDATE ===
    private var lastFrame=0L
    private fun update(dt:Float){
        if(state!=2&&state!=3)return
        if(state==3){apTimer-=dt
            if(apTimer<=0f){state=1;padY=H/2f;aiY=H/2f;bx=W/2f;by=H/2f;bvx=0f;bvy=0f;tH=0
                giant=false;mini=false;ballC=0f;ballI=false;ballS=false;cuPU=-1
                spawnPU=SystemClock.elapsedRealtime()+6000+(Math.random()*4000).toLong()};return}
        rTime+=dt;diff=min(90f,(rTime/10f)*18f)
        if(!puA&&cuPU<0&&SystemClock.elapsedRealtime()>spawnPU){puT=(Math.random()*puN.size).toInt()
            puX=W/2f+((Math.random().toFloat()-0.5f)*W*0.35f);puY=H/2f+((Math.random().toFloat()-0.5f)*H*0.25f);puA=true
            spawnPU=SystemClock.elapsedRealtime()+8000+(Math.random()*4000).toLong()}
        if(cuPU>=0){cuTimer-=dt
            if(cuTimer<=0f){when(cuPU){1->giant=false;2->mini=false;3->ballS=false;4->ballI=false;0->ballC=0f};cuPU=-1}}
        val sm=if(ballS)0.4f else 1f;bx+=bvx*sm;by+=bvy*sm
        if(ballC!=0f)by+=ballC*0.15f*s*sm
        tX[tH%12]=bx;tY[tH%12]=by;tL[tH%12]=1f;tH++
        if(by-br<0f){by=br;bvy=-bvy;ph(2)}
        if(by+br>H){by=H.toFloat()-br;bvy=-bvy;ph(2)}
        if(puA){val dx=bx-puX;val dy=by-puY
            if(dx*dx+dy*dy<(br+puR).pow(2)){puA=false;ppu();cuPU=puT;cuTimer=5f
                val col=intArrayOf(255,255,0);spawnP(col,10,2f)
                val sd=if(bvx>0f)1 else 0
                when(puT){0->if(sd==0)ballC=if(Math.random()>0.5)1f else-1f;1->if(sd==0)giant=true;2->if(sd==1)mini=true;3->if(sd==0)ballS=true;4->if(sd==0)ballI=true}}}
        val pph=if(giant)ph*2f else ph
        if(bx-pm-pw/2f<=br&&bx+br>=pm-pw/2f&&by+br>padY-pph/2f&&by-br<padY+pph/2f&&bvx<0f){
            val hp=(by-padY)/(pph/2f);val ang=hp*PI/3.0;val spd=sqrt((bvx*bvx+bvy*bvy).toDouble()).toFloat()*1.03f
            bvx=cos(ang).toFloat()*spd;bvy=sin(ang).toFloat()*spd;bx=pm+pw/2f+br+1f;ballC=0f;ph(0)
            spawnP(intArrayOf(Color.red(pCol),Color.green(pCol),Color.blue(pCol)),6,2f)}
        val aah=if(mini)ph*0.5f else ph
        if(bx+br>=W.toFloat()-pm-pw/2f&&bx-br<=W.toFloat()-pm+pw/2f&&by+br>aiY-aah/2f&&by-br<aiY+aah/2f&&bvx>0f){
            val hp=(by-aiY)/(aah/2f);val ang=hp*PI/3.0;val spd=sqrt((bvx*bvx+bvy*bvy).toDouble()).toFloat()*1.03f
            bvx=-cos(ang).toFloat()*spd;bvy=sin(ang).toFloat()*spd;bx=W.toFloat()-pm-pw/2f-br-1f;ph(1)
            spawnP(intArrayOf(Color.red(aCol),Color.green(aCol),Color.blue(aCol)),6,2f)}
        if(bx-br<0f){aScore++;pls();ap(aScore>=win)}
        if(bx+br>W){pScore++;pls();ap(pScore>=win)}
        val errBase=floatArrayOf(0.5f,0.25f,0.1f)[aiDiff];val spdBase=floatArrayOf(3f,5f,8f)[aiDiff]
        val at=if(bvx>0f) by+((Math.random().toFloat()-0.5f)*max(errBase,1.5f-diff/60f)*H*0.25f)
            else H/2f+((Math.random().toFloat()-0.5f)*H*0.15f)
        val asp=max(2f,spdBase+diff/25f)*s;val dd=at-aiY
        if(abs(dd)>3f)aiY+=sign(dd)*min(abs(dd),asp)
        aiY=max(aah/2f,min(H.toFloat()-aah/2f,aiY))
        padY=touchY.coerceIn(pph/2f,H.toFloat()-pph/2f)
        var pi=0;while(pi<pCnt){arrX[pi]+=arrVX[pi];arrY[pi]+=arrVY[pi];arrL[pi]-=arrD[pi]
            if(arrL[pi]<=0f){pCnt--;if(pi<pCnt){val li=pCnt;arrX[pi]=arrX[li];arrY[pi]=arrY[li];arrVX[pi]=arrVX[li];arrVY[pi]=arrVY[li]
                    arrL[pi]=arrL[li];arrD[pi]=arrD[li];arrCR[pi]=arrCR[li];arrCG[pi]=arrCG[li];arrCB[pi]=arrCB[li];arrRad[pi]=arrRad[li]}}else pi++}
        // Decay trail
        for(i in 0 until min(tH,12))tL[i]*=0.92f
    }

    // === RENDER ===
    private fun render(c:Canvas){
        c.drawColor(Color.rgb(5,5,16))
        // Field border
        paint.style=Paint.Style.STROKE;paint.strokeWidth=max(2f,3f*s)
        paint.color=Color.argb(80,255,255,255);c.drawRect(0f,0f,W,H,paint)
        paint.style=Paint.Style.FILL;paint.strokeWidth=0f
        // Grid
        paint.color=Color.argb(20,30,30,80);paint.strokeWidth=1f
        var i=0f;while(i<W){c.drawLine(i,0f,i,H,paint);i+=60f}
        i=0f;while(i<H){c.drawLine(0f,i,W,i,paint);i+=60f}
        // Center dashes
        paint.color=Color.argb(50,100,200,255);paint.strokeWidth=2f
        i=0f;while(i<H){c.drawLine(W/2f,i,W/2f,min(i+8f,H),paint);i+=16f}
        // Paddle colors (for score hints below)
        val pc=pCol;val ac=aCol
        // Scores
        tp.textSize=min(56f,56f*s);tp.color=pc
        c.drawText(pScore.toString(),W/2f-80f,55f,tp)
        tp.color=ac
        c.drawText(aScore.toString(),W/2f+80f,55f,tp)
        // Trail
        val th=min(tH,12)
        for(j in 0 until th){val ti=(tH-1-j)%12;val l=max(0f,tL[ti])
            val alpha=(l*80f).toInt().coerceIn(0,80)
            paint.color=Color.argb(alpha,255,255,255);c.drawCircle(tX[ti],tY[ti],br*l*0.6f,paint)}
        // Ball
        if(!ballI||state!=2){
            paint.color=Color.argb(50,200,200,255);c.drawCircle(bx,by,br*1.5f,paint)
            paint.color=Color.WHITE;c.drawCircle(bx,by,br*0.8f,paint)
        }
        // Paddles
        val pph=if(giant)ph*2f else ph;val aah=if(mini)ph*0.5f else ph
        nrRect(c,pm-pw/2f,padY-pph/2f,pw,pph,pc)
        nrRect(c,W-pm-pw/2f,aiY-aah/2f,pw,aah,ac)
        // Particles
        for(pi in 0 until pCnt){
            val al=(max(0f,arrL[pi])*200f).toInt().coerceIn(0,200)
            paint.alpha=al;paint.color=Color.rgb(arrCR[pi],arrCG[pi],arrCB[pi])
            c.drawCircle(arrX[pi],arrY[pi],arrRad[pi],paint)}
        paint.alpha=255
        // PU
        if(puA){val pl=1f+sin(SystemClock.elapsedRealtime()/200.0).toFloat()*0.15f
            paint.color=puC[puT];paint.alpha=40;c.drawCircle(puX,puY,puR*pl*1.3f,paint)
            paint.alpha=200;c.drawCircle(puX,puY,puR*pl*0.8f,paint);paint.alpha=255
            tp.textSize=puR*pl*0.7f;tp.color=Color.WHITE
            c.drawText(puN[puT].take(1),puX,puY+puR*pl*0.35f,tp)}
        if(cuPU>=0){val ci=cuPU;val al=(128+(sin(SystemClock.elapsedRealtime()/300.0)*0.3f*127f).toInt()).coerceIn(0,255)
            tp.textSize=14f*s;tp.color=Color.argb(al,Color.red(puC[ci]),Color.green(puC[ci]),Color.blue(puC[ci]))
            c.drawText("${puN[ci]} ${ceil(cuTimer).toInt()}s",W/2f,H/2f+70f,tp)}
        // Menu
        if(state==0){val cx=W/2f
            // Title
            tp.textSize=min(58f,58f*s);tp.color=Color.rgb(0,255,204)
            c.drawText("CYBER PONG",cx,100f*s,tp)
            // Difficulty label
            val lblSize=min(16f,18f*s)
            tp.textSize=lblSize;tp.color=Color.argb(180,200,200,200)
            c.drawText("DIFFICULTY",cx,150f*s,tp)
            // Difficulty buttons
            val bw=min(140f,110f*s);val bh=min(60f,42f*s);val gap=min(16f,12f*s)
            val bx=cx-(bw*3f+gap*2f)/2f;val by=155f*s
            val diffs=arrayOf("EASY","MEDIUM","EXPERT")
            paint.style=Paint.Style.FILL
            for(i in 0..2){val l=bx+i*(bw+gap);val sel=i==aiDiff
                paint.color=if(sel)Color.argb(60,0,255,200)else Color.argb(30,100,100,100)
                paint.alpha=if(sel)60 else 30;c.drawRoundRect(l,by,l+bw,by+bh,bh*0.3f,bh*0.3f,paint)
                if(sel){paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
                    paint.color=Color.rgb(0,255,200);c.drawRoundRect(l,by,l+bw,by+bh,bh*0.3f,bh*0.3f,paint)
                    paint.style=Paint.Style.FILL}
                tp.textSize=lblSize;tp.color=if(sel)Color.rgb(0,255,200)else Color.rgb(150,150,150)
                c.drawText(diffs[i],l+bw/2f,by+bh/2f+lblSize*0.35f,tp)}
            // Paddle label
            tp.textSize=lblSize;tp.color=Color.argb(180,200,200,200)
            c.drawText("PADDLE COLOR",cx,240f*s,tp)
            // Color swatches
            val cs=min(36f,30f*s);val cg=min(24f,18f*s);val cRow=3
            val cw=cRow*(cs+cg)-cg;val cy=250f*s
            for(i in pal.indices){val col=i%cRow;val row=i/cRow
                val cl=cx-cw/2f+col*(cs+cg);val ct=cy+row*(cs+cg)
                paint.color=pal[i];paint.alpha=if(i==pIdx)255 else 150
                c.drawCircle(cl,ct,cs/2f,paint)
                if(i==pIdx){paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
                    paint.color=Color.WHITE;c.drawCircle(cl,ct,cs/2f+2f,paint)
                    paint.style=Paint.Style.FILL}}
            // Start button
            val sW=min(180f,150f*s);val sH=min(60f,48f*s);val sX=cx-sW/2f;val sY=340f*s
            paint.color=Color.argb(50,0,255,200);c.drawRoundRect(sX,sY,sX+sW,sY+sH,sH*0.3f,sH*0.3f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
            paint.color=Color.rgb(0,255,200);c.drawRoundRect(sX,sY,sX+sW,sY+sH,sH*0.3f,sH*0.3f,paint)
            paint.style=Paint.Style.FILL
            tp.textSize=min(22f,24f*s);tp.color=Color.rgb(0,255,200)
            c.drawText("START",cx,sY+sH/2f+min(22f,24f*s)*0.35f,tp)}
        // Serving
        if(state==1){tp.textSize=min(18f,20f*s);tp.color=Color.argb(200,0,255,204)
            c.drawText("TAP TO SERVE",W/2f,240f,tp)
            by=H/2f+sin(SystemClock.elapsedRealtime()/300.0).toFloat()*15f;bx=W/2f
            paint.color=Color.argb(50,200,200,255);c.drawCircle(bx,by,br*1.5f,paint)
            paint.color=Color.WHITE;c.drawCircle(bx,by,br*0.8f,paint)}
        // Game over
        if(state==4){c.drawColor(Color.argb(150,0,0,0))
            val w=pScore>=7;val clr=if(w)pc else ac
            tp.textSize=min(42f,42f*s);tp.color=clr
            c.drawText(if(w)"YOU WIN!"else"YOU LOSE",W/2f,150f,tp)
            tp.textSize=min(28f,28f*s);tp.color=Color.WHITE
            c.drawText("$pScore - $aScore",W/2f,200f,tp)
            tp.textSize=min(14f,16f*s);tp.color=Color.argb(153,100,200,255)
            if((SystemClock.elapsedRealtime()/400L).toInt()%2==0)c.drawText("TAP TO PLAY AGAIN",W/2f,260f,tp)}
        // Diff
        if(state==2&&diff>0f){val bars=(diff/10f).toInt()
            if(bars>0){tp.textSize=10f*s;tp.color=Color.argb(40,255,255,255)
                c.drawText("\u25B6".repeat(bars),W/2f,H.toFloat()-10f,tp)}}
    }

    private fun nrRect(c:Canvas,x:Float,y:Float,w:Float,hh:Float,col:Int){
        paint.color=col;paint.alpha=20;c.drawRoundRect(x-3f,y-3f,x+w+3f,y+hh+3f,min(w,hh)*0.2f,min(w,hh)*0.2f,paint)
        paint.alpha=200;c.drawRoundRect(x,y,x+w,y+hh,min(w,hh)*0.2f,min(w,hh)*0.2f,paint);paint.alpha=255
    }

    // === SURFACE ===
    override fun surfaceCreated(sh:SurfaceHolder){rcalc(width.toFloat(),height.toFloat());running=true
        loop=Thread{var lt=SystemClock.elapsedRealtime()
            while(running){val now=SystemClock.elapsedRealtime();val dt=min((now-lt)/1000f,0.05f);lt=now
                update(dt);sh.lockCanvas()?.let{cv->try{render(cv)}finally{sh.unlockCanvasAndPost(cv)}}
                val e=SystemClock.elapsedRealtime()-now;if(e<16)try{Thread.sleep(16-e)}catch(_:Exception){}}}
        loop?.start()}
    override fun surfaceChanged(sh:SurfaceHolder,fmt:Int,w:Int,hh:Int){rcalc(w.toFloat(),hh.toFloat())}
    override fun surfaceDestroyed(sh:SurfaceHolder){running=false;bRun=false
        try{bTh?.join(300);bAT?.release()}catch(_:Exception){};bAT=null}

    override fun onDetachedFromWindow(){super.onDetachedFromWindow()
        bRun=false;try{bTh?.join(300);bAT?.stop();bAT?.release()}catch(_:Exception){}
        sAT.forEach{try{it?.stop();it?.release()}catch(_:Exception){}}}
}
