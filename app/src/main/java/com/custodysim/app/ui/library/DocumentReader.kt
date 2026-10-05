package com.custodysim.app.ui.library

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.animation.PathInterpolator
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import org.json.JSONObject

internal data class DocumentChapter(val path: String, val title: String, val html: String, val text: String,
    val start: Int, val length: Int, val linear: Boolean, val styles: String? = null,
    val viewportWidth: Int = 0, val viewportHeight: Int = 0)
internal data class DocumentEntry(val title: String, val chapter: Int, val fragment: String, val depth: Int)
internal data class ReadingDocument(val chapters: List<DocumentChapter>, val toc: List<DocumentEntry>,
    val pages: Int, val revision: String, val readerKey: String, val fixed: Boolean, val styles: String, val startChapter: Int) {
    fun chapterAt(page: Int): Int = chapters.indexOfLast { it.linear && it.start <= (page - 1) * 2000 }.coerceAtLeast(0)
    companion object {
        fun from(json: JSONObject): ReadingDocument {
            require(json.optInt("version") == 1) { "阅读文档版本不兼容，请更新 App" }
            val chapters = json.getJSONArray("chapters")
            val toc = json.getJSONArray("toc")
            return ReadingDocument((0 until chapters.length()).map { i -> chapters.getJSONObject(i).let {
                DocumentChapter(it.getString("path"), it.getString("title"), it.getString("html"), it.getString("text"),
                    it.getInt("start"), it.getInt("length"), it.optBoolean("linear", true),
                    if (it.has("styles")) it.optString("styles") else null,
                    it.optInt("viewportWidth").coerceIn(0, 20000), it.optInt("viewportHeight").coerceIn(0, 20000))
            } }, (0 until toc.length()).map { i -> toc.getJSONObject(i).let {
                DocumentEntry(it.getString("title"), it.getInt("chapter"), it.optString("fragment"), it.optInt("depth").coerceIn(0, 5))
            } }, json.getInt("pages"), json.getString("revision"), json.getString("readerKey"), json.optString("layout") == "fixed", json.optString("styles"), json.optInt("startChapter"))
                .also { require(it.chapters.isNotEmpty()) { "文档没有可阅读章节" } }
        }
    }
}

