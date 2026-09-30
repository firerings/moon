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
- `GET /estado` también trae `pregunta` (la pregunta sí/no abierta: `id`, `texto`, `restante`, `espera`, `extra`) y `POST /responder` `{r: si|no, id}` la contesta con un toque (mismo efecto que decir sí o no)
- `POST /escuchar`, `POST /parar`, `POST /limpiar` (vacía el historial)

### Gesto de invocación
Franja fina en el borde inferior izquierdo. Qué la activa (toque, doble toque, mantener, deslizar), en qué direcciones (o «hacia dentro»), distancia mínima y vibración se eligen en Moon > Sistema > Activar con (defecto: toque + deslizar hacia dentro, con vibración). Alto, ancho y altura sobre el borde se cambian tocando las filas de Moon > Sistema > Zona del gesto (defecto 12 / 56 / 0 dp). Si la franja tapa una tecla del teclado, bajar el alto o el ancho; con navegación por gestos puede hacer falta subir "Altura sobre el borde".

### App
- Inicio: estado, texto en vivo y onda mientras escucha (igual que el overlay)
- Actividad: historial persistente (se guarda en el móvil, sobrevive a cerrar la app)
- Sistema: asistente, servidor, Shizuku, superposición, modelo, gesto y versión (dos semáforos: APK contra la última release de GitHub y servidor de Termux; botón «Buscar actualizaciones»)

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

## Linterna y batería desde la APK
La app Moon enciende la linterna (`CameraManager`, sin permisos, instantánea) y lee la batería (`BatteryManager`) cuando está conectada al
servidor: `cerebro/sistema.py` le manda la acción (`linterna` / `bateria`) y espera su confirmación (`/ack`, la batería viaja en `pct` y `carg`).
Si la app no está conectada, no la recoge o no contesta en `ESPERA_ORDEN_APP` (2,5 s), cae a Termux (`termux-torch`, `termux-battery-status`).
Nota: Android apaga la linterna si el proceso de Moon muere; si se apaga sola al cerrar la app, dejar activa la franja, «Luna» o el auricular
(mantienen el servicio vivo) o volver a probar.

## Ajustes (pestaña Sistema)
Lista de categorías con subpantallas (el botón Atrás vuelve a la lista): **Asistente y servidor**, **Gesto de la franja**, **Otras formas de
activar**, **Contactos y llamadas**, **Diagnóstico** y **Versión y actualizaciones**. Código en `Ajustes.kt`; `MainActivity.kt` ya no construye la pestaña.
- **Diagnóstico:** un semáforo por componente (servidor, modelo, NLU, micrófono, Shizuku, permisos, asistente, gesto, «Luna», auricular, apps,
  contactos, llamadas, linterna, batería) y la caja **Probar frase**: escribe una frase y muestra qué decidiría Moon (vía, acción, intención,
  confianza, contacto o app resuelta) **sin ejecutar ni hablar**.
- Servidor: `GET /diagnostico` (estado de todo), `POST /probar` `{texto}`, `GET/POST /ajustes` (`confirmar_llamadas`, se guarda en `ajustes.json`).

## Contactos y llamadas por voz
- La app Moon lee los contactos con teléfono (permiso `READ_CONTACTS`, se pide en Sistema > Contactos y llamadas) y manda **solo id y nombre**
  (`POST /contactos` -> `contactos.json`, una vez por hora o con «Sincronizar ahora»). El número no sale del móvil.
- «llama a Daniel» / «llamar a mi suegro» / «marca a Beth» (`cerebro/llamadas.py`): busca por parecido de **sonido** en español (b/v, h muda, ll/y,
  c/k/qu, z/s, g/j). Pregunta «¿Llamo a …?»; si dices que no, propone el siguiente parecido (hasta 3). Al decir que sí, el servidor manda
  `llamar` con el id del contacto y la app busca el número y marca (`CALL_PHONE`).
- Alias: `contactos_alias.json` (`{"mi suegro": "id"}`), se edita a mano; cuando hay un único parecido y dices que sí, el alias se guarda solo.
  Se prefiere al contacto más llamado (`contactos_uso.json`).
