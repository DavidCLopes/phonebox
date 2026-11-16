package net.oxog.phonebox.deskpanel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bluetooth LE LED lights (MELK-OT21 bars, ELK-BLEDOB strips: the "ELK-BLEDOM" family), driven by the phone itself.
 *
 * Lights are reached by their address (no scan, so no location permission on Android 11 and older). Once a light has
 * been asked for, the app keeps it connected and reconnects on its own while the app runs. Every command is a 9-byte
 * frame written without response to characteristic fff3; all work happens on one background thread, one write at a
 * time, because Android's GATT does not queue writes.
 */
@SuppressLint("MissingPermission")
class Lights(private val ctx: Context) {

    private val thread = HandlerThread("lights").apply { start() }
    private val h = Handler(thread.looper)
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val lights = HashMap<String, Light>()   // address -> light; touched only on the lights thread
    @Volatile private var snapshot = "{}"
    // last power / colour / brightness sent to each light, kept across app restarts so the panel shows the real colour
    private val prefs = ctx.getSharedPreferences("lights", Context.MODE_PRIVATE)

    init {
        h.post {
            val saved = try { JSONObject(prefs.getString("last", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            for (addr in saved.keys()) {
                val o = saved.optJSONObject(addr) ?: continue
                val l = lights.getOrPut(addr) { Light(addr) }
                if (o.has("power")) l.power = o.optBoolean("power")
                o.optString("color").takeIf { it.isNotEmpty() }?.let { l.color = it }
                if (o.has("bright")) l.bright = o.optInt("bright")
            }
            publish()
        }
    }

    private fun save() {
        val all = JSONObject()
        for (l in lights.values) all.put(l.address, JSONObject()
            .apply { l.power?.let { put("power", it) }; l.color?.let { put("color", it) }; l.bright?.let { put("bright", it) } })
        prefs.edit().putString("last", all.toString()).apply()
    }

    private inner class Light(val address: String) {
        var gatt: BluetoothGatt? = null
        var ch: BluetoothGattCharacteristic? = null
        var state = "idle"          // idle | connecting | ready | error
        var error = ""
        var retryMs = 2_000L
        val queue = ArrayDeque<ByteArray>()
        var writing = false
        // last wanted state, sent again after a reconnect so a light that dropped out catches up
        val wanted = LinkedHashMap<String, ByteArray>()
        // the same, readable, for the panel (so it can show each light's real colour)
        var power: Boolean? = null
        var color: String? = null
        var bright: Int? = null
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Command from the panel: {"lights":[addr...], "power":bool?, "color":"#rrggbb"?, "bright":0-100?, "effect":int?, "speed":0-100?} */
    fun command(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val addrs = o.optJSONArray("lights") ?: JSONArray()
        val frames = LinkedHashMap<String, ByteArray>()
        if (o.has("power")) frames["power"] = if (o.getBoolean("power")) frame(0x04, 0xF0, 0x00, 0x01, 0xFF) else frame(0x04, 0x00, 0x00, 0x00, 0xFF)
        o.optString("color").takeIf { it.matches(Regex("#?[0-9a-fA-F]{6}")) }?.let {
            val v = it.removePrefix("#").toInt(16)
            frames["color"] = frame(0x05, 0x03, v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
            frames.remove("effect")
        }
        if (o.has("bright")) frames["bright"] = frame(0x01, o.getInt("bright").coerceIn(0, 100))
        if (o.has("effect")) frames["effect"] = frame(0x03, o.getInt("effect") and 0xFF, 0x03)
        if (o.has("speed")) frames["speed"] = frame(0x02, o.getInt("speed").coerceIn(0, 100))
        if (frames.isEmpty() && !o.optBoolean("connect")) return
        h.post {
            for (i in 0 until addrs.length()) {
                val addr = addrs.optString(i).uppercase()
                if (!BluetoothAdapter.checkBluetoothAddress(addr)) continue
                val l = lights.getOrPut(addr) { Light(addr) }
                if (o.has("power")) l.power = o.getBoolean("power")
                if (frames.containsKey("color")) l.color = "#" + o.getString("color").removePrefix("#").lowercase()
                if (o.has("bright")) l.bright = o.getInt("bright").coerceIn(0, 100)
                if (frames.isNotEmpty()) save()
                for ((k, f) in frames) {
                    if (k == "color") l.wanted.remove("effect")
                    if (k == "effect") l.wanted.remove("color")
                    l.wanted[k] = f
                    l.queue.addLast(f)
                }
                // only the newest few frames matter (dragging the brightness slider sends many)
                while (l.queue.size > 12) l.queue.removeFirst()
                if (l.state == "ready") pump(l) else connect(l)
            }
            publish()
        }
    }

    /** Every light this app knows (it has talked to it before): off. */
    fun allOff() = h.post {
        val addrs = JSONArray(lights.keys.toList())
        if (addrs.length() > 0) command(JSONObject().put("lights", addrs).put("power", false).toString())
    }

    /** {"bluetooth":"on"|"off"|"none"|"permission", "lights":{addr:{"state":..., "error":...}}} */
    fun state(): String = snapshot

    fun close() {
        h.post {
            lights.values.forEach { it.gatt?.close(); it.gatt = null }
            lights.clear()
            thread.quitSafely()
        }
    }

    private fun publish() {
        val bt = when {
            adapter == null -> "none"
            !hasPermission() -> "permission"
            !adapter.isEnabled -> "off"
            else -> "on"
        }
        val all = JSONObject()
        for (l in lights.values) all.put(l.address, JSONObject().put("state", l.state).put("error", l.error)
            .apply { l.power?.let { put("power", it) }; l.color?.let { put("color", it) }; l.bright?.let { put("bright", it) } })
        snapshot = JSONObject().put("bluetooth", bt).put("lights", all).toString()
    }

    private fun connect(l: Light) {
        if (l.state == "connecting") return
        val a = adapter
        if (a == null || !a.isEnabled || !hasPermission()) {
            l.state = "error"; l.error = if (a == null) "no Bluetooth" else if (!hasPermission()) "no permission" else "Bluetooth off"
            retry(l); return
        }
        l.state = "connecting"; l.error = ""
        l.gatt?.close()
        val dev: BluetoothDevice = a.getRemoteDevice(l.address)
        l.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            dev.connectGatt(ctx, false, callback(l), BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(ctx, false, callback(l))
        // a light that is off the mains or out of range never answers: give up after a while and try again later
        h.postDelayed({ if (l.state == "connecting") fail(l, "no answer") }, 15_000)
        publish()
    }

    private fun fail(l: Light, why: String) {
        l.gatt?.close(); l.gatt = null; l.ch = null; l.writing = false
        l.state = "error"; l.error = why
        Log.w(TAG, "${l.address}: $why")
        retry(l)
        publish()
    }

    private fun retry(l: Light) {
        val wait = l.retryMs
        l.retryMs = (l.retryMs * 2).coerceAtMost(30_000)
        h.postDelayed({ if (l.state == "error" || l.state == "idle") { l.state = "idle"; connect(l) } }, wait)
    }

    private fun callback(l: Light) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
            if (g != l.gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) fail(l, "service discovery failed")
            } else fail(l, if (newState == BluetoothProfile.STATE_DISCONNECTED) "disconnected ($status)" else "status $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
            if (g != l.gatt) return@post
            val ch = g.getService(SERVICE)?.getCharacteristic(WRITE)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) { fail(l, "no fff3 channel (another app connected?)"); return@post }
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            l.ch = ch; l.state = "ready"; l.error = ""; l.retryMs = 2_000
            // the handshake these lights expect, then the last wanted state (power first)
            val catchUp = INIT + l.wanted.values
            l.queue.clear(); l.queue.addAll(catchUp)
            pump(l)
            publish()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post {
            if (g != l.gatt) return@post
            l.writing = false
            h.postDelayed({ pump(l) }, GAP_MS)
            }
        }
    }

    private fun pump(l: Light) {
        if (l.writing || l.state != "ready") return
        val g = l.gatt ?: return
        val ch = l.ch ?: return
        val f = l.queue.removeFirstOrNull() ?: return
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, f, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0
        } else {
            @Suppress("DEPRECATION") run { ch.value = f; g.writeCharacteristic(ch) }
        }
        if (!ok) { l.queue.addFirst(f); h.postDelayed({ pump(l) }, GAP_MS); return }
        l.writing = true
        // some stacks never report writes without response: do not wait for the callback forever
        h.postDelayed({ if (l.writing) { l.writing = false; pump(l) } }, 150)
    }

    companion object {
        private const val TAG = "PhoneBoxLights"
        private const val GAP_MS = 25L
        private val SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val WRITE = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
        private val INIT = listOf(byteArrayOf(0x7E, 0x07, 0x83.toByte()), byteArrayOf(0x7E, 0x04, 0x04))

        /** 0x7e 0x00, body, zero padding, 0xef: 9 bytes (same as protocol.py in ../leds, where every body starts with 0x00). */
        fun frame(vararg body: Int): ByteArray =
            ByteArray(9).also { f -> f[0] = 0x7E; body.forEachIndexed { i, b -> f[i + 2] = b.toByte() }; f[8] = 0xEF.toByte() }
    }
}
