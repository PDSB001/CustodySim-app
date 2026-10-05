package com.custodysim.app.ui.library

import android.annotation.SuppressLint
import android.app.Activity
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.webkit.*
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Engine regressions run in an attached, visible WebView, without a server or reading credits. */
class ReaderEngineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun foreground(): Activity {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${instrumentation.targetContext.packageName}/com.custodysim.app.MainActivity").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
        lateinit var result: Activity
        instrumentation.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first() }
        return result
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun host(chapters: List<String>, mode: String = "paged", fixed: Boolean = false, check: (WebView) -> Unit) {
        val activity = foreground(); val ready = CountDownLatch(1)
        var hostDocument = ByteArray(0)
        val blocks=chapters.mapIndexed { index, html -> if(!fixed&&html.length>36_000)HtmlBlockIndex.build(html,index) else null }
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(activity).apply {
                settings.javaScriptEnabled = true; settings.useWideViewPort = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        android.util.Log.i("ReaderEngineTest", "${message.lineNumber()}: ${message.message()}"); return true
                    }
                }
                webViewClient = ReaderAssetClient(activity, { hostDocument }, chapter = { index ->
                    chapters.getOrNull(index)?.let { html ->
                        val parts = blocks.getOrNull(index)
                        JSONObject().put("html", if (parts == null) html else "").put("parts", parts ?: JSONObject.NULL)
                            .toString().toByteArray(Charsets.UTF_8)
                    }
                })
                addJavascriptInterface(HtmlReaderBridge(1, { it() }, { ready.countDown() }, {}, {}, {}, {}), "ReaderBridge")
            }
            (activity.window.decorView as ViewGroup).addView(web, FrameLayout.LayoutParams(-1, -1))
            val config = JSONObject().put("generation",1).put("title","真实引擎回归").put("styles","").put("fixed",fixed)
                .put("mode",mode).put("animate",false).put("chapter",0).put("offset",0).put("fragment","")
                .put("fraction",JSONObject.NULL).put("font",19).put("spacing",1.85).put("family","serif")
                .put("background","#f8f2e6").put("ink","#32312d")
                // Chapter markup is served per request, exactly like the app: the host document is metadata only.
                .put("chapters",JSONArray().apply { chapters.forEachIndexed { index, _ -> put(JSONObject().put("linear",true).put("title","第${index+1}节")) } })
            hostDocument = htmlReaderMarkup(config).toByteArray(Charsets.UTF_8)
            web.loadUrl("https://reader.invalid/reader/host.html")
        }
        try { assertTrue("Attached H5 engine must become readable", ready.await(20,TimeUnit.SECONDS)); check(web) }
        finally { instrumentation.runOnMainSync { (web.parent as? ViewGroup)?.removeView(web);web.destroy() } }
    }
    private fun evaluate(web: WebView, script: String): String {
        val done=CountDownLatch(1);var result="null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) {result=it;done.countDown()} }
        assertTrue(done.await(10,TimeUnit.SECONDS));return result
    }
    private fun state(web: WebView): JSONObject = JSONObject(JSONArray("[${evaluate(web,"JSON.stringify(Reader.location())")}]").getString(0))
    private fun await(web: WebView, condition: () -> Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(30)}
        fail("Engine did not settle: ${evaluate(web,"JSON.stringify(Reader.debug())")}")
    }
    private fun body(count: Int): String = (0 until count).joinToString("") { index ->
        "<p id='paragraph-$index'><span data-reader-text-offset='${index*120}'>${("第${index}段内容，图片表格和正文需要保持连续。".repeat(6)).take(120)}</span></p>"
    }
    @Test fun htmlCrossesChaptersBothDirectionsWithoutReloadingTheHost() {
        host(listOf("<p><span data-reader-text-offset='0'>第一节。</span></p>","<p><span data-reader-text-offset='0'>第二节。</span></p>")) { web ->
            evaluate(web,"Reader.turn(1,false)");await(web){state(web).getInt("chapter")==1}
            evaluate(web,"Reader.turn(-1,false)");await(web){state(web).getInt("chapter")==0}
            assertEquals("2",evaluate(web,"document.querySelectorAll('iframe').length"))
        }
    }
    @Test fun longHtmlHasBoundedDomAndMonotonicOffsetsAcrossWindows() {
        host(listOf(body(1800))) { web ->
            var offset=-1
            repeat(110) {
                evaluate(web,"Reader.turn(1,false)");await(web){evaluate(web,"Reader.isAnimating()")!="true"}
                val next=state(web).getInt("offset");assertTrue("Content may not move backward on a forward turn: $offset -> $next",next>offset)
                offset=next
            }
            val debug=JSONObject(JSONArray("[${evaluate(web,"JSON.stringify(Reader.debug())")}]").getString(0))
            assertTrue(debug.getInt("nodes")<1600);assertTrue(debug.getInt("windows")<=8)
            assertEquals("0",evaluate(web,"Array.from(document.querySelectorAll('iframe')).filter(f=>f.getBoundingClientRect().height===0).length"))
        }
    }
    @Test fun scrollingLongHtmlCanReachContentOutsideTheFirstWindow() {
        host(listOf(body(800)),"scroll") { web ->
            repeat(16) { evaluate(web,"document.querySelector('iframe').contentDocument.scrollingElement.scrollTop=1e9");Thread.sleep(80) }
            assertTrue(state(web).getInt("offset")>10000)
        }
    }
    @Test fun nativePaginationPreservesEveryCharacterAndCodePointAnchor() = runBlocking {
        val text=("段落🙂中英文 e\u0301 mixed text。\n\n".repeat(2200))
        val paint=TextPaint().apply {textSize=54f}
        val pages=NativeTextPaginator.paginate(text,paint,1000,1800,1.85f)
        assertTrue(pages.size>100);assertEquals(text.length,pages.last().end)
        var end=0
        for(page in pages){assertEquals(end,page.start);assertTrue(page.end>page.start);assertEquals(text.codePointCount(0,page.start),page.offset)
            if(page.end<text.length)assertFalse(Character.isLowSurrogate(text[page.end]));end=page.end}
        assertEquals(text,pages.joinToString(""){text.substring(it.start,it.end)})
    }

    private fun swipe(web: WebView, from: Float, to: Float, cancel: Boolean = false) {
        val started=SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            instrumentation.runOnMainSync {
                MotionEvent.obtain(started,SystemClock.uptimeMillis(),action,web.width*x,web.height*.55f,0).also {
                    web.dispatchTouchEvent(it);it.recycle()
                }
            }
        }
        send(MotionEvent.ACTION_DOWN,from)
        repeat(12){Thread.sleep(12);send(MotionEvent.ACTION_MOVE,from+(to-from)*(it+1)/12)}
        send(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,to)
        Thread.sleep(80)
    }

    @Test fun fixedPagesAcceptRealSwipeAndCancelledBoundaryKeepsCurrentPage() {
        host(listOf("<p>固定页面一</p>","<p>固定页面二</p>"),fixed=true){ web ->
            Thread.sleep(500)
            swipe(web,.8f,.3f,cancel=true)
            await(web){evaluate(web,"Reader.isAnimating()")!="true"}
            assertEquals(0,state(web).getInt("chapter"))
            swipe(web,.8f,.2f)
            await(web){state(web).getInt("chapter")==1}
            swipe(web,.2f,.8f)
            await(web){state(web).getInt("chapter")==0}
        }
    }

    @Test fun reverseTurnsReturnToTheSamePassagesAcrossWindows() {
        host(listOf(body(900))) { web ->
            val offsets=mutableListOf(state(web).getInt("offset"))
            repeat(120){evaluate(web,"Reader.turn(1,false)");await(web){evaluate(web,"Reader.isAnimating()")!="true"};offsets.add(state(web).getInt("offset"))}
            for(expected in offsets.dropLast(1).reversed()){
                evaluate(web,"Reader.turn(-1,false)");await(web){evaluate(web,"Reader.isAnimating()")!="true"}
                assertEquals("Reverse navigation must restore the preceding passage",expected,state(web).getInt("offset"))
            }
        }
    }

    @Test fun cancelledCrossChapterAnimationCannotCommitOrCoverTheOldPage() {
        host(listOf("<p>第一节</p>","<p>第二节</p>")){web->
            Thread.sleep(500)
            evaluate(web,"Reader.turn(1,true)")
            Thread.sleep(45)
            evaluate(web,"Reader.cancel()")
            Thread.sleep(250)
            assertEquals(0,state(web).getInt("chapter"))
            assertEquals("1",evaluate(web,"Array.from(document.querySelectorAll('iframe')).filter(f=>Math.abs(f.getBoundingClientRect().left)<1).length"))
            evaluate(web,"Reader.turn(1,false)");await(web){state(web).getInt("chapter")==1}
        }
    }

    @Test fun pdfWorkerRendersConcurrentPagesAndSharpTilesThenCloses() = runBlocking {
        val file=java.io.File.createTempFile("reader-regression-", ".pdf", instrumentation.targetContext.cacheDir)
        val output=android.graphics.pdf.PdfDocument()
        try {
            repeat(4){index->val page=output.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600,900,index+1).create())
                page.canvas.drawColor(if(index%2==0)android.graphics.Color.RED else android.graphics.Color.BLUE)
                output.finishPage(page)}
            file.outputStream().use(output::writeTo)
        } finally { output.close() }
        val source=PdfDocument(file)
        try {
            val previews=kotlinx.coroutines.coroutineScope { (0..3).map { index -> async { source.page(index,index==2) } }.map { it.await() } }
            assertEquals(4,previews.size);assertEquals(android.graphics.Color.RED,previews[2].getPixel(30,30))
            val tile=source.tile(1,3f,android.graphics.Rect(100,100,600,600))
            assertEquals(500,tile.width);assertEquals(android.graphics.Color.BLUE,tile.getPixel(30,30))
            assertSame(previews[2],source.page(2))
        } finally { source.close() }
        assertFalse(file.exists())
    }

    @Test fun backgroundBlocksKeepImagesUnicodeAndInlineAncestors() {
        val paragraph="<p id='long'><em>"+(0 until 700).joinToString(""){ index ->
            "<span data-reader-text-offset='${index*100}'>${"中文🙂&lt;&amp;".repeat(25)}</span>"
        }+"</em></p>"
        val html="<div><img src='data:image/png;base64,AA==' alt='扉页'/><p><span data-reader-text-offset='0'>ISBN</span></p>$paragraph<table><tr><td>完整表格</td></tr></table></div>"
        val parts=HtmlBlockIndex.build(html,3)
        assertTrue(parts.length()>2)
        val combined=(0 until parts.length()).joinToString(""){parts.getJSONObject(it).getString("html")}
        assertEquals(1,Regex("<img ").findAll(combined).count())
        assertEquals(701,Regex("data-reader-text-offset=").findAll(combined).count())
        assertTrue(combined.contains("reader-image-3-0"));assertTrue(combined.contains("ISBN"))
        assertTrue(combined.contains("<table><tr><td>完整表格</td></tr></table>"))
        assertTrue(combined.contains("data-continuation"));assertTrue(combined.contains("中文🙂&lt;&amp;"))
    }

    @Test fun hugeHostAndPictureCaptionRemainReadableWithoutDataUrlNavigation() {
        val picture="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8l0AAAAASUVORK5CYII="
        host(listOf("<img id='cover' src='$picture' style='width:120px;height:160px'><p id='isbn'><span data-reader-text-offset='0'>ISBN 1234</span></p>",body(6000))){web->
            assertTrue(evaluate(web,"document.querySelector('iframe').contentDocument.getElementById('isbn').getBoundingClientRect().bottom<innerHeight") == "true")
            assertTrue(evaluate(web,"document.querySelector('iframe').contentDocument.getElementById('cover').getBoundingClientRect().height>0") == "true")
            evaluate(web,"Reader.turn(1,false)");await(web){state(web).getInt("chapter")==1}
            assertTrue(state(web).getInt("screens")>1)
        }
    }
}
