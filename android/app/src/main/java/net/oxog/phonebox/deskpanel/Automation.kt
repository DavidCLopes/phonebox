package net.oxog.phonebox.deskpanel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "The PC is off": when the panel has had no connection to the PC for [offAfterMin] minutes, switch off every light
 * the phone drives and the chosen air conditioners, then let the phone's screen sleep. When the PC is back (the agent
 * wakes the phone and opens the panel), the screen stays on again. Nothing is switched back on.
 *
 * The rule comes from the panel ([configure], kept across restarts). A partial wake lock keeps the countdown running
 * even if someone turns the screen off by hand.
 */
class Automation(
    private val ctx: Context,
    private val lights: Lights,
    private val thinq: ThinQ,
    private val keepScreenOn: (Boolean) -> Unit,
    private val pcAnswers: () -> Boolean,   // asks the agent over USB and Wi-Fi; blocking
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("automation", Context.MODE_PRIVATE)
    private var rule: JSONObject? = try { prefs.getString("rule", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
    private var pending: Runnable? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile var asleep = false   // the rule has run and the screen may sleep
        private set

    /** {"offAfterMin":30, "thinq":{"token","country","clientId"}, "acs":["Ar Escritório"]} */
    fun configure(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        rule = o
        prefs.edit().putString("rule", o.toString()).apply()
    }

    fun pcOnline() = main.post {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        release()
        if (asleep) Log.i(TAG, "PC back")
        asleep = false
        keepScreenOn(true)
    }

    /** Starts the countdown unless it is already running (or has already run). */
    fun pcOffline() = main.post {
        val r = rule ?: return@post
        if (pending != null || asleep) return@post
        val ms = (r.optDouble("offAfterMin", 30.0) * 60_000).toLong().coerceAtLeast(10_000)
        val job = Runnable { pending = null; fire() }
        pending = job
        main.postDelayed(job, ms)
        try {
            wake = ctx.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBox:pc-off")?.apply { acquire(ms + 120_000) }
        } catch (_: Exception) {}
        Log.i(TAG, "PC gone: everything off in ${ms / 60_000} min")
    }

    private fun fire() {
        val r = rule ?: return
        Thread {
            // the cable may only have slipped out: if the PC still answers (USB or Wi-Fi), do nothing and look again later
            if (pcAnswers()) {
                Log.i(TAG, "countdown over, but the PC answers: nothing switched off")
                main.post { release(); pcOffline() }
                return@Thread
            }
            Log.i(TAG, "PC off for a while: switching off")
            lights.allOff()
            val t = r.optJSONObject("thinq")
            val names = r.optJSONArray("acs") ?: JSONArray()
            if (t != null && names.length() > 0) {
                val base = JSONObject().put("token", t.optString("token")).put("country", t.optString("country", "PT")).put("clientId", t.optString("clientId"))
                val list = thinq.call(JSONObject(base.toString()).put("method", "GET").put("path", "devices"))
                val wanted = (0 until names.length()).map { names.optString(it) }.toSet()
                val devices = list.optJSONArray("data") ?: JSONArray()
                for (k in 0 until devices.length()) {
                    val d = devices.optJSONObject(k) ?: continue
                    val alias = d.optJSONObject("deviceInfo")?.optString("alias")
                    if (alias !in wanted) continue
                    val res = thinq.call(JSONObject(base.toString()).put("method", "POST").put("path", "devices/${d.optString("deviceId")}/control")
                        .put("body", JSONObject().put("operation", JSONObject().put("airConOperationMode", "POWER_OFF"))))
                    Log.i(TAG, "AC $alias off: ${res.optBoolean("ok")} ${res.optString("error")}")
                }
            }
            main.post { asleep = true; keepScreenOn(false); release() }
        }.start()
    }

    private fun release() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null
    }

    fun close() { pending?.let { main.removeCallbacks(it) }; release() }

    companion object { private const val TAG = "PhoneBoxAuto" }
}
