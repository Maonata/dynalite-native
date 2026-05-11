package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DynaliteClient — DyNet1 Protocol over TCP para Dynalite PDEG
 *
 * Formato DyNet1 (8 bytes):
 * [0x1C][AREA][DATA1][OPCODE][DATA2][DATA3][JOIN][CHECKSUM]
 *
 * AREA   = área lógica (1-255)
 * DATA1  = nivel (0-255) o dato según opcode
 * OPCODE = tipo de comando
 * DATA2  = dato / fade_hi
 * DATA3  = dato / fade_lo
 * JOIN   = 0xFF = todos los canales, o (channel - 1) para uno concreto
 * CHECKSUM = XOR de bytes[1..6] con bit alto forzado (xor | 0x80)
 *
 * Opcodes:
 * 0x71 = Set channel level with fade (TX/RX)
 * 0x61 = Report channel level (RX)
 * 0x62 = Request channel level (TX)
 * 0x79 = Recall preset (ejemplo)
 */
class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class LevelEvent(
        val area: Int,
        val channel: Int,   // 0 = ALL, 1..N = canal
        val levelPct: Int,  // 0-100
        val opcode: Int,
        val rawHex: String
    )

    var onStateChange: ((State, String) -> Unit)? = null
    var onLevelEvent: ((LevelEvent) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val TAG = "Dynalite"
    private val SYNC = 0x1C

    private var socket: Socket? = null
    private var state = State.DISCONNECTED
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastIp = ""
    private var lastPort = 50000

    // ------------------------------------------------------------------
    // CONEXIÓN
    // ------------------------------------------------------------------
    fun connect(ip: String, port: Int) {
        lastIp = ip
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
                if (state == State.DISCONNECTED && lastIp.isNotEmpty()) {
                    connect(lastIp, lastPort)
                }
            }
        }
    }

    fun disconnect() {
        state = State.DISCONNECTED
        closeSocket()
    }

    // ------------------------------------------------------------------
    // READ LOOP — reconstruye paquetes DyNet1 de 8 bytes
    // ------------------------------------------------------------------
    private fun readLoop(s: Socket) {
        val buf = ByteArray(1024)
        val packet = ByteArray(8)
        var pos = 0
        try {
            while (state == State.CONNECTED) {
                val n = s.getInputStream().read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt().and(0xFF)
                    // Re-sync: si estamos en posición 0, esperamos SYNC 0x1C
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
                    if (state == State.DISCONNECTED) {
                        connect(lastIp, lastPort)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // PARSE INCOMING PACKET
    // ------------------------------------------------------------------
    private fun processPacket(pkt: ByteArray) {
        if (pkt.size != 8) return
        if (pkt[0].toInt().and(0xFF) != SYNC) return

        val hex = pkt.toHex()

        if (!verifyChecksum(pkt)) {
            log("RX bad checksum: $hex")
            return
        }

        val area = pkt[1].toInt().and(0xFF)
        val data1 = pkt[2].toInt().and(0xFF)
        val opcode = pkt[3].toInt().and(0xFF)
        val data2 = pkt[4].toInt().and(0xFF)
        val data3 = pkt[5].toInt().and(0xFF)
        val join = pkt[6].toInt().and(0xFF)

        log("RX $hex")

        // JOIN: 0xFF = ALL, si no canal = JOIN+1
        val channel = if (join == 0xFF) 0 else join + 1

        when (opcode) {
            0x71 -> {
                // Set level: DATA1 = nivel 0-255
                val levelPct = (data1 * 100) / 255
                onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            }
            0x61 -> {
                // Report level: DATA1 = nivel 0-255
                val levelPct = (data1 * 100) / 255
                onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            }
            else -> {
                log("RX opcode=0x${opcode.toString(16).uppercase()} area=$area join=$join d2=$data2 d3=$data3")
            }
        }
    }

    // ------------------------------------------------------------------
    // COMANDOS
    // ------------------------------------------------------------------

    /**
     * Set level para un canal o ALL (channel=0).
     */
    fun setLevel(area: Int, channel: Int, level: Int, fadeMs: Int = 1000) {
        val data1 = (level.coerceIn(0, 100) * 255 / 100) // 0-255
        val fade = (fadeMs / 20).coerceIn(0, 0xFFFF)
        val d2 = (fade shr 8) and 0xFF
        val d3 = fade and 0xFF
        val join = if (channel <= 0) 0xFF else (channel - 1) and 0xFF
        sendPacket(area, data1, 0x71, d2, d3, join)
    }

    fun turnOn(area: Int, channel: Int, level: Int = 100) =
        setLevel(area, channel, level, 500)

    fun turnOff(area: Int, channel: Int) =
        setLevel(area, channel, 0, 200)

    /**
     * Ejemplo de recall de preset (puede variar según instalación).
     */
    fun recallPreset(area: Int, preset: Int) {
        val opcode = ((preset - 1) % 4) and 0xFF
        val data3 = ((preset - 1) / 4) and 0xFF
        sendPacket(area, 0, opcode, 0, data3, 0xFF)
    }

    /**
     * Request current levels: envía peticiones por JOIN 0..7
     */
    fun requestLevels(area: Int) {
        for (ch in 1..8) {
            val join = (ch - 1) and 0xFF
            // Uso opcode 0x62 para "request" para diferenciar de 0x61 (report)
            sendPacket(area, 0, 0x62, 0, 0, join)
        }
    }

    // ------------------------------------------------------------------
    // RAW PACKET SEND
    // ------------------------------------------------------------------
    fun sendRaw(pkt: ByteArray) {
        if (pkt.size != 8) {
            log("TX error: packet must be 8 bytes")
            return
        }
        log("TX ${pkt.toHex()}")
        if (state != State.CONNECTED) {
            log("TX discarded — not connected")
            return
        }
        scope.launch {
            try {
                socket?.getOutputStream()?.apply {
                    write(pkt)
                    flush()
                }
            } catch (e: Exception) {
                setState(State.DISCONNECTED, "TX error: ${e.message}")
            }
        }
    }

    /**
     * Construye paquete DyNet1 con checksum correcto.
     */
    fun buildPacket(
        area: Int,
        data1: Int,
        opcode: Int,
        d2: Int,
        d3: Int,
        join: Int
    ): ByteArray {
        val pkt = ByteArray(8)
        pkt[0] = SYNC.toByte()
        pkt[1] = (area and 0xFF).toByte()
        pkt[2] = (data1 and 0xFF).toByte()
        pkt[3] = (opcode and 0xFF).toByte()
        pkt[4] = (d2 and 0xFF).toByte()
        pkt[5] = (d3 and 0xFF).toByte()
        pkt[6] = (join and 0xFF).toByte()
        pkt[7] = checksum(pkt)
        return pkt
    }

    // ------------------------------------------------------------------
    // HELPERS INTERNOS
    // ------------------------------------------------------------------
    private fun sendPacket(
        area: Int,
        data1: Int,
        opcode: Int,
        d2: Int,
        d3: Int,
        join: Int
    ) {
        val pkt = buildPacket(area, data1, opcode, d2, d3, join)
        sendRaw(pkt)
    }

    private fun checksum(pkt: ByteArray): Byte {
    // Suma bytes 0..6 (inclusive)
    var sum = 0
    for (i in 0..6) {
        sum = (sum + pkt[i].toInt().and(0xFF)) and 0xFF
    }
    // Negativo en complemento a 2 (8 bits)
    val cs = (-sum) and 0xFF
    return cs.toByte()
}

    private fun verifyChecksum(pkt: ByteArray): Boolean =
        pkt.size == 8 && pkt[7] == checksum(pkt)

    private fun ByteArray.toHex(): String =
        joinToString(" ") { it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0') }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun setState(s: State, msg: String) {
        state = s
        log(msg)
        onStateChange?.invoke(s, msg)
    }

    fun isConnected(): Boolean = state == State.CONNECTED

    fun destroy() {
        state = State.DISCONNECTED
        closeSocket()
        scope.cancel()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