/** Only the app supplies script. Book markup is sanitized by the document endpoint. */
internal class DocumentController {
    var engine: ReaderEngine? = null
    private val coordinator = ReaderCoordinator()
    fun attach(active: ReaderEngine, identity: String, initial: ReaderAnchor): (ReaderPosition) -> Unit {
        engine?.cancel(); engine = active; loaded = false; anchor = initial
        val generation = coordinator.open(identity, initial)
        return { position -> if (engine === active && coordinator.commit(identity, generation, position.anchor)) publish(position) }
    }
    var onCommitted: ((ReaderPosition) -> Unit)? = null
    var anchor = ReaderAnchor(0)
        private set
    fun publish(position: ReaderPosition) {
        anchor = position.anchor
        screenPage = position.screen; screenPages = position.screens
        scrollFraction = position.anchor.scrollFraction ?: 0f
        canTurnBackward = !position.atStart; canTurnForward = !position.atEnd
        loaded = true
        onCommitted?.invoke(position)
    }
    fun goToChapter(chapter: Int, offset: Int, fragment: String = "", fraction: Float? = null): Boolean {
        val active = engine ?: return false
        active.goTo(ReaderAnchor(chapter, offset, fragment, fraction))
        return true
    }
    var view: WebView? = null
    var screenPage by mutableIntStateOf(1)
    var screenPages by mutableIntStateOf(1)
    var scrollFraction by mutableFloatStateOf(0f)
        private set
    var canTurnBackward by mutableStateOf(false)
        private set
    var canTurnForward by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
    private var pendingTurn: WebView? = null
    val isTurning: Boolean get() = pendingTurn != null || previousFrame != null
    private var turnSequence = 0L
    private var locationSequence = 0L
    var animateTurns = false
    var previousFrame by mutableStateOf<Bitmap?>(null)
        private set
    var transitionProgress by mutableFloatStateOf(1f)
        private set
    var transitionDirection by mutableIntStateOf(1)
        private set
    private var animator: ValueAnimator? = null
    private var findSequence = 0L
    private val animationHandler = Handler(Looper.getMainLooper())
    fun turn(forward: Boolean, boundary: () -> Unit) {
        engine?.let { it.turn(forward, animateTurns, boundary); return }
        val web = view ?: return
        if (!loaded || pendingTurn === web || previousFrame != null) return
        val animated = animateTurns
        val sequence = ++turnSequence
        locationSequence++
        pendingTurn = web
        web.evaluateJavascript("Reader.turn(${if (forward) 1 else -1},$animated)") {
            if (view !== web || turnSequence != sequence) return@evaluateJavascript
            if (it == "false") {
                captureBoundary(web, sequence, forward, animated, boundary)
            } else if (it == "true") {
                val complete = Runnable {
                    if (turnSequence != sequence || pendingTurn !== web) return@Runnable
                    pendingTurn = null
                    if (view === web) location { }
                }
                if (animated) {
                    // Background WebView renderer callbacks can be throttled. The native input
                    // lock must expire independently; JS still rejects overlapping transitions.
                    animationHandler.postDelayed(complete, 700)
                    val deadline = android.os.SystemClock.uptimeMillis() + 600
                    fun awaitFinish() {
                        if (pendingTurn !== web || view !== web || turnSequence != sequence) return
                        web.evaluateJavascript("Reader.isAnimating()") { busy ->
                            if (busy == "true" && android.os.SystemClock.uptimeMillis() < deadline)
                                animationHandler.postDelayed({ awaitFinish() }, 20)
                            else complete.run()
                        }
                    }
                    animationHandler.postDelayed({ awaitFinish() }, 220)
                } else complete.run()
            } else { pendingTurn = null; cancelTransition() }
        }
    }
    private fun captureBoundary(web: WebView, sequence: Long, forward: Boolean, animated: Boolean, boundary: () -> Unit) {
        var context = web.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
        fun complete(frame: Bitmap?) {
            if (view !== web || pendingTurn !== web || turnSequence != sequence) {
                frame?.recycle()
                return
            }
            pendingTurn = null
            previousFrame = frame
            transitionDirection = if (forward) 1 else -1
            transitionProgress = if (frame != null) 0f else 1f
            boundary()
        }
        if (!animated || !web.isAttachedToWindow || window == null || web.width <= 0 || web.height <= 0) {
            complete(null); return
        }
        // Read the GPU surface asynchronously. Software WebView.draw() blocks the UI thread and
        // can deadlock a renderer callback; PixelCopy also avoids a fresh full-document paint.
        val position = IntArray(2).also { web.getLocationInWindow(it) }
        val frame = createBitmap(web.width, web.height)
        runCatching {
            PixelCopy.request(window, Rect(position[0], position[1], position[0] + web.width, position[1] + web.height),
                frame, { result ->
                    if (result == PixelCopy.SUCCESS) complete(frame)
                    else { frame.recycle(); complete(null) }
                }, animationHandler)
            animationHandler.postDelayed({
                if (pendingTurn === web && turnSequence == sequence) complete(null)
            }, 350)
        }.onFailure { frame.recycle(); complete(null) }
    }
    fun finishTransition(web: WebView) {
        val frame = previousFrame ?: return
        if (animator != null) return
        val start = Runnable {
            if (view !== web || previousFrame !== frame || animator != null) return@Runnable
            val transition = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = PathInterpolator(.25f, .1f, .25f, 1f)
                addUpdateListener { transitionProgress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (previousFrame === frame) { previousFrame = null; transitionProgress = 1f; animator = null }
                    }
                })
            }
            animator = transition
            transition.start()
        }
        web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { start.run() }
        })
        // A detached/offscreen view may not produce a draw callback. Never leave input locked.
        animationHandler.postDelayed(start, 160)
    }
    fun cancelTransition() {
        engine?.cancel()
        turnSequence++
        locationSequence++
        pendingTurn = null
        previousFrame = null
        animator?.cancel(); animator = null
        transitionProgress = 1f
        animationHandler.removeCallbacksAndMessages(null)
    }
    fun release(web: WebView) {
        // Compose can create the next chapter's WebView before releasing the previous one.
        if (pendingTurn === web) { turnSequence++; pendingTurn = null }
        web.setFindListener(null)
        if (view === web) { loaded = false; view = null }
    }
    fun beginDocument(web: WebView) {
        loaded = false
        locationSequence++
        findSequence++
        turnSequence++
        pendingTurn = null
        web.setFindListener(null)
    }
    fun jump(offset: Int, fragment: String = "", scrollFraction: Float? = null) {
        engine?.let { it.goTo(ReaderAnchor(anchor.chapter, offset, fragment, scrollFraction)); return }
        val web = view ?: return
        val sequence = ++locationSequence
        val fraction = scrollFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.toString() ?: "null"
        web.evaluateJavascript("Reader.jump(${offset.coerceAtLeast(0)},${JSONObject.quote(fragment)},$fraction)") {
            if (view === web && loaded && locationSequence == sequence) location { }
        }
    }
    fun resize(web: WebView) {
        val density = web.resources.displayMetrics.density
        if (web.width > 0 && web.height > 0) {
            locationSequence++
            web.evaluateJavascript("Reader.resize(${web.width / density},${web.height / density})", null)
        }
    }
    fun location(result: (Int) -> Unit) {
        engine?.let { active -> active.location { position -> if (engine === active) { publish(position); result(position.anchor.offset) } }; return }
        if (!loaded || pendingTurn != null) return
        val web = view ?: return
        val sequence = locationSequence
        web.evaluateJavascript("JSON.stringify(Reader.location())") { value ->
            if (view !== web || !loaded || locationSequence != sequence) return@evaluateJavascript
            runCatching {
                val decoded = org.json.JSONArray("[$value]").getString(0)
                val json = JSONObject(decoded)
                screenPage = json.optInt("screen", 1)
                screenPages = json.optInt("screens", 1)
                scrollFraction = json.optDouble("scrollFraction", 0.0).toFloat().coerceIn(0f, 1f)
                canTurnBackward = !json.optBoolean("atStart", screenPage <= 1)
                canTurnForward = !json.optBoolean("atEnd", screenPage >= screenPages)
                result(json.optInt("offset"))
            }
        }
    }
    fun find(query: String, onMatches: (Int, Int) -> Unit) {
        engine?.let { it.find(query, onMatches); return }
        val web = view?.takeIf { loaded } ?: return
        val sequence = ++findSequence
        val navigation = locationSequence
        web.setFindListener { active, count, done ->
            if (done && view === web && loaded && findSequence == sequence && locationSequence == navigation)
                onMatches(if (count > 0) active + 1 else 0, count)
        }
        if (query.isBlank()) { web.clearMatches(); onMatches(0, 0) } else web.findAllAsync(query)
    }
}

