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
