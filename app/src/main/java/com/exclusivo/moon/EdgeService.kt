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
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Franja en la esquina inferior izquierda (configurable). Abre el overlay según lo que se active en
 * Sistema > Activar con: fr_toque, fr_doble, fr_largo, fr_desliza (por defecto toque + deslizar).
 * Deslizar: fr_dir_dentro (hacia dentro de la pantalla, según el borde donde esté la franja) o, si se
 * desactiva, fr_dir_izq / fr_dir_der / fr_dir_arr / fr_dir_aba. fr_dist = distancia mínima (dp).
 * fr_vibra = vibración corta al activar.
 * «gesto» = la franja está puesta (el servicio también puede vivir solo para Luna o el auricular).
 * «luna»: consulta al servidor cada segundo (GET /luna?on=1); cuando el contador sube abre el overlay.
 * «auricular»: sesión multimedia; una pulsación larga del botón saluda por voz y abre el overlay.
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
    private var movido = false
    private var sesion: MediaSession? = null
    private var vigilando = false
    private var lunaVista = -1L
    private var auricLanzado = false
    private val saludos = listOf(
        "¿Me necesita, señor?", "A sus órdenes, señor.", "Dígame, señor.",
        "¿En qué puedo ayudarle, señor?", "Aquí estoy, señor. ¿Qué necesita?", "Estoy atento, señor.")
    private var ultimoToque = 0L
    private val h = Handler(Looper.getMainLooper())
    private val alPulsarLargo = Runnable {
        if (!lanzado && !movido && panel == null && pr.getBoolean("fr_largo", false)) { lanzado = true; abrir() }
    }

    // SharedPreferences guarda el listener con referencia débil: hay que conservarlo en un campo.
    private val escucha = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && (k.startsWith("fr_") || k == "gesto")) aplicar()
        if (k == "luna" || k == "auricular") extras()
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
            .setContentText("Usa la franja o toca «Hablar»")
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
        extras()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACCION_ABRIR && panel == null) mostrar()
        return START_STICKY
    }

    /** Lee las preferencias y deja la franja como corresponde: tamaño, posición, aspecto y si está puesta. */
    private fun aplicar() {
        val v = esquina ?: return
        val p = lp ?: return
        if (!pr.getBoolean("gesto", false)) {
            if (agregada) { try { wm.removeView(v) } catch (e: Exception) {}; agregada = false }
            return
        }
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
        val holgura = 12 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; lanzado = false; arrastro = false; movido = false
                px0 = p.x; py0 = p.y; t0 = e.eventTime
                h.removeCallbacks(alPulsarLargo)
                if (!mover && pr.getBoolean("fr_largo", false))
                    h.postDelayed(alPulsarLargo, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (abs(dx) > holgura || abs(dy) > holgura) { movido = true; h.removeCallbacks(alPulsarLargo) }
                if (mover) {
                    if (abs(dx) > 6 * d || abs(dy) > 6 * d) arrastro = true
                    if (arrastro) {
                        p.x = (px0 + dx.toInt()).coerceIn(0, maxOf(0, dm.widthPixels - p.width))
                        p.y = (py0 - dy.toInt()).coerceIn(0, maxOf(0, dm.heightPixels - p.height))
                        try { if (agregada) wm.updateViewLayout(esquina, p) } catch (ex: Exception) {}
                    }
                } else if (!lanzado && panel == null && pr.getBoolean("fr_desliza", true) &&
                    hypot(dx, dy) >= pr.getInt("fr_dist", 40).coerceIn(15, 200) * d && direccionValida(dx, dy, p)) {
                    lanzado = true; abrir()
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(alPulsarLargo)
                if (mover) {
                    if (arrastro) pr.edit().putInt("fr_x", (p.x / d).toInt()).putInt("fr_sube", (p.y / d).toInt()).apply()
                } else if (!lanzado && !movido && panel == null && e.eventTime - t0 < 400) {
                    if (pr.getBoolean("fr_toque", true)) {
                        abrir()
                    } else if (pr.getBoolean("fr_doble", false)) {
                        if (e.eventTime - ultimoToque < 300) { ultimoToque = 0L; abrir() } else ultimoToque = e.eventTime
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> h.removeCallbacks(alPulsarLargo)
        }
        return true
    }

    /** ¿El deslizamiento va en una dirección permitida? "Hacia dentro" depende del borde donde esté la franja. */
    private fun direccionValida(dx: Float, dy: Float, p: WindowManager.LayoutParams): Boolean {
        val dm = resources.displayMetrics
        val horizontal = abs(dx) >= abs(dy)
        if (pr.getBoolean("fr_dir_dentro", true)) {
            val cx = p.x + p.width / 2f
            val cyDesdeArriba = dm.heightPixels - (p.y + p.height / 2f)
            return if (horizontal) { if (cx < dm.widthPixels / 2f) dx > 0 else dx < 0 }
            else { if (cyDesdeArriba < dm.heightPixels / 2f) dy > 0 else dy < 0 }
        }
        return if (horizontal) pr.getBoolean(if (dx > 0) "fr_dir_der" else "fr_dir_izq", true)
        else pr.getBoolean(if (dy < 0) "fr_dir_arr" else "fr_dir_aba", true)
    }

    /** Abre el overlay por un gesto de la franja, con vibración corta si está activada. */
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
        auricular()
        luna()
    }

    // ---------- palabra «Luna» (la oye el servidor; aquí solo se espera su aviso) ----------
    private fun luna() {
        if (!pr.getBoolean("luna", false) || vigilando) return
        vigilando = true
        lunaVista = -1L
        val ctx = applicationContext
        Thread {
            while (pr.getBoolean("luna", false)) {
                val r = Api.call(ctx, "/luna?on=1")
                val n = try { JSONObject(r ?: "").optLong("n", -1L) } catch (e: Exception) { -1L }
                if (n >= 0) {
                    if (lunaVista < 0 || n < lunaVista) lunaVista = n   // primera lectura o servidor reiniciado
                    else if (n > lunaVista) { lunaVista = n; h.post { if (panel == null) abrir() } }
                }
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
            Api.call(ctx, "/luna?on=0")
            vigilando = false
        }.start()
    }

    // ---------- botón del auricular: pulsación larga ----------
    private fun auricular() {
        if (!pr.getBoolean("auricular", false)) {
            sesion?.let { try { it.isActive = false; it.release() } catch (e: Exception) {} }
            sesion = null
            return
        }
        if (sesion != null) return
        Voz.calentar(this)
        try {
            val s = MediaSession(this, "MoonAuricular")
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev != null) botonAuricular(ev)
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

    private fun botonAuricular(ev: KeyEvent) {
        val k = ev.keyCode
        if (k != KeyEvent.KEYCODE_HEADSETHOOK && k != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            k != KeyEvent.KEYCODE_MEDIA_PLAY && k != KeyEvent.KEYCODE_MEDIA_PAUSE) return
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) auricLanzado = false
                if (!auricLanzado && (ev.isLongPress || ev.repeatCount >= 1)) { auricLanzado = true; saludarYAbrir() }
            }
            KeyEvent.ACTION_UP -> {
                if (!auricLanzado && ev.eventTime - ev.downTime >= 600) saludarYAbrir()
                auricLanzado = false
            }
        }
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
        h.removeCallbacks(alPulsarLargo)
        pr.unregisterOnSharedPreferenceChangeListener(escucha)
        sesion?.let { try { it.isActive = false; it.release() } catch (e: Exception) {} }
        sesion = null
        cerrar()
        esquina?.let { if (agregada) { try { wm.removeView(it) } catch (e: Exception) {} } }
        agregada = false
        super.onDestroy()
    }
}
