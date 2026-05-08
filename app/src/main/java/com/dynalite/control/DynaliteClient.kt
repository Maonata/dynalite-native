package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DynaliteClient — Protocolo DTP sobre TCP para PDEG
 *
 * Paquete DTP = 8 bytes:
 * [0x1C][AREA][CANAL][OPCODE][D1][D2][D3][CHECKSUM]
 *
 * Opcodes relevantes:
 *   0x71 = Set level (TX y RX)
 *   0x79 = Recall preset
 *   0x62 = Request level
 *   0x61 = Report level (respuesta del PDEG)
 */
class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    // Evento de cambio de nivel recibido del PDEG (desde otro control)
    data class LevelEvent(
        val area    : Int,
        val channel : Int,
        val level   : Int,   // 0-100%
        val opcode  : Int,
        val raw     : String // hex del paquete completo
    )

    var onStateChange : ((State, String) -> Unit)? = null
    var onLevelEvent  : ((LevelEvent) -> Unit)?    = null  // retroalimentación
    var onRawRx       : ((String) -> Unit)?         = null  // log hex crudo

    private val SYNC = 0x1C
    private var socket: Socket? = null
    private var state  = State.DISCONNECTED
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastIp   = ""
    private var lastPort = 50000

    // ---------------------------------------------------------------
    // CONEXION
    // ---------------------------------------------------------------
    fun connect(ip: String, port: Int) {
        lastIp   = ip
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
                readLoop(s)
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

    // ---------------------------------------------------------------
    // LOOP DE LECTURA — parsea paquetes DTP entrantes
    // ---------------------------------------------------------------
    private fun readLoop(s: Socket) {
        val input  = s.getInputStream()
        val buffer = ByteArray(1024)
        val packet = ByteArray(8)
        var pktPos = 0

        try {
            while (state == State.CONNECTED) {
                val n = input.read(buffer)
                if (n < 0) break

                // Procesar cada byte recibido
                for (i in 0 until n) {
                    val b = buffer[i].toInt().and(0xFF)

                    // Sincronizar con byte SYNC (0x1C)
                    if (pktPos == 0 && b != SYNC) {
                        Log.w("Dynalite", "Byte fuera de sync: ${b.toString(16)}")
                        continue
                    }

                    packet[pktPos++] = b.toByte()

                    // Paquete completo = 8 bytes
                    if (pktPos == 8) {
                        processPacket(packet.copyOf())
                        pktPos = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Dynalite", "Error lectura: ${e.message}")
        } finally {
            if (state == State.CONNECTED) {
                setState(State.DISCONNECTED, "Conexión perdida. Reintentando...")
                scope.launch {
                    delay(8000)
                    if (state == State.DISCONNECTED) connect(lastIp, lastPort)
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // PARSEO DE PAQUETE DTP ENTRANTE
    // ---------------------------------------------------------------
    private fun processPacket(pkt: ByteArray) {
        // Verificar SYNC
        if (pkt[0].toInt().and(0xFF) != SYNC) return

        val area    = pkt[1].toInt().and(0xFF)
        val channel = pkt[2].toInt().and(0xFF)
        val opcode  = pkt[3].toInt().and(0xFF)
        val d1      = pkt[4].toInt().and(0xFF)
        val hex     = pkt.joinToString(" ") {
            it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
        }

        Log.d("Dynalite", "RX: $hex")
        onRawRx?.invoke("RX: $hex")

        // Verificar checksum
        if (!verifyChecksum(pkt)) {
            Log.w("Dynalite", "Checksum inválido: $hex")
            return
        }

        when (opcode) {
            0x71 -> {
                // Set level — enviado por controles físicos u otros dispositivos
                // D1 = nivel 0-255
                val levelPct = (d1 * 100) / 255
                Log.d("Dynalite", "Level RX: Area=$area Ch=$channel Level=$levelPct%")
                onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            }
            0x61 -> {
                // Report level — respuesta a request level
                val levelPct = (d1 * 100) / 255
                Log.d("Dynalite", "Report RX: Area=$area Ch=$channel Level=$levelPct%")
                onLevelEvent?.invoke(LevelEvent(area, channel, levelPct, opcode, hex))
            }
            0x79 -> {
                // Preset recall
                Log.d("Dynalite", "Preset RX: Area=$area Preset=$d1")
            }
            else -> {
                Log.d("Dynalite", "Opcode desconocido: 0x${opcode.toString(16)} | $hex")
            }
        }
    }

    // ---------------------------------------------------------------
    // COMANDOS TX
    // ---------------------------------------------------------------
    fun setLevel(area: Int, channel: Int, level: Int, fadeMs: Int = 1000) {
        val value = (level.coerceIn(0, 100) * 255 / 100)
        val fade  = (fadeMs / 20).coerceIn(0, 0xFFFF)
        send(area, channel, 0x71, value, (fade shr 8) and 0xFF, fade and 0xFF)
    }

    fun turnOn(area: Int, channel: Int, level: Int = 100) =
        setLevel(area, channel, level, 500)

    fun turnOff(area: Int, channel: Int) =
        setLevel(area, channel, 0, 200)

    fun recallPreset(area: Int, preset: Int) =
        send(area, 0, 0x79, preset and 0xFF, 0, 0xFF)

    /** Solicita el nivel actual de todos los canales de un área */
    fun requestLevels(area: Int) {
        for (ch in 1..8) {
            send(area, ch, 0x62, 0, 0, 0xFF)
        }
    }

    // ---------------------------------------------------------------
    // CONSTRUCCION Y ENVIO DE PAQUETE
    // ---------------------------------------------------------------
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

        val hex = pkt.joinToString(" ") {
            it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
        }
        Log.d("Dynalite", "TX: $hex")
        onRawRx?.invoke("TX: $hex")

        if (state != State.CONNECTED) {
            Log.w("Dynalite", "Sin conexión — descartado")
            return
        }

        scope.launch {
            try {
                socket?.getOutputStream()?.apply { write(pkt); flush() }
            } catch (e: Exception) {
                setState(State.DISCONNECTED, "Error TX: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------
    // CHECKSUM: XOR bytes 1-6, OR 0x80
    // ---------------------------------------------------------------
    private fun checksum(pkt: ByteArray): Byte {
        var xor = 0
        for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
        return ((xor or 0x80) and 0xFF).toByte()
    }

    private fun verifyChecksum(pkt: ByteArray): Boolean {
        return pkt[7] == checksum(pkt)
    }

    // ---------------------------------------------------------------
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
    fun getState()    = state

    fun destroy() {
        state = State.DISCONNECTED
        closeSocket()
        scope.cancel()
    }
}
