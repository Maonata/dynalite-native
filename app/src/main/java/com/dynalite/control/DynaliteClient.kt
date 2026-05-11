package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DynaliteClient — DyNet1 Protocol over TCP for Dynalite PDEG
 *
 * CORRECT DyNet1 packet format (8 bytes):
 * [0x1C][AREA][DATA1][OPCODE][DATA2][DATA3][JOIN][CHECKSUM]
 *
 * Where:
 *   AREA    = Logical area number (1-255)
 *   DATA1   = Fade time high byte (or level for some opcodes)
 *   OPCODE  = Command type
 *   DATA2   = Fade time low byte (or additional data)
 *   DATA3   = Additional data
 *   JOIN    = Channel join byte (0xFF = all channels, or specific channel mask)
 *   CHECKSUM = XOR of bytes[1..6] | 0x80
 *
 * Opcodes:
 *   0x71 = Set channel level with fade
 *   0x61 = Report channel level (incoming from PDEG)
 *   0x62 = Request channel level
 *   0x79 = Recall preset
 *
 * IMPORTANT: In DyNet1 logical messages (0x1C), the CHANNEL is NOT
 * a separate byte. Individual channel control is done via the JOIN byte.
 * To control a specific channel, set JOIN to (channel - 1).
 * To control all channels in an area, set JOIN to 0xFF.
 */
class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class LevelEvent(
        val area    : Int,
        val channel : Int,   // decoded from JOIN byte (JOIN + 1), or 0 = all
        val levelPct: Int,   // 0-100
        val opcode  : Int,
        val rawHex  : String
    )

    var onStateChange: ((State, String) -> Unit)? = null
    var onLevelEvent : ((LevelEvent) -> Unit)?    = null
    var onLog        : ((String) -> Unit)?         = null

    private val TAG  = "Dynalite"
    private val SYNC = 0x1C

    private var socket       : Socket? = null
    private var state                   = State.DISCONNECTED
    private val scope                   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastIp                  = ""
    private var lastPort                = 50000

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
    // READ LOOP — assembles 8-byte DyNet1 packets from the TCP stream
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
                    // Re-sync: if we're at position 0, wait for SYNC byte (0x1C)
                    if (pos == 0 && b != SYNC) continue
                    packet[pos++] = b.toByte()
                    if (pos == 8) {
                        processPacket(packet.copyOf())
                        pos = 0
                    }
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
    // DyNet1: [0x1C][AREA][DATA1][OPCODE][DATA2][DATA3][JOIN][CHECKSUM]
    // ------------------------------------------------------------------
    private fun processPacket(pkt: ByteArray) {
        if (pkt[0].toInt().and(0xFF) != SYNC) return
        val hex = pkt.toHex()

        if (!verifyChecksum(pkt)) {
            log("RX bad checksum: $hex")
            return
        }

        val area   = pkt[1].toInt().and(0xFF)
        val data1  = pkt[2].toInt().and(0xFF)
        val opcode = pkt[3].toInt().and(0xFF)
        val data2  = pkt[4].toInt().and(0xFF)
        val join   = pkt[6].toInt().and(0xFF)

        log("RX $hex")

        // Decode channel from JOIN byte: 0xFF = all channels, else JOIN+1
        val channel = if (join == 0xFF) 0 else join + 1

        when (opcode) {
            0x71 -> {
                // Set level: DATA1 = level (0-255)
                val levelPct = (data1 * 100) / 255
                onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            }
            0x61 -> {
                // Report level: DATA1 = level (0-255)
                val levelPct = (data1 * 100) / 255
                onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            }
            else -> log("RX opcode=0x${opcode.toString(16).uppercase()} area=$area join=$join")
        }
    }

    // ------------------------------------------------------------------
    // COMMANDS
    // ------------------------------------------------------------------

    /**
     * Set level for a specific channel in an area.
     * DyNet1 packet: [0x1C][AREA][LEVEL][0x71][FADE_HI][FADE_LO][CHANNEL-1][CHECKSUM]
     *
     * @param area      Dynalite area number (1-255)
     * @param channel   Channel within area (1-255), use 0 for all channels
     * @param level     Brightness level (0-100%)
     * @param fadeMs    Fade time in milliseconds
     */
    fun setLevel(area: Int, channel: Int, level: Int, fadeMs: Int = 1000) {
        val data1 = (level.coerceIn(0, 100) * 255 / 100)   // level as 0-255
        val fade  = (fadeMs / 20).coerceIn(0, 0xFFFF)
        val d2    = (fade shr 8) and 0xFF
        val d3    = fade and 0xFF
        val join  = if (channel <= 0) 0xFF else (channel - 1) and 0xFF
        sendPacket(area, data1, 0x71, d2, d3, join)
    }

    fun turnOn(area: Int, channel: Int, level: Int = 100) = setLevel(area, channel, level, 500)
    fun turnOff(area: Int, channel: Int)                   = setLevel(area, channel, 0, 200)

    /**
     * Recall a preset/scene for an area.
     * Opcode 0x00-0x03 = preset 1-4; for preset N use opcode (N-1) % 4 with DATA3 offset
     */
    fun recallPreset(area: Int, preset: Int) {
        val opcode = ((preset - 1) % 4) and 0xFF
        val data3  = ((preset - 1) / 4) and 0xFF
        sendPacket(area, 0, opcode, 0, data3, 0xFF)
    }

    /** Request current level for channels in an area */
    fun requestLevels(area: Int) {
        for (ch in 1..8) {
            val join = (ch - 1) and 0xFF
            sendPacket(area, 0, 0x61, 0, 0, join)
        }
    }

    // ------------------------------------------------------------------
    // RAW PACKET SEND (used by DiagActivity)
    // ------------------------------------------------------------------
    fun sendRaw(pkt: ByteArray) {
        if (pkt.size != 8) { log("TX error: packet must be 8 bytes"); return }
        log("TX ${pkt.toHex()}")
        if (state != State.CONNECTED) { log("TX discarded — not connected"); return }
        scope.launch {
            try {
                socket?.getOutputStream()?.apply { write(pkt); flush() }
            } catch (e: Exception) {
                setState(State.DISCONNECTED, "TX error: ${e.message}")
            }
        }
    }

    /** Build a DyNet1 packet with auto-calculated checksum */
    fun buildPacket(area: Int, data1: Int, opcode: Int, d2: Int, d3: Int, join: Int): ByteArray {
        val pkt = ByteArray(8)
        pkt[0] = SYNC.toByte()
        pkt[1] = (area   and 0xFF).toByte()
        pkt[2] = (data1  and 0xFF).toByte()
        pkt[3] = (opcode and 0xFF).toByte()
        pkt[4] = (d2     and 0xFF).toByte()
        pkt[5] = (d3     and 0xFF).toByte()
        pkt[6] = (join   and 0xFF).toByte()
        pkt[7] = checksum(pkt)
        return pkt
    }

    // ------------------------------------------------------------------
    // INTERNAL HELPERS
    // ------------------------------------------------------------------
    private fun sendPacket(area: Int, data1: Int, opcode: Int, d2: Int, d3: Int, join: Int) {
        val pkt = buildPacket(area, data1, opcode, d2, d3, join)
        sendRaw(pkt)
    }

    private fun checksum(pkt: ByteArray): Byte {
        var xor = 0
        for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
        return ((xor or 0x80) and 0xFF).toByte()
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
