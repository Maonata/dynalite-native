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
 * Muestra bytes TX/RX para verificar DyNet logical (0x1C).
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
                ).apply { setPadding(0, 0, 
