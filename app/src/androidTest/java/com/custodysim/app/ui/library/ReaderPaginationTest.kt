package com.custodysim.app.ui.library

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises Blink's actual column layout; no network, accounts or reading sessions. */
class ReaderPaginationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun keepAnimationTestsInTheForeground() {
        // Android may freeze a windowless test process. Exercise the same foreground lifecycle
        // as real reading, while keeping the document fixture free of reading-session requests.
        val component = "${instrumentation.targetContext.packageName}/com.custodysim.app.MainActivity"
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $component").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream -> stream.readBytes() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun withReader(html: String, wide: Boolean = true, check: (WebView) -> Unit) {
        val loaded = CountDownLatch(1)
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.useWideViewPort = wide
                settings.loadWithOverviewMode = false
                measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1080, 1600)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.host == "reader.invalid" && request.url.path?.startsWith("/action/") == true
                    override fun onPageFinished(view: WebView, url: String) {
                        DocumentController().resize(view)
                        loaded.countDown()
                    }
                }
                loadDataWithBaseURL("https://reader.invalid/", html, "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("Local document should finish loading", loaded.await(15, TimeUnit.SECONDS))
            check(web)
        } finally { instrumentation.runOnMainSync { web.destroy() } }
    }

    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("Reader script should respond", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun paragraphs(): String = (0 until 80).joinToString("") { index ->
        "<p><span data-reader-text-offset=\"${index * 100}\">" +
            "这是保留段落和标点的阅读正文。".repeat(12) + "</span></p>"
    }

    @Test fun everyScreenHasTextAndCannotStopBetweenColumns() {
        for (font in listOf(16, 28)) withReader(readerHtml(paragraphs(), font = font)) { web ->
            val report = JSONArray(evaluate(web, """JSON.stringify((()=>{
                const pages=[];do{const location=Reader.location();
                  const flow=document.getElementById('reader-flow'),width=flow.getBoundingClientRect().width+36,height=flow.getBoundingClientRect().height+48;
                  let visible=0,clipped=0;
                  document.querySelectorAll('[data-reader-text-offset]').forEach(el=>{
                    const range=document.createRange();range.selectNodeContents(el);
                    for(const r of range.getClientRects())if(r.right>18&&r.left<width-18&&r.bottom>24&&r.top<height-24){
                      visible++;if(r.top<23||r.bottom>height-23)clipped++;
                    }
                  });pages.push({...location,visible,clipped,x:scrollX});
                }while(Reader.turn(1));return pages;
            })())""").let { JSONArray("[$it]").getString(0) })
            assertTrue("Fixture must span multiple screens", report.length() > 3)
            var previous = -1
            for (i in 0 until report.length()) {
                val page = report.getJSONObject(i)
                assertEquals(i + 1, page.getInt("screen"))
                assertEquals(report.length(), page.getInt("screens"))
                assertTrue("Screen ${i + 1} cannot be blank: $report; " + evaluate(web,
                    "JSON.stringify({width:innerWidth,flow:document.getElementById('reader-flow').scrollWidth,last:document.querySelector('p:last-child').getBoundingClientRect().toJSON()})"), page.getInt("visible") > 0)
                assertEquals("No line can be cut at the bottom of the window", 0, page.getInt("clipped"))
                assertEquals("Native scrolling cannot offset a screen", 0, page.getInt("x"))
                assertTrue(page.getInt("offset") >= previous)
                previous = page.getInt("offset")
            }
            assertEquals("false", evaluate(web, "Reader.turn(1)"))
            assertEquals("true", evaluate(web, "Reader.turn(-1)"))
        }
    }

    @Test fun jumpingToAnAnchorFindsItsColumnAndShortChaptersHaveOneScreen() {
        withReader(readerHtml("<p id=\"start\"><span data-reader-text-offset=\"0\">短章节</span></p>")) { web ->
            val page = JSONObject(JSONArray("[${evaluate(web, "JSON.stringify(Reader.location())")}]").getString(0))
            assertEquals(1, page.getInt("screens"))
            assertTrue(page.getBoolean("atStart"))
            assertTrue(page.getBoolean("atEnd"))
            assertEquals("false", evaluate(web, "Reader.turn(1)"))
        }
        withReader(readerHtml(paragraphs() + "<h2 id=\"end\"><span data-reader-text-offset=\"8000\">最后一节</span></h2>")) { web ->
            evaluate(web, "Reader.jump(8000,'end')")
            assertEquals("true", evaluate(web, "(()=>{const r=document.getElementById('end').getBoundingClientRect();return r.left>=17&&r.left<innerWidth-18})()"))
            evaluate(web, "Reader.jump(0,'')")
            assertEquals("1", evaluate(web, "Reader.location().screen"))
        }
    }

    @Test fun tallIllustrationsFitInsideTheReadingWindow() {
        val image = Base64.encodeToString("<svg xmlns='http://www.w3.org/2000/svg' width='400' height='2400'><rect width='400' height='2400' fill='red'/></svg>".toByteArray(), Base64.NO_WRAP)
        withReader(readerHtml("<figure><img id=\"illustration\" src=\"data:image/svg+xml;base64,$image\"/><figcaption><span data-reader-text-offset=\"0\">插图说明</span></figcaption></figure><p><span data-reader-text-offset=\"10\">插图后的正文</span></p>")) { web ->
            // Explicit native bounds must win even if WebView reports a broken viewport height.
            evaluate(web, "Reader.resize(360,600)")
            assertEquals("504px", JSONArray("[${evaluate(web, "getComputedStyle(document.getElementById('illustration')).maxHeight")}]").getString(0))
            evaluate(web, "Reader.resize(360,720)")
            assertEquals("624px", JSONArray("[${evaluate(web, "getComputedStyle(document.getElementById('illustration')).maxHeight")}]").getString(0))
            evaluate(web, "Reader.jump(0,'illustration')")
            assertEquals("true", evaluate(web, """(()=>{
                const r=document.getElementById('illustration').getBoundingClientRect(),flow=document.getElementById('reader-flow').getBoundingClientRect();
                return r.width>0&&r.height>0&&r.top>=23&&r.bottom<=flow.height+25&&r.left>=17&&r.right<=flow.width+19;
            })()"""))
        }
    }

    @Test fun scrollingModeStillUsesVerticalDocumentFlow() {
        withReader(readerHtml(paragraphs(), paged = false), wide = false) { web ->
            assertEquals("true", evaluate(web, "document.documentElement.scrollHeight>innerHeight"))
            evaluate(web, "Reader.jump(4000,'')")
            assertEquals("true", evaluate(web, "scrollY>0"))
            assertEquals("1", evaluate(web, "Reader.location().screens"))
        }
    }

    @Test fun resizingTheReadingWindowKeepsTheCurrentPassage() {
        withReader(readerHtml(paragraphs())) { web ->
            evaluate(web, "Reader.jump(4000,'')")
            val before = evaluate(web, "Reader.location().offset").toInt()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            evaluate(web, "Reader.resize(${1080 / density},${1100 / density})")
            val after = evaluate(web, "Reader.location().offset").toInt()
            assertTrue("Reflow should keep the passage, not reset to chapter start: $before -> $after",
                kotlin.math.abs(before - after) <= 300)
        }
    }

    @Test fun passageInsideASpanRestoresItsScreenWithoutSplittingEmoji() {
        val text = "正文🙂保持阅读位置。".repeat(180)
        withReader(readerHtml("<p><span data-reader-text-offset=\"0\">$text</span></p>", font = 28)) { web ->
            assertEquals("true", evaluate(web, "Reader.turn(1)"))
            val page = evaluate(web, "Reader.location().screen")
            val offset = evaluate(web, "Reader.location().offset").toInt()
            assertTrue("The second screen must save the visible character, not the entire span's start", offset > 0)
            evaluate(web, "Reader.jump(0,'')")
            evaluate(web, "Reader.jump($offset,'')")
            assertEquals("A passage that spans columns must return to the same screen", page,
                evaluate(web, "Reader.location().screen"))
        }
    }

    @Test fun imageOnlyDocumentRestoresScrollFractionAndPreviousSectionEndsAtTheBottom() {
        val image = Base64.encodeToString(
            "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='1800'><rect width='400' height='1800' fill='red'/></svg>".toByteArray(),
            Base64.NO_WRAP)
        val html = readerHtml("<img src=\"data:image/svg+xml;base64,$image\"/><img src=\"data:image/svg+xml;base64,$image\"/>", paged = false)
        withReader(html, wide = false) { web ->
            evaluate(web, "Reader.jump(0,'',0.65)")
            val fraction = evaluate(web, "Reader.location().scrollFraction").toFloat()
            assertTrue("Image-only chapters need a stable scroll position", kotlin.math.abs(fraction - .65f) < .01f)
            evaluate(web, "Reader.jump(0,'')")
            assertEquals("0", evaluate(web, "scrollY"))
            evaluate(web, "Reader.jump(${Int.MAX_VALUE},'')")
            assertEquals("Scroll positions are rounded to physical pixels", 1f,
                evaluate(web, "Reader.location().scrollFraction").toFloat(), .001f)
            assertEquals("true", evaluate(web, "Reader.location().atEnd"))
            val controller = DocumentController()
            val saved = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.view = web; controller.loaded = true
                controller.location { saved.countDown() }
            }
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            assertEquals(1f, controller.scrollFraction, .01f)
            assertTrue("A long image document can move back from its end", controller.canTurnBackward)
            assertFalse("The final screen must disable forward movement", controller.canTurnForward)
        }
    }

    @Test fun scrollingTextCanEnterFromTheEndAndExplicitAnchorsOverrideSavedFractions() {
        withReader(readerHtml("<p id=\"first\"><span data-reader-text-offset=\"0\">章节开头</span></p>" + paragraphs(), paged = false), wide = false) { web ->
            evaluate(web, "Reader.jump(${Int.MAX_VALUE},'')")
            assertEquals("Scroll positions are rounded to physical pixels", 1f,
                evaluate(web, "Reader.location().scrollFraction").toFloat(), .001f)
            assertEquals("true", evaluate(web, "Reader.location().atEnd"))
            evaluate(web, "Reader.jump(0,'first',0.85)")
            assertEquals("An explicit table-of-contents target must win over the saved scroll position", "0", evaluate(web, "scrollY"))
            assertEquals("false", evaluate(web, "Reader.turn(-1)"))
        }
    }

    @Test fun delayedImagesKeepTheRestoredPositionUntilTheUserStartsReading() {
        val image = Base64.encodeToString(
            "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='1800'><rect width='400' height='1800' fill='red'/></svg>".toByteArray(),
            Base64.NO_WRAP)
        withReader(readerHtml("<img id=\"late\" src=\"data:image/svg+xml;base64,$image\"/>" + paragraphs(), paged = false), wide = false) { web ->
            evaluate(web, "Reader.jump(0,'',0.5)")
            evaluate(web, "document.getElementById('late').style.height='2400px';document.getElementById('late').dispatchEvent(new Event('load'))")
            assertTrue(kotlin.math.abs(evaluate(web, "Reader.location().scrollFraction").toFloat() - .5f) < .01f)
            evaluate(web, """(()=>{
                const event=new Event('touchstart',{bubbles:true});
                Object.defineProperty(event,'touches',{value:[{clientX:150,clientY:100}]});
                document.body.dispatchEvent(event);
                window.scrollTo(0,100);
                document.getElementById('late').dispatchEvent(new Event('load'));
            })()""")
            assertEquals("A late image cannot pull the page away after a user gesture", "100", evaluate(web, "scrollY"))
        }
    }

    @Test fun draggingAScrollingDocumentCannotSummonControls() {
        withReader(readerHtml(paragraphs(), paged = false), wide = false) { web ->
            val actions = java.util.concurrent.LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync {
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        actions.offer(request.url.path.orEmpty()); return true
                    }
                }
            }
            assertEquals("true", evaluate(web, """(()=>{
                const start=new Event('touchstart',{bubbles:true}),move=new Event('touchmove',{bubbles:true,cancelable:true}),end=new Event('touchend',{bubbles:true,cancelable:true});
                Object.defineProperty(start,'touches',{value:[{clientX:150,clientY:100}]});
                Object.defineProperty(move,'touches',{value:[{clientX:150,clientY:400}]});
                Object.defineProperty(end,'changedTouches',{value:[{clientX:150,clientY:400}]});
                document.body.dispatchEvent(start);const allowed=document.body.dispatchEvent(move);document.body.dispatchEvent(end);
                document.body.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));return allowed;
            })()"""))
            assertNull("Dragging must keep native scrolling and suppress its trailing synthetic tap", actions.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun aPendingSearchCannotJumpBackAfterNewNavigation() {
        withReader(readerHtml(paragraphs())) { web ->
            val controller = DocumentController()
            val staleSearch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.view = web; controller.loaded = true
                controller.find("正文") { _, _ -> staleSearch.countDown(); controller.jump(0) }
                controller.jump(4000)
            }
            assertFalse("A search issued for the previous passage must not navigate after a newer jump",
                staleSearch.await(750, TimeUnit.MILLISECONDS))
            assertTrue("The requested new passage must remain visible", evaluate(web, "Reader.location().offset").toInt() > 0)
        }
    }

    @Test fun realLocalEpubChapterDoesNotCreateHundredsOfEmptyScreens() {
        val fixture = java.io.File(instrumentation.targetContext.filesDir, "reader-real-chapter.json")
        org.junit.Assume.assumeTrue("Optional local EPUB reproduction fixture", fixture.isFile)
        val document = JSONObject(fixture.readText())
        withReader(readerHtml(document.getString("html"), styles = document.getString("styles"))) { web ->
            val pages = evaluate(web, "Reader.location().screens").toInt()
            assertTrue("A 5300-character preface must not turn into hundreds of pages: $pages; " +
                evaluate(web, "JSON.stringify(Reader.location())"), pages in 2..40)
            val pagesWithBody = evaluate(web, """(()=>{
                let visible=0;do{const offset=Reader.location().offset;if(offset>9)visible++;}while(Reader.turn(1));return visible;
            })()""").toInt()
            assertTrue("Actual paragraphs must be visible on the chapter's pages", pagesWithBody > 1)
        }
    }

    @Test fun chapterReplacementKeepsTheNewControllerAndBothBoundariesWork() {
        withReader(readerHtml("<p><span data-reader-text-offset=\"0\">小节一</span></p>")) { old ->
            withReader(readerHtml(paragraphs())) { next ->
                val controller = DocumentController()
                val boundary = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    controller.view = old; controller.loaded = true
                    controller.turn(true) { boundary.countDown() }
                }
                assertTrue("End of a short section must notify the book navigator", boundary.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    controller.view = next; controller.loaded = true
                    controller.release(old)
                    assertSame("Old chapter release cannot erase the new chapter", next, controller.view)
                    assertTrue(controller.loaded)
                    controller.turn(true) { fail("The next chapter has more screens") }
                }
                assertEquals("2", evaluate(next, "Reader.location().screen"))
                evaluate(next, "Reader.jump(9000,'')")
                assertEquals(evaluate(next, "Reader.location().screens"), evaluate(next, "Reader.location().screen"))
                evaluate(next, "Reader.jump(0,'')")
                val previous = CountDownLatch(1)
                instrumentation.runOnMainSync { controller.turn(false) { previous.countDown() } }
                assertTrue("Start of a section must notify the previous-section navigator", previous.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun tappingOnlyShowsControlsAndASwipeTurnsOnePageWhileLinksRemainClickable() {
        withReader(readerHtml("<p><span data-reader-text-offset=\"0\">测试正文</span><a id=\"note\" href=\"https://reader.invalid/chapter/1#note\">脚注</a></p>")) { web ->
            val actions = java.util.concurrent.LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync {
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        actions.offer(request.url.path.orEmpty()); return true
                    }
                }
            }
            evaluate(web, "document.body.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:310}))")
            assertEquals("/action/controls", actions.poll(5, TimeUnit.SECONDS))
            evaluate(web, "document.body.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:10}))")
            assertEquals("/action/controls", actions.poll(5, TimeUnit.SECONDS))
            evaluate(web, """(()=>{
                const start=new Event('touchstart',{bubbles:true}),end=new Event('touchend',{bubbles:true,cancelable:true});
                Object.defineProperty(start,'touches',{value:[{clientX:250,clientY:150}]});
                Object.defineProperty(end,'changedTouches',{value:[{clientX:100,clientY:150}]});
                document.body.dispatchEvent(start);document.body.dispatchEvent(end);
            })()""")
            assertEquals("/action/next", actions.poll(5, TimeUnit.SECONDS))
            assertEquals("false", evaluate(web, """(()=>{
                const start=new Event('touchstart',{bubbles:true}),move=new Event('touchmove',{bubbles:true,cancelable:true}),end=new Event('touchend',{bubbles:true,cancelable:true});
                Object.defineProperty(start,'touches',{value:[{clientX:150,clientY:100}]});
                Object.defineProperty(move,'touches',{value:[{clientX:150,clientY:450}]});
                Object.defineProperty(end,'changedTouches',{value:[{clientX:150,clientY:450}]});
                document.body.dispatchEvent(start);const allowed=document.body.dispatchEvent(move);
                document.body.dispatchEvent(end);document.body.dispatchEvent(end);return allowed;
            })()"""))
            assertNull("A vertical drag cannot scroll or trigger an extra page", actions.poll(200, TimeUnit.MILLISECONDS))
            evaluate(web, "document.getElementById('note').click()")
            assertEquals("/chapter/1", actions.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test fun tappingInScrollingModeShowsControlsWithoutMovingTheDocument() {
        withReader(readerHtml(paragraphs(), paged = false), wide = false) { web ->
            val actions = java.util.concurrent.LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync {
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        actions.offer(request.url.path.orEmpty()); return true
                    }
                }
            }
            evaluate(web, "Reader.jump(4000,'')")
            val before = evaluate(web, "scrollY")
            evaluate(web, "document.body.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:100}))")
            assertEquals("/action/controls", actions.poll(5, TimeUnit.SECONDS))
            assertEquals(before, evaluate(web, "scrollY"))
            assertEquals("true", evaluate(web, "document.documentElement.scrollHeight>innerHeight"))
        }
    }

    private fun waitForAnimation(controller: DocumentController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var active = true
        while (active && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { active = controller.isTurning }
            if (active) Thread.sleep(20)
        }
        assertFalse("The transition must finish and unlock navigation", active)
    }

    @Test fun nativeButtonsSerializeWhilePagesScrollInsideABoundedViewport() {
        withReader(readerHtml(paragraphs())) { web ->
            val controller = DocumentController()
            instrumentation.runOnMainSync {
                controller.view = web; controller.loaded = true; controller.animateTurns = true
                controller.turn(true) { fail("Fixture has more than one page") }
                controller.turn(true) { fail("A second turn must be ignored while animating") }
            }
            assertTrue("Long books must scroll a viewport instead of transforming the entire document",
                evaluate(web, "getComputedStyle(document.getElementById('reader-flow')).transform === 'none' && document.getElementById('reader-viewport') !== null").toBoolean())
            assertNull("Ordinary pages must not allocate a full-screen bitmap", controller.previousFrame)
            waitForAnimation(controller)
            assertEquals("2", evaluate(web, "Reader.location().screen"))
            instrumentation.runOnMainSync { controller.turn(false) { fail("Second page can turn back") } }
            waitForAnimation(controller)
            assertEquals("1", evaluate(web, "Reader.location().screen"))
        }
    }

    @Test fun rapidPageTurnsContinueWhileThePriorAnimationIsRunning() {
        withReader(readerHtml(paragraphs(), font = 28)) { web ->
            assertTrue(evaluate(web, "Reader.location().screens > 20").toBoolean())
            assertEquals("20", evaluate(web, "(()=>{let accepted=0;for(let i=0;i<20;i++)if(Reader.turn(1,true))accepted++;return accepted;})()"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (evaluate(web, "Reader.isAnimating()").toBoolean() && System.nanoTime() < deadline) Thread.sleep(30)
            assertEquals("21", evaluate(web, "Reader.location().screen"))
            assertTrue("Rapid turns retain a visible text anchor", evaluate(web, "Reader.location().offset > 0").toBoolean())
            assertEquals("true", evaluate(web, "Reader.turn(-1,false)"))
            assertEquals("20", evaluate(web, "Reader.location().screen"))
        }
    }

    @Test fun clippingViewportKeepsNativeDimensionsWithACollapsedContainingBlock() {
        withReader(readerHtml(paragraphs(), styles = "body{position:relative;height:0!important}")) { web ->
            evaluate(web, "Reader.resize(360,600)")
            assertEquals("600", evaluate(web, "document.getElementById('reader-viewport').getBoundingClientRect().height"))
            assertEquals("360", evaluate(web, "document.getElementById('reader-viewport').getBoundingClientRect().width"))
            assertEquals("true", evaluate(web, "Reader.turn(1,false)"))
            assertTrue("A resized native viewport retains a visible reading position", evaluate(web, "Reader.location().offset > 0").toBoolean())
        }
    }

    @Test fun crossSectionTransitionSurvivesReleaseAndKeepsTheNextSectionUsable() {
        withReader(readerHtml("<p><span data-reader-text-offset=\"0\">小节末尾</span></p>")) { old ->
            val controller = DocumentController()
            val switched = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.view = old; controller.loaded = true; controller.animateTurns = true
                controller.turn(true) { switched.countDown() }
            }
            assertTrue(switched.await(5, TimeUnit.SECONDS))
            // Match the app: capture the outgoing page before creating the next section's WebView.
            withReader(readerHtml(paragraphs())) { next ->
                instrumentation.runOnMainSync {
                    controller.view = next; controller.loaded = true
                    controller.release(old)
                    assertNull("An unattached WebView has no GPU surface to capture; navigation must still work", controller.previousFrame)
                    controller.finishTransition(next)
                }
                waitForAnimation(controller)
                assertSame(next, controller.view)
                instrumentation.runOnMainSync { controller.turn(true) { fail("The next section should still be navigable") } }
                waitForAnimation(controller)
                assertEquals("2", evaluate(next, "Reader.location().screen"))
            }
        }
    }
}
