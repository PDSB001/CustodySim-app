package com.custodysim.app.ui.library

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import com.custodysim.app.ui.theme.LocalEffects
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal class NativePdfView(context: Context) : View(context), ReaderEngine {
    private lateinit var document: PdfDocument
    constructor(context: Context, document: PdfDocument) : this(context) { this.document = document; count = document.renderer.pageCount }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pages = mutableMapOf<Int, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val drawRect = RectF()
    private var current = 0
    private var count = 1
    private var generation = 0
    private var target: Job? = null
    private var tileJob: Job? = null
    private var tiles: List<Pair<Rect, Bitmap>> = emptyList()
    private var tileScale = 0f
    private var dimensions = 1 to 1
    private var animator: ValueAnimator? = null
    private var shift = 0f
    private var baseShift = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pinched = false
    private var tracker: VelocityTracker? = null
    private var queue = ArrayDeque<Boolean>()
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    var animate = true
    var scrolling = false
    var onPosition: (ReaderPosition) -> Unit = {}
    var onControls: () -> Unit = {}
    var onZoom: (Float) -> Unit = {}
    var onFailure: (String) -> Unit = {}
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinched = true; parent?.requestDisallowInterceptTouchEvent(true)
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 3f); constrainPan(); onZoom(zoom); invalidate(); return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { requestTiles() }
    })
    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (index in current-1..current+1) {
            val bitmap=pages[index] ?: continue
            val fit=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            val scale=if(index==current) zoom else 1f
            val left=(width-bitmap.width*fit*scale)/2 + (index-current)*width + shift + if(index==current)panX else 0f
            val top=(height-bitmap.height*fit*scale)/2 + if(index==current)panY else 0f
            drawRect.set(left,top,left+bitmap.width*fit*scale,top+bitmap.height*fit*scale)
            canvas.drawBitmap(bitmap,null,drawRect,paint)
        }
        if(zoom>1 && shift==0f && tileScale>0f) {
            val scale=fitScale()*zoom
            val left=(width-dimensions.first*scale)/2+panX;val top=(height-dimensions.second*scale)/2+panY
            val ratio=scale/tileScale
            tiles.forEach { (rect,bitmap) -> drawRect.set(left+rect.left*ratio,top+rect.top*ratio,left+rect.right*ratio,top+rect.bottom*ratio)
                canvas.drawBitmap(bitmap,null,drawRect,paint) }
        }
    }
    private fun fitScale() = minOf(width.toFloat()/dimensions.first,height.toFloat()/dimensions.second)
    private fun constrainPan() {
        val scale=fitScale()*zoom
        panX=panX.coerceIn(-maxOf(0f,(dimensions.first*scale-width)/2),maxOf(0f,(dimensions.first*scale-width)/2))
        panY=panY.coerceIn(-maxOf(0f,(dimensions.second*scale-height)/2),maxOf(0f,(dimensions.second*scale-height)/2))
    }
    fun setZoom(value: Float) { if(abs(value-zoom)<.001f)return;zoom=value.coerceIn(1f,3f);panX=0f;panY=0f;onZoom(zoom);requestTiles();invalidate() }
    private fun requestTiles() {
        tileJob?.cancel()
        if(zoom<=1f||width==0||height==0){tiles=emptyList();tileScale=0f;return}
        val token=generation;val page=current;val scale=fitScale()*zoom
        val left=(width-dimensions.first*scale)/2+panX;val top=(height-dimensions.second*scale)/2+panY
        val area=Rect(floor(-left).toInt().coerceAtLeast(0),floor(-top).toInt().coerceAtLeast(0),
            ceil(width-left).toInt().coerceAtMost(ceil(dimensions.first*scale).toInt()),
            ceil(height-top).toInt().coerceAtMost(ceil(dimensions.second*scale).toInt()))
        tileJob=scope.launch {
            delay(80)
            val rendered=mutableListOf<Pair<Rect,Bitmap>>()
            try {
                for(y in area.top until area.bottom step 1024)for(x in area.left until area.right step 1024) {
                    val rect=Rect(x,y,minOf(x+1024,area.right),minOf(y+1024,area.bottom))
                    rendered.add(rect to document.tile(page,scale,rect))
                    ensureActive()
                }
                if(token==generation&&page==current){tiles=rendered;tileScale=scale;invalidate()}
            } catch(cancelled: CancellationException){throw cancelled} catch(_:Exception){/* Preview remains readable. */}
        }
    }
    private fun position()=ReaderPosition(ReaderAnchor(current),current+1,count,current==0,current==count-1)
    private fun publish(){contentDescription="第 ${current+1} 页，共 $count 页";onPosition(position())}
    override fun location(result:(ReaderPosition)->Unit){result(position())}
    override fun find(query:String,result:(Int,Int)->Unit){result(0,0)}
    override fun goTo(anchor:ReaderAnchor){cancel();load(anchor.chapter.coerceIn(0,count-1),false)}
    override fun turn(forward:Boolean,animated:Boolean,boundary:()->Unit){
        if(target?.isActive==true||animator!=null){if(queue.size<2)queue.addLast(forward);return}
        val index=current+if(forward)1 else -1
        if(index !in 0 until count){boundary();return}
        load(index,animated)
    }
    private fun load(index:Int,animated:Boolean){
        val token=++generation;target?.cancel();tileJob?.cancel()
        target=scope.launch {
            try {
                val bitmap=document.page(index)
                val size=withContext(Dispatchers.IO){document.dimensions(index)}
                if(token!=generation)return@launch
                pages[index]=bitmap
                val direction=(index-current).coerceIn(-1,1)
                fun complete(){
                    current=index;dimensions=size;shift=0f;zoom=1f;panX=0f;panY=0f;tiles=emptyList();tileScale=0f
                    pages.keys.filter{abs(it-current)>1}.forEach(pages::remove)
                    animator=null;target=null;onZoom(zoom);invalidate();publish()
                    for(neighbour in listOf(current+1,current-1).filter{!scrolling && it in 0 until count})scope.launch{
                        runCatching{document.page(neighbour,false)}.getOrNull()?.let{if(abs(neighbour-current)<=1){pages[neighbour]=it;invalidate()}}
                    }
                    if(queue.isNotEmpty())turn(queue.removeFirst(),animate){}
                }
                if(!animated||pages[current]==null||direction==0){complete();return@launch}
                val from=shift;val to=-direction*width.toFloat()
                animator=ValueAnimator.ofFloat(from,to).apply{
                    duration=TurnPolicy.duration(to-from,width.toFloat());interpolator=android.view.animation.DecelerateInterpolator(1.5f)
                    addUpdateListener{shift=it.animatedValue as Float;invalidate()}
                    addListener(object:android.animation.AnimatorListenerAdapter(){
                        private var cancelled=false
                        override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                        override fun onAnimationEnd(animation:android.animation.Animator){if(!cancelled)complete()}
                    });start()
                }
            }catch(cancelled:CancellationException){throw cancelled}catch(problem:Exception){target=null;shift=0f;invalidate();onFailure(problem.message?:"这一页无法显示")}
        }
    }
    override fun cancel(){generation++;target?.cancel();target=null;animator?.cancel();animator=null;queue.clear();shift=0f;invalidate()}
    override fun performClick():Boolean{super.performClick();onControls();return true}
    override fun onTouchEvent(event:MotionEvent):Boolean{
        detector.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{if(pages[current]!=null){animator?.cancel();animator=null;target?.cancel();target=null;generation++;queue.clear()}
                downX=event.x;downY=event.y;lastX=event.x;lastY=event.y;baseShift=shift;moved=false;pinched=false
                tracker?.recycle();tracker=VelocityTracker.obtain();tracker?.addMovement(event)}
            MotionEvent.ACTION_MOVE->{
                tracker?.addMovement(event)
                if(detector.isInProgress||event.pointerCount!=1)return true
                val dx=event.x-downX;val dy=event.y-downY
                if(abs(dx)+abs(dy)>android.view.ViewConfiguration.get(context).scaledTouchSlop)moved=true
                if(zoom>1f){panX+=event.x-lastX;panY+=event.y-lastY;constrainPan()}
                else if(!scrolling&&abs(dx)>abs(dy)*1.5){shift=(baseShift+dx).coerceIn(-width.toFloat(),width.toFloat());if(current==0&&shift>0||current==count-1&&shift<0)shift*=.18f}
                lastX=event.x;lastY=event.y;invalidate()
            }
            MotionEvent.ACTION_UP->{
                tracker?.computeCurrentVelocity(1000)
                val velocity=tracker?.xVelocity?:0f
                tracker?.recycle();tracker=null
                if(!moved&&!pinched&&!detector.isInProgress){performClick();return true}
                if(zoom>1f||pinched){requestTiles();return true}
                if(scrolling)return true
                val density=resources.displayMetrics.density
                val direction=TurnPolicy.direction(shift/density,velocity/density/1000,width/density)
                if(direction!=0&&current+direction in 0 until count)load(current+direction,animate)else{shift=0f;invalidate()}
                tracker?.recycle();tracker=null
            }
            MotionEvent.ACTION_CANCEL->{shift=0f;tracker?.recycle();tracker=null;invalidate()}
        }
        return true
    }
    fun close(){cancel();tileJob?.cancel();scope.cancel();pages.clear();tiles=emptyList();tracker?.recycle();tracker=null}
}

