package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DynaliteClient
 * Handles TCP connection and DyNet1 protocol with a Dynalite PDEG gateway.
 *
 * DTP packet = 8 bytes:
 * [0x1C][AREA][CHANNEL][OPCODE][D1][D2][D3][CHECKSUM]
 * Checksum = XOR(bytes 1..6) | 0x80
 *
 * Key opcodes:
 *   0x71 = Set level (sent and received)
 *   0x61 = Report level (PDEG response to request)
 *   0x62 = Request level
 *   0x79 = Recall preset
 */
class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class LevelEvent(
        val area    : Int,
        val channel : Int,
        val levelPct: Int,    // 0-100
        val opcode  : Int,
        val rawHex  : String
    )

    var onStateChange: ((State, String) -> Unit)? = null
    var onLevelEvent : ((LevelEvent) -> Unit)?     = null
    var onLog        : ((String) -> Unit)?          = null

    private val TAG  = "Dynalite"
    private val SYNC = 0x1C

    private var socket   : Socket? = null
    private var state               = State.DISCONNECTED
    private val scope               = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastIp              = ""
    private var lastPort            = 50000

    // ------------------------------------------------------------------
    // CONNECTION
    // ------------------------------------------------------------------
    fun connect(ip: String, port: Int) {
        lastIp   = ip
        lastPort = port
        setState(State.CONNECTING, "Connecting to $ip:$port...")
        scope.launch {
            try {
                closeSocket()
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 6000)
                s.soTimeout = 0
                socket = s
                setState(State.CONNECTED, "Connected to $ip:$port")
                readLoop(s)
            } catch (e: Exception) {
                closeSocket()
                setState(State.DISCONNECTED, "Connection error: ${e.message}")
                delay(8000)
                if (state == State.DISCONNECTED && lastIp.isNotEmpty()) connect(lastIp, lastPort)
            }
        }
    }

    fun disconnect() {
        state = State.DISCONNECTED
        closeSocket()
    }

    // ------------------------------------------------------------------
    // READ LOOP — assembles and parses incoming DTP packets
    // ------------------------------------------------------------------
    private fun readLoop(s: Socket) {
        val buf    = ByteArray(1024)
        val packet = ByteArray(8)
        var pos    = 0
        try {
            while (state == State.CONNECTED) {
                val n = s.getInputStream().read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt().and(0xFF)
                    if (pos == 0 && b != SYNC) continue  // wait for sync byte
                    packet[pos++] = b.toByte()
                    if (pos == 8) { processPacket(packet.copyOf()); pos = 0 }
                }
            }
        } catch (e: Exception) {
            log("Read error: ${e.message}")
        } finally {
            if (state == State.CONNECTED) {
                setState(State.DISCONNECTED, "Connection lost. Reconnecting...")
                scope.launch {
                    delay(8000)
                    if (state == State.DISCONNECTED) connect(lastIp, lastPort)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // PARSE INCOMING PACKET
    // ------------------------------------------------------------------
    private fun processPacket(pkt: ByteArray) {
        if (pkt[0].toInt().and(0xFF) != SYNC) return
        val area    = pkt[1].toInt().and(0xFF)
        val channel = pkt[2].toInt().and(0xFF)
        val opcode  = pkt[3].toInt().and(0xFF)
        val d1      = pkt[4].toInt().and(0xFF)
        val hex     = pkt.toHex()

        if (!verifyChecksum(pkt)) { log("RX bad checksum: $hex"); return }

        log("RX $hex")

        val levelPct = (d1 * 100) / 255
        when (opcode) {
            0x71 -> onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            0x61 -> onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            else -> log("RX opcode 0x${opcode.toString(16).uppercase()} area=$area ch=$channel")
        }
    }

    // ------------------------------------------------------------------
    // COMMANDS
    // ------------------------------------------------------------------
    fun setLevel(area: Int, channel: Int, level: Int, fadeMs: Int = 1000) {
        val value = (level.coerceIn(0, 100) * 255 / 100)
        val fade  = (fadeMs / 20).coerceIn(0, 0xFFFF)
        send(area, channel, 0x71, value, (fade shr 8) and 0xFF, fade and 0xFF)
    }

    fun turnOn(area: Int, channel: Int, level: Int = 100)  = setLevel(area, channel, level, 500)
    fun turnOff(area: Int, channel: Int)                    = setLevel(area, channel, 0, 200)
    fun recallPreset(area: Int, preset: Int)                = send(area, 0, 0x79, preset and 0xFF, 0, 0xFF)

    /** Request current level for all channels in an area */
    fun requestLevels(area: Int) {
        for (ch in 1..16) send(area, ch, 0x62, 0, 0, 0xFF)
    }

    // ------------------------------------------------------------------
    // BUILD AND SEND PACKET
    // ------------------------------------------------------------------
    fun sendRaw(pkt: ByteArray) {
        if (pkt.size != 8) { log("TX error: packet must be 8 bytes"); return }
        val hex = pkt.toHex()
        log("TX $hex")
        if (state != State.CONNECTED) { log("TX discarded — not connected"); return }
        scope.launch {
            try {
                socket?.getOutputStream()?.apply { write(pkt); flush() }
            } catch (e: Exception) {
                setState(State.DISCONNECTED, "TX error: ${e.message}")
            }
        }
    }

    private fun send(area: Int, channel: Int, opcode: Int, d1: Int, d2: Int, d3: Int) {
        val pkt = ByteArray(8)
        pkt[0] = SYNC.toByte()
        pkt[1] = (area    and 0xFF).toByte()
        pkt[2] = (channel and 0xFF).toByte()
        pkt[3] = (opcode  and 0xFF).toByte()
        pkt[4] = (d1      and 0xFF).toByte()
        pkt[5] = (d2      and 0xFF).toByte()
        pkt[6] = (d3      and 0xFF).toByte()
        pkt[7] = checksum(pkt)
        sendRaw(pkt)
    }

    // ------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------
    private fun checksum(pkt: ByteArray): Byte {
        var xor = 0
        for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
        return ((xor or 0x80) and 0xFF).toByte()
    }

    fun buildPacket(area: Int, channel: Int, opcode: Int, d1: Int, d2: Int, d3: Int): ByteArray {
        val pkt = ByteArray(8)
        pkt[0] = SYNC.toByte()
        pkt[1] = (area    and 0xFF).toByte()
        pkt[2] = (channel and 0xFF).toByte()
        pkt[3] = (opcode  and 0xFF).toByte()
        pkt[4] = (d1      and 0xFF).toByte()
        pkt[5] = (d2      and 0xFF).toByte()
        pkt[6] = (d3      and 0xFF).toByte()
        pkt[7] = checksum(pkt)
        return pkt
    }

    private fun verifyChecksum(pkt: ByteArray) = pkt[7] == checksum(pkt)

    private fun ByteArray.toHex() = joinToString(" ") {
        it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun setState(s: State, msg: String) {
        state = s
        log(msg)
        onStateChange?.invoke(s, msg)
    }

    fun isConnected() = state == State.CONNECTED

    fun destroy() {
        state = State.DISCONNECTED
        closeSocket()
        scope.cancel()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }
}
