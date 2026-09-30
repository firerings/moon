package com.exclusivo.moon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager

/**
 * Servicio en primer plano que mantiene vivas las formas de invocar a Moon y abre el overlay:
 *  - [Franja]: gesto en el borde de la pantalla (Sistema > Activar con: fr_toque, fr_doble, fr_largo, fr_desliza...).
 *  - [VigiaLuna]: palabra «Luna» (interruptor «luna»).
 *  - [BotonAuricular]: pulsación larga del auricular (interruptor «auricular»).
 * El servicio vive mientras esté activa la franja, «Luna» o el auricular.
 * Cualquier cambio en una preferencia fr_* se aplica al instante, sin reiniciar el servicio.
 */
class EdgeService : Service() {
    companion object {
        const val ACCION_ABRIR = "com.exclusivo.moon.ABRIR"
        const val ACCION_SALUDAR = "com.exclusivo.moon.SALUDAR"   // desde VozActivity: saluda y abre el overlay
    }

    private lateinit var wm: WindowManager
    private lateinit var pr: SharedPreferences
    private lateinit var franja: Franja
    private lateinit var luna: VigiaLuna
    private lateinit var auricular: BotonAuricular
    private var panel: OverlayPanel? = null
    private val h = Handler(Looper.getMainLooper())
    private val saludos = listOf(
        "¿Me necesita, señor?", "A sus órdenes, señor.", "Dígame, señor.",
        "¿En qué puedo ayudarle, señor?", "Aquí estoy, señor. ¿Qué necesita?", "Estoy atento, señor.")

    // SharedPreferences guarda el listener con referencia débil: hay que conservarlo en un campo.
    private val escucha = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && (k.startsWith("fr_") || k == "gesto")) franja.aplicar()
        if (k == "luna" || k == "auricular") extras()
        if (k == "tarjeta_flotante") Tarjeta.arrancar(this)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pr = getSharedPreferences("moon", 0)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("moon", "Moon", NotificationManager.IMPORTANCE_MIN))
        val pIntAbrir = PendingIntent.getService(this, 1,
            Intent(this, EdgeService::class.java).setAction(ACCION_ABRIR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pIntApp = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "moon").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Moon listo")
            .setContentText("Usa la franja o toca «Hablar»")
            .setContentIntent(pIntApp)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Hablar", pIntAbrir).build())
            .build())
        franja = Franja(this, pr, h, { panel != null }, { abrir() })
        luna = VigiaLuna(this, pr, h, { panel != null }, { abrir() })
        auricular = BotonAuricular(this, pr) { saludarYAbrir() }
        franja.crear()
        Tarjeta.servicio(this, true)
        Voz.calentar(this)
        pr.registerOnSharedPreferenceChangeListener(escucha)
        franja.aplicar()
        extras()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACCION_ABRIR && panel == null) mostrar()
        if (i?.action == ACCION_SALUDAR) saludarYAbrir()
        return START_STICKY
    }

    /** Abre el overlay por un gesto, con vibración corta si está activada. */
    private fun abrir() {
        vibrar()
        mostrar()
    }

    private fun vibrar() {
        if (!pr.getBoolean("fr_vibra", true)) return
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (ex: Exception) { /* sin vibrador */ }
    }

    /** Pone o quita lo que depende de los interruptores «luna» y «auricular». */
    private fun extras() {
        auricular.actualizar()
        luna.actualizar()
    }

    /** Vibra, saluda por voz («¿Me necesita, señor?» y variantes) y, al terminar de hablar, abre el overlay. */
    private fun saludarYAbrir() {
        if (panel != null) return
        vibrar()
        Voz.decirYLuego(this, saludos.random()) { if (panel == null) mostrar() }
    }

    private fun mostrar() {
        if (panel != null) return
        val p = OverlayPanel(this)
        panel = p
        p.onClose = { cerrar() }
        val lpp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        wm.addView(p.view, lpp)
        p.start()
    }

    private fun cerrar() {
        panel?.let { it.stop(); try { wm.removeView(it.view) } catch (e: Exception) {} }
        panel = null
    }

    override fun onDestroy() {
        pr.unregisterOnSharedPreferenceChangeListener(escucha)
        auricular.liberar()
        Tarjeta.servicio(this, false)
        cerrar()
        franja.quitar()
        super.onDestroy()
    }
}
