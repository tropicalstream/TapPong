package com.tropicalstream.tappong

import android.app.Application

/**
 * Application entry point. TapPong renders via its own dual-draw
 * BinocularSbsLayout, so the Mercury AAR is OPTIONAL — if present we init it
 * reflectively as a courtesy; if not, nothing happens. Never crash here.
 */
class TapPongApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val cls = Class.forName("com.ffalcon.mercury.android.sdk.MercurySDK")
            cls.getMethod("init", Application::class.java).invoke(null, this)
        }
    }
}
