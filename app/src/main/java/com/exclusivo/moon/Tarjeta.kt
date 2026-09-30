package com.exclusivo.moon

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tarjeta Sí/No en su propia ventana flotante (TYPE_APPLICATION_OVERLAY): sale encima de cualquier app, también
 * de Moon, sin fondo oscuro y sin tocar lo que hay detrás (FLAG_NOT_TOUCH_MODAL). No depende del overlay de escucha:
 * un hilo consulta `GET /pregunta` (no cuenta como «app mirando») mientras haya un overlay abierto o el servicio
 * de la franja esté vivo y el ajuste «tarjeta_flotante» (por defecto sí) esté activo. Así también salen las
 * preguntas que Moon hace solo, como la de la batería.
 *
 * Una opción: nombre, número, ✓ Sí y ✕ No. Varias opciones: lista con ✓ y ✕ por fila y «No llamar a nadie» debajo.
 * Los paneles de escucha se suscriben con [oyente] para ocultarse mientras la tarjeta está a la vista.
 * El número lo lee esta app de tus contactos; el servidor de Termux solo conoce id y nombre.
 */
object Tarjeta {
    /** Avisos a los paneles de escucha. */
    const val RESTAURAR = 0   // la tarjeta terminó: el panel vuelve a verse
    const val MOSTRAR = 1     // la tarjeta salió: el panel se oculta
    const val CERRAR = 2      // se dijo que no a todo: se oculta todo (panel incluido)

    private val ui = Handler(Looper.getMainLooper())
    private val oyentes = CopyOnWriteArrayList<(Int) -> Unit>()
    private var paneles = 0
    private var servicioVivo = false
    private var corriendo = false

    /** true mientras la tarjeta está a la vista (o esperando a que el servidor cierre tras un «No»). */
    @Volatile var activa = false
        private set

    // Solo se tocan desde el hilo de interfaz.
    private var wm: WindowManager? = null
    private var raiz: View? = null
    private var barra: ProgressBar? = null
    private var pregId = ""
    private var pregIgnorada = ""          // la que acabas de tocar: no se vuelve a dibujar mientras el servidor la cierra
    private var cerrarAlTerminar = false   // tras un «No»: cuando la pregunta termine, se oculta todo

    fun oyente(f: (Int) -> Unit) { oyentes.add(f) }
    fun quitarOyente(f: (Int) -> Unit) { oyentes.remove(f) }

    /** Un overlay de escucha se abrió (on=true) o se cerró (on=false). */
    fun panel(ctx: Context, on: Boolean) {
        synchronized(this) { paneles = maxOf(0, paneles + (if (on) 1 else -1)) }
        if (on) arrancar(ctx)
    }

    /** El servicio de la franja nació (on=true) o murió (on=false). */
    fun servicio(ctx: Context, on: Boolean) {
        synchronized(this) { servicioVivo = on }
        if (on) arrancar(ctx)
    }

    /** Arranca el hilo de consulta si hace falta y no hay uno (también al activar el ajuste). */
    fun arrancar(ctx: Context) {
        val a = ctx.applicationContext
        synchronized(this) {
            if (corriendo || !necesario(a)) return
            corriendo = true
        }
        Thread { bucle(a) }.start()
    }

    private fun necesario(a: Context) =
        paneles > 0 || (servicioVivo && a.getSharedPreferences("moon", 0).getBoolean("tarjeta_flotante", true))

    private fun bucle(a: Context) {
        while (true) {
            synchronized(this) {
                if (!necesario(a)) {
                    corriendo = false
                    ui.post { terminar(false) }
                    return
                }
            }
            val r = Api.call(a, "/pregunta")
            val pr = try { JSONObject(r ?: "").optJSONObject("pregunta") } catch (e: Exception) { null }
            ui.post { pintar(a, pr) }
            try { Thread.sleep(if (activa) 350L else 1000L) } catch (e: InterruptedException) { }
        }
    }

    // ---------- hilo de interfaz ----------
    private fun pintar(a: Context, pr: JSONObject?) {
        val id = pr?.optString("id") ?: ""
        if (pr == null || id.isEmpty()) {
            pregIgnorada = ""
            if (raiz != null || activa) terminar(cerrarAlTerminar)
            return
        }
        if (id == pregIgnorada) return
        if (!Settings.canDrawOverlays(a)) return          // sin permiso de superposición no se puede dibujar
        if (id != pregId) armar(a, id, pr)
        val espera = maxOf(pr.optDouble("espera", 15.0), 1.0)
        val resto = pr.optDouble("restante", espera)
        barra?.setProgress((resto / espera * 1000).toInt().coerceIn(0, 1000), true)
    }

    private fun avisar(e: Int) { for (f in oyentes) f(e) }

    private fun quitarVista() {
        raiz?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
        raiz = null; barra = null; pregId = ""
    }

    /** Ya no hay tarjeta. [cerrarTodo]: se dijo que no a todo, así que también se oculta el overlay de escucha. */
    private fun terminar(cerrarTodo: Boolean) {
        quitarVista()
        cerrarAlTerminar = false
        if (activa) { activa = false; avisar(if (cerrarTodo) CERRAR else RESTAURAR) }
    }

