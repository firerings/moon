# -*- coding: utf-8 -*-
"""Constantes y rutas del cerebro. Los demás módulos las leen como `cfg.NOMBRE` en el momento de usarlas,
así que cambiar un valor aquí (o en los tests) afecta a todo."""
import os

HOME = os.path.expanduser("~")
DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))     # carpeta del proyecto (la de acciones.py)
LOGDIR = os.environ.get("MOON_LOGDIR", "/storage/emulated/0/Download/MoonLogs")

UMBRAL_INTENT = 0.55        # confianza minima del NLU
ESPERA_RESPUESTA = 15       # segundos que Moon espera un si/no
SILENCIO_AUTO = None        # None = la escucha nunca se cierra por silencio (antes 20 s: se apagaba sola)
COLA_TTS = 0.9              # segundos de "oido apagado" tras hablar (evita oirse a si mismo)
ESPERA_ENTREGA = 1.5        # segundos para que la app Moon recoja una frase a decir
ESPERA_ACK = 4              # segundos que se espera a que la app Moon confirme
BATERIA_BAJA = 20           # % a partir del cual pregunta por el modo ahorro
BATERIA_OK = 35             # % a partir del cual vuelve a poder preguntar
REVISION_BATERIA = 120      # cada cuantos segundos mira la bateria
ESPERA_ORDEN_APP = 2.5      # segundos que se espera a que la app Moon ejecute linterna/bateria/llamada
UMBRAL_CONTACTO = 0.72      # parecido minimo (de sonido) para proponer un contacto
UMBRAL_DIRECTO = 0.9        # parecido a partir del cual se llama sin preguntar (si la confirmacion esta apagada)
MAX_CANDIDATOS = 3          # contactos parecidos que se proponen, uno a uno
