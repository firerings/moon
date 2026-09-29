package com.exclusivo.moon

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val ocupado = AtomicBoolean(false)
    private var conectado = false
    private var escuchando = false
    private var seq = 0

    private lateinit var tvEstado: TextView
    private lateinit var tvHist: TextView
    private lateinit var scroll: ScrollView
    private lateinit var fab: FloatingActionButton
    private lateinit var pulso: View
    private lateinit var animPulso: ObjectAnimator
    private lateinit var vistas: List<View>
    private lateinit var tabs: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvEstado = findViewById(R.id.tvEstado)
        tvHist = findViewById(R.id.tvHist)
        scroll = findViewById(R.id.scroll)
        fab = findViewById(R.id.fab)
        pulso = findViewById(R.id.pulso)
        vistas = listOf(R.id.vInicio, R.id.vActividad, R.id.vSistema).map { findViewById<View>(it) }
        tabs = listOf(R.id.tab0, R.id.tab1, R.id.tab2).map { findViewById<TextView>(it) }
        tabs.forEachIndexed { i, t -> t.setOnClickListener { mostrar(i) } }
        mostrar(0)

        animPulso = ObjectAnimator.ofPropertyValuesHolder(
            pulso,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.6f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.6f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)
        ).apply { duration = 1200; repeatCount = ValueAnimator.INFINITE; interpolator = DecelerateInterpolator() }

        fab.setOnClickListener { alternar() }
        findViewById<View>(R.id.btnLimpiar).setOnClickListener { tvHist.text = "" }

        val prefs = getSharedPreferences("moon", 0)
        val et = findViewById<EditText>(R.id.etToken)
        et.setText(prefs.getString("token", ""))
        findViewById<View>(R.id.btnToken).setOnClickListener {
            prefs.edit().putString("token", et.text.toString().trim()).apply()
            Toast.makeText(this, "Token guardado", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnAsis).setOnClickListener {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
        findViewById<View>(R.id.btnUpd).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Firerings/moon/releases/latest")))
        }
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        findViewById<TextView>(R.id.tvVersion).text = "Versión $v"
    }

    private fun mostrar(i: Int) {
        vistas.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t -> t.setTextColor(ContextCompat.getColor(this, if (k == i) R.color.moon_accent else R.color.moon_muted)) }
    }

    private fun esAsistente(): Boolean =
        Build.VERSION.SDK_INT >= 29 && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    private fun chip(id: Int, ok: Boolean, txt: String? = null) {
        val t = findViewById<TextView>(id)
        if (txt != null) t.text = txt
        t.setTextColor(ContextCompat.getColor(this, if (ok) R.color.moon_ok else R.color.moon_muted))
    }

    override fun onResume() {
        super.onResume()
        val a = esAsistente()
        chip(R.id.chipAsis, a); chip(R.id.stAsis, a, if (a) "Activo" else "Sin activar")
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
                    val r = Api.call(this@MainActivity, "/estado?desde=$seq")
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
        Thread { Api.call(this, ruta, "POST") }.start()
    }

    private fun estado(txt: String, ok: Boolean) {
        tvEstado.text = txt
        tvEstado.setTextColor(ContextCompat.getColor(this, if (ok) R.color.moon_ok else R.color.moon_bad))
        chip(R.id.chipVoz, ok); chip(R.id.stVoz, ok, if (ok) "Conectado" else "Sin conexión")
    }

    private fun pintar(resp: String?) {
        if (resp == null) {
            conectado = false; escuchando = false
            estado("Sin conexión con Termux", false)
            actualizarBoton(); return
        }
        try {
            val j = JSONObject(resp)
            if (j.has("auth")) { conectado = false; estado("Token incorrecto", false); actualizarBoton(); return }
            conectado = true
            escuchando = j.getBoolean("escuchando")
            if (j.getInt("total") < seq) seq = 0
            val fin = j.getJSONArray("finales")
            for (i in 0 until fin.length()) {
                val f = fin.getJSONObject(i)
                seq = maxOf(seq, f.getInt("n"))
                tvHist.append("• " + f.getString("t") + "\n\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            estado(if (escuchando) "Escuchando…" else "Listo", true)
        } catch (e: Exception) { /* respuesta inesperada: se ignora hasta el próximo ciclo */ }
        actualizarBoton()
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) { if (!animPulso.isStarted) animPulso.start() } else { animPulso.cancel(); pulso.alpha = 0f }
    }
}
