package com.exclusivo.moon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Franja en la esquina inferior izquierda (configurable). Al deslizar hacia la derecha abre el overlay.
 * Preferencias (dp): fr_alto, fr_ancho, fr_x (desde la izquierda), fr_sube (desde abajo).
 * Interruptores: fr_oculto (por defecto si: invisible pero activa; el gesto funciona igual) y
 * fr_mover (desbloqueada: se ve y se arrastra). La franja nunca se quita mientras el servicio corre.
 * Cualquier cambio en una preferencia fr_* se aplica al instante, sin reiniciar el servicio.
 */
class EdgeService : Service() {
    companion object { const val ACCION_ABRIR = "com.exclusivo.moon.ABRIR" }

    private lateinit var wm: WindowManager
    private lateinit var pr: SharedPreferences
    private var esquina: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var agregada = false
    private var panel: OverlayPanel? = null
    private var d = 1f

    private var x0 = 0f; private var y0 = 0f
    private var px0 = 0; private var py0 = 0
    private var t0 = 0L
    private var lanzado = false
    private var arrastro = false

    // SharedPreferences guarda el listener con referencia débil: hay que conservarlo en un campo.
    private val escucha = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && k.startsWith("fr_")) aplicar()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pr = getSharedPreferences("moon", 0)
        d = resources.displayMetrics.density
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("moon", "Moon", NotificationManager.IMPORTANCE_MIN))
        val abrir = PendingIntent.getService(this, 1,
            Intent(this, EdgeService::class.java).setAction(ACCION_ABRIR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val app = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "moon").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Moon listo")
            .setContentText("Desliza a la derecha desde la franja o toca «Hablar»")
            .setContentIntent(app)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Hablar", abrir).build())
            .build())
        val v = View(this)
        v.setOnTouchListener { _, e -> tocar(e) }
        esquina = v
        lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START }
        pr.registerOnSharedPreferenceChangeListener(escucha)
        aplicar()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACCION_ABRIR && panel == null) mostrar()
        return START_STICKY
    }

    /** Lee las preferencias y deja la franja como corresponde: tamaño, posición, aspecto y si está puesta. */
    private fun aplicar() {
        val v = esquina ?: return
        val p = lp ?: return
        val dm = resources.displayMetrics
        val moviendo = pr.getBoolean("fr_mover", false)
        val visible = moviendo || !pr.getBoolean("fr_oculto", true)
        p.width = (pr.getInt("fr_ancho", 56).coerceIn(16, 400) * d).toInt()
        p.height = (pr.getInt("fr_alto", 12).coerceIn(6, 400) * d).toInt()
        p.x = (pr.getInt("fr_x", 0) * d).toInt().coerceIn(0, maxOf(0, dm.widthPixels - p.width))
        p.y = (pr.getInt("fr_sube", 0) * d).toInt().coerceIn(0, maxOf(0, dm.heightPixels - p.height))
        v.background = if (visible) GradientDrawable().apply {
            cornerRadius = 99 * d
            setColor(if (moviendo) 0xCC8AB4FF.toInt() else 0x668AB4FF)
            if (moviendo) setStroke((2 * d).toInt(), Color.WHITE)
        } else null
        try {
            if (agregada) wm.updateViewLayout(v, p)
            else { wm.addView(v, p); agregada = true }
        } catch (e: Exception) { /* sin permiso de superposición o vista ya quitada: se ignora */ }
    }

    private fun tocar(e: MotionEvent): Boolean {
        val p = lp ?: return true
        val dm = resources.displayMetrics
        val mover = pr.getBoolean("fr_mover", false)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; lanzado = false; arrastro = false
                px0 = p.x; py0 = p.y; t0 = e.eventTime
            }
            MotionEvent.ACTION_MOVE ->
                if (mover) {
                    val dx = (e.rawX - x0).toInt(); val dy = (e.rawY - y0).toInt()
                    if (abs(dx) > 6 * d || abs(dy) > 6 * d) arrastro = true
                    if (arrastro) {
                        p.x = (px0 + dx).coerceIn(0, maxOf(0, dm.widthPixels - p.width))
                        p.y = (py0 - dy).coerceIn(0, maxOf(0, dm.heightPixels - p.height))
                        try { if (agregada) wm.updateViewLayout(esquina, p) } catch (ex: Exception) {}
                    }
                } else if (!lanzado && panel == null && e.rawX - x0 > 60 * d && abs(e.rawY - y0) < 40 * d) {
                    lanzado = true; mostrar()
                }
            MotionEvent.ACTION_UP ->
                if (mover) {
                    if (arrastro) pr.edit().putInt("fr_x", (p.x / d).toInt()).putInt("fr_sube", (p.y / d).toInt()).apply()
                } else if (!lanzado && panel == null && !pr.getBoolean("fr_oculto", true) &&
                    abs(e.rawX - x0) < 12 * d && abs(e.rawY - y0) < 12 * d && e.eventTime - t0 < 400) {
                    mostrar()   // si la franja se ve, un toque corto también la abre
                }
        }
        return true
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
        cerrar()
        esquina?.let { if (agregada) { try { wm.removeView(it) } catch (e: Exception) {} } }
        agregada = false
        super.onDestroy()
    }
}
