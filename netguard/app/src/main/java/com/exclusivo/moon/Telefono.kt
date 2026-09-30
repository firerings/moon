package com.exclusivo.moon

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager

/** Cosas del propio móvil que Moon hace directamente, sin pasar por Termux: linterna y batería. */
object Telefono {
    fun hayLinterna(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    /** Enciende o apaga la linterna (la de la cámara trasera, o la primera con flash). No pide permisos. */
    fun linterna(ctx: Context, encender: Boolean): Boolean = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var elegida: String? = null
        for (id in cm.cameraIdList) {
            val cc = cm.getCameraCharacteristics(id)
            if (cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            if (elegida == null) elegida = id
            if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) { elegida = id; break }
        }
        val camara = elegida
        if (camara == null) false else { cm.setTorchMode(camara, encender); true }
    } catch (e: Exception) { false }

    /** Porcentaje y si está cargando, o null si no se pudo leer. */
    fun bateria(ctx: Context): Pair<Int, Boolean>? = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) null else {
            val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val estado = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (nivel < 0 || escala <= 0) null
            else Pair(nivel * 100 / escala, estado == BatteryManager.BATTERY_STATUS_CHARGING || estado == BatteryManager.BATTERY_STATUS_FULL)
        }
    } catch (e: Exception) { null }
}