@Composable
internal fun NativePdfReader(document:PdfDocument,controller:DocumentController,page:Int,zoom:Float,tone:String,mode:String,
    onZoom:(Float)->Unit,onControls:()->Unit,onFailure:(String)->Unit,modifier:Modifier=Modifier){
    val zoomCallback by rememberUpdatedState(onZoom);val controls by rememberUpdatedState(onControls);val failure by rememberUpdatedState(onFailure)
    val reduce=LocalEffects.current.reduceMotion
    if(mode!="paged") {
        NativePdfScroll(document,controller,page,zoom,tone,onZoom,onControls,onFailure,modifier)
        return
    }
    AndroidView(modifier=modifier,factory={context->NativePdfView(context,document).apply{
        val publish=controller.attach(this,"pdf|${document.file.name}",ReaderAnchor(page-1))
        controller.animateTurns=!reduce;animate=!reduce
        this.onZoom={zoomCallback(it)};this.onControls={controls()};this.onFailure={failure(it)}
        onPosition=publish;goTo(ReaderAnchor(page-1))
    }},update={view->view.animate=!reduce;view.setBackgroundColor((when(tone){"night"->"#1c1d21";"day"->"#ffffff";else->"#f8f2e6"}).toColorInt());view.setZoom(zoom)},
        onRelease={view->if(controller.engine===view){controller.engine=null;controller.loaded=false};view.close()})
}
