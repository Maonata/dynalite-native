package com.dynalite.control

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DynaliteClient — DyNet RS485 logical messages (0x1C) sobre TCP (PDEG)
 *
 * Paquetes de 8 bytes:
 * [0] SYNC (0x1C lógico)
 * [1] Area
 * [2] Data2 (canal, preset, etc. según mensaje)
 * [3] OpCode
 * [4] Data4
 * [5] Data5
 * [6] Join
 * [7] Checksum
 *
 * Checksum (Integrator's Handbook, pág. 6):
 *   "The DyNet Checksum is equal to the Negative 8 bit 2's complement sum of bytes 0‑6."
 *
 * Implementa:
 *  - 2.1 Linear Channel/Area Control (0x71/0x72/0x73)
 *  - 2.3 Channel Level Request / Reply (0x61 / 0x60)
 */
class DynaliteClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class LevelEvent(
        val area: Int,
        val channel: Int,  // 1..255, 0 = ALL channels (CHAN_IDX=FF)
        val levelPct: Int, // 0..100
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
            // 2.1 Linear Channel/Area Control (0x71/0x72/0x73)
            // [1C][Area][ChannelIdx][OpCode][ChannelLevel][Fade][Join][CS]
            0x71, 0x72, 0x73 -> {
                val levelDyn = b4         // 0x01=100%, 0xFF=0%
                val levelPct = dynLevelToPercent(levelDyn)
                onLevelEvent?.invoke(
                    LevelEvent(area, channel, levelPct, opcode, hex)
                )
            }

            // 2.3 Channel Level Reply (0x60)
            // [1C][Area][ChannelIdx][0x60][TargetLevel][CurrentLevel][Join][CS]
            0x60 -> {
                val target = b4
                val current = b5
                val levelDyn = if (current != 0x00) current else target
                val levelPct = dynLevelToPercent(levelDyn)
                onLevelEvent?.invoke(
                    LevelEvent(area, channel, levelPct, opcode, hex)
                )
            }

            else -> 