- Ajuste **Preguntar antes de llamar** (por defecto sí). Sin la pregunta solo marca directo si el nombre es exacto y único.
- `python3 chequear_contactos.py` (sin argumentos) lee `contactos.json` y mide qué palabras de tus contactos conoce Vosk.
- Privacidad: `contactos*.json` y `ajustes.json` están en `.gitignore`.

## Próximos cambios acordados
- **Abrir app con nombre dudoso:** si «abre X» no llega al umbral (0,75), proponer la app más parecida por voz («No encuentro Beth. ¿Quieres abrir 1xBet?»); si dice sí, guardar el alias solo.
- **Medir el consumo de «Luna»:** batería gastada en unas horas con «Luna» encendida frente a apagada (Redmi 10A).

## Tarjeta Sí/No flotante
Cuando Moon hace una pregunta de sí/no («¿Llamo a la Pura?», modo ahorro...) sale una tarjeta en **su propia ventana**, encima de cualquier app
(Moon incluida), sin fondo oscuro y sin cerrar nada detrás. También sale para las preguntas que Moon hace solo, como la de la batería.
- **Una opción:** nombre, **número** (lo lee la app de tus contactos; el servidor solo conoce id y nombre), barra con el tiempo que queda, ✓ Sí y ✕ No.
- **Varias opciones** (hasta 4 parecidos): lista con ✓ y ✕ por fila y debajo «No llamar a nadie». ✕ en una fila quita solo esa opción.
  Por voz sigue igual: «sí» llama al primero, «no» pasa al siguiente.
- Al salir la tarjeta el overlay de escucha se oculta; con ✓ vuelve a verse para ejecutar la orden; con «No» a todo (o ✕ en la última fila) se oculta todo.
- Ajuste **Tarjeta sobre otras apps** (Sistema > Asistente y servidor, por defecto sí). Activo, la app consulta `GET /pregunta` una vez por segundo
  mientras viva el servicio de la franja (no cuenta como «app mirando» para el servidor). Apagado, solo sale con el overlay de escucha abierto.
  Necesita el permiso de superposición.
- Código: `Tarjeta.kt` y `tarjeta.xml` (ventana y sondeo), `cerebro/dialogo.py` (`vista_pregunta`, `responder_toque(r, id, cid)`; `r` = si / no / nadie),
  `cerebro/llamadas.py` (`_proponer` manda `extra.opciones`). Pruebas: bloque «lista:» de `test_acciones.py`.

## Versión y actualizaciones
Moon > Sistema > Versión y actualizaciones: dos semáforos y el botón **Buscar actualizaciones** (fuerza la consulta y muestra la hora).
- **Aplicación:** compara la versión instalada con la última release de GitHub (`Actualizaciones.kt`, sin token; el repositorio debe ser público).
  Si no puede consultar dice «No pude comprobar» (nunca «Al día») y por qué: sin conexión, límite de GitHub o sin releases públicas.
- **Servidor de Termux:** `GET /version` (versión, protocolo, commit) y `POST /git/comprobar` (hace `git fetch`, no cambia tus archivos, y cuenta los
  commits por bajar). Los cambios se bajan a mano con `git pull` en Termux y se reinicia el servidor. Código en `version_moon.py`.
- **Compatibilidad:** `PROTOCOLO`/`MIN_APP` en `version_moon.py` y `PROTOCOLO_APP`/`MIN_SERVIDOR` en `Actualizaciones.kt`. Subir el número del que cambie
  cuando app y servidor se hablen distinto; si no cuadran sale en rojo qué hay que actualizar.
- Al entrar solo se usa lo guardado (máx. una hora) y `/version`, sin red; `git fetch` y GitHub solo al pulsar el botón.

## Pendiente de verificar en el móvil (cambios de esta versión)
- El Kotlin no se compiló fuera de GitHub Actions: el primer build valida `Tarjeta.kt`, `Actualizaciones.kt` y `Ajustes.kt`.
- Probar: «llama a daniel» con varios parecidos (lista, ✓ en la fila 2, ✕, «No llamar a nadie»), la pregunta de batería sin overlay abierto, y la tarjeta encima de otra app.
- Probar «Buscar actualizaciones» con datos y sin datos (debe decir «No pude comprobar»). Si GitHub da 404: el repositorio no es público o no hay releases.
- El servidor debe estar en un repositorio git con remoto (`git branch -u origin/main`) para que el semáforo del servidor vea los cambios por bajar.
