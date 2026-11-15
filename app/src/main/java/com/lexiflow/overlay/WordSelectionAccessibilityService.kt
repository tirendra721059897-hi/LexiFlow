package com.lexiflow.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

class WordSelectionAccessibilityService : AccessibilityService() {

    private var lastLookedUpWord = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        if (!Settings.canDrawOverlays(this)) return

        val source = event.source ?: return
        val fullText = source.text?.toString() ?: return
        val start = source.textSelectionStart
        val end = source.textSelectionEnd

        if (start >= 0 && end > start && end <= fullText.length) {
            val selectedWord = fullText.substring(start, end).trim()
                .replace(Regex("[^a-zA-Z-]"), "")

            if (selectedWord.length in 2..30 && selectedWord != lastLookedUpWord) {
                lastLookedUpWord = selectedWord
                try {
                    val intent = Intent(this, OverlayDictionaryService::class.java).apply {
                        action = OverlayDictionaryService.ACTION_SHOW_WORD
                        putExtra("WORD", selectedWord)
                    }
                    startService(intent)
                } catch (_: Exception) {
                    // Prevents crash if background service hasn't been started yet
                }
            }
        }
    }

    override fun onInterrupt() {}
}
