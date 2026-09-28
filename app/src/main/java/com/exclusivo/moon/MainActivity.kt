package com.exclusivo.moon

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val base = "http://127.0.0.1:8765"
    private val ui = Handler(Looper.getMainLooper())
    private val ocupado = AtomicBoolean(false)
    private var conectado = false
    private var escuchando = false
    private var seq = 0

    private lateinit var tvEstado: TextView
    private lateinit var tvHist: TextView
    private lateinit var tvParcial: TextView
    private lateinit var scroll: ScrollView
    private lateinit var fab: FloatingActionButton
    private lateinit var pulso: View
    private lateinit var animPulso: ObjectAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvEstado = findViewById(R.id.tvEstado)
        tvHist = findViewById(R.id.tvHist)
        tvParcial = findViewById(R.id.tvParcial)
        scroll = findViewById(R.id.scroll)
        fab = findViewById(R.id.fab)
        pulso = findViewById(R.id.pulso)

        animPulso = ObjectAnimator.ofPropertyValuesHolder(
            pulso,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.6f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.6f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)
        ).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }

        fab.setOnClickListener { alternar() }
        findViewById<View>(R.id.btnLimpiar).setOnClickListener { tvHist.text = "" }
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
        animPulso.cancel()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (ocupado.compareAndSet(false, true)) {
                Thread {
                    val r = llamar("/estado?desde=$seq")
                    ocupado.set(false)
                    ui.post { pintar(r) }
                }.start()
            }
            ui.postDelayed(this, 400)
        }
    }

    private fun alternar() {
        if (!conectado) {
            Toast.makeText(this, "Inicia el servidor en Termux: python3.9 voz_servidor.py", Toast.LENGTH_LONG).show()
            return
        }
        val ruta = if (escuchando) "/parar" else "/escuchar"
        Thread { llamar(ruta, "POST") }.start()
    }

    private fun llamar(ruta: String, metodo: String = "GET"): String? = try {
        val c = URL(base + ruta).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 800
        c.readTimeout = 1500
        if (metodo == "POST") {
            c.doOutput = true
            c.outputStream.close()
        }
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun pintar(resp: String?) {
        if (resp == null) {
            conectado = false
            escuchando = false
            tvEstado.text = "Sin conexión con Termux"
            tvEstado.setTextColor(ContextCompat.getColor(this, R.color.moon_bad))
            tvParcial.text = ""
            actualizarBoton()
            return
        }
        conectado = true
        try {
            val j = JSONObject(resp)
            escuchando = j.getBoolean("escuchando")
            if (j.getInt("total") < seq) seq = 0
            val fin = j.getJSONArray("finales")
            for (i in 0 until fin.length()) {
                val f = fin.getJSONObject(i)
                seq = maxOf(seq, f.getInt("n"))
                tvHist.append("• " + f.getString("t") + "\n\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            tvParcial.text = j.getString("parcial")
            tvEstado.text = if (escuchando) "Escuchando…" else "Listo"
            tvEstado.setTextColor(ContextCompat.getColor(this, R.color.moon_ok))
        } catch (e: Exception) {
            // respuesta inesperada: se ignora hasta el próximo ciclo
        }
        actualizarBoton()
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) {
            if (!animPulso.isStarted) animPulso.start()
        } else {
            animPulso.cancel()
            pulso.alpha = 0f
        }
    }
}
