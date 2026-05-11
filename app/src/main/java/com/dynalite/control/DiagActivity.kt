package com.dynalite.control

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * DiagActivity — TCP Diagnostic Tool for Dynalite PDEG
 *
 * Shows exactly what bytes are sent and received.
 * Use this to verify the DTP packets reach the gateway.
 */
class DiagActivity : AppCompatActivity() {

    private val client  = DynaliteClient()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    private lateinit var tvLog   : TextView
    private lateinit var btnConn : Button
    private lateinit var etIp    : EditText
    private lateinit var etPort  : EditText
    private lateinit var etArea  : EditText
    private lateinit var etCh    : EditText

    private val C_BG     = Color.parseColor("#0d0d14")
    private val C_TEXT   = Color.parseColor("#f0eee8")
    private val C_GREEN  = Color.parseColor("#5ecf8a")
    private val C_RED    = Color.parseColor("#ff6b6b")
    private val C_YELLOW = Color.parseColor("#ffc850")
    private val C_BLUE   = Color.parseColor("#64b5f6")
    private val C_MUTED  = Color.parseColor("#666677")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        root.addView(tv("⚙ TCP Diagnostic Tool", 16f, C_YELLOW, bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        })

        // IP and Port
        val row1 = hll()
        etIp = EditText(this).apply {
            hint = "PDEG IP"; setText("172.18.0.101")
            textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            layoutParams = LP(0, WRAP).apply { weight = 3f; rightMargin = dp(8) }
        }
        etPort = EditText(this).apply {
            hint = "Port"; setText("50000")
            textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LP(0, WRAP).apply { weight = 1f }
        }
        row1.addView(etIp); row1.addView(etPort)
        root.addView(row1, LP(MATCH, WRAP))

        btnConn = Button(this).apply {
            text = "Connect"; textSize = 13f; setTextColor(C_TEXT)
            setBackgroundColor(Color.parseColor("#222233"))
            layoutParams = LP(MATCH, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(12) }
            setOnClickListener { toggleConnect() }
        }
        root.addView(btnConn)

