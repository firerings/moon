package com.exclusivo.moon

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Punto de entrada de «comando de voz» (Intent VOICE_COMMAND): es lo que mandan los auriculares Bluetooth
 * (p. ej. QCY) cuando su gesto está asignado al asistente de voz. Sin pantalla: pide al servicio que salude
 * y abra el overlay, y se cierra.
 */
class VozActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Settings.canDrawOverlays(this))
            startForegroundService(Intent(this, EdgeService::class.java).setAction(EdgeService.ACCION_SALUDAR))
        else
            startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
