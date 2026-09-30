# -*- coding: utf-8 -*-
"""Utilidades compartidas: normalizar texto y escribir el log diario."""
import datetime
import json
import os
import re
import threading
import unicodedata

from . import config as cfg

_log_lock = threading.Lock()


def norm(t):
    """minusculas, sin acentos ni signos."""
    t = unicodedata.normalize("NFKD", (t or "").lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return " ".join(re.sub(r"[^a-z0-9 ]+", " ", t).split())

def log_evento(reg):
    """Una linea JSON por evento en Download/MoonLogs/moon_AAAA-MM-DD.jsonl"""
    ahora = datetime.datetime.now()
    reg = {"ts": ahora.strftime("%H:%M:%S"), "t": round(ahora.timestamp(), 1), **reg}
    try:
        with _log_lock:
            os.makedirs(cfg.LOGDIR, exist_ok=True)
            ruta = os.path.join(cfg.LOGDIR, "moon_%s.jsonl" % ahora.strftime("%Y-%m-%d"))
            with open(ruta, "a", encoding="utf-8") as f:
                f.write(json.dumps(reg, ensure_ascii=False) + "\n")
    except OSError:
        pass


# ---------- ajustes del servidor (ajustes.json), editables desde la app Moon ----------
AJUSTES_DEF = {"confirmar_llamadas": True}


def leer_ajustes():
    """Ajustes con sus valores por defecto; lo que no esté (o esté mal) en ajustes.json se ignora."""
    out = dict(AJUSTES_DEF)
    try:
        with open(os.path.join(cfg.DIR, "ajustes.json"), encoding="utf-8") as f:
            datos = json.load(f)
        for k, v in AJUSTES_DEF.items():
            if isinstance(datos.get(k), type(v)):
                out[k] = datos[k]
    except Exception:
        pass
    return out


def guardar_ajuste(clave, valor):
    """Guarda un ajuste conocido (True si se pudo). Escritura atómica."""
    if clave not in AJUSTES_DEF or not isinstance(valor, type(AJUSTES_DEF[clave])):
        return False
    datos = leer_ajustes()
    datos[clave] = valor
    ruta = os.path.join(cfg.DIR, "ajustes.json")
    try:
        with open(ruta + ".tmp", "w", encoding="utf-8") as f:
            json.dump(datos, f, ensure_ascii=False, indent=1)
        os.replace(ruta + ".tmp", ruta)
        return True
    except OSError:
        return False
