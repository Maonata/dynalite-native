package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DynaliteClient — DyNet RS485 logical messages (0x1C) sobre TCP (PDEG)
 *
 * Todos los mensajes son paquetes de 8 bytes:
 * [0] SYNC (0x1C lógico)
 * [1] Area
 * [2] Data2 (según tipo de mensaje: canal, preset, etc.)
 * [3] OpCode
 * [4] Data4
 * [5] Data5
 * [6] Join
 * [7] Checksum
 *
 * Checksum (según Integrator's Handbook):
 *   "The DyNet Checksum is equal to the Negative 8 bit 2's complement sum of bytes 0‑6."
 *
 * Referencias clave del manual:
 *  - 2.1 Linear Channel/Area Control
 *  - 2.3 Channel Level Request / Reply
 */
class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    /**
     * Evento de nivel lógico por canal.
     *
     * @param area    Área DyNet (1..255)
     * @param channel Canal lógico (1..255, 0 = ALL channels del área)
     * @param levelPct Nivel en %, 0..100
     * @param opcode  OpCode del mensaje origen (71/72/73/60/etc.)
     * @param rawHex  Paquete completo en hex
     */
    data class LevelEvent(
        val area: Int,
        val channel: Int,
        val levelPct: Int,
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
    // READ LOOP — monta paquetes DyNet de 8 bytes
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
                    // Re-sync: en posición 0 esperamos SYNC 0x1C
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
    // PARSE INCOMING PACKET (según handbook)
    // ------------------------------------------------------------------
    private fun processPacket(pkt: ByteArray) {
        if (pkt.size != 8) return
        if (pkt[0].toInt().and(0xFF) != SYNC) return

        val hex = pkt.toHex()

        if (!verifyChecksum(pkt)) {
            log("RX bad checksum: $hex")
            return
        }

        val area   = pkt[1].toInt().and(0xFF)
        val b2     = pkt[2].toInt().and(0xFF)
        val opcode = pkt[3].toInt().and(0xFF)
        val b4     = pkt[4].toInt().and(0xFF)
        val b5     = pkt[5].toInt().and(0xFF)
        val join   = pkt[6].toInt().and(0xFF)

        log("RX $hex")

        // Canal lógico según 2.1 / 2.3: byte2 = channel (0-origin, FF = ALL)
        val channel = if (b2 == 0xFF) 0 else b2 + 1

        when (opcode) {
            // ------------------------------------------------------------------
            // 2.1 Linear Channel/Area Control: 0x71 / 0x72 / 0x73
            // [1C][Area][ChannelIdx][OpCode][ChannelLevel][Fade][Join][CS]
            // ChannelLevel: 0x01=100%, 0xFF=0%
            // ------------------------------------------------------------------
            0x71, 0x72, 0x73 -> {
                val levelDyn = b4
                val levelPct = dynLevelToPercent(levelDyn)
                onLevelEvent?.invoke(
                    LevelEvent(area, channel, levelPct, opcode, hex)
                )
            }

            // ------------------------------------------------------------------
            // 2.3 Channel Level Reply
            // [1C][Area][ChannelIdx][0x60][TargetLevel][CurrentLevel][Join][CS]
            // Levels: 0x01=100%, 0xFF=0%
            // ------------------------------------------------------------------
            0x60 -> {
                val target = b4
                val current = b5
                val levelDyn = if (current != 0x00) current else target
                val levelPct = dynLevelToPercent(levelDyn)
                onLevelEvent?.invoke(
                    LevelEvent(area, channel, levelPct, opcode, hex)
                )
            }

            else -> {
                // Otros opcodes (presets, pánico, etc.) se loguean solamente
                log("RX opcode=0x${opcode.toString(16).uppercase()} area=$area b2=$b2 join=$join")
            }
        }
    }

    // Convierte nivel DyNet (0x01=100%, 0xFF=0%) a %
    private fun dynLevelToPercent(levelDyn: Int): Int = when {
        levelDyn <= 0x01 -> 100
        levelDyn >= 0xFF -> 0
        else -> ((0xFF - levelDyn) * 100) / 0xFE
    }

    // ------------------------------------------------------------------
    // COMANDOS (alineados con 2.1 y 2.3)
    // ------------------------------------------------------------------

    /**
     * 2.1 Linear Channel/Area Control
     *
     * Mensaje:
     * [1C][Area][ChannelIdx][OpCode][ChannelLevel][Fade][Join][CS]
     *
     * ChannelLevel: 0x01 = 100 %, 0xFF = 0 %.
     * ChannelIdx: 0-origin (0=canal 1, 1=canal 2, FF=ALL).
     *
     * @param area    Área DyNet (1..255)
     * @param channel Canal lógico (1..255, 0 = ALL)
     * @param level   % de 0..100
     * @param fadeMs  tiempo de fade aproximado (100 ms resolución)
     */
    fun setLevel(area: Int, channel: Int, level: Int, fadeMs: Int = 1000) {
        val chanIndex = if (channel <= 0) 0xFF else (channel - 1) and 0xFF

        val levelPct = level.coerceIn(0, 100)
        val levelDyn = when {
            levelPct >= 100 -> 0x01
            levelPct <= 0   -> 0xFF
            else -> {
                val inv = 100 - levelPct
                // Escala lineal sobre 0x02..0xFE
                (0x02 + (inv * 0xFC) / 100).coerceIn(0x02, 0xFE)
            }
        }

        // Usamos OpCode 0x71 (100 ms resolución, hasta 25.5 s)
        val opcode = 0x71
        val fadeSteps = (fadeMs / 100).coerceIn(1, 255)
        val fadeByte = fadeSteps and 0xFF

        val join = 0xFF // Join por defecto

        val pkt = buildPacket(
            area = area,
            data2 = chanIndex,
            opcode = opcode,
            data4 = levelDyn,
            data5 = fadeByte,
            join = join
        )
        sendRaw(pkt)
    }

    fun turnOn(area: Int, channel: Int, level: Int = 100) =
        setLevel(area, channel, level, 500)

    fun turnOff(area: Int, channel: Int) =
        setLevel(area, channel, 0, 200)

    /**
     * 2.3 Channel Level Request:
     * [1C][Area][ChannelIdx][0x61][00][00][Join][CS]
     */
    fun requestLevel(area: Int, channel: Int) {
        val chanIndex = (channel - 1).coerceAtLeast(0) and 0xFF
        val join = 0xFF
        val pkt = buildPacket(
            area = area,
            data2 = chanIndex,
            opcode = 0x61,
            data4 = 0x00,
            data5 = 0x00,
            join = join
        )
        sendRaw(pkt)
    }

    // ------------------------------------------------------------------
    // RAW PACKET SEND (DiagActivity y otros)
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
     * Construye un paquete DyNet lógico (0x1C) con checksum correcto.
     *
     * @param area  Byte1
     * @param data2 Byte2 (canal, preset, etc.)
     * @param opcode Byte3
     * @param data4 Byte4
     * @param data5 Byte5
     * @param join  Byte6
     */
    fun buildPacket(
        area: Int,
        data2: Int,
        opcode: Int,
        data4: Int,
        data5: Int,
        join: Int
    ): ByteArray {
        val pkt = ByteArray(8)
        pkt[0] = SYNC.toByte()
        pkt[1] = (area and 0xFF).toByte()
        pkt[2] = (data2 and 0xFF).toByte()
        pkt[3] = (opcode and 0xFF).toByte()
        pkt[4] = (data4 and 0xFF).toByte()
        pkt[5] = (data5 and 0xFF).toByte()
        pkt[6] = (join and 0xFF).toByte()
        pkt[7] = checksum(pkt)
        return pkt
    }

    // ------------------------------------------------------------------
    // HELPERS INTERNOS
    // ------------------------------------------------------------------
    private fun checksum(pkt: ByteArray): Byte {
        // Suma de bytes 0..6
        var sum = 0
        for (i in 0..6) {
            sum = (sum + pkt[i].toInt().and(0xFF)) and 0xFF
        }
        // Negativo 8 bits (2's complement)
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
