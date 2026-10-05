package com.custodysim.app.ui.library

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Parses the server's sanitized vocabulary off the UI thread, before WebView sees a long chapter. */
internal object HtmlBlockIndex {
    private data class Node(val tag: String, val attributes: LinkedHashMap<String, String> = linkedMapOf(),
        val children: MutableList<Node> = mutableListOf(), val text: String = "") {
        val length: Int get() = if (tag.isEmpty()) text.length else children.sumOf { it.length }
        fun html(): String = if (tag.isEmpty()) escape(text) else buildString {
            append('<').append(tag)
            attributes.forEach { (key, value) -> append(' ').append(key).append("=\"").append(escape(value)).append('"') }
            append('>'); children.forEach { append(it.html()) }
            if (tag !in voidTags) append("</").append(tag).append('>')
        }
        fun walk(action: (Node) -> Unit) { action(this); children.forEach { it.walk(action) } }
    }
    private val voidTags = setOf("img", "br", "hr")
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // Only canonical server output enters here: quoted attributes and the img/br/hr void vocabulary.
    // The parser, not this normalization, establishes every structural split and ancestor boundary.
    private fun xmlSource(html: String): String = buildString(html.length + 32) {
        append("<reader-root>")
        var cursor = 0
        while (cursor < html.length) {
            if (html[cursor] != '<') { append(html[cursor++]); continue }
            val start = cursor++; var quote: Char? = null
            while (cursor < html.length) {
                val char = html[cursor++]
                if (quote != null) { if (char == quote) quote = null }
                else if (char == '\'' || char == '"') quote = char
                else if (char == '>') break
            }
            val token = html.substring(start, cursor)
            val name = token.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase()
            if (name in voidTags && !token.endsWith("/>")) append(token.dropLast(1)).append("/>") else append(token)
        }
        append("</reader-root>")
    }
    fun build(html: String, chapter: Int): JSONArray {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlSource(html)))
        val root = Node("reader-root"); val stack = ArrayDeque<Node>(); stack.add(root)
        var image = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) when (parser.eventType) {
            XmlPullParser.START_TAG -> if (parser.name != "reader-root") {
                val node = Node(parser.name)
                repeat(parser.attributeCount) { node.attributes[parser.getAttributeName(it)] = parser.getAttributeValue(it) }
                if (node.tag == "img") { node.attributes.putIfAbsent("id", "reader-image-$chapter-$image"); image++ }
                stack.last().children.add(node); stack.add(node)
            }
            XmlPullParser.END_TAG -> if (parser.name != "reader-root") stack.removeLast()
            XmlPullParser.TEXT -> stack.last().children.add(Node("", text = parser.text))
        }
        val result = JSONArray(); var group = mutableListOf<Node>(); var bytes = 0; var priorEnd = 0
        fun flush() {
            if (group.isEmpty()) return
            var start: Int? = null; var end = priorEnd; val ids = JSONArray()
            group.forEach { node -> node.walk {
                it.attributes["id"]?.let(ids::put)
                it.attributes["data-reader-text-offset"]?.toIntOrNull()?.let { offset ->
                    if (start == null) start = offset
                    var points = 0; it.walk { child -> if (child.tag.isEmpty()) points += child.text.codePointCount(0, child.text.length) }
                    end = offset + points
                }
            } }
            result.put(JSONObject().put("html", group.joinToString("") { it.html() }).put("start", start ?: priorEnd).put("end", end).put("ids", ids))
            priorEnd = end; group = mutableListOf(); bytes = 0
        }
        root.children.flatMap(::split).forEach { node ->
            if (bytes >= 18_000) flush()
            group.add(node); bytes += node.html().length
        }
        flush()
        return result
    }
    private fun split(node: Node): List<Node> {
        if (node.length <= 12_000 || node.tag in setOf("", "table", "figure", "pre") || node.attributes["style"].orEmpty().contains("float")) return listOf(node)
        val result = mutableListOf<Node>(); var wrapper = Node(node.tag, LinkedHashMap(node.attributes)); var length = 0
        var number = node.attributes["start"]?.toIntOrNull() ?: 1
        fun flush() {
            if (wrapper.children.isEmpty()) return
            result.add(wrapper); number += wrapper.children.count { it.tag == "li" }
            wrapper = Node(node.tag, LinkedHashMap(node.attributes).apply { put("data-continuation", ""); if (node.tag == "ol") put("start", number.toString()) })
            length = 0
        }
        node.children.flatMap(::split).forEach { child ->
            if (length >= 12_000) flush()
            wrapper.children.add(child); length += child.length
        }
        flush(); return result
    }
}
