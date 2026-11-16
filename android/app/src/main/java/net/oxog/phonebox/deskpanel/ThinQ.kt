package net.oxog.phonebox.deskpanel

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors

/**
 * LG ThinQ Connect (the official cloud API, personal access token) called by the phone itself, so the air
 * conditioners work without the PC. HTML widgets cannot call it (sandboxed iframe, no CORS), so they ask the panel,
 * which asks this class; the answer comes back through [done]. Only https://api-<region>.lgthinq.com is reachable.
 *
 * Request JSON: {"id":..., "token":..., "country":"PT", "clientId":..., "method":"GET"|"POST", "path":"devices/...", "body":{...}?}
 * Answer JSON:  {"id":..., "ok":bool, "status":int, "data":<response>|null, "error":"..."}
 */
class ThinQ(private val done: (String) -> Unit) {

    private val pool = Executors.newFixedThreadPool(2)

    fun request(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val id = o.optString("id")
        pool.execute { done(call(o).put("id", id).toString()) }
    }

    fun close() = pool.shutdownNow()

    /** One request, blocking (also used by Automation). */
    fun call(o: JSONObject): JSONObject {
        val path = o.optString("path")
        val country = o.optString("country", "PT").uppercase()
        if (!path.matches(Regex("[A-Za-z0-9/_\\-]{1,200}")) || !country.matches(Regex("[A-Z]{2}")))
            return fail(0, "bad request")
        val method = if (o.optString("method", "GET").uppercase() == "POST") "POST" else "GET"
        return try {
            val c = URL("https://api-${region(country)}.lgthinq.com/$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer " + o.optString("token"))
            c.setRequestProperty("x-country", country)
            c.setRequestProperty("x-message-id", messageId())
            c.setRequestProperty("x-client-id", o.optString("clientId"))
            c.setRequestProperty("x-api-key", API_KEY)
            c.setRequestProperty("x-service-phase", "OP")
            c.setRequestProperty("Content-Type", "application/json")
            if (method == "POST") {
                c.setRequestProperty("x-conditional-control", "true")
                c.doOutput = true
                c.outputStream.use { it.write((o.optJSONObject("body") ?: JSONObject()).toString().toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            val body = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (status in 200..299) JSONObject().put("ok", true).put("status", status).put("data", body.opt("response") ?: JSONObject.NULL)
            else fail(status, body.optJSONObject("error")?.let { "${it.optString("code")} ${it.optString("message")}".trim() } ?: "HTTP $status")
        } catch (e: Exception) {
            fail(0, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(status: Int, why: String) = JSONObject().put("ok", false).put("status", status).put("data", JSONObject.NULL).put("error", why)

    private fun messageId(): String {
        val u = UUID.randomUUID()
        val b = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        // the public key of LG's own ThinQ Connect SDK (thinqconnect, const.API_KEY)
        private const val API_KEY = "v6GFvkweNo7DK7yD3ylIZ9w52aKBU0eJ7wLXkSR3"
        private val AIC = setOf("US", "CA", "MX", "BR", "AR", "CL", "CO", "PE", "EC", "VE", "PA", "CR", "GT", "DO", "PR", "JM", "UY", "PY", "BO", "HN", "SV", "NI")
        private val KIC = setOf("KR", "JP", "CN", "TW", "HK", "AU", "NZ", "SG", "MY", "TH", "VN", "PH", "ID", "IN")

        /** Same split as the SDK's country.get_region_from_country, roughly: Americas, Asia-Pacific, the rest (EIC). */
        fun region(country: String) = when (country) { in AIC -> "aic"; in KIC -> "kic"; else -> "eic" }
    }
}