/** EPUB manifest assets are not required to have extensions. In particular, a
 * Calibre SVG cover must not be served as JPEG merely because its name is "cover". */
internal fun readerImageMime(path: String, bytes: ByteArray): String {
    val prefix = bytes.take(1024).toByteArray().toString(Charsets.UTF_8)
    return when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        prefix.startsWith("GIF87a") || prefix.startsWith("GIF89a") -> "image/gif"
        prefix.length >= 12 && prefix.startsWith("RIFF") && prefix.substring(8, 12) == "WEBP" -> "image/webp"
        Regex("<svg(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(prefix) -> "image/svg+xml"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".avif") -> "image/avif"
        else -> "image/jpeg"
    }
}

/** Shared with real-WebView instrumentation tests: page geometry must match the visible reading area. */
internal fun readerHtml(content: String, styles: String = "", background: String = "#f8f2e6", ink: String = "#32312d",
    night: Boolean = false, font: Int = 19, lineHeight: Float = 1.85f, family: String = "serif", paged: Boolean = true) =
    """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"/>
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://reader.invalid data:; style-src 'unsafe-inline'; script-src 'nonce-reader'; base-uri 'none'; form-action 'none'"/>
    <style>$styles</style><style>
    :root{color-scheme:${if (night) "dark" else "light"};background:$background;color:$ink}
    *{box-sizing:border-box}body{margin:0;padding:24px 18px;font-family:${if (family == "serif") "serif" else "sans-serif"};font-size:${font}px;line-height:$lineHeight;overflow-wrap:break-word}
    #reader-flow{max-width:720px;margin:0 auto}p{margin:0 0 1em;text-align:justify}h1,h2,h3,h4{line-height:1.4;margin:1.4em 0 .8em;break-after:avoid}h1{font-size:1.55em}h2{font-size:1.3em}h3{font-size:1.15em}
    img{max-width:100%;height:auto;object-fit:contain;break-inside:avoid}figure{margin:1.2em 0;text-align:center}figcaption,caption{font-size:.85em;opacity:.7}blockquote{margin:1em 0;padding:0 1em;border-left:3px solid #8888}
    table{border-collapse:collapse;max-width:100%;font-size:.9em}td,th{border:1px solid #8886;padding:.4em;overflow-wrap:anywhere}pre{white-space:pre-wrap}a{color:${if (night) "#82aeff" else "#3478f6"};text-decoration:none}ul,ol{padding-left:1.6em}.reader-unavailable{font-size:.8em;opacity:.6}
    ${if (paged) "html,body{width:100%;height:100%;overflow:hidden;touch-action:none;overscroll-behavior:none}body{padding:0}#reader-viewport{position:absolute;top:0;left:0;width:100vw;height:100vh;overflow:hidden;touch-action:none;overscroll-behavior:none;scrollbar-width:none}#reader-flow{position:absolute;left:18px;top:24px;width:calc(100vw - 36px);height:calc(100vh - 48px);max-width:none;margin:0;column-width:calc(100vw - 36px);column-gap:36px;column-fill:auto}#reader-tail{position:absolute;top:0;width:1px;height:1px;pointer-events:none}#reader-flow figure{margin:.5em 0}#reader-flow img{max-height:var(--reader-image-height,calc(100vh - 96px))}" else ""}
    </style></head><body><div id="reader-viewport"><main id="reader-flow">$content</main><i id="reader-tail" aria-hidden="true"></i></div>
    <script nonce="reader">${readerScript(paged)}</script></body></html>"""

