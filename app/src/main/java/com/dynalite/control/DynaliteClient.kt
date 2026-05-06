package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var onStateChange: ((State, String) -> Unit)? = null

    private val SYNC = 0x1C
    private var socket: Socket? = null
    private var state = State.DISCONNECTED
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastIp = ""
    private var lastPort = 50000

    fun connect(ip: String, port: Int) {
        lastIp = ip
        lastPort = port
        setState(State.CONNECTING, "Conectando a $ip:$port...")
        scope.launch {
            try {
                closeSocket()
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 6000)
                s.soTimeout = 0
                socket = s
                setState(State.CONNECTED, "Conectado a $ip:$port ✓")
                // Read loop
                val input = s.getInputStream()
                val buf = ByteArray(256)
                while (state == State.CONNECTED) {
                    val n = input.read(buf)
                    if (n < 0) break
                }
            } catch (e: Exception) {
                closeSocket()
                setState(State.DISCONNECTED, "Error: ${e.message}")
                delay(8000)
                if (state == State.DISCONNECTED && lastIp.isNotEmpty()) {
                    connect(lastIp, lastPort)
                }
            }
        }
    }

    fun setLevel(area: Int, channel: Int, level: Int, fadeMs: Int = 1000) {
        val value = (level.coerceIn(0, 100) * 255 / 100)
        val fade = fadeMs / 20
        send(area, channel, 0x71, value, (fade shr 8) and 0xFF, fade and 0xFF)
    }

    fun turnOn(area: Int, channel: Int, level: Int = 100) = setLevel(area, channel, level, 500)
    fun turnOff(area: Int, channel: Int) = setLevel(area, channel, 0, 200)

    private fun send(area: Int, channel: Int, opcode: Int, d1: Int, d2: Int, d3: Int) {
        val pkt = ByteArray(8)
        pkt[0] = SYNC.toByte()
        pkt[1] = (area and 0xFF).toByte()
        pkt[2] = (channel and 0xFF).toByte()
        pkt[3] = (opcode and 0xFF).toByte()
        pkt[4] = (d1 and 0xFF).toByte()
        pkt[5] = (d2 and 0xFF).toByte()
        pkt[6] = (d3 and 0xFF).toByte()
        pkt[7] = checksum(pkt)
        val hex = pkt.joinToString(" ") {
            it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
        }
        Log.d("Dynalite", "TX: $hex")
        if (state != State.CONNECTED) { Log.w("Dynalite", "Sin conexion"); return }
        scope.launch {
            try {
                socket?.getOutputStream()?.apply { write(pkt); flush() }
            } catch (e: Exception) {
                setState(State.DISCONNECTED, "Error TX: ${e.message}")
            }
        }
    }

    private fun checksum(pkt: ByteArray): Byte {
        var xor = 0
        for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
        return ((xor or 0x80) and 0xFF).toByte()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }

    private fun setState(s: State, msg: String) {
        state = s
        Log.d("Dynalite", msg)
        onStateChange?.invoke(s, msg)
    }

    fun isConnected() = state == State.CONNECTED
    fun destroy() { closeSocket(); scope.cancel() }
}
