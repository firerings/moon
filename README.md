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
- `GET /actividad`: últimos 40 eventos de `Download/MoonLogs/actividad.jsonl` (texto oído y órdenes, por voz o por SMS)
- `GET /sistema`: Shizuku activo y modelo de voz
- `POST /escuchar`, `POST /parar`, `POST /limpiar` (vacía el historial)

### Gesto de invocación
Franja fina en el borde inferior izquierdo. Qué la activa (toque, doble toque, mantener, deslizar), en qué direcciones (o «hacia dentro»), distancia mínima y vibración se eligen en Moon > Sistema > Activar con (defecto: toque + deslizar hacia dentro, con vibración). Alto, ancho y altura sobre el borde se cambian tocando las filas de Moon > Sistema > Zona del gesto (defecto 12 / 56 / 0 dp). Si la franja tapa una tecla del teclado, bajar el alto o el ancho; con navegación por gestos puede hacer falta subir "Altura sobre el borde".

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

## Cerebro de voz (acciones, respuestas habladas y logs)

- `acciones.py`: interpreta cada frase de Vosk. Primero `comandos.json` (frases fijas, editable), luego el NLU
  (`nlu_np.py` + `modelo_nlu.npz`, Python puro: sin numpy ni PyTorch) como respaldo. «abre X» / «cierra X» se entienden con reglas, aunque el modelo falle. Ejecuta, responde por voz (`termux-tts-speak`) y pregunta sí/no.
- Abrir apps: la app Moon envía su lista real de apps (`apps_app.json`) y abre la app pedida; si no está conectada o no
  confirma, usa Shizuku (`monkey`) como respaldo. Nombres propios extra en `apps_alias.json` (`{"nombre": ["paquete"]}`).
- Modo ahorro: con batería ≤ 20 % Moon pregunta por voz si lo activa; en modo ahorro la escucha se cierra a los 20 s de silencio.
- Logs: `Download/MoonLogs/moon_AAAA-MM-DD.jsonl`, una línea por evento (texto de Vosk, intención, confianza, acción, resultado).
- `moon.sh` arranca el servidor (idempotente). La app Moon lo llama sola (RUN_COMMAND) cuando no encuentra el servidor;
  requiere `allow-external-apps=true` en `~/.termux/termux.properties` y el permiso «Ejecutar comandos en Termux» para Moon.
- `python3 test_acciones.py` prueba el cerebro con Shizuku, voz y app simulados. `pt_a_npz.py` convierte un `.pt` nuevo a `.npz` sin PyTorch.


## Correccion en Actividad y franja configurable

**Actividad.** Tocar una tarjeta abre una hoja con lo que decidio Moon (via, intencion y confianza, accion,
resultado, ms). Botones: "Estuvo bien" y "Corregir" (escribes lo que dijiste). Si lo corregido es una app
instalada que lo oido no alcanzaba, Moon propone guardar el alias y lo escribe en `apps_alias.json` (vale al instante).
- Servidor: `GET /detalle?id=`, `POST /corregir` `{id, fb: ok|corr, oido, dije}`, `POST /alias` `{alias, pkg}`.
- Datos en `Download/MoonLogs`: `actividad.jsonl`, `correcciones.jsonl` (una linea por feedback; un alias se suma a su correccion) y eventos
  `correccion` / `alias_guardado` en el diario. Al arrancar, el servidor migra los archivos antiguos de `logs/` (quedan como `.migrado`).
  `servidor.log` y `netguard.log` siguen en `logs/` porque llevan el token y el numero autorizado.
- La hoja de detalle muestra si la entrada esta corregida (o marcada correcta) y el alias guardado; permite corregir de nuevo.
- Cada frase lleva `id` y cada evento `t` (epoch); las ordenes de la tarjeta guardan `texto`, `frase_id` y `dur_ms`.

**Franja.** Sistema > Franja del gesto: desbloquear para mover (se ve y se arrastra), ocultar (invisible pero activa, es lo normal),
alto y ancho con deslizadores (dp) y restablecer. Prefs `fr_mover`, `fr_oculto` (por defecto si), `fr_alto`, `fr_ancho`,
`fr_x`, `fr_sube`; se aplican al instante. Con la franja visible, un toque corto tambien abre el overlay.
La notificacion trae el boton "Hablar".


## Otras formas de activar (Moon > Sistema > Otras formas de activar)
- **Decir «Luna»** (interruptor, apagado por defecto): con él encendido `voz_servidor.py` corre un reconocedor Vosk aparte que solo entiende «luna»; la app consulta `GET /luna?on=1` cada segundo (latido) y abre el overlay cuando sube el contador `n`. Sin latido durante 6 s el vigilante se apaga solo. Se aparta mientras hay una escucha normal o Moon habla. Umbral de confianza `UMBRAL_LUNA` (0.6) y pausa de 3 s tras cada detección.
- **Botón del auricular** (interruptor, apagado por defecto): sesión multimedia dentro de `EdgeService`; al mantener pulsado el botón Moon vibra, dice «¿Me necesita, señor?» (o una variante) y abre el overlay al terminar de hablar. La pulsación corta se ignora.
- El servicio `EdgeService` vive mientras esté activa la franja, «Luna» o el auricular; la franja solo se dibuja si «gesto» está activo. Se necesita el permiso de superposición.
- **Auriculares Bluetooth con gesto de «asistente de voz»** (QCY, etc.): mandan el Intent `VOICE_COMMAND`, que recoge `VozActivity` (sin pantalla): Moon saluda por voz y abre el overlay. No depende del interruptor del auricular.
