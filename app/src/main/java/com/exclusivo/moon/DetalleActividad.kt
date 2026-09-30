package com.exclusivo.moon

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject

/**
 * Hoja de detalle y corrección de una entrada de Actividad: muestra qué decidió Moon, deja marcar
 * «Estuvo bien» o corregir lo que se dijo, y propone guardar el alias de una app. [alCambiar] se llama
 * cuando se guardó algo y hay que recargar la lista.
 */
class DetalleActividad(
    private val act: Activity,
    private val kit: UiKit,
    private val ui: Handler,
    private val alCambiar: () -> Unit,
) {
    fun mostrar(o: JSONObject) {
        val id = o.optString("id")
        if (id.isEmpty()) return
        val oido = o.optString("texto")
        val esOrden = o.optString("tipo") == "orden"
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val raiz = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.sheet_bg)
            setPadding(kit.dp(20), kit.dp(18), kit.dp(20), kit.dp(18))
        }
        raiz.addView(kit.tv(if (esOrden) o.optString("nombre", "Orden") else "«$oido»", 18f, kit.c(R.color.moon_text), true))
        if (esOrden && oido.isNotEmpty())
            raiz.addView(kit.tv("Oí: «$oido»", 14f, kit.c(R.color.moon_muted)).apply { setPadding(0, kit.dp(4), 0, 0) })
        val fb = o.optString("fb")
        val dijeAnt = o.optString("dije")
        val estado = when (fb) {
            "ok" -> "✓ Marcado como correcto"
            "corr" -> "Corregido" + (if (dijeAnt.isNotEmpty()) ": dijiste «$dijeAnt»" else "") +
                (if (o.optString("alias").isNotEmpty()) "\nAlias guardado: «${o.optString("alias")}» → ${o.optString("alias_nombre")}" else "")
            else -> ""
        }
        if (estado.isNotEmpty())
            raiz.addView(kit.tv(estado, 14f, kit.c(R.color.moon_ok)).apply { setPadding(0, kit.dp(8), 0, 0) })
        val info = kit.tv("Cargando detalle…", 13f, kit.c(R.color.moon_muted)).apply { setPadding(0, kit.dp(10), 0, kit.dp(2)) }
        raiz.addView(info)
        val zona = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(zona)

        Thread {
            val r = Api.call(act, "/detalle?id=" + Uri.encode(id))
            val ev = try { JSONObject(r ?: "").optJSONObject("evento") } catch (e: Exception) { null }
            ui.post { info.text = if (ev != null) lineasDetalle(ev) else "Sin más detalle para esta entrada." }
        }.start()

        fun cuerpo(fb: String, dije: String = "") =
            JSONObject().put("id", id).put("fb", fb).put("oido", oido).put("dije", dije)

        fun ofrecerAlias(sug: JSONObject) {
            zona.removeAllViews()
            zona.addView(kit.tv("¿Recordar que «${sug.optString("alias")}» significa ${sug.optString("nombre")}?", 15f, kit.c(R.color.moon_text))
                .apply { setPadding(0, kit.dp(12), 0, 0) })
            zona.addView(kit.filaBotones(
                kit.boton("No", false) { dlg.dismiss() },
                kit.boton("Sí, recordar", true) {
                    postJson("/alias", JSONObject().put("id", id).put("alias", sug.optString("alias"))
                        .put("pkg", sug.optString("pkg")).put("nombre", sug.optString("nombre"))) { j ->
                        Toast.makeText(act, if (j != null) "Alias guardado" else "No se pudo guardar el alias", Toast.LENGTH_SHORT).show()
                        if (j != null) alCambiar()
                        dlg.dismiss()
                    }
                }))
        }

        fun formularioCorreccion() {
            zona.removeAllViews()
            val et = EditText(act).apply {
                hint = if (esOrden) "Lo que dije fue… (ej. abre telegram)" else "Lo que dije fue…"
                setTextColor(kit.c(R.color.moon_text)); setHintTextColor(kit.c(R.color.moon_muted)); textSize = 16f
                isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
                if (fb == "corr" && dijeAnt.isNotEmpty()) { setText(dijeAnt); setSelection(dijeAnt.length) }
            }
            zona.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
            zona.addView(kit.filaBotones(
                kit.boton("Cancelar", false) { dlg.dismiss() },
                kit.boton("Guardar", true) {
                    val dije = et.text.toString().trim()
                    if (dije.isEmpty()) { Toast.makeText(act, "Escribe lo que dijiste", Toast.LENGTH_SHORT).show(); return@boton }
                    postJson("/corregir", cuerpo("corr", dije)) { j ->
                        if (j == null) Toast.makeText(act, "No se pudo guardar (¿servidor?)", Toast.LENGTH_LONG).show()
                        else {
                            alCambiar()
                            val sug = j.optJSONObject("sugerencia")
                            if (sug != null) ofrecerAlias(sug) else { Toast.makeText(act, "Corrección guardada", Toast.LENGTH_SHORT).show(); dlg.dismiss() }
                        }
                    }
                }))
            et.requestFocus()
        }

        zona.addView(kit.filaBotones(
            kit.boton("✓ Estuvo bien", false) {
                postJson("/corregir", cuerpo("ok")) { j ->
                    Toast.makeText(act, if (j != null) "Anotado" else "No se pudo guardar (¿servidor?)", Toast.LENGTH_SHORT).show()
                    if (j != null) alCambiar()
                    dlg.dismiss()
                }
            },
            kit.boton(if (fb == "corr") "Corregir de nuevo" else "Corregir", true) { formularioCorreccion() }))

        val marco = FrameLayout(act).apply { setPadding(kit.dp(10), 0, kit.dp(10), kit.dp(10)); addView(raiz) }
        dlg.setContentView(marco)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
    }

    private fun lineasDetalle(ev: JSONObject): String {
        val l = mutableListOf<String>()
        val via = when (val v = ev.optString("via")) {
            "comando" -> "un comando fijo"
            "regla" -> "la regla «abre / cierra …»"
            "nlu" -> "el modelo de intenciones"
            "respuesta" -> "tu respuesta a una pregunta"
            "confirmado" -> "una confirmación"
            "ninguna" -> "nada la entendió"
            else -> v
        }
        if (via.isNotEmpty()) l.add("La decidió: $via")
        if (ev.has("intent")) {
            val cf = ev.optDouble("confianza", -1.0)
            l.add("Intención: ${ev.optString("intent")}" + (if (cf >= 0) " (${Math.round(cf * 100)} % de confianza)" else ""))
        }
        val ent = ev.optJSONObject("entidades")
        if (ent != null && ent.length() > 0) l.add("Entidades: $ent")
        if (ev.has("accion")) l.add("Acción: ${ev.optString("accion")}")
        val motivo = ev.optString("motivo")
        if (ev.has("ok")) {
            l.add(if (ev.optBoolean("ok")) "Resultado: hecho"
                  else "Resultado: no se ejecutó" + (if (motivo.isNotEmpty()) " ($motivo)" else ""))
        } else if (motivo.isNotEmpty()) l.add("Motivo: $motivo")
        if (ev.has("ms")) l.add("Tardó ${ev.optInt("ms")} ms")
        return if (l.isEmpty()) "Sin más detalle para esta entrada." else l.joinToString("\n")
    }

    private fun postJson(ruta: String, cuerpo: JSONObject, fin: (JSONObject?) -> Unit) {
        Thread {
            val r = Api.call(act, ruta, "POST", 3000, cuerpo.toString())
            val j = try { JSONObject(r ?: "") } catch (e: Exception) { null }
            ui.post { fin(if (j != null && j.optBoolean("ok")) j else null) }
        }.start()
    }
}
