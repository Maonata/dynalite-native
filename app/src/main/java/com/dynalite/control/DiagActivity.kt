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
 * DiagActivity — TCP Diagnostic Tool
 * Shows exact bytes sent/received to verify DyNet1 protocol.
 *
 * DyNet1 packet: [1C][AREA][DATA1][OPCODE][D2][D3][JOIN][CHECKSUM]
 * JOIN byte: 0xFF = all channels, or (channel - 1) for specific channel
 */
class DiagActivity : AppCompatActivity() {

    private val client   = DynaliteClient()
    private val uiScope  = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt  = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    private lateinit var tvLog  : TextView
    private lateinit var btnConn: Button
    private lateinit var etIp   : EditText
    private lateinit var etPort : EditText
    private lateinit var etArea : EditText
    private lateinit var etCh   : EditText

    private val C_BG     = Color.parseColor("#0d0d14")
    private val C_TEXT   = Color.parseColor("#f0eee8")
    private val C_GREEN  = Color.parseColor("#5ecf8a")
    private val C_RED    = Color.parseColor("#ff6b6b")
    private val C_YELLOW = Color.parseColor("#ffc850")
    private val C_BLUE   = Color.parseColor("#64b5f6")
    private val C_MUTED  = Color.parseColor("#555566")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // Title
        root.addView(tv("⚙ DyNet1 Diagnostic", 16f, C_YELLOW, bold = true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(tv("Format: [1C][AREA][DATA1=LEVEL][0x71][FADE_HI][FADE_LO][JOIN=CH-1][CS]",
            10f, C_MUTED).apply { setPadding(0, 0, 0, dp(10)) })

        // Connection row
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

        // Area / Channel
        root.addView(tv("— Area & Channel —", 11f, C_MUTED).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val row2 = hll()
        val lArea = tv("Area:", 12f, C_MUTED).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(4), 0)
        }
        etArea = EditText(this).apply {
            hint = "1"; setText("1")
            textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LP(0, WRAP).apply { weight = 1f; rightMargin = dp(8) }
        }
        val lCh = tv("Ch:", 12f, C_MUTED).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(4), 0)
        }
        etCh = EditText(this).apply {
            hint = "1"; setText("1")
            textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LP(0, WRAP).apply { weight = 1f }
        }
        row2.addView(lArea); row2.addView(etArea); row2.addView(lCh); row2.addView(etCh)
        root.addView(row2, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // Level buttons
        root.addView(tv("— Send Level Commands —", 11f, C_MUTED).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val levelRow = hll()
        listOf(Triple("100%", 100, C_GREEN), Triple("75%", 75, C_YELLOW),
               Triple("50%", 50, C_YELLOW), Triple("25%", 25, C_YELLOW),
               Triple("OFF", 0, C_RED)).forEach { (label, pct, color) ->
            levelRow.addView(Button(this).apply {
                text = label; textSize = 11f; setTextColor(color)
                setBackgroundColor(Color.parseColor("#1a1a28"))
                layoutParams = LP(0, dp(42)).apply { weight = 1f; rightMargin = dp(3) }
                setOnClickListener { sendLevel(pct) }
            })
        }
        root.addView(levelRow, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // All channels button
        root.addView(Button(this).apply {
            text = "Send to ALL channels in area (JOIN=0xFF)"; textSize = 11f
            setTextColor(C_BLUE); setBackgroundColor(Color.parseColor("#08101a"))
            layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(4) }
            setOnClickListener { sendLevelAllChannels(100) }
        })

        // Request level
        root.addView(Button(this).apply {
            text = "Request Current Level (opcode 0x61)"; textSize = 11f
            setTextColor(C_BLUE); setBackgroundColor(Color.parseColor("#08101a"))
            layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(12) }
            setOnClickListener { sendRequestLevel() }
        })

        // Manual packet
        root.addView(tv("— Manual Raw Packet (7 bytes hex, checksum auto) —", 11f, C_MUTED).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val etManual = EditText(this).apply {
            hint = "1C 01 FF 71 00 00 FF  (area=1, level=100%, all channels)"
            setText("1C 01 FF 71 00 00 FF")
            textSize = 11f; setTextColor(C_TEXT); setHintTextColor(C_MUTED)
            typeface = Typeface.MONOSPACE
        }
        root.addView(etManual, LP(MATCH, WRAP))
        root.addView(Button(this).apply {
            text = "Send Manual Packet"; textSize = 11f; setTextColor(C_BLUE)
            setBackgroundColor(Color.parseColor("#080818"))
            layoutParams = LP(MATCH, dp(40)).apply { topMargin = dp(4); bottomMargin = dp(12) }
            setOnClickListener { sendManual(etManual.text.toString()) }
        })

        // Packet preview
        root.addView(tv("— Packet Preview —", 11f, C_MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        root.addView(Button(this).apply {
            text = "Show what packet will be sent"; textSize = 11f; setTextColor(C_GREEN)
            setBackgroundColor(Color.parseColor("#081008"))
            layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(12) }
            setOnClickListener { previewPacket() }
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
            text = "Clear"; textSize = 11f; setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#1a1a28"))
            layoutParams = LP(MATCH, dp(36)).apply { topMargin = dp(8) }
            setOnClickListener { logLines.clear(); tvLog.text = "" }
        })

        scroll.addView(root)
        setContentView(scroll)
        setupClient()
        addLog("INFO", "DyNet1 format: [1C][AREA][LEVEL][0x71][FADE_HI][FADE_LO][JOIN][CS]")
        addLog("INFO", "JOIN=0xFF all channels, JOIN=ch-1 for specific channel")
    }

    private fun getArea()    = etArea.text.toString().toIntOrNull() ?: 1
    private fun getChannel() = etCh.text.toString().toIntOrNull() ?: 1

    private fun previewPacket() {
        val area = getArea(); val ch = getChannel()
        val level = 80
        val pkt = client.buildPacket(area, level * 255 / 100, 0x71, 0, 25, ch - 1)
        addLog("PRV", "Area=$area Ch=$ch Level=80%: ${pkt.toHex()}")
        addLog("PRV", "[1C][${area.hex}][${(level*255/100).hex}][71][00][19][${(ch-1).hex}][CS]")
    }

    private fun sendLevel(levelPct: Int) {
        val area = getArea(); val ch = getChannel()
        addLog("TX ", "Level $levelPct% → Area=$area Ch=$ch (JOIN=${(ch-1).hex})")
        client.setLevel(area, ch, levelPct, 500)
    }

    private fun sendLevelAllChannels(levelPct: Int) {
        val area = getArea()
        addLog("TX ", "Level $levelPct% → Area=$area ALL channels (JOIN=FF)")
        client.setLevel(area, 0, levelPct, 500)  // channel=0 means all (JOIN=0xFF)
    }

    private fun sendRequestLevel() {
        val area = getArea(); val ch = getChannel()
        val pkt = client.buildPacket(area, 0, 0x61, 0, 0, ch - 1)
        addLog("TX ", "Request level → Area=$area Ch=$ch: ${pkt.toHex()}")
        client.sendRaw(pkt)
    }

    private fun sendManual(hexStr: String) {
        try {
            val parts = hexStr.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 7) { addLog("ERR", "Need 7 bytes (checksum auto-calculated)"); return }
            val pkt = ByteArray(8)
            for (i in 0 until 7) pkt[i] = parts[i].toInt(16).toByte()
            var xor = 0
            for (i in 1..6) xor = xor xor pkt[i].toInt().and(0xFF)
            pkt[7] = ((xor or 0x80) and 0xFF).toByte()
            addLog("MAN", "Sending: ${pkt.toHex()}")
            client.sendRaw(pkt)
        } catch (e: Exception) {
            addLog("ERR", "Invalid hex format: ${e.message}")
        }
    }

    private fun toggleConnect() {
        if (client.isConnected()) {
            client.disconnect()
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
                    btnConn.text = "Disconnect"; btnConn.setTextColor(C_GREEN)
                    btnConn.setBackgroundColor(Color.parseColor("#0a200f"))
                } else {
                    btnConn.text = "Connect"; btnConn.setTextColor(C_TEXT)
                    btnConn.setBackgroundColor(Color.parseColor("#222233"))
                }
            }
        }
        client.onLevelEvent = { event ->
            uiScope.launch {
                addLog("RX ", "A${event.area} Ch${event.channel} → ${event.levelPct}% [${event.rawHex}]")
            }
        }
        client.onLog = { msg ->
            uiScope.launch { addLog("LOG", msg) }
        }
    }

    private fun addLog(tag: String, msg: String) {
        val time = timeFmt.format(Date())
        logLines.add(0, "[$time] $tag $msg")
        if (logLines.size > 300) logLines.removeAt(logLines.size - 1)
        tvLog.text = logLines.joinToString("\n")
    }

    private fun ByteArray.toHex() = joinToString(" ") {
        it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
    }
    private val Int.hex get() = toString(16).uppercase().padStart(2, '0')

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
