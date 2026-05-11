package com.dynalite.control

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    // ZONES — edit area and channel to match your Dynalite programming
    // ------------------------------------------------------------------
    data class Zone(
        val id: Int, val name: String, val area: Int, val channel: Int,
        var level: Int = 0, var isOn: Boolean = false
    )

    private val zones = mutableListOf(
        Zone(1, "Main Hall",    area = 1, channel = 1),
        Zone(2, "Dining Room",  area = 1, channel = 2),
        Zone(3, "Kitchen",      area = 1, channel = 3),
        Zone(4, "Bedroom 1",    area = 2, channel = 1),
        Zone(5, "Bedroom 2",    area = 2, channel = 2),
        Zone(6, "Main Bathroom",area = 2, channel = 3),
        Zone(7, "Hallway",      area = 1, channel = 4),
        Zone(8, "Terrace",      area = 3, channel = 1)
    )

    private val scenes = mapOf(
        "Normal"  to listOf(80, 60, 100, 60, 60, 70, 40, 50),
        "Meeting" to listOf(100, 80, 60, 30, 30, 60, 80, 70),
        "Dinner"  to listOf(20, 40, 10, 10, 10, 20, 15, 60),
        "Relax"   to listOf(15, 15, 0, 30, 30, 20, 10, 20),
        "Night"   to listOf(0, 0, 0, 10, 10, 10, 10, 0)
    )

    // Colors
    private val C_BG      = Color.parseColor("#0d0d14")
    private val C_CARD    = Color.parseColor("#141420")
    private val C_CARD_ON = Color.parseColor("#1c1b28")
    private val C_ACCENT  = Color.parseColor("#ffc850")
    private val C_GREEN   = Color.parseColor("#5ecf8a")
    private val C_RED     = Color.parseColor("#ff6b6b")
    private val C_BLUE    = Color.parseColor("#64b5f6")
    private val C_TEXT    = Color.parseColor("#f0eee8")
    private val C_MUTED   = Color.parseColor("#666677")
    private val C_LOG_BG  = Color.parseColor("#080810")

    private val client   = DynaliteClient()
    private val uiScope  = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt  = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    private val prefs     get() = getSharedPreferences("dynalite", MODE_PRIVATE)
    private var savedIp   get() = prefs.getString("ip", "172.18.0.101") ?: "172.18.0.101"
        set(v) { prefs.edit().putString("ip", v).apply() }
    private var savedPort get() = prefs.getInt("port", 50000)
        set(v) { prefs.edit().putInt("port", v).apply() }

    private lateinit var btnConnect : Button
    private lateinit var tvLog      : TextView
    private lateinit var zonesLL    : LinearLayout

    data class ZoneUI(
        val card     : LinearLayout,
        val tvSub    : TextView,
        val seek     : SeekBar,
        val btnToggle: Button,
        val tvPct    : TextView
    )
    private val zoneUIs = mutableMapOf<Int, ZoneUI>()

    // ------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
        setupClient()
    }

    override fun onDestroy() {
        client.destroy()
        uiScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // BUILD UI — works in both landscape and portrait
    // ------------------------------------------------------------------
    private fun buildUI(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val root   = vll(C_BG)

        // Header
        val header = hll().apply { setPadding(dp(14), dp(12), dp(14), dp(8)) }
        val tvTitle = tv("Dynalite Control", 18f, C_TEXT, bold = true).apply {
            layoutParams = LP(0, WRAP).apply { weight = 1f }
        }
        btnConnect = Button(this).apply {
            text = "Connect"; textSize = 12f
            setTextColor(C_TEXT)
            setBackgroundColor(Color.parseColor("#222233"))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { showConnectDialog() }
        }
        val btnDiag = Button(this).apply {
            text = "⚙"; textSize = 14f
            setTextColor(C_BLUE)
            setBackgroundColor(Color.parseColor("#080818"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LP(dp(44), dp(44)).apply { leftMargin = dp(6) }
            setOnClickListener { startActivity(Intent(this@MainActivity, DiagActivity::class.java)) }
        }
        header.addView(tvTitle)
        header.addView(btnConnect)
        header.addView(btnDiag)
        root.addView(header, LP(MATCH, WRAP))
        root.addView(divider())

        // Scenes
        val scenesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        val scenesRow = hll()
        scenes.keys.forEach { name ->
            scenesRow.addView(Button(this).apply {
                text = name; textSize = 12f; setTextColor(C_MUTED)
                setBackgroundColor(Color.parseColor("#1a1a28"))
                setPadding(dp(14), dp(4), dp(14), dp(4))
                layoutParams = LP(WRAP, dp(38)).apply { rightMargin = dp(8) }
                setOnClickListener { applyScene(name) }
            })
        }
        scenesScroll.addView(scenesRow)
        root.addView(scenesScroll, LP(MATCH, WRAP))

        // Global controls
        val globalRow = hll().apply { setPadding(dp(14), dp(4), dp(14), dp(8)) }
        globalRow.addView(Button(this).apply {
            text = "All ON"; textSize = 12f; setTextColor(C_ACCENT)
            setBackgroundColor(Color.parseColor("#1a1800"))
            layoutParams = LP(0, dp(40)).apply { weight = 1f; rightMargin = dp(6) }
            setOnClickListener { allOn() }
        })
        globalRow.addView(Button(this).apply {
            text = "All OFF"; textSize = 12f; setTextColor(C_RED)
            setBackgroundColor(Color.parseColor("#1a0808"))
            layoutParams = LP(0, dp(40)).apply { weight = 1f; rightMargin = dp(6) }
            setOnClickListener { allOff() }
        })
        globalRow.addView(Button(this).apply {
            text = "↺ Sync"; textSize = 12f; setTextColor(C_BLUE)
            setBackgroundColor(Color.parseColor("#08101a"))
            layoutParams = LP(0, dp(40)).apply { weight = 1f }
            setOnClickListener { syncLevels() }
        })
        root.addView(globalRow, LP(MATCH, WRAP))
        root.addView(divider())

        // Zone cards
        zonesLL = vll().apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        zones.forEach { zone ->
            val zui = buildZoneCard(zone)
            zoneUIs[zone.id] = zui
            zonesLL.addView(zui.card, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        root.addView(zonesLL, LP(MATCH, WRAP))
        root.addView(divider())

        // Activity log
        val logHeader = hll(C_LOG_BG).apply { setPadding(dp(14), dp(6), dp(14), dp(4)) }
        logHeader.addView(tv("ACTIVITY LOG", 10f, C_MUTED).apply {
            layoutParams = LP(0, WRAP).apply { weight = 1f }
        })
        logHeader.addView(tv("● LIVE", 10f, C_GREEN))
        root.addView(logHeader, LP(MATCH, WRAP))

        tvLog = TextView(this).apply {
            text = "Tap Connect to link the PDEG gateway"
            textSize = 11f; setTextColor(C_MUTED)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(C_LOG_BG)
            setPadding(dp(14), dp(6), dp(14), dp(10))
            minLines = 4; maxLines = 4
        }
        root.addView(tvLog, LP(MATCH, WRAP))

        scroll.addView(root)
        return scroll
    }

    private fun buildZoneCard(zone: Zone): ZoneUI {
        val card = vll(C_CARD).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }

        val topRow = hll().apply { gravity = Gravity.CENTER_VERTICAL }
        val nameCol = vll().apply { layoutParams = LP(0, WRAP).apply { weight = 1f } }
        nameCol.addView(tv(zone.name, 14f, C_TEXT, bold = true))
        val tvSub = tv("Off · A${zone.area} Ch${zone.channel}", 11f, C_MUTED)
        nameCol.addView(tvSub)

        val tvPct = tv("--", 14f, C_MUTED).apply {
            typeface = Typeface.MONOSPACE
            minWidth = dp(46); gravity = Gravity.END
            setPadding(0, 0, dp(10), 0)
        }
        val btnToggle = Button(this).apply {
            text = "OFF"; textSize = 12f; setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#2a2a38"))
            setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LP(dp(72), dp(36))
        }
        topRow.addView(nameCol); topRow.addView(tvPct); topRow.addView(btnToggle)
        card.addView(topRow, LP(MATCH, WRAP).apply { bottomMargin = dp(8) })

        val seekRow = hll().apply { gravity = Gravity.CENTER_VERTICAL }
        seekRow.addView(tv("○", 11f, C_MUTED).apply { setPadding(0, 0, dp(6), 0) })
        val seek = SeekBar(this).apply {
            max = 100; progress = 0; isEnabled = false; alpha = 0.3f
            layoutParams = LP(0, WRAP).apply { weight = 1f }
        }
        seekRow.addView(seek)
        seekRow.addView(tv("●", 11f, C_ACCENT).apply { setPadding(dp(6), 0, 0, 0) })
        card.addView(seekRow, LP(MATCH, WRAP))

        val zui = ZoneUI(card, tvSub, seek, btnToggle, tvPct)

        // Toggle ON/OFF
        btnToggle.setOnClickListener {
            zone.isOn = !zone.isOn
            if (zone.isOn && zone.level == 0) zone.level = 80
            refreshZone(zone, zui)
            if (zone.isOn) client.turnOn(zone.area, zone.channel, zone.level)
            else           client.turnOff(zone.area, zone.channel)
            addLog("APP", "${zone.name} → ${if (zone.isOn) "ON ${zone.level}%" else "OFF"}")
        }

        // Dimmer slider
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                zone.level = p
                zone.isOn  = p > 0
                refreshZone(zone, zui)
                client.setLevel(zone.area, zone.channel, p, 300)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        return zui
    }

    // ------------------------------------------------------------------
    // REFRESH ZONE UI
    // ------------------------------------------------------------------
    private fun refreshZone(zone: Zone, zui: ZoneUI, remote: Boolean = false) {
        val on = zone.isOn
        zui.card.setBackgroundColor(if (on) C_CARD_ON else C_CARD)
        zui.btnToggle.text = if (on) "ON" else "OFF"
        zui.btnToggle.setTextColor(if (on) C_ACCENT else C_MUTED)
        zui.btnToggle.setBackgroundColor(
            if (on) Color.parseColor("#2a2000") else Color.parseColor("#2a2a38")
        )
        zui.seek.isEnabled = on
        zui.seek.alpha     = if (on) 1f else 0.3f
        zui.seek.progress  = zone.level
        zui.tvPct.text     = if (on) "${zone.level}%" else "--"
        zui.tvPct.setTextColor(if (on) C_ACCENT else C_MUTED)
        val remoteTag = if (remote) " ↩" else ""
        zui.tvSub.text = if (on) "${zone.level}% · A${zone.area} Ch${zone.channel}$remoteTag"
                         else    "Off · A${zone.area} Ch${zone.channel}"
        zui.tvSub.setTextColor(if (remote) C_BLUE else if (on) C_ACCENT else C_MUTED)
    }

    // ------------------------------------------------------------------
    // ACTIONS
    // ------------------------------------------------------------------
    private fun allOn() {
        zones.forEach { z ->
            if (z.level == 0) z.level = 80
            z.isOn = true
            zoneUIs[z.id]?.let { refreshZone(z, it) }
            client.turnOn(z.area, z.channel, z.level)
        }
        addLog("APP", "All zones ON")
    }

    private fun allOff() {
        zones.forEach { z ->
            z.isOn = false
            zoneUIs[z.id]?.let { refreshZone(z, it) }
            client.turnOff(z.area, z.channel)
        }
        addLog("APP", "All zones OFF")
    }

    private fun syncLevels() {
        addLog("SYN", "Requesting current levels...")
        zones.map { it.area }.distinct().forEach { client.requestLevels(it) }
    }

    private fun applyScene(name: String) {
        val levels = scenes[name] ?: return
        zones.forEachIndexed { i, z ->
            z.level = levels.getOrElse(i) { 0 }
            z.isOn  = z.level > 0
            zoneUIs[z.id]?.let { refreshZone(z, it) }
            client.setLevel(z.area, z.channel, z.level, 1500)
        }
        addLog("APP", "Scene '$name' applied")
    }

    // ------------------------------------------------------------------
    // CLIENT CALLBACKS
    // ------------------------------------------------------------------
    private fun setupClient() {
        client.onStateChange = { state, msg ->
            uiScope.launch {
                addLog(if (state == DynaliteClient.State.CONNECTED) "CON" else "---", msg)
                when (state) {
                    DynaliteClient.State.CONNECTED -> {
                        btnConnect.text = "✓ $savedIp"
                        btnConnect.setTextColor(C_GREEN)
                        btnConnect.setBackgroundColor(Color.parseColor("#0a200f"))
                        delay(500)
                        syncLevels()
                    }
                    DynaliteClient.State.CONNECTING -> {
                        btnConnect.text = "Connecting..."
                        btnConnect.setTextColor(C_ACCENT)
                        btnConnect.setBackgroundColor(Color.parseColor("#1a1800"))
                    }
                    DynaliteClient.State.DISCONNECTED -> {
                        btnConnect.text = "Reconnect"
                        btnConnect.setTextColor(C_TEXT)
                        btnConnect.setBackgroundColor(Color.parseColor("#222233"))
                    }
                }
            }
        }

        // Incoming level event from PDEG (remote control or keypad)
        client.onLevelEvent = { event ->
            uiScope.launch {
                val zone = zones.find { it.area == event.area && it.channel == event.channel }
                if (zone != null) {
                    val wasOn  = zone.isOn
                    zone.level = event.levelPct
                    zone.isOn  = event.levelPct > 0
                    zoneUIs[zone.id]?.let { refreshZone(zone, it, remote = true) }
                    val action = when {
                        !wasOn && zone.isOn -> "turned ON at ${event.levelPct}%"
                        wasOn && !zone.isOn -> "turned OFF"
                        else               -> "level → ${event.levelPct}%"
                    }
                    addLog("REM", "${zone.name} $action")
                } else {
                    addLog("RX ", "A${event.area} Ch${event.channel} → ${event.levelPct}%")
                }
            }
        }

        client.onLog = { msg ->
            uiScope.launch { addLog("PKT", msg) }
        }
    }

    // ------------------------------------------------------------------
    // ACTIVITY LOG
    // ------------------------------------------------------------------
    private fun addLog(tag: String, msg: String) {
        val time = timeFmt.format(Date())
        logLines.add(0, "[$time] $tag  $msg")
        if (logLines.size > 100) logLines.removeAt(logLines.size - 1)
        tvLog.text = logLines.take(4).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // CONNECT DIALOG
    // ------------------------------------------------------------------
    private fun showConnectDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val etIp = EditText(this).apply {
            hint = "PDEG IP Address"; setText(savedIp)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etPort = EditText(this).apply {
            hint = "Port"; setText(savedPort.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(tv("PDEG IP Address", 12f, C_MUTED))
        layout.addView(etIp)
        layout.addView(tv("TCP Port", 12f, C_MUTED).apply { setPadding(0, dp(10), 0, 0) })
        layout.addView(etPort)

        AlertDialog.Builder(this)
            .setTitle("Gateway Settings")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                savedIp   = etIp.text.toString().trim()
                savedPort = etPort.text.toString().toIntOrNull() ?: 50000
                client.connect(savedIp, savedPort)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    // LAYOUT HELPERS
    // ------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private fun LP(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun vll(bg: Int = Color.TRANSPARENT) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
    }
    private fun hll(bg: Int = Color.TRANSPARENT) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bg)
    }
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(Color.parseColor("#1a1a2a"))
        layoutParams = LP(MATCH, dp(1))
    }
}
