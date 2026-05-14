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
 * Muestra bytes TX/RX para verificar protocolo DyNet (logical 0x1C).
 *
 * 2.1 Linear Channel/Area Control:
 * [1C][AREA][CHAN_IDX][OPCODE][LEVEL][FADE][JOIN][CS]
 *   CHAN_IDX: 0-origin (0=ch1, 1=ch2, FF=ALL)
 *   LEVEL   : 0x01=100%, 0xFF=0%
 */
class DiagActivity : AppCompatActivity() {

    private val client = DynaliteClient()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    private lateinit var tvLog: TextView
    private lateinit var btnConn: Button
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etArea: EditText
    private lateinit var etCh: EditText

    private val C_BG = Color.parseColor("#0d0d14")
    private val C_TEXT = Color.parseColor("#f0eee8")
    private val C_GREEN = Color.parseColor("#5ecf8a")
    private val C_RED = Color.parseColor("#ff6b6b")
    private val C_YELLOW = Color.parseColor("#ffc850")
    private val C_BLUE = Color.parseColor("#64b5f6")
    private val C_MUTED = Color.parseColor("#555566")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(14), dp(14), dp(14), dp(14))

            // Título
            addView(tv("⚙ DyNet Diagnostic (Linear Channel)", 16f, C_YELLOW, bold = true).apply {
                setPadding(0, 0, 0, dp(4))
            })
            addView(
                tv(
                    "Format: [1C][AREA][CHAN_IDX][OPCODE][LEVEL][FADE][JOIN][CS]",
                    10f,
                    C_MUTED
                ).apply { setPadding(0, 0, 0, dp(10)) }
            )

            // Fila conexión
            val row1 = hll()
            etIp = EditText(this@DiagActivity).apply {
                hint = "PDEG IP"
                setText("172.18.0.101")
                textSize = 13f
                setTextColor(C_TEXT)
                setHintTextColor(C_MUTED)
                layoutParams = LP(0, WRAP).apply { weight = 3f; rightMargin = dp(8) }
            }
            etPort = EditText(this@DiagActivity).apply {
                hint = "Port"
                setText("50000")
                textSize = 13f
                setTextColor(C_TEXT)
                setHintTextColor(C_MUTED)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LP(0, WRAP).apply { weight = 1f }
            }
            row1.addView(etIp)
            row1.addView(etPort)
            addView(row1, LP(MATCH, WRAP))

            btnConn = Button(this@DiagActivity).apply {
                text = "Connect"
                textSize = 13f
                setTextColor(C_TEXT)
                setBackgroundColor(Color.parseColor("#222233"))
                layoutParams = LP(MATCH, dp(44)).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(12)
                }
                setOnClickListener { toggleConnect() }
            }
            addView(btnConn)

            // Área / Canal
            addView(tv("— Area & Channel —", 11f, C_MUTED).apply {
                setPadding(0, 0, 0, dp(4))
            })
            val row2 = hll()
            val lArea = tv("Area:", 12f, C_MUTED).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(4), 0)
            }
            etArea = EditText(this@DiagActivity).apply {
                hint = "1"
                setText("1")
                textSize = 13f
                setTextColor(C_TEXT)
                setHintTextColor(C_MUTED)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LP(0, WRAP).apply { weight = 1f; rightMargin = dp(8) }
            }
            val lCh = tv("Ch:", 12f, C_MUTED).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(4), 0)
            }
            etCh = EditText(this@DiagActivity).apply {
                hint = "1"
                setText("1")
                textSize = 13f
                setTextColor(C_TEXT)
                setHintTextColor(C_MUTED)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LP(0, WRAP).apply { weight = 1f }
            }
            row2.addView(lArea)
            row2.addView(etArea)
            row2.addView(lCh)
            row2.addView(etCh)
            addView(row2, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

            // Botones de nivel
            addView(tv("— Send Linear Channel Level —", 11f, C_MUTED).apply {
                setPadding(0, 0, 0, dp(4))
            })
            val levelRow = hll()
            listOf(
                Triple("100%", 100, C_GREEN),
                Triple("75%", 75, C_YELLOW),
                Triple("50%", 50, C_YELLOW),
                Triple("25%", 25, C_YELLOW),
                Triple("OFF", 0, C_RED)
            ).forEach { (label, pct, color) ->
                levelRow.addView(Button(this@DiagActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(color)
                    setBackgroundColor(Color.parseColor("#1a1a28"))
                    layoutParams = LP(0, dp(42)).apply {
                        weight = 1f
                        rightMargin = dp(3)
                    }
                    setOnClickListener { sendLevel(pct) }
                })
            }
            addView(levelRow, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

            // ALL channels
            addView(Button(this@DiagActivity).apply {
                text = "Send to ALL channels in area (CHAN_IDX=FF)"
                textSize = 11f
                setTextColor(C_BLUE)
                setBackgroundColor(Color.parseColor("#08101a"))
                layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(4) }
                setOnClickListener { sendLevelAllChannels(100) }
            })

            // Request level (2.3)
            addView(Button(this@DiagActivity).apply {
                text = "Request Current Level (0x61/0x60)"
                textSize = 11f
                setTextColor(C_BLUE)
                setBackgroundColor(Color.parseColor("#08101a"))
                layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(12) }
                setOnClickListener { sendRequestLevel() }
            })

            // Manual packet
            addView(
                tv(
                    "— Manual Raw Packet (7 bytes hex, checksum auto) —",
                    11f,
                    C_MUTED
                ).apply { setPadding(0, 0, 0, dp(4)) }
            )
            val etManual = EditText(this@DiagActivity).apply {
                hint = "1C 01 00 71 01 14 FF (Area=1, Ch1, 100%, 2.0s)"
                setText("1C 01 00 71 01 14 FF")
                textSize = 11f
                setTextColor(C_TEXT)
                setHintTextColor(C_MUTED)
                typeface = Typeface.MONOSPACE
            }
            addView(etManual, LP(MATCH, WRAP))
            addView(Button(this@DiagActivity).apply {
                text = "Send Manual Packet"
                textSize = 11f
                setTextColor(C_BLUE)
                setBackgroundColor(Color.parseColor("#080818"))
                layoutParams = LP(MATCH, dp(40)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(12)
                }
                setOnClickListener { sendManual(etManual.text.toString()) }
            })

            // Preview
            addView(tv("— Packet Preview —", 11f, C_MUTED).apply {
                setPadding(0, 0, 0, dp(4))
            })
            addView(Button(this@DiagActivity).apply {
                text = "Show what packet will be sent"
                textSize = 11f
                setTextColor(C_GREEN)
                setBackgroundColor(Color.parseColor("#081008"))
                layoutParams = LP(MATCH, dp(40)).apply { bottomMargin = dp(12) }
                setOnClickListener { previewPacket() }
            })

            // Log
            addView(tv("— Traffic Log —", 11f, C_MUTED).apply {
                setPadding(0, 0, 0, dp(4))
            })
            tvLog = TextView(this@DiagActivity).apply {
                text = "Not connected..."
                textSize = 10f
                setTextColor(C_MUTED)
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#080810"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            addView(tvLog, LP(MATCH, WRAP))

            addView(Button(this@DiagActivity).apply {
                text = "Clear"
                textSize = 11f
                setTextColor(C_MUTED)
                setBackgroundColor(Color.parseColor("#1a1a28"))
                layoutParams = LP(MATCH, dp(36)).apply { topMargin = dp(8) }
                setOnClickListener {
                    logLines.clear()
                    tvLog.text = ""
                }
            })
        }

        scroll.addView(root)
        setContentView(scroll)

        setupClient()
        addLog("INFO", "Linear channel format: [1C][AREA][CHAN_IDX][OPCODE][LEVEL][FADE][JOIN][CS]")
    }

    private fun getArea() = etArea.text.toString().toIntOrNull() ?: 1
    private fun getChannel() = etCh.text.toString().toIntOrNull() ?: 1

    private fun toDynLevel(levelPct: Int): Int = when {
        levelPct >= 100 -> 0x01
        levelPct <= 0   -> 0xFF
        else -> {
            val inv = 100 - levelPct
            (0x02 + (inv * 0xFC) / 100).coerceIn(0x02, 0xFE)
        }
    }

    private fun previewPacket() {
        val area = getArea()
        val ch = getChannel()
        val levelPct = 80
        val chanIdx = (ch - 1).coerceAtLeast(0) and 0xFF
        val levelDyn = toDynLevel(levelPct)
        val fadeByte = 20 // 2 s (20 * 100ms)
        val opcode = 0x71
        val join = 0xFF

        val pkt = client.buildPacket(area, chanIdx, opcode, levelDyn, fadeByte, join)
        addLog("PRV", "Area=$area Ch=$ch $levelPct%: ${pkt.toHex()}")
        addLog(
            "PRV",
            "[1C][${area.hex}][${chanIdx.hex}][${opcode.hex}][${levelDyn.hex}][${fadeByte.hex}][${join.hex}][CS]"
        )
    }

    private fun sendLevel(levelPct: Int) {
        val area = getArea()
        val ch = getChannel()
        addLog("TX ", "Level $levelPct% → Area=$area Ch=$ch")
        client.setLevel(area, ch, levelPct, 2000)
    }

    private fun sendLevelAllChannels(levelPct: Int) {
        val area = getArea()
        addLog("TX ", "Level $levelPct% → Area=$area ALL channels")
        client.setLevel(area, 0, levelPct, 2000)
    }

    private fun sendRequestLevel() {
        val area = getArea()
        val ch = getChannel()
        addLog("TX ", "Request level → Area=$area Ch=$ch")
        client.requestLevel(area, ch)
    }

    private fun sendManual(hexStr: String) {
        try {
            val parts = hexStr.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 7) {
                addLog("ERR", "Need 7 bytes (checksum auto-calculated)")
                return
            }
            val pkt7 = ByteArray(8)
            for (i in 0 until 7) {
                pkt7[i] = parts[i].toInt(16).toByte()
            }
            val full = client.buildPacket(
                area = pkt7[1].toInt().and(0xFF),
                data2 = pkt7[2].toInt().and(0xFF),
                opcode = pkt7[3].toInt().and(0xFF),
                data4 = pkt7[4].toInt().and(0xFF),
                data5 = pkt7[5].toInt().and(0xFF),
                join = pkt7[6].toInt().and(0xFF)
            )
            addLog("MAN", "Sending: ${full.toHex()}")
            client.sendRaw(full)
        } catch (e: Exception) {
            addLog("ERR", "Invalid hex format: ${e.message}")
        }
    }

    private fun toggleConnect() {
        if (client.isConnected()) {
            client.disconnect()
        } else {
            val ip = etIp.text.toString().trim()
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
                } else {
                    btnConn.text = "Connect"
                    btnConn.setTextColor(C_TEXT)
                    btnConn.setBackgroundColor(Color.parseColor("#222233"))
                }
            }
        }
        client.onLevelEvent = { event ->
            uiScope.launch {
                addLog(
                    "RX ",
                    "A${event.area} Ch${event.channel} → ${event.levelPct}% [${event.rawHex}]"
                )
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

    private fun ByteArray.toHex(): String =
        joinToString(" ") { it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0') }

    private val Int.hex: String
        get() = toString(16).uppercase().padStart(2, '0')

    override fun onDestroy() {
        client.destroy()
        uiScope.cancel()
        super.onDestroy()
    }

    // Helpers UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private fun LP(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun hll() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
}
