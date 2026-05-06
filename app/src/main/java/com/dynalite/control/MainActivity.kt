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

class MainActivity : AppCompatActivity() {

    // ---------------------------------------------------------------
    // ZONAS — edita area y channel según tu programación Dynalite
    // ---------------------------------------------------------------
    data class Zone(
        val id: Int, val name: String, val area: Int, val channel: Int,
        var level: Int = 80, var isOn: Boolean = false
    )

    private val zones = mutableListOf(
        Zone(1, "Sala principal",  area=1, channel=1, level=80),
        Zone(2, "Comedor",         area=1, channel=2, level=60),
        Zone(3, "Cocina",          area=1, channel=3, level=100),
        Zone(4, "Dormitorio 1",    area=2, channel=1, level=40),
        Zone(5, "Dormitorio 2",    area=2, channel=2, level=40),
        Zone(6, "Baño principal",  area=2, channel=3, level=70),
        Zone(7, "Pasillo",         area=1, channel=4, level=40),
        Zone(8, "Terraza",         area=3, channel=1, level=50),
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
    private val C_TEXT    = Color.parseColor("#f0eee8")
    private val C_MUTED   = Color.parseColor("#666677")

    private val client  = DynaliteClient()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val prefs get() = getSharedPreferences("dyn", MODE_PRIVATE)
    private var savedIp   get() = prefs.getString("ip",   "172.18.0.101")!!; set(v) { prefs.edit().putString("ip", v).apply() }
    private var savedPort get() = prefs.getInt("port",     50000);            set(v) { prefs.edit().putInt("port", v).apply() }

    // UI refs
    private lateinit var btnConnect : Button
    private lateinit var tvLog      : TextView
    private data class ZoneUI(val card: LinearLayout, val tvSub: TextView, val seek: SeekBar, val btnToggle: Button)
    private val zoneUIs = mutableMapOf<Int, ZoneUI>()

    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    // BUILD UI
    // ---------------------------------------------------------------
    private fun buildUI(): ScrollView {
        val scroll = ScrollView(this)
        val root = ll(vertical = true, bg = C_BG, pad = 14)

        // Header
        val header = ll(vertical = false)
        val tvTitle = tv("Control de Iluminación", 18f, C_TEXT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, wrap).apply { weight = 1f }
        }
        btnConnect = Button(this).apply {
            text = "Conectar"
            textSize = 13f
            setTextColor(C_TEXT)
            setBackgroundColor(Color.parseColor("#222233"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { showConnectDialog() }
        }
        header.addView(tvTitle)
        header.addView(btnConnect)
        root.addView(header, matchWrap())

        // Divider
        root.addView(divider(), matchH(1).apply { setMargins(0, dp(10), 0, dp(10)) })

        // Scenes
        root.addView(tv("ESCENAS", 10f, C_MUTED), matchWrap().apply { bottomMargin = dp(6) })
        val scenesRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val scenesLL  = ll(vertical = false)
        scenes.keys.forEach { name ->
            val b = Button(this).apply {
                text     = name
                textSize = 12f
                setTextColor(C_MUTED)
                setBackgroundColor(Color.parseColor("#1a1a28"))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(wrap, dp(40)).apply { rightMargin = dp(8) }
                setOnClickListener { applyScene(name) }
            }
            scenesLL.addView(b)
        }
        scenesRow.addView(scenesLL)
        root.addView(scenesRow, matchWrap().apply { bottomMargin = dp(10) })

        // All on/off
        val globalRow = ll(vertical = false)
        val btnOn = Button(this).apply {
            text = "⚡ Encender todo"; textSize = 13f
            setTextColor(C_ACCENT); setBackgroundColor(Color.parseColor("#1a1800"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44)).apply { weight = 1f; rightMargin = dp(6) }
            setOnClickListener { allOn() }
        }
        val btnOff = Button(this).apply {
            text = "✕ Apagar todo"; textSize = 13f
            setTextColor(C_RED); setBackgroundColor(Color.parseColor("#1a0808"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44)).apply { weight = 1f }
            setOnClickListener { allOff() }
        }
        globalRow.addView(btnOn)
        globalRow.addView(btnOff)
        root.addView(globalRow, matchWrap().apply { bottomMargin = dp(10) })

        // Zone cards
        root.addView(tv("ZONAS", 10f, C_MUTED), matchWrap().apply { bottomMargin = dp(8) })
        zones.forEach { zone ->
            val zui = buildZoneCard(zone)
            zoneUIs[zone.id] = zui
            root.addView(zui.card, matchWrap().apply { bottomMargin = dp(8) })
        }

        // Log
        tvLog = tv("Listo. Toca Conectar para vincular el PDEG.", 11f, C_MUTED).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(tvLog, matchWrap())

        scroll.addView(root)
        return scroll
    }

    private fun buildZoneCard(zone: Zone): ZoneUI {
        val card = ll(vertical = true, bg = C_CARD, pad = 14)

        // Top row
        val topRow = ll(vertical = false)
        topRow.gravity = Gravity.CENTER_VERTICAL

        val tvName = tv(zone.name, 15f, C_TEXT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, wrap).apply { weight = 1f }
        }
        val tvSub = tv("Apagado · A${zone.area} Ch${zone.channel}", 12f, C_MUTED)

        val info = ll(vertical = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, wrap).apply { weight = 1f }
            addView(tvName)
            addView(tvSub)
        }

        val btnToggle = Button(this).apply {
            text = "OFF"
            textSize = 12f
            setTextColor(C_MUTED)
            setBackgroundColor(Color.parseColor("#2a2a38"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                zone.isOn = !zone.isOn
                refreshZone(zone)
                if (zone.isOn) client.turnOn(zone.area, zone.channel, zone.level)
                else           client.turnOff(zone.area, zone.channel)
            }
        }

        topRow.addView(info)
        topRow.addView(btnToggle)
        card.addView(topRow, matchWrap().apply { bottomMargin = dp(10) })

        // Slider row
        val seekRow = ll(vertical = false)
        seekRow.gravity = Gravity.CENTER_VERTICAL

        val tvMin = tv("○", 12f, C_MUTED).apply { setPadding(0, 0, dp(8), 0) }
        val seek  = SeekBar(this).apply {
            max      = 100
            progress = zone.level
            isEnabled = false
            alpha     = 0.3f
            layoutParams = LinearLayout.LayoutParams(0, wrap).apply { weight = 1f }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    zone.level = p
                    tvSub.text = "${p}% · A${zone.area} Ch${zone.channel}"
                    tvSub.setTextColor(C_ACCENT)
                    if (zone.isOn) client.setLevel(zone.area, zone.channel, p, 300)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val tvMax = tv("●", 12f, C_ACCENT).apply { setPadding(dp(8), 0, 0, 0) }

        seekRow.addView(tvMin)
        seekRow.addView(seek)
        seekRow.addView(tvMax)
        card.addView(seekRow, matchWrap())

        return ZoneUI(card, tvSub, seek, btnToggle)
    }

    private fun refreshZone(zone: Zone) {
        val zui = zoneUIs[zone.id] ?: return
        val on  = zone.isOn
        zui.card.setBackgroundColor(if (on) C_CARD_ON else C_CARD)
        zui.btnToggle.text = if (on) "ON" else "OFF"
        zui.btnToggle.setTextColor(if (on) C_ACCENT else C_MUTED)
        zui.btnToggle.setBackgroundColor(
            if (on) Color.parseColor("#2a2000") else Color.parseColor("#2a2a38")
        )
        zui.seek.isEnabled = on
        zui.seek.alpha     = if (on) 1f else 0.3f
        zui.tvSub.text     = if (on) "${zone.level}% · A${zone.area} Ch${zone.channel}" else "Apagado · A${zone.area} Ch${zone.channel}"
        zui.tvSub.setTextColor(if (on) C_ACCENT else C_MUTED)
    }

    // ---------------------------------------------------------------
    // ACCIONES
    // ---------------------------------------------------------------
    private fun allOn() {
        zones.forEach { z -> z.isOn = true; refreshZone(z); client.turnOn(z.area, z.channel, z.level) }
        log("Encendido general")
    }

    private fun allOff() {
        zones.forEach { z -> z.isOn = false; refreshZone(z); client.turnOff(z.area, z.channel) }
        log("Apagado general")
    }

    private fun applyScene(name: String) {
        val levels = scenes[name] ?: return
        zones.forEachIndexed { i, z ->
            z.level = levels.getOrElse(i) { 0 }
            z.isOn  = z.level > 0
            zoneUIs[z.id]?.seek?.progress = z.level
            refreshZone(z)
            client.setLevel(z.area, z.channel, z.level, 1500)
        }
        log("Escena '$name' aplicada")
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
            hint      = "IP del PDEG"
            setText(savedIp)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etPort = EditText(this).apply {
            hint      = "Puerto"
            setText(savedPort.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(tv("Dirección IP del PDEG", 12f, C_MUTED))
        layout.addView(etIp)
        layout.addView(tv("Puerto", 12f, C_MUTED).apply { setPadding(0, dp(10), 0, 0) })
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
    // CLIENTE
    // ---------------------------------------------------------------
    private fun setupClient() {
        client.onStateChange = { state, msg ->
            uiScope.launch {
                log(msg)
                when (state) {
                    DynaliteClient.State.CONNECTED -> {
                        btnConnect.text = "✓ Conectado"
                        btnConnect.setTextColor(C_GREEN)
                        btnConnect.setBackgroundColor(Color.parseColor("#0a200f"))
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
    }

    private fun log(msg: String) { tvLog.text = msg }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wrap  = LinearLayout.LayoutParams.WRAP_CONTENT
    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private fun matchWrap() = LinearLayout.LayoutParams(match, wrap)
    private fun matchH(h: Int) = LinearLayout.LayoutParams(match, dp(h))

    private fun ll(vertical: Boolean, bg: Int = Color.TRANSPARENT, pad: Int = 0) =
        LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            if (pad > 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize  = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(Color.parseColor("#1a1a2a"))
    }
}
