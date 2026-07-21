package com.tropicalstream.tappong

import android.content.Context
import com.tropicalstream.tappong.game.PongGame

/** Small persistent settings surface designed for the glasses' intro menu. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tappong_settings", Context.MODE_PRIVATE)

    var difficulty: PongGame.Difficulty
        get() = runCatching {
            PongGame.Difficulty.valueOf(prefs.getString("difficulty", null) ?: "CLASSIC")
        }.getOrDefault(PongGame.Difficulty.CLASSIC)
        set(value) { prefs.edit().putString("difficulty", value.name).apply() }

    var powerUps: Boolean
        get() = prefs.getBoolean("power_ups", true)
        set(value) { prefs.edit().putBoolean("power_ups", value).apply() }

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) { prefs.edit().putBoolean("sound", value).apply() }
}
