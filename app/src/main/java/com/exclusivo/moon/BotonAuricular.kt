package com.exclusivo.moon

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Botón del auricular: sesión multimedia activa mientras «auricular» esté encendido.
 * Una pulsación larga llama a [alLargo]; la corta se ignora.
 */
class BotonAuricular(
    private val ctx: Context,
    private val pr: SharedPreferences,
    private val alLargo: () -> Unit,
) {
    private var sesion: MediaSession? = null
    private var lanzado = false

    /** Crea o libera la sesión según el interruptor «auricular». */
    fun actualizar() {
        if (!pr.getBoolean("auricular", false)) {
            liberar()
            return
        }
        if (sesion != null) return
        Voz.calentar(ctx)
        try {
            val s = MediaSession(ctx, "MoonAuricular")
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev != null) boton(ev)
                    return true
                }
            })
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0L, 0f).build())
            s.isActive = true
            sesion = s
        } catch (e: Exception) { sesion = null }
    }

    fun liberar() {
        sesion?.let { try { it.isActive = false; it.release() } catch (e: Exception) {} }
        sesion = null
    }

    private fun boton(ev: KeyEvent) {
        val k = ev.keyCode
        if (k != KeyEvent.KEYCODE_HEADSETHOOK && k != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            k != KeyEvent.KEYCODE_MEDIA_PLAY && k != KeyEvent.KEYCODE_MEDIA_PAUSE) return
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) lanzado = false
                if (!lanzado && (ev.isLongPress || ev.repeatCount >= 1)) { lanzado = true; alLargo() }
            }
            KeyEvent.ACTION_UP -> {
                if (!lanzado && ev.eventTime - ev.downTime >= 600) alLargo()
                lanzado = false
            }
        }
    }
}
