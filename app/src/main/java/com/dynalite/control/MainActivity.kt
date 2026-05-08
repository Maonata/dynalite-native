package com.dynalite.control

import android.app.AlertDialog
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

    // ---------------------------------------------------------------
    // ZONAS — edita area y channel según tu programación Dynalite
    // ---------------------------------------------------------------
    data class Zone(
        val id: Int, val name: String, val area: Int, val channel: Int,
        var level: Int = 0, var isOn: Boolean = false
    )

    private val zones = mutableListOf(
        Zone(1, "Sala principal", area=1, channel=1),
        Zone(2, "Comedor",        area=1, channel=2),
        Zone(3, "Cocina",         area=1, channel=3),
        Zone(4, "Dormitorio 1",   area=2, channel=1),
        Zone(5, "Dormitorio 2",   area=2, channel=2),
        Zone(6, "Baño principal", area=2, channel=3),
        Zone(7, "Pasillo",        area=1, channel=4),
        Zone(8, "Terraza",        area=3, channel=1),
    )

    private val scenes = mapOf(
        "Normal"  to listOf(80, 60, 100, 60, 60, 70, 40, 50),
        "Reunión" to listOf(100, 80, 60, 30, 30, 60, 80, 70),
        "Cena"    to listOf(20, 40, 10, 10, 10, 20, 15, 60),
        "Relax"   to listOf(15, 15, 0, 30, 30, 20, 10, 20),
        "Noche"   to listOf(0, 0, 0, 10, 10, 10, 10, 0),
    )

    // ---------------------------------------------------------------
    // Colores
    // ---------------------------------------------------------------
    private val C_BG      = Color.parseColor("#0d0d14")
    private val C_CARD    = Color.parseColor("#141420")
    private val C_CARD_ON = Color.parseColor("#1c1b28")
    private val C_ACCENT  = Color.parseColor("#ffc850")
    private val C_GREEN   = Color.parseColor("#5ecf8a")
    private val C_RED     = Color.parseColor("#ff6b6b")
    private val C_BLUE    = Color.parseColor("#64b5f6")
    private val C_TEXT    = Color.parseColor("#f0eee8")
    private val C_MUTED   = Color.parseColor("#666677")
    private val C_LOG_BG  = Color.parseColor("#0a0a10")

    private val client  = DynaliteClient()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val prefs     get() = getSharedPreferences("dyn", MODE_PRIVATE)
    private var savedIp   get() = prefs.getString("ip", "172.18.0.101")!!
        set(v) { prefs.edit().putString("ip", v).apply() }
    private var savedPort get() = prefs.getInt("port", 50000)
        set(v) { prefs.edit().putInt("port", v).apply() }

    // UI refs
    private lateinit var btnConnect : Button
    private lateinit var tvLog      : TextView
    private lateinit var logLines   : MutableList<String>

    data class ZoneUI(
        val card     : LinearLayout,
        val tvSub    : TextView,
        val seek     : SeekBar,
        val btnToggle: Button,
        val tvPct    : TextView
    )
    private val zoneUIs = mutableMapOf<Int, ZoneUI>()

    // ---------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logLines = mutableListOf()
        setContentView(buildUI())
        setupClient()
    }

    override fun onDestroy() {
        client.destroy()
        uiScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // CONSTRUIR UI
    // ---------------------------------------------------------------
    private fun buildUI(): LinearLayout {
        val root = ll(true, C_BG, 0)

        // ---- Header ----
        val header = ll(false).apply { setPadding(dp(14), dp(12), dp(14), dp(8)) }

        val tvTitle = tv("Control Dynalite", 17f, C_TEXT, bold = true).apply {
            layoutParams = lp(0, wrap).apply { weight = 1f }
        }
        btnConnect = Button(this).apply {
            text = "Conectar"
            textSize = 12f
            setTextColor(C_TEXT)
            setBackgroundColor(Color.parseColor("#222233"))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { showConnectDialog() }
        }
        header.addView(tvTitle)
        header.addView(btnConnect)
        root.addView(header, lp(match, wrap))
        root.addView(divider())

        // ---- Escenas ----
        val scenesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        val scenesLL = ll(false)
        scenes.keys.forEach { name ->
            scenesLL.addView(Button(this).apply {
                text = name; textSize = 12f
                setTextColor(C_MUTED)
                setBackgroundColor(Color.parseColor("#1a1a28"))
                setPadding(dp(14), dp(4), dp(14), dp(4))
                layoutParams = lp(wrap, dp(38)).apply { rightMargin = dp(8) }
                setOnClickListener { applyScene(name) }
            })
        }
        scenesScroll.addView(scenesLL)
        root.addView(scenesScroll, lp(match, wrap))

        // ---- All on/off ----
        val globalRow = ll(false).apply { setPadding(dp(14), dp(4), dp(14), dp(8)) }
        globalRow.addView(Button(this).apply {
            text = "⚡ Todo ON"; textSize = 12f
            setTextColor(C_ACCENT); setBackgroundColor(Color.parseColor("#1a1800"))
            layoutParams = lp(0, dp(40)).apply { weight = 1f; rightMargin = dp(6) }
            setOnClickListener { allOn() }
        })
        globalRow.addView(Button(this).apply {
            text = "✕ Todo OFF"; textSize = 12f
            setTextColor(C_RED); setBackgroundColor(Color.parseColor("#1a0808"))
            layoutParams = lp(0, dp(40)).apply { weight = 1f; rightMargin = dp(6) }
            setOnClickListener { allOff() }
        })
        globalRow.addView(Button(this).apply {
            text = "↺ Sync"; textSize = 12f
            setTextColor(C_BLUE); setBackgroundColor(Color.parseColor("#08101a"))
            layoutParams = lp(0, dp(40)).apply { weight = 1f }
            setOnClickListener { syncLevels() }
        })
        root.addView(globalRow, lp(match, wrap))
        root.addView(divider())

        // ---- Zonas (scroll) ----
        val zonesScroll = ScrollView(this).apply {
            layoutParams = lp(match, 0).apply { weight = 1f }
        }
        val zonesLL = ll(true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        zones.forEach { zone ->
            val zui = buildZoneCard(zone)
            zoneUIs[zone.id] = zui
            zonesLL.addView(zui.card, lp(match, wrap).apply { bottomMargin = dp(8) })
        }
        zonesScroll.addView(zonesLL)
        root.addView(zonesScroll)

        // ---- Log de actividad ----
        root.addView(divider())
        val logHeader = ll(false).apply {
            setPadding(dp(14), dp(6), dp(14), dp(4))
            setBackgroundColor(C_LOG_BG)
        }
        logHeader.addView(tv("ACTIVIDAD", 10f, C_MUTED).apply {
            layoutParams = lp(0, wrap).apply { weight = 1f }
        })
        logHeader.addView(tv("● EN VIVO", 10f, C_GREEN))
        root.addView(logHeader, lp(match, wrap))

        tvLog = TextView(this).apply {
            text = "Esperando conexión..."
            textSize = 11f
            setTextColor(C_MUTED)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(C_LOG_BG)
            setPadding(dp(14), dp(4), dp(14), dp(10))
            minLines = 4
            maxLines = 4
        }
        root.addView(tvLog, lp(match, wrap))

        return root
    }

    private fun buildZoneCard(zone: Zone): ZoneUI {
        val card = ll(true, C_CARD, 0).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // Fila superior
        val topRow = ll(false).apply { gravity = Gravity.CENTER_VERTICAL }

        val nameCol = ll(true).apply {
            layoutParams = lp(0, wrap).apply { weight = 1f }
        }
        nameCol.addView(tv(zone.name, 14f, C_TEXT, bold = true))
        val tvSub = tv("Apagado · A${zone.area} Ch${zone.channel}", 11f, C_MUTED)
        nameCol.addView(tvSub)

        val tvPct = tv("--", 14f, C_MUTED).apply {
            typeface = Typeface.MONOSPACE
            width = dp(46)
            gravity = Gravity.END
            setPadding(0, 0, dp(10), 0)
        }

        val btnToggle = Button(this).apply {
            text = "OFF"
            textSize = 12f
            setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#2a2a38"))
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = lp(dp(70), dp(36))
        }

        topRow.addView(nameCol)
        topRow.addView(tvPct)
        topRow.addView(btnToggle)
        card.addView(topRow, lp(match, wrap).apply { bottomMargin = dp(8) })

        // Slider
        val seekRow = ll(false).apply { gravity = Gravity.CENTER_VERTICAL }
        seekRow.addView(tv("○", 11f, C_MUTED).apply { setPadding(0, 0, dp(6), 0) })
        val seek = SeekBar(this).apply {
            max = 100; progress = 0; isEnabled = false; alpha = 0.3f
            layoutParams = lp(0, wrap).apply { weight = 1f }
        }
        seekRow.addView(seek)
        seekRow.addView(tv("●", 11f, C_ACCENT).apply { setPadding(dp(6), 0, 0, 0) })
        card.addView(seekRow, lp(match, wrap))

        val zui = ZoneUI(card, tvSub, seek, btnToggle, tvPct)

        // Listeners
        btnToggle.setOnClickListener {
            zone.isOn = !zone.isOn
            if (zone.isOn && zone.level == 0) zone.level = 80
            refreshZone(zone, zui, source = "app")
            if (zone.isOn) client.turnOn(zone.area, zone.channel, zone.level)
            else           client.turnOff(zone.area, zone.channel)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                zone.level = p
                zone.isOn  = p > 0
                refreshZone(zone, zui, source = "app")
                client.setLevel(zone.area, zone.channel, p, 300)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        return zui
    }

    // ---------------------------------------------------------------
    // ACTUALIZAR ZONA — source: "app" | "remote" | "sync"
    // ---------------------------------------------------------------
    private fun refreshZone(zone: Zone, zui: ZoneUI, source: String = "app") {
        val on = zone.isOn
        zui.card.setBackgroundColor(if (on) C_CARD_ON else C_CARD)

        // Toggle button
        zui.btnToggle.text = if (on) "ON" else "OFF"
        zui.btnToggle.setTextColor(if (on) C_ACCENT else C_MUTED)
        zui.btnToggle.setBackgroundColor(
            if (on) Color.parseColor("#2a2000") else Color.parseColor("#2a2a38")
        )

        // Seek
        zui.seek.isEnabled = on
        zui.seek.alpha     = if (on) 1f else 0.3f
        zui.seek.progress  = zone.level

        // Labels
        zui.tvPct.text = if (on) "${zone.level}%" else "--"
        zui.tvPct.setTextColor(if (on) C_ACCENT else C_MUTED)

        val sourceTag = when (source) {
            "remote" -> " ↩ remoto"
            "sync"   -> " ↺ sync"
            else     -> ""
        }
        zui.tvSub.text = if (on)
            "${zone.level}% · A${zone.area} Ch${zone.channel}$sourceTag"
        else
            "Apagado · A${zone.area} Ch${zone.channel}$sourceTag"
        zui.tvSub.setTextColor(when (source) {
            "remote" -> C_BLUE
            "sync"   -> C_GREEN
            else     -> if (on) C_ACCENT else C_MUTED
        })

        // Limpiar tag de fuente después de 3 segundos
        if (source != "app") {
            uiScope.launch {
                delay(3000)
                val z = zones.find { it.id == zone.id } ?: return@launch
                zui.tvSub.text = if (z.isOn)
                    "${z.level}% · A${z.area} Ch${z.channel}"
                else
                    "Apagado · A${z.area} Ch${z.channel}"
                zui.tvSub.setTextColor(if (z.isOn) C_ACCENT else C_MUTED)
            }
        }
    }

    // ---------------------------------------------------------------
    // ACCIONES
    // ---------------------------------------------------------------
    private fun allOn() {
        zones.forEach { z ->
            if (z.level == 0) z.level = 80
            z.isOn = true
            zoneUIs[z.id]?.let { refreshZone(z, it) }
            client.turnOn(z.area, z.channel, z.level)
        }
        addLog("APP", "Encendido general")
    }

    private fun allOff() {
        zones.forEach { z ->
            z.isOn = false
            zoneUIs[z.id]?.let { refreshZone(z, it) }
            client.turnOff(z.area, z.channel)
        }
        addLog("APP", "Apagado general")
    }

    private fun syncLevels() {
        addLog("SYN", "Solicitando niveles al PDEG...")
        zones.map { it.area }.distinct().forEach { area ->
            client.requestLevels(area)
        }
    }

    private fun applyScene(name: String) {
        val levels = scenes[name] ?: return
        zones.forEachIndexed { i, z ->
            z.level = levels.getOrElse(i) { 0 }
            z.isOn  = z.level > 0
            zoneUIs[z.id]?.let { refreshZone(z, it) }
            client.setLevel(z.area, z.channel, z.level, 1500)
        }
        addLog("APP", "Escena '$name'")
    }

    // ---------------------------------------------------------------
    // CLIENTE — CALLBACKS
    // ---------------------------------------------------------------
    private fun setupClient() {
        client.onStateChange = { state, msg ->
            uiScope.launch {
                addLog(if (state == DynaliteClient.State.CONNECTED) "CON" else "---", msg)
                when (state) {
                    DynaliteClient.State.CONNECTED -> {
                        btnConnect.text = "✓ ${savedIp}"
                        btnConnect.setTextColor(C_GREEN)
                        btnConnect.setBackgroundColor(Color.parseColor("#0a200f"))
                        // Pedir niveles actuales al conectar
                        delay(500)
                        syncLevels()
                    }
                    DynaliteClient.State.CONNECTING -> {
                        btnConnect.text = "Conectando..."
                        btnConnect.setTextColor(C_ACCENT)
                        btnConnect.setBackgroundColor(Color.parseColor("#1a1800"))
                    }
                    DynaliteClient.State.DISCONNECTED -> {
                        btnConnect.text = "Reconectar"
                        btnConnect.setTextColor(C_TEXT)
                        btnConnect.setBackgroundColor(Color.parseColor("#222233"))
                    }
                }
            }
        }

        // RETROALIMENTACION — evento de nivel recibido del PDEG
        client.onLevelEvent = { event ->
            uiScope.launch {
                // Buscar zona que coincida con area+channel
                val zone = zones.find {
                    it.area == event.area && it.channel == event.channel
                }

                if (zone != null) {
                    val wasOn  = zone.isOn
                    zone.level = event.level
                    zone.isOn  = event.level > 0
                    val zui    = zoneUIs[zone.id]

                    if (zui != null) {
                        refreshZone(zone, zui, source = "remote")
                    }

                    val action = when {
                        !wasOn && zone.isOn -> "encendido"
                        wasOn && !zone.isOn -> "apagado"
                        else               -> "nivel ${event.level}%"
                    }
                    addLog("REM", "${zone.name} → $action")
                } else {
                    // Zona no configurada — mostrar en log igual
                    addLog("RX ", "A${event.area} Ch${event.channel} → ${event.level}%")
                }
            }
        }

        // Log de paquetes crudos TX/RX
        client.onRawRx = { hex ->
            uiScope.launch {
                addLog("PKT", hex)
            }
        }
    }

    // ---------------------------------------------------------------
    // LOG DE ACTIVIDAD
    // ---------------------------------------------------------------
    private fun addLog(tag: String, msg: String) {
        val time = timeFmt.format(Date())
        val line = "[$time] $tag  $msg"
        logLines.add(0, line)
        if (logLines.size > 50) logLines.removeAt(logLines.size - 1)
        tvLog.text = logLines.take(4).joinToString("\n")
        tvLog.setTextColor(when {
            tag == "REM" -> C_BLUE
            tag == "CON" -> C_GREEN
            tag.startsWith("TX") || tag == "PKT" -> C_MUTED
            else -> C_MUTED
        })
    }

    // ---------------------------------------------------------------
    // DIALOGO CONEXION
    // ---------------------------------------------------------------
    private fun showConnectDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val etIp = EditText(this).apply {
            hint = "IP del PDEG"; setText(savedIp)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etPort = EditText(this).apply {
            hint = "Puerto"; setText(savedPort.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(tv("Dirección IP del PDEG", 12f, C_MUTED))
        layout.addView(etIp)
        layout.addView(tv("Puerto TCP", 12f, C_MUTED).apply { setPadding(0, dp(10), 0, 0) })
        layout.addView(etPort)

        AlertDialog.Builder(this)
            .setTitle("Configuración PDEG")
            .setView(layout)
            .setPositiveButton("Conectar") { _, _ ->
                savedIp   = etIp.text.toString().trim()
                savedPort = etPort.text.toString().toIntOrNull() ?: 50000
                client.connect(savedIp, savedPort)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wrap  = LinearLayout.LayoutParams.WRAP_CONTENT
    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun ll(vertical: Boolean, bg: Int = Color.TRANSPARENT, pad: Int = 0) =
        LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            if (pad > 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(Color.parseColor("#1a1a2a"))
        layoutParams = lp(match, dp(1))
    }
}