    private fun dp(a: Context, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun armar(a: Context, id: String, pr: JSONObject) {
        quitarVista()
        cerrarAlTerminar = false
        val v = LayoutInflater.from(a).inflate(R.layout.tarjeta, null)
        val ex = pr.optJSONObject("extra")
        val llamar = ex != null && ex.optString("tipo") == "llamar"
        val ops = ex?.optJSONArray("opciones")
        val lista = llamar && ops != null && ops.length() > 1
        val n = ex?.optInt("n", 1) ?: 1
        val tNombre = v.findViewById<TextView>(R.id.tkNombre)
        val tNumero = v.findViewById<TextView>(R.id.tkNumero)
        val tOpcion = v.findViewById<TextView>(R.id.tkOpcion)
        val contLista = v.findViewById<LinearLayout>(R.id.tkLista)
        barra = v.findViewById<ProgressBar>(R.id.tkBarra)

        v.findViewById<TextView>(R.id.tkTitulo).text =
            if (lista) "¿A QUIÉN LLAMO?" else if (llamar) "CONFIRMAR LLAMADA" else "MOON PREGUNTA"
        if (lista && ops != null) {
            tNombre.visibility = View.GONE; tNumero.visibility = View.GONE
            contLista.visibility = View.VISIBLE
            for (i in 0 until ops.length()) contLista.addView(fila(a, id, ops.getJSONObject(i), contLista))
            v.findViewById<View>(R.id.tkBotones).visibility = View.GONE
            v.findViewById<TextView>(R.id.tkNadie).apply {
                visibility = View.VISIBLE
                setOnClickListener { alNo(a, id, null, "nadie") }
            }
            v.findViewById<TextView>(R.id.tkHint).text = "Por voz: «sí» llama al primero, «no» pasa al siguiente"
        } else {
            tNombre.text = if (llamar) (ex?.optString("nombre") ?: "") else pr.optString("texto")
            tNombre.textSize = if (llamar) 26f else 20f
            tOpcion.visibility = if (llamar && n > 1) View.VISIBLE else View.GONE
            tOpcion.text = "Opción ${ex?.optInt("k", 1) ?: 1} de $n"
            tNumero.visibility = if (llamar) View.VISIBLE else View.GONE
            tNumero.text = ""
            if (llamar && ex != null) numero(a, id, ex.optString("cid"), tNumero)
            v.findViewById<View>(R.id.tkSi).setOnClickListener { alSi(a, id, null) }
            v.findViewById<View>(R.id.tkNo).setOnClickListener { alNo(a, id, null, "no") }
        }

        val w = WindowManager.LayoutParams(
            minOf(a.resources.displayMetrics.widthPixels - dp(a, 48), dp(a, 420)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val m = a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            m.addView(v, w)
        } catch (e: Exception) { return }                 // permiso retirado justo ahora: no se dibuja
        wm = m; raiz = v; pregId = id
        if (!activa) { activa = true; avisar(MOSTRAR) }
    }

    private fun fila(a: Context, id: String, o: JSONObject, lista: LinearLayout): View {
        val cid = o.optString("cid")
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(a, 8), 0, dp(a, 8))
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(a).apply {
            text = o.optString("nombre"); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(a.getColor(R.color.moon_text))
        })
        val num = TextView(a).apply { textSize = 14f; setTextColor(a.getColor(R.color.moon_accent)) }
        col.addView(num)
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(boton(a, "✕", R.drawable.btn_no, a.getColor(R.color.moon_bad)) { alDescartar(a, id, cid, r, lista) },
            LinearLayout.LayoutParams(dp(a, 52), dp(a, 48)).apply { marginEnd = dp(a, 8) })
        r.addView(boton(a, "✓", R.drawable.btn_si, 0xFF06281B.toInt()) { alSi(a, id, cid) },
            LinearLayout.LayoutParams(dp(a, 52), dp(a, 48)))
        numero(a, id, cid, num)
        return r
    }

    private fun boton(a: Context, t: String, fondo: Int, color: Int, accion: () -> Unit) = TextView(a).apply {
        text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(color)
        gravity = Gravity.CENTER; setBackgroundResource(fondo); setOnClickListener { accion() }
    }

    private fun numero(a: Context, id: String, cid: String, destino: TextView) {
        Thread {
            val n = Contactos.numeroLegible(a, cid)
            ui.post { if (pregId == id) destino.text = n ?: "Número no disponible" }
        }.start()
    }

    private fun enviar(a: Context, r: String, id: String, cid: String?) {
        val cuerpo = JSONObject().put("r", r).put("id", id)
        if (cid != null) cuerpo.put("cid", cid)
        Thread { Api.call(a, "/responder", "POST", 3000, cuerpo.toString()) }.start()
    }

    /** ✓: la tarjeta se va y el overlay de escucha vuelve a verse para ejecutar la orden (llamar, etc.). */
    private fun alSi(a: Context, id: String, cid: String?) {
        pregIgnorada = id
        terminar(false)
        enviar(a, "si", id, cid)
    }

    /** ✕ de abajo o «No llamar a nadie»: la tarjeta se va; si el servidor no abre otra pregunta, se oculta todo. */
    private fun alNo(a: Context, id: String, cid: String?, r: String) {
        pregIgnorada = id
        quitarVista()
        cerrarAlTerminar = true
        enviar(a, r, id, cid)
    }

    /** ✕ de una fila: quita solo esa opción. Si era la última, es un «No» a todo. */
    private fun alDescartar(a: Context, id: String, cid: String, fila: View, lista: LinearLayout) {
        lista.removeView(fila)
        enviar(a, "no", id, cid)
        if (lista.childCount == 0) { pregIgnorada = id; quitarVista(); cerrarAlTerminar = true }
    }
}
