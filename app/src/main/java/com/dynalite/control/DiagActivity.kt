package com.dynalite.control

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

/**
 * DiagActivity — Herramienta de diagnóstico TCP para Dynalite PDEG
 * Muestra exactamente qué bytes se envían y reciben.
 * Accesible desde el botón "Diagnóstico" en MainActivity.
 */
class DiagActivity : AppCompatActivity() {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText

    private val C_BG     = Color.parseColor("#0d0d14")
    private val C_TEXT   = Color.parseColor("#f0eee8")
    private val C_GREEN  = Color.parseColor("#5ecf8a")
    private val C_RED    = Color.parseColor("#ff6b6b")
    private val C_BLUE   = Color.parseColor("#64b5f6")
    private val C_YELLOW = Color.parseColor("#ffc850")
    private val C_MUTED  = Color.parseColor("#666677")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // Título
        root.addView(tv("🔧 Diagnóstico TCP Dynalite", 16f, C_YELLOW, bold = true).apply {
            setPadding(0, 0, 0, dp(10))
        })

        // IP y Puerto
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etIp = EditText(this).apply {
            hint = "IP PDEG"; setText("172.18.0.101")
            setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 3f; rightMargin = dp(8) }
        }
        etPort = EditText(this).apply {
            hint = "Puerto"; setText("50000")
            setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        row1.addView(etIp); row1.addView(etPort)
        root.addView(row1)

        // Botón conectar
        btnConnect = Button(this).apply {
            text = "Conectar"
            setTextColor(C_TEXT)
            setBackgroundColor(Color.parseColor("#222233"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(8) }
            setOnClickListener { toggleConnect() }
        }
        root.addView(btnConnect)

        // Botones de prueba DTP
        root.addView(tv("— Pruebas DTP (Area 1, Canal 1) —", 11f, C_MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("100%", 255, C_GREEN),
            Triple("50%",  128, C_YELLOW),
            Triple("OFF",  0,   C_RED)
        ).forEach { (label, level, color) ->
            row2.addView(Button(this).apply {
                text = label; textSize = 12f
                setTextColor(color)
                setBackgroundColor(Color.parseColor("#1a1a28"))
                layoutParams = LinearLayout.LayoutParams(0, dp(44)).apply { weight = 1f; rightMargin = dp(4) }
                setOnClickListener { sendTestLevel(1, 1, level) }
            })
        }
        root.addView(row2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })

        // Botón paquete manual
        val etManual = EditText(this).apply {
            hint = "Bytes hex ej: 1C 01 01 71 FF 00 00 ??"; setText("1C 01 01 71 FF 00 00")
            setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            textSize = 12f; typeface = Typeface.MONOSPACE
        }
        root.addView(etManual)
        root.addView(Button(this).apply {
            text = "Enviar paquete manual (calcula checksum auto)"
            textSize = 11f; setTextColor(C_BLUE)
            setBackgroundColor(Color.parseColor("#080818"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4); bottomMargin = dp(8) }
            setOnClickListener { sendManual(etManual.text.toString()) }
        })

        // Log
        root.addView(tv("— Log de tráfico —", 11f, C_MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        tvLog = TextView(this).apply {
            text = "Sin conexión..."
            textSize = 11f; typeface = Typeface.MONOSPACE
            setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#080810"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }
        logScroll.addView(tvLog)
        root.addView(logScroll)

        // Botón limpiar
        root.addView(Button(this).apply {
            text = "Limpiar log"
            textSize = 11f; setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#1a1a28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(4) }
            setOnClickListener { logLines.clear(); updateLog() }
        })

        setContentView(root)
        addLog("APP", "Herramienta de diagnóstico lista", C_MUTED)
        addLog("APP", "IP default: 172.18.0.101:50000", C_MUTED)
    }

    private fun toggleConnect() {
        if (socket?.isConnected == true) {
            disconnect()
        } else {
            val ip   = etIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 50000
            connect(ip, port)
        }
    }

    private fun connect(ip: String, port: Int) {
        addLog("CON", "Conectando a $ip:$port...", C_YELLOW)
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 6000)
                s.soTimeout = 0
                socket       = s
                outputStream = s.getOutputStream()
                inputStream  = s.getInputStream()

                uiScope.launch {
                    addLog("CON", "✅ Conectado a $ip:$port", C_GREEN)
                    btnConnect.text = "Desconectar"
                    btnConnect.setBackgroundColor(Color.parseColor("#0a200f"))
                    btnConnect.setTextColor(C_GREEN)
                }

                // Loop de lectura
                val buf = ByteArray(256)
                while (socket?.isConnected == true) {
                    val n = inputStream?.read(buf) ?: break
                    if (n < 0) break
                    val hex = (0 until n).joinToString(" ") {
                        buf[it].toInt().and(0xFF).toString(16).uppercase().padStart(2,'0')
                    }
                    val parsed = parseDTP(buf, n)
                    uiScope.launch {
                        addLog("RX ", "[$n bytes] $hex", C_BLUE)
                        if (parsed.isNotEmpty()) addLog("   ", "→ $parsed", C_GREEN)
                    }
                }
            } catch (e: Exception) {
                uiScope.launch {
                    addLog("ERR", "❌ ${e.message}", C_RED)
                    btnConnect.text = "Conectar"
                    btnConnect.setBackgroundColor(Color.parseColor("#222233"))
                    btnConnect.setTextColor(C_TEXT)
                }
            }
        }
    }

    private fun disconnect() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null; outputStream = null; inputStream = null
        btnConnect.text = "Conectar"
        btnConnect.setBackgroundColor(Color.parseColor("#222233"))
        btnConnect.setTextColor(C_TEXT)
        addLog("CON", "Desconectado", C_MUTED)
    }

    private fun sendTestLevel(area: Int, channel: Int, level: Int) {
        val pkt = buildDTP(area, channel, 0x71, level, 0, 0)
        sendBytes(pkt)
    }

    private fun sendManual(hexStr: String) {
        try {
            val parts = hexStr.trim().split("\\s+".toRegex())
                .filter { it.isNotEmpty() && it != "??" }
            if (parts.size < 7) {
                addLog("ERR", "Necesitas al menos 7 bytes (checksum se calcula solo)", C_RED)
                return
            }
            val pkt = ByteArray(8)
            for (i in 0 until minOf(7, parts.size)) {
                pkt[i] = parts[i].toInt(16).toByte()
            }
            // Calcular checksum
            var xor = 0
            for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
            pkt[7] = ((xor or 0x80) and 0xFF).toByte()
            sendBytes(pkt)
        } catch (e: Exception) {
            addLog("ERR", "Formato inválido: ${e.message}", C_RED)
        }
    }

    private fun buildDTP(area: Int, ch: Int, op: Int, d1: Int, d2: Int, d3: Int): ByteArray {
        val pkt = ByteArray(8)
        pkt[0] = 0x1C.toByte()
        pkt[1] = (area and 0xFF).toByte()
        pkt[2] = (ch   and 0xFF).toByte()
        pkt[3] = (op   and 0xFF).toByte()
        pkt[4] = (d1   and 0xFF).toByte()
        pkt[5] = (d2   and 0xFF).toByte()
        pkt[6] = (d3   and 0xFF).toByte()
        var xor = 0
        for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
        pkt[7] = ((xor or 0x80) and 0xFF).toByte()
        return pkt
    }

    private fun sendBytes(pkt: ByteArray) {
        val hex = pkt.joinToString(" ") {
            it.toInt().and(0xFF).toString(16).uppercase().padStart(2,'0')
        }
        val parsed = parseDTP(pkt, pkt.size)
        addLog("TX ", hex, C_YELLOW)
        if (parsed.isNotEmpty()) addLog("   ", "→ $parsed", C_GREEN)

        if (outputStream == null) {
            addLog("ERR", "No conectado — paquete descartado", C_RED)
            return
        }
        scope.launch {
            try {
                outputStream?.write(pkt)
                outputStream?.flush()
                uiScope.launch { addLog("OK ", "Enviado correctamente", C_GREEN) }
            } catch (e: Exception) {
                uiScope.launch { addLog("ERR", "Fallo TX: ${e.message}", C_RED) }
            }
        }
    }

    private fun parseDTP(buf: ByteArray, n: Int): String {
        if (n < 8) return ""
        if (buf[0].toInt().and(0xFF) != 0x1C) return "No es DTP (SYNC inválido)"
        val area    = buf[1].toInt().and(0xFF)
        val ch      = buf[2].toInt().and(0xFF)
        val opcode  = buf[3].toInt().and(0xFF)
        val d1      = buf[4].toInt().and(0xFF)
        val opcName = when (opcode) {
            0x71 -> "SET_LEVEL"
            0x61 -> "REPORT_LEVEL"
            0x79 -> "RECALL_PRESET"
            0x62 -> "REQUEST_LEVEL"
            0x63 -> "STOP_FADE"
            else -> "OPCODE_0x${opcode.toString(16).uppercase()}"
        }
        val levelPct = if (opcode == 0x71 || opcode == 0x61) " = ${(d1*100)/255}%" else ""
        return "Area=$area Ch=$ch $opcName D1=$d1$levelPct"
    }

    private fun addLog(tag: String, msg: String, color: Int) {
        val time = timeFmt.format(Date())
        logLines.add(0, "[$time] $tag $msg")
        if (logLines.size > 200) logLines.removeAt(logLines.size - 1)
        updateLog()
        tvLog.setTextColor(color)
    }

    private fun updateLog() {
        tvLog.text = logLines.joinToString("\n")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        uiScope.cancel()
        super.onDestroy()
    }
}
