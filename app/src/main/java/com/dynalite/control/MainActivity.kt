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
    // ZONES — edita area y channel según tu proyecto Dynalite
    // ------------------------------------------------------------------
    data class Zone(
        val id: Int,
        val name: String,
        val area: Int,
        val channel: Int,
        var level: Int = 0,
        var isOn: Boolean = false
    )

    private val zones = mutableListOf(
        Zone(1, "Main Hall", area = 1, channel = 1),
        Zone(2, "Dining Room", area = 1, channel = 2),
        Zone(3, "Kitchen", area = 1, channel = 3),
        Zone(4, "Bedroom 1", area = 2, channel = 1),
        Zone(5, "Bedroom 2", area = 2, channel = 2),
        Zone(6, "Main Bathroom", area = 2, channel = 3),
        Zone(7, "Hallway", area = 1, channel = 4),
        Zone(8, "Terrace", area = 3, channel = 1)
    )

    private val scenes = mapOf(
        "Normal" to listOf(80, 60, 100, 60, 60, 70, 40, 50),
        "Meeting" to listOf(100, 80, 60, 30, 
