package net.oxog.phonebox.deskpanel

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Real-time stock quotes (Yahoo Finance v7 quote, "Nasdaq Real Time Price", with pre-market and after-hours) for the
 * watchlist widget. TradingView's public scanner gives US stocks 15 minutes late, and HTML widgets cannot call Yahoo
 * (sandboxed iframe, no CORS), so they ask the panel, which asks this class; the answer comes back through [done]
 * (the same window.__thinqDone path as ThinQ, matched by id). Only query1.finance.yahoo.com is reachable.
 *
 * Request JSON: {"id":..., "symbols":["AMD","BRK-B",...]}
 * Answer JSON:  {"id":..., "ok":bool, "data":[{"symbol","state","price","change","pct","pre":{price,change,pct}|null,"post":{...}|null}], "error":"..."}
 */
class Quotes(private val done: (String) -> Unit) {

    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var cookie = ""
    @Volatile private var crumb = ""

    fun request(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val id = o.optString("id")
        pool.execute { done(call(o).put("id", id).toString()) }
    }

    fun close() = pool.shutdownNow()

    private fun call(o: JSONObject): JSONObject {
        val arr = o.optJSONArray("symbols") ?: JSONArray()
        val symbols = (0 until minOf(arr.length(), 100)).map { arr.optString(it) }.filter { it.matches(Regex("[A-Za-z0-9.\\-^=]{1,20}")) }
        if (symbols.isEmpty()) return fail("no symbols")
        return try {
            if (crumb.isEmpty()) login()
            var r = quote(symbols)
            if (r.first == 401 || r.first == 403) { login(); r = quote(symbols) }   // the crumb expired
            if (r.first !in 200..299) return fail("HTTP ${r.first}")
            val list = JSONObject(r.second).optJSONObject("quoteResponse")?.optJSONArray("result") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until list.length()) {
                val q = list.optJSONObject(i) ?: continue
                out.put(JSONObject()
                    .put("symbol", q.optString("symbol"))
                    .put("state", q.optString("marketState"))
                    .put("price", q.opt("regularMarketPrice") ?: JSONObject.NULL)
                    .put("change", q.opt("regularMarketChange") ?: JSONObject.NULL)
                    .put("pct", q.opt("regularMarketChangePercent") ?: JSONObject.NULL)
                    .put("pre", ext(q, "preMarket"))
                    .put("post", ext(q, "postMarket")))
            }
            JSONObject().put("ok", true).put("data", out)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ext(q: JSONObject, p: String): Any =
        if (!q.has(p + "Price")) JSONObject.NULL
        else JSONObject().put("price", q.opt(p + "Price")).put("change", q.opt(p + "Change") ?: JSONObject.NULL)
            .put("pct", q.opt(p + "ChangePercent") ?: JSONObject.NULL).put("time", q.opt(p + "Time") ?: JSONObject.NULL)

    /** Yahoo wants its A3 cookie plus a matching crumb on the quote API. */
    private fun login() {
        val c = open("https://fc.yahoo.com/")
        c.instanceFollowRedirects = false
        c.responseCode   // 404 is normal: we only want the cookie
        cookie = (c.headerFields["Set-Cookie"] ?: c.headerFields["set-cookie"] ?: emptyList())
            .map { it.substringBefore(';') }.filter { it.contains('=') }.joinToString("; ")
        c.disconnect()
        val k = open("https://query1.finance.yahoo.com/v1/test/getcrumb")
        crumb = if (k.responseCode == 200) k.inputStream.bufferedReader().use { it.readText() }.trim() else ""
        k.disconnect()
        if (crumb.isEmpty() || crumb.contains('<')) { crumb = ""; throw IllegalStateException("Yahoo login failed") }
    }

    private fun quote(symbols: List<String>): Pair<Int, String> {
        val fields = "regularMarketPrice,regularMarketChange,regularMarketChangePercent,preMarketPrice,preMarketChange," +
            "preMarketChangePercent,preMarketTime,postMarketPrice,postMarketChange,postMarketChangePercent,postMarketTime,marketState"
        val url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=" + URLEncoder.encode(symbols.joinToString(","), "UTF-8") +
            "&fields=" + fields + "&crumb=" + URLEncoder.encode(crumb, "UTF-8")
        val c = open(url)
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return status to text
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", UA)
        if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
    }

    private fun fail(why: String) = JSONObject().put("ok", false).put("data", JSONObject.NULL).put("error", why)

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
