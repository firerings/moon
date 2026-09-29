package com.exclusivo.moon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Franja fina en el borde inferior izquierdo. Al deslizar horizontalmente hacia la derecha abre el overlay.
 * Alto, ancho y margen inferior se ajustan en Sistema (prefs fr_alto, fr_ancho, fr_sube, en dp).
 */
class EdgeService : Service() {
    private lateinit var wm: WindowManager
    private var esquina: View? = null
    private var panel: OverlayPanel? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("moon", "Moon", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "moon").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Moon listo").setContentText("Desliza a la derecha desde la esquina inferior izquierda").build())
        val d = resources.displayMetrics.density
        val pr = getSharedPreferences("moon", 0)
        val alto = (pr.getInt("fr_alto", 12) * d).toInt()
        val ancho = (pr.getInt("fr_ancho", 56) * d).toInt()
        val sube = (pr.getInt("fr_sube", 0) * d).toInt()
        var x0 = 0f; var y0 = 0f; var lanzado = false
        val v = View(this)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; lanzado = false }
                MotionEvent.ACTION_MOVE ->
                    if (!lanzado && panel == null && e.rawX - x0 > 60 * d && abs(e.rawY - y0) < 40 * d) {
                        lanzado = true; mostrar()
                    }
            }
            true
        }
        val lp = WindowManager.LayoutParams(ancho, alto, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START; y = sube }
        wm.addView(v, lp)
        esquina = v
    }

    private fun mostrar() {
        val p = OverlayPanel(this)
        panel = p
        p.onClose = { cerrar() }
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        wm.addView(p.view, lp)
        p.start()
    }

    private fun cerrar() {
        panel?.let { it.stop(); try { wm.removeView(it.view) } catch (e: Exception) {} }
        panel = null
    }

    override fun onDestroy() {
        cerrar()
        esquina?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        super.onDestroy()
    }
}
