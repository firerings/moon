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

## Moon (app Android + servidor de voz)
Termux es el cerebro (`voz_servidor.py`, Vosk offline) y la app Kotlin es la cara (overlay, historial, estado). Se hablan por `127.0.0.1:8765` con el token de `.token`.

    python3.9 voz_servidor.py     # imprime el token; pegarlo en Moon > Sistema

API local (cabecera `X-Moon-Token`):
- `GET /estado?desde=N`: escuchando, texto parcial, frases finales, orden en curso y `act` (cambia cuando cambia el historial)
- `GET /actividad`: últimos 40 eventos de `logs/actividad.jsonl` (texto oído y órdenes, por voz o por SMS)
- `GET /sistema`: Shizuku activo y modelo de voz
- `POST /escuchar`, `POST /parar`, `POST /limpiar` (vacía el historial)

### Gesto de invocación
Franja fina en el borde inferior izquierdo: tocar y deslizar a la derecha abre el overlay. Alto, ancho y altura sobre el borde se cambian tocando las filas de Moon > Sistema > Zona del gesto (defecto 12 / 56 / 0 dp). Si la franja tapa una tecla del teclado, bajar el alto o el ancho; con navegación por gestos puede hacer falta subir "Altura sobre el borde".

### App
- Inicio: estado, texto en vivo y onda mientras escucha (igual que el overlay)
- Actividad: historial persistente (se guarda en el móvil, sobrevive a cerrar la app)
- Sistema: asistente, servidor, Shizuku, superposición, modelo, gesto y versión (consulta la última release en GitHub, máx. una vez por hora)

## Pendiente
- Verificar en el móvil el botón de hablar de la app. Causa hallada: el ScrollView de Actividad seguía visible encima de Inicio y se comía los toques del botón (corregido, falta confirmar). Si el texto en vivo de Inicio no aparece, el problema es el micrófono de Termux; probar con `curl -s -H "X-Moon-Token: $(cat .token)" 127.0.0.1:8765/estado` mientras se habla
- Gesto de invocación poco fiable (franja de 12 dp muy difícil de acertar y la barra de gestos del sistema compite). Probar primero los gestos nativos del asistente (botón de encendido, esquina); alternativas: tile de ajustes rápidos y botón en la notificación que abran la sesión del asistente. En MIUI: Moon en batería "Sin restricciones" e inicio automático, para que el servicio no se cierre
- Actualizaciones ligeras: el "cerebro" (voz_servidor.py, comandos.json, skills) se actualiza con `git pull` en Termux (solo baja lo que cambió) y el APK solo cambia cuando cambia la interfaz
- Duración de la orden en Actividad (ej. "18 s")
- Skills del asistente: registro de comandos en `comandos.json` (frase -> acción), conectividad, apps, temporizadores, consultas locales, vigilante automático con aviso por SMS
- Asistente por voz en el móvil de ella
