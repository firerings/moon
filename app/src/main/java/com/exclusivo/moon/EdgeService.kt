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

/** Vigila la esquina inferior izquierda; al deslizar hacia el centro abre el overlay. */
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
            .setContentTitle("Moon listo").setContentText("Desliza desde la esquina inferior izquierda").build())
        val d = resources.displayMetrics.density
        var x0 = 0f; var y0 = 0f
        val v = View(this)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_MOVE -> if (panel == null && e.rawX - x0 > 50 * d && y0 - e.rawY > 50 * d) mostrar()
            }
            true
        }
        val s = (44 * d).toInt()
        val lp = WindowManager.LayoutParams(s, s, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START }
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