        // Area and Channel selectors
        root.addView(tv("— Test Commands —", 11f, C_MUTED).apply { setPadding(0, 0, 0, dp(6)) })
        val row2 = hll()
        etArea = EditText(this).apply {
            hint = "Area"; setText("1")
            textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LP(0, WRAP).apply { weight = 1f; rightMargin = dp(8) }
        }
        etCh = EditText(this).apply {
            hint = "Channel"; setText("1")
            textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LP(0, WRAP).apply { weight = 1f }
        }
        row2.addView(tv("Area:", 12f, C_MUTED).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0,0,dp(4),0) })
        row2.addView(etArea)
        row2.addView(tv("Ch:", 12f, C_MUTED).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(4),0) })
        row2.addView(etCh)
        root.addView(row2, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // Level buttons
        val row3 = hll()
        listOf(Triple("100%", 100, C_GREEN), Triple("75%", 75, C_YELLOW),
               Triple("50%", 50, C_YELLOW), Triple("25%", 25, C_YELLOW), Triple("OFF", 0, C_RED))
            .forEach { (label, level, color) ->
                row3.addView(Button(this).apply {
                    text = label; textSize = 11f; setTextColor(color)
                    setBackgroundColor(Color.parseColor("#1a1a28"))
                    layoutParams = LP(0, dp(42)).apply { weight = 1f; rightMargin = dp(4) }
                    setOnClickListener { sendLevel(level) }
                })
            }
        root.addView(row3, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // Request level button
        root.addView(Button(this).apply {
            text = "Request Current Level (0x62)"; textSize = 11f; setTextColor(C_BLUE)
            setBackgroundColor(Color.parseColor("#08101a"))
            layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(12) }
            setOnClickListener { sendRequestLevel() }
        })

        // Manual packet
        root.addView(tv("— Manual Packet (7 bytes, checksum auto) —", 11f, C_MUTED).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val etManual = EditText(this).apply {
            hint = "e.g.: 1C 01 01 71 FF 00 00"
            setText("1C 01 01 71 FF 00 00")
            textSize = 12f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            typeface = Typeface.MONOSPACE
        }
        root.addView(etManual, LP(MATCH, WRAP))
        root.addView(Button(this).apply {
            text = "Send Manual Packet"; textSize = 11f; setTextColor(C_BLUE)
            setBackgroundColor(Color.parseColor("#080818"))
            layoutParams = LP(MATCH, dp(40)).apply { topMargin = dp(4); bottomMargin = dp(12) }
            setOnClickListener { sendManual(etManual.text.toString()) }
        })

        // Log
        root.addView(tv("— Traffic Log —", 11f, C_MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        tvLog = TextView(this).apply {
            text = "Not connected..."
            textSize = 10f; setTextColor(C_MUTED); typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#080810"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(tvLog, LP(MATCH, WRAP))

        root.addView(Button(this).apply {
            text = "Clear Log"; textSize = 11f; setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#1a1a28"))
            layoutParams = LP(MATCH, dp(36)).apply { topMargin = dp(8) }
            setOnClickListener { logLines.clear(); tvLog.text = "" }
        })

        scroll.addView(root)
        setContentView(scroll)
        setupClient()
        addLog("APP", "Diagnostic tool ready. Connect to PDEG to start.")
    }

    private fun getArea()    = etArea.text.toString().toIntOrNull() ?: 1
    private fun getChannel() = etCh.text.toString().toIntOrNull() ?: 1

    private fun sendLevel(levelPct: Int) {
        val area = getArea(); val ch = getChannel()
        addLog("APP", "Sending level $levelPct% → Area=$area Ch=$ch")
        client.setLevel(area, ch, levelPct, 500)
    }

    private fun sendRequestLevel() {
        val area = getArea(); val ch = getChannel()
        addLog("APP", "Requesting level → Area=$area Ch=$ch")
        val pkt = client.buildPacket(area, ch, 0x62, 0, 0, 0xFF)
        client.sendRaw(pkt)
    }

    private fun sendManual(hexStr: String) {
        try {
            val parts = hexStr.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() && it != "??" }
            if (parts.size < 7) { addLog("ERR", "Need 7 bytes (checksum calculated automatically)"); return }
            val pkt = ByteArray(8)
            for (i in 0 until 7) pkt[i] = parts[i].toInt(16).toByte()
            var xor = 0
            for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
            pkt[7] = ((xor or 0x80) and 0xFF).toByte()
            addLog("MAN", "Sending: ${pkt.toHex()}")
            client.sendRaw(pkt)
        } catch (e: Exception) {
            addLog("ERR", "Invalid format: ${e.message}")
        }
    }

    private fun toggleConnect() {
        if (client.isConnected()) {
            client.disconnect()
            btnConn.text = "Connect"
            btnConn.setTextColor(C_TEXT)
            btnConn.setBackgroundColor(Color.parseColor("#222233"))
            addLog("CON", "Disconnected")
        } else {
            val ip   = etIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 50000
            client.connect(ip, port)
        }
    }

    private fun setupClient() {
        client.onStateChange = { state, msg ->
            uiScope.launch {
                addLog(if (state == DynaliteClient.State.CONNECTED) "CON" else "---", msg)
                if (state == DynaliteClient.State.CONNECTED) {
                    btnConn.text = "Disconnect"
                    btnConn.setTextColor(C_GREEN)
                    btnConn.setBackgroundColor(Color.parseColor("#0a200f"))
                } else if (state == DynaliteClient.State.DISCONNECTED) {
                    btnConn.text = "Connect"
                    btnConn.setTextColor(C_TEXT)
                    btnConn.setBackgroundColor(Color.parseColor("#222233"))
                }
            }
        }
        client.onLevelEvent = { event ->
            uiScope.launch {
                addLog("RX ", "A${event.area} Ch${event.channel} → ${event.levelPct}% (opcode=0x${event.opcode.toString(16).uppercase()})")
            }
        }
        client.onLog = { msg ->
            uiScope.launch { addLog("PKT", msg) }
        }
    }

    private fun addLog(tag: String, msg: String) {
        val time = timeFmt.format(Date())
        logLines.add(0, "[$time] $tag $msg")
        if (logLines.size > 200) logLines.removeAt(logLines.size - 1)
        tvLog.text = logLines.joinToString("\n")
    }

    private fun ByteArray.toHex() = joinToString(" ") {
        it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
    }

    override fun onDestroy() { client.destroy(); uiScope.cancel(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private fun LP(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun hll() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
}
