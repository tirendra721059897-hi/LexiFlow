package com.lexiflow.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

class OverlayDictionaryService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SHOW_WORD = "ACTION_SHOW_WORD"
        const val ACTION_SHOW_WORD_FORCE = "ACTION_SHOW_WORD_FORCE"
        const val CHANNEL_ID = "lexiflow_bg_channel"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private var bottomCardView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var offlineDictionary: Map<String, String> = emptyMap()
    private var isServiceActive = false
    private var lastClipboardWord = ""

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isServiceActive || !Settings.canDrawOverlays(this)) return@OnPrimaryClipChangedListener
        val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount > 0) {
            val copiedText = clip.getItemAt(0).text?.toString()?.trim() ?: return@OnPrimaryClipChangedListener
            val cleanWord = copiedText.replace(Regex("[^a-zA-Z-]"), "")
            if (cleanWord.length in 2..30 && !copiedText.contains(" ") && cleanWord != lastClipboardWord) {
                lastClipboardWord = cleanWord
                showBottomDefinitionCard(cleanWord)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        createNotificationChannel()
        offlineDictionary = loadOfflineDictionary()
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isServiceActive = true
                startAsForeground()
            }
            ACTION_STOP -> {
                isServiceActive = false
                removeBottomCard()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SHOW_WORD -> {
                if (!isServiceActive) return START_NOT_STICKY
                val word = intent.getStringExtra("WORD") ?: return START_NOT_STICKY
                showBottomDefinitionCard(word)
            }
            ACTION_SHOW_WORD_FORCE -> {
                isServiceActive = true
                startAsForeground()
                val word = intent.getStringExtra("WORD") ?: return START_NOT_STICKY
                showBottomDefinitionCard(word)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        try {
            val stopIntent = Intent(this, OverlayDictionaryService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LexiFlow Reader Active")
                .setContentText("Select, tap Define, or Copy any word for meaning")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(101, notification)
            }
        } catch (_: Exception) {}
    }

    private fun showBottomDefinitionCard(word: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable Overlay Permission in LexiFlow first", Toast.LENGTH_SHORT).show()
            return
        }

        removeBottomCard()

        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.overlay_bottom_card, null)

            val tvWord = view.findViewById<TextView>(R.id.tvWord)
            val tvMeaning = view.findViewById<TextView>(R.id.tvMeaning)
            val btnClose = view.findViewById<ImageButton>(R.id.btnClose)

            tvWord.text = word
            tvMeaning.text = "Fetching definition..."

            btnClose.setOnClickListener { removeBottomCard() }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
            }

            bottomCardView = view
            windowManager.addView(view, params)

            serviceScope.launch {
                val definition = fetchDefinition(word)
                tvMeaning.text = definition
            }
        } catch (_: Exception) {}
    }

    private fun loadOfflineDictionary(): Map<String, String> {
        return try {
            val jsonString = assets.open("offline_dict.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val map = mutableMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                map[key.lowercase()] = jsonObject.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun fetchDefinition(word: String): String = withContext(Dispatchers.IO) {
        val cleanWord = word.lowercase().trim()

        offlineDictionary[cleanWord]?.let { offlineMeaning ->
            return@withContext "$offlineMeaning\n(Offline)"
        }

        try {
            val json = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$cleanWord").readText()
            val array = JSONArray(json)
            val meanings = array.getJSONObject(0).getJSONArray("meanings")
            meanings.getJSONObject(0)
                .getJSONArray("definitions")
                .getJSONObject(0)
                .getString("definition")
        } catch (e: Exception) {
            "No definition found for \"$word\"."
        }
    }

    private fun removeBottomCard() {
        bottomCardView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            bottomCardView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "LexiFlow Background Listener", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