private fun readerScript(paged: Boolean) = """
const Reader=(()=>{
 const paged=$paged, flow=document.getElementById('reader-flow');
 const viewport=document.getElementById('reader-viewport'),tail=document.getElementById('reader-tail');
 const spans=Array.from(flow.querySelectorAll('[data-reader-text-offset]'));
 let geometry=null;
 const pageAnchors=new Map();
 let visibleSpan=0,measuredScreen=0,continuousAnchor=null,lastScrollTime=-Infinity,warmEpoch=0,warmHandle=0,hintTimer=0;
 function layout(){
  if(!geometry){
   const box=flow.getBoundingClientRect();
   geometry={width:box.width+36,height:box.height+48,
    screens:Math.max(1,Math.ceil((flow.scrollWidth+36-1)/(box.width+36))),
    maxScroll:Math.max(0,document.documentElement.scrollHeight-innerHeight),column:getComputedStyle(flow).columnWidth};
   if(paged)tail.style.left=(geometry.screens*geometry.width-1)+'px';
  }
  return geometry;
 }
 function invalidateLayout(){
  geometry=null;pageAnchors.clear();continuousAnchor=null;visibleSpan=0;measuredScreen=0;warmEpoch++;
  if(warmHandle&&typeof cancelIdleCallback==='function')cancelIdleCallback(warmHandle);
  warmHandle=0;
 }
 function prepareScroll(){
  viewport.style.willChange='scroll-position';clearTimeout(hintTimer);
  hintTimer=setTimeout(()=>{viewport.style.willChange='auto';},1200);
 }
 const width=()=>layout().width;
 const height=()=>layout().height;
 const maxScroll=()=>layout().maxScroll;
 const fraction=()=>maxScroll()>0?Math.max(0,Math.min(1,scrollY/maxScroll())):0;
 const screens=()=>layout().screens;
 let screen=0,anchor=0,animating=false,animationTimeout,restoration=null,dragOffset=0,animationFrame=0;
 let notifyTimer=0;
 function notifyLocation(){
  clearTimeout(notifyTimer);
  notifyTimer=setTimeout(()=>{if(!animating&&dragOffset===0)action('relocated');},80);
 }
 function stopAnimation(){
  cancelAnimationFrame(animationFrame);animationFrame=0;
  clearTimeout(animationTimeout);animating=false;
 }
 function scrollPage(target,animated){
  prepareScroll();
  stopAnimation();
  const from=viewport.scrollLeft,to=target*width();
  dragOffset=0;screen=target;
  if(!animated||Math.abs(to-from)<1){viewport.scrollLeft=to;notifyLocation();return;}
  animating=true;
  // Scroll a bounded viewport, not a GPU layer containing the whole DOCX/TXT book.
  // The next gesture may take over from the current visual position immediately.
  const duration=Math.max(90,Math.min(200,200*Math.abs(to-from)/width()));
  let start=null;
  const step=now=>{
   if(start===null)start=now;
   const t=Math.min(1,(now-start)/duration);
   viewport.scrollLeft=from+(to-from)*(1-Math.pow(1-t,3));
   if(t<1)animationFrame=requestAnimationFrame(step);
   else finishPageAnimation();
  };
  animationFrame=requestAnimationFrame(step);
  animationTimeout=setTimeout(finishPageAnimation,duration+200);
 }

 function show(next){
  dragOffset=0;
  screen=Math.max(0,Math.min(next,screens()-1));
  viewport.scrollLeft=screen*width();
  window.scrollTo(0,0);
 }
 function rects(element){
  const range=document.createRange();range.selectNodeContents(element);
  const values=Array.from(range.getClientRects());
  return values.length?values:[element.getBoundingClientRect()];
 }
 const visible=r=>r.bottom>24&&r.top<(paged?height():innerHeight)-24&&r.right>18&&r.left<(paged?width():innerWidth)-18;
 const textMaps=new WeakMap();
 function textMap(element){
  const node=element.firstChild;
  if(!node||node.nodeType!==Node.TEXT_NODE)return null;
  const cached=textMaps.get(element);
  if(cached&&cached.node===node&&cached.text===node.textContent)return cached;
  const chars=Array.from(node.textContent),units=[0];
  for(const char of chars)units.push(units[units.length-1]+char.length);
  const value={node,text:node.textContent,chars,units};textMaps.set(element,value);return value;
 }
 function characterRect(element,index){
  // Offsets count Unicode characters; DOM Range uses UTF-16 units. Keep surrogate
  // pairs intact and reuse their index mapping rather than copy a prefix for every range.
  const map=textMap(element);
  if(!map)return rects(element)[0];
  const point=Math.max(0,Math.min(index,map.chars.length));
  const range=document.createRange();range.setStart(map.node,map.units[point]);
  range.setEnd(map.node,map.units[Math.min(point+1,map.chars.length)]);
  return range.getClientRects()[0]||rects(element)[0];
 }
 function firstVisibleCharacter(element,isVisible=visible){
  const map=textMap(element);
  if(!map)return 0;
  // Whether a prefix intersects this page is monotonic even for bidirectional text.
  // Search that prefix, then skip whitespace, without measuring every preceding character.
  const range=document.createRange();range.setStart(map.node,0);
  let low=0,high=map.chars.length;
  while(low<high){
   const middle=Math.floor((low+high)/2);range.setEnd(map.node,map.units[middle+1]);
   if(Array.from(range.getClientRects()).some(isVisible))high=middle;else low=middle+1;
  }
  for(let index=low;index<map.chars.length;index++)
   if(map.chars[index].trim()&&isVisible(characterRect(element,index)))return index;
  return null;
 }
 function warmNextPage(){
  if(!paged||warmHandle||typeof requestIdleCallback!=='function'||touch||animating)return;
  const base=screen,target=base+1,epoch=warmEpoch;
  if(target>=screens()||pageAnchors.has(target))return;
  let i=visibleSpan;
  const work=deadline=>{
   warmHandle=0;
   if(epoch!==warmEpoch||base!==screen||touch||animating||dragOffset!==0)return;
   const shift=viewport.scrollLeft-target*width();
   const inPage=r=>visible({left:r.left+shift,right:r.right+shift,top:r.top,bottom:r.bottom});
   let examined=0;
   while(i<spans.length&&examined<24&&deadline.timeRemaining()>2){
    const index=i++,el=spans[index];examined++;
    if(!el.textContent.trim())continue;
    const boxes=rects(el);
    if(boxes.every(r=>r.left+shift>=width()-18))return;
    if(!boxes.some(inPage))continue;
    const character=firstVisibleCharacter(el,inPage);
    if(character===null)continue;
    pageAnchors.set(target,{offset:Number(el.dataset.readerTextOffset)+character,span:index});return;
   }
   if(i<spans.length)warmHandle=requestIdleCallback(work);
  };
  warmHandle=requestIdleCallback(work);
 }
 function positionRect(r){
  if(!r)return;
  if(paged)show(Math.floor((r.left+viewport.scrollLeft-18+.5)/width()));
  else window.scrollTo(0,r.top+scrollY-24);
 }
 function location(){
  let offset=anchor;
  if(dragOffset===0&&!animating&&(!paged||!touch)&&(paged||performance.now()-lastScrollTime>160)){
   const cached=paged?pageAnchors.get(screen):continuousAnchor?.y===scrollY?continuousAnchor:null;
   if(cached){offset=cached.offset;visibleSpan=cached.span;}
   else {
    // DOCX may contain the entire book in one flow. Normal forward turns start
    // at the prior visible span, and revisiting a page uses its exact cached anchor.
    const start=(paged?screen>=measuredScreen:continuousAnchor&&scrollY>=continuousAnchor.y)?visibleSpan:0;
    for(let i=start;i<spans.length;i++){
     const el=spans[i];
     if(!el.textContent.trim()||!rects(el).some(visible))continue;
     const index=firstVisibleCharacter(el);
     if(index===null)continue;
     offset=Number(el.dataset.readerTextOffset)+index;visibleSpan=i;break;
    }
    if(paged)pageAnchors.set(screen,{offset,span:visibleSpan});
    else continuousAnchor={offset,span:visibleSpan,y:scrollY};
   }
   measuredScreen=screen;
   warmNextPage();
  }
  anchor=offset;
  return{offset,scrollFraction:paged?0:fraction(),screen:paged?screen+1:1,screens:paged?screens():1,
   atStart:paged?screen===0:scrollY<=1,atEnd:paged?screen>=screens()-1:scrollY>=maxScroll()-4,
   width:width(),height:height(),viewport:innerHeight,column:layout().column};
 }
 function applyRestoration(){
  if(!restoration)return;
  const {offset,fragment,scrollFraction}=restoration;
  const target=fragment?document.getElementById(fragment):null;
  if(target){
   // A block or heading anchor must land on the reading margin; an inline anchor keeps its own line.
   // Only the top is adjusted — rects() returns font boxes centred in the line box, half a leading
   // below the block edge — and the horizontal column maths must still see the real text rect.
   const box=target.getBoundingClientRect(),line=rects(target)[0];
   positionRect({top:Math.min(box.top,line.top),left:line.left});location();return;
  }
  if(!paged&&Number.isFinite(scrollFraction)){
   window.scrollTo(0,maxScroll()*Math.max(0,Math.min(1,scrollFraction)));location();return;
  }
  const last=spans[spans.length-1];
  const end=last?Number(last.dataset.readerTextOffset)+Array.from(last.textContent).length:0;
  if(offset>0&&offset>=Math.max(0,end-1)){
   if(paged)show(screens()-1);else window.scrollTo(0,maxScroll());
   location();return;
  }
  let low=0,high=spans.length;
  while(low<high){const mid=(low+high)>>>1;if(Number(spans[mid].dataset.readerTextOffset)<=offset)low=mid+1;else high=mid;}
  const passage=spans[low-1];
  if(passage){
   const index=Math.max(0,Math.min(offset-Number(passage.dataset.readerTextOffset),Array.from(passage.textContent).length-1));
   positionRect(characterRect(passage,index));
  }else if(paged)show(0);else window.scrollTo(0,0);
  location();
 }
 function jump(offset,fragment='',scrollFraction=null){
  stopAnimation();
  restoration={offset,fragment,scrollFraction};applyRestoration();
 }
 function finishPageAnimation(){
  if(!animating)return;
  stopAnimation();viewport.scrollLeft=screen*width();notifyLocation();
 }
 function turn(direction,animated=false){
  restoration=null;
  if(paged){
   const next=screen+direction;
   if(next<0||next>=screens())return false;
   scrollPage(next,animated);
   return true;
  }
  if(direction>0&&scrollY>=maxScroll()-4||direction<0&&scrollY<=1)return false;
  window.scrollBy(0,direction*(innerHeight-48));location();return true;
 }
 function resize(w,h){
  if(!paged||w<=48||h<=48)return;
  const retained=location().offset;
  flow.style.setProperty('--reader-image-height',Math.max(1,h-96)+'px');
  flow.style.width=(w-36)+'px';flow.style.height=(h-48)+'px';flow.style.columnWidth=(w-36)+'px';
  // An embedded Android WebView can initially have a zero-height containing block,
  // even after innerHeight updates. Never derive the clipping viewport from inset.
  viewport.style.width=w+'px';viewport.style.height=h+'px';
  document.documentElement.style.width=w+'px';document.documentElement.style.height=h+'px';
  invalidateLayout();
  jump(retained,'');
 }
 const selected=()=>{const selection=window.getSelection();return selection&&!selection.isCollapsed;};
 const interactive=target=>target instanceof Element&&target.closest('a,button,input,textarea,select');
 const action=value=>window.location.href='https://reader.invalid/action/'+value;
 let dragFrame=0;
 function paintDrag(){
  dragFrame=0;
  if(touch)viewport.scrollLeft=touch.base-dragOffset;
 }
 function flushDrag(){if(dragFrame){cancelAnimationFrame(dragFrame);paintDrag();}}
 function settleDrag(){
  if(!paged)return;
  flushDrag();
  if(dragOffset===0&&Math.abs(viewport.scrollLeft-screen*width())<1)return;
  scrollPage(screen,true);
 }
 let touch=null,suppressClickUntil=0;
 document.addEventListener('click',event=>{
  if(performance.now()<suppressClickUntil||selected()||interactive(event.target))return;
  event.preventDefault();action('controls');
 });
 document.addEventListener('touchstart',event=>{
  restoration=null;
  if(paged)prepareScroll();
  if(paged)stopAnimation();
  touch=event.touches.length===1&&!selected()?{
   x:event.touches[0].clientX,y:event.touches[0].clientY,time:event.timeStamp,link:!!interactive(event.target),
   width:width(),screens:screens(),base:viewport.scrollLeft,lastX:event.touches[0].clientX,lastTime:event.timeStamp,velocity:0
  }:null;
 },{passive:true});
 document.addEventListener('touchmove',event=>{
  if(event.touches.length!==1){touch=null;settleDrag();return;}
  if(!touch)return;
  if(selected()){settleDrag();return;}
  const dx=event.touches[0].clientX-touch.x,dy=event.touches[0].clientY-touch.y;
  const dt=event.timeStamp-touch.lastTime;
  if(dt>0){touch.velocity=(event.touches[0].clientX-touch.lastX)/dt;touch.lastX=event.touches[0].clientX;touch.lastTime=event.timeStamp;}
  if(Math.abs(dx)+Math.abs(dy)>10){touch.moved=true;if(paged)event.preventDefault();}
  if(paged&&!touch.link&&(touch.dragging||Math.abs(dx)>Math.abs(dy)*1.5)&&Math.abs(dx)>10){
   touch.dragging=true;
   const boundary=dx>0?screen===0:screen===touch.screens-1;
   dragOffset=Math.max(-touch.width,Math.min(touch.width,dx))*(boundary ? 0.18 : 1);
   if(!dragFrame)dragFrame=requestAnimationFrame(paintDrag);
  }
 },{passive:!paged});
 document.addEventListener('touchend',event=>{
  if(!touch)return;
  flushDrag();
  const start=touch;touch=null;
  const end=event.changedTouches[0];
  if(!end){settleDrag();return;}
  const dx=end.clientX-start.x,dy=end.clientY-start.y;
  if(start.moved||Math.abs(dx)+Math.abs(dy)>10){
   if(paged)event.preventDefault();
   suppressClickUntil=performance.now()+400;
  }
  if(!paged)return;
  const flick=event.timeStamp-start.lastTime<100&&Math.abs(start.velocity)>.45&&Math.abs(dx)>12;
  if(!selected()&&!start.link&&(Math.abs(dx)>=Math.max(28,width()*.08)||flick)&&Math.abs(dx)>Math.abs(dy)*1.5){
   // At a chapter edge the native reader captures the current page before loading
   // its neighbour. Capture a complete page, never the temporarily dragged frame.
   const direction=dx<0?1:-1;
   // Same-section gestures never wait for a native bridge round trip or input lock.
   if(turn(direction,true)===false){show(screen);action(direction>0?'next':'previous');}
  }else settleDrag();
 },{passive:!paged});
 document.addEventListener('touchcancel',()=>{touch=null;settleDrag();suppressClickUntil=performance.now()+400;},{passive:true});
 for(const event of ['wheel','keydown'])document.addEventListener(event,()=>restoration=null,{passive:true});
 window.addEventListener('scroll',()=>{lastScrollTime=performance.now();},{passive:true});
 viewport.addEventListener('scroll',()=>{
  if(paged&&!animating&&!touch&&dragOffset===0){
   const next=Math.max(0,Math.min(screens()-1,Math.round(viewport.scrollLeft/width())));
   if(next!==screen)scrollPage(next,false);
  }
 },{passive:true});
 document.querySelectorAll('img').forEach(image=>{
  image.loading='eager';
  image.decoding='async';
  image.addEventListener('load',()=>{invalidateLayout();if(!animating&&dragOffset===0)applyRestoration();});
  image.addEventListener('error',invalidateLayout);
 });
 if(document.fonts)document.fonts.ready.then(()=>{invalidateLayout();if(!animating&&dragOffset===0)applyRestoration();});
 let layoutWidth=width(),layoutHeight=height(),resizeTimer;
 window.addEventListener('resize',()=>{
  clearTimeout(resizeTimer);
  resizeTimer=setTimeout(()=>{
   invalidateLayout();
   const nextWidth=width(),nextHeight=height();
   if(Math.abs(nextWidth-layoutWidth)<.5&&Math.abs(nextHeight-layoutHeight)<.5)return;
   layoutWidth=nextWidth;layoutHeight=nextHeight;
   if(restoration)applyRestoration();else if(paged)jump(anchor,'');
  },50);
 });
 return{location,jump,turn,resize,isAnimating:()=>animating};})();
""".trimIndent()
