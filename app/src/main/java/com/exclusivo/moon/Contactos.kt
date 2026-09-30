package com.exclusivo.moon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contactos y llamadas. Al servidor solo viajan id y nombre (para que entienda a quién te refieres);
 * el número se queda en el móvil: el servidor pide «llama al contacto id» y es esta app la que lo busca y marca.
 */
object Contactos {
    private var intento = 0L

    fun puedeLeer(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun puedeLlamar(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Manda id y nombre de los contactos con teléfono. Como mucho una vez por hora, salvo que se fuerce. */
    fun sincronizar(ctx: Context, forzar: Boolean): Boolean {
        if (!puedeLeer(ctx)) return false
        val ahora = System.currentTimeMillis()
        val pr = ctx.getSharedPreferences("moon", 0)
        if (!forzar) {
            if (ahora - pr.getLong("cont_ts", 0) < 3600_000) return true
            if (ahora - intento < 30_000) return false
        }
        intento = ahora
        return try {
            val arr = JSONArray()
            val cur = ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                ContactsContract.Contacts.HAS_PHONE_NUMBER + " = 1", null, null
            )
            if (cur != null) {
                cur.use { c ->
                    val iId = c.getColumnIndex(ContactsContract.Contacts._ID)
                    val iNom = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val nombre = c.getString(iNom)
                        if (nombre != null) arr.put(JSONObject().put("i", c.getLong(iId).toString()).put("n", nombre))
                    }
                }
            }
            val r = Api.call(ctx, "/contactos", "POST", 6000, arr.toString())
            val ok = r != null && !r.contains("auth") && !r.contains("error")
            if (ok) pr.edit().putLong("cont_ts", ahora).putInt("cont_n", arr.length()).apply()
            ok
        } catch (e: Exception) { false }
    }

    /** Número del contacto [cid]: el principal si lo hay, si no el primero. */
    private fun numero(ctx: Context, cid: String): String? {
        var primero: String? = null
        var principal: String? = null
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_PRIMARY),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", arrayOf(cid), null
        )
        if (cur != null) {
            cur.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (primero == null) primero = n
                    if (principal == null && c.getInt(1) != 0) principal = n
                }
            }
        }
        return principal ?: primero
    }

    /** Marca al contacto [cid]. false si falta algún permiso, no hay número o el sistema no deja marcar. */
    fun llamar(ctx: Context, cid: String): Boolean {
        if (cid.isEmpty() || !puedeLeer(ctx) || !puedeLlamar(ctx)) return false
        return try {
            val n = numero(ctx, cid)
            if (n == null) false else {
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n)))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                true
            }
        } catch (e: Exception) { false }
    }
}
