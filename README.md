# netguard

Reinicia la conexión del móvil que comparte internet (modo avión ON/OFF y luego hotspot ON) cuando llega un SMS autorizado. Sin root, con Shizuku + Termux.

## Requisitos
- Termux y Termux:API (misma fuente, F-Droid)
- Shizuku activo, con `rish` exportado en `~` y `RISH_APPLICATION_ID=com.termux`
- Paquetes: `pkg install termux-api jq`
- Permiso de SMS para Termux:API (si falla: `cd ~ && ./rish -c "pm grant com.termux.api android.permission.READ_SMS"`)
- MIUI: Depuración USB (ajustes de seguridad) activada; Termux "Sin restricciones" en batería

## Archivos
- `config.sh`: número autorizado, palabra clave, pausas, intervalo
- `lib.sh`: funciones (avion_on, avion_off, hotspot_on, log)
- `reiniciar.sh`: la secuencia completa, con candado anti doble ejecución
- `escuchar_sms.sh`: vigila SMS nuevos y dispara `reiniciar.sh`
- `logs/netguard.log`: historial

## Uso
    ./reiniciar.sh                       # prueba manual
    termux-wake-lock
    nohup ./escuchar_sms.sh > /dev/null 2>&1 &   # dejar escuchando
    pkill -f escuchar_sms.sh             # detener
    cat logs/netguard.log                # ver historial

Orden: enviar por SMS, desde el número configurado, un texto que contenga la palabra clave (no distingue mayúsculas).

## Tras reiniciar el móvil
1. Conectar a una Wi-Fi y volver a iniciar Shizuku (depuración inalámbrica)
2. `termux-wake-lock` y volver a lanzar `escuchar_sms.sh`

## Problemas conocidos
- `start-softap` no funciona (falta permiso); se usa `service call tethering 4`
- Si el hotspot no enciende tras el modo avión, subir `PAUSA_RED` en `config.sh`
- El hotspot usa el nombre y la clave guardados en Ajustes

## Pendiente
- Asistente por voz en el móvil de ella
- Versión como app Kotlin
