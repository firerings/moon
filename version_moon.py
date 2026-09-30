# -*- coding: utf-8 -*-
"""Versión del servidor de Termux y comprobación de cambios por bajar con git.
La app Moon lee esto para sus dos semáforos (Sistema > Versión y actualizaciones).

PROTOCOLO  sube cuando cambia algo en lo que hablan app y servidor (rutas nuevas, campos obligatorios).
MIN_APP    protocolo mínimo de app que este servidor todavía entiende.
La app lleva sus dos números equivalentes (Actualizaciones.kt): si no coinciden sale en rojo con lo que hay que actualizar."""
import os
import subprocess
import time

SERVIDOR = "0.2.0"
PROTOCOLO = 2
MIN_APP = 1

DIR = os.path.dirname(os.path.abspath(__file__))


def _git(*args, timeout=10):
    """(código, salida) de `git <args>` en la carpeta del proyecto. Nunca lanza: (-1, '') si git no existe o tarda."""
    try:
        r = subprocess.run(["git", "-C", DIR] + list(args), capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "").strip()
    except (OSError, subprocess.SubprocessError):
        return -1, ""


def info():
    """Rápido y sin red: versión, protocolo y commit actual."""
    d = {"servidor": SERVIDOR, "protocolo": PROTOCOLO, "min_app": MIN_APP, "git": False, "commit": "", "rama": ""}
    rc, top = _git("rev-parse", "--show-toplevel")
    if rc != 0:
        return d
    d["git"] = True
    d["commit"] = _git("rev-parse", "--short", "HEAD")[1]
    d["rama"] = _git("rev-parse", "--abbrev-ref", "HEAD")[1]
    return d


def comprobar_remoto(espera=25):
    """Baja las novedades del remoto SIN tocar tus archivos (`git fetch`) y cuenta los commits por bajar.
    estado: ok | sin_git | sin_remoto | fallo. Si hay cambios, se bajan con `git pull` en Termux."""
    t = round(time.time(), 1)
    if _git("rev-parse", "--git-dir")[0] != 0:
        return {"estado": "sin_git", "t": t}
    if _git("rev-parse", "--abbrev-ref", "@{u}")[0] != 0:
        return {"estado": "sin_remoto", "t": t}
    if _git("fetch", "--quiet", timeout=espera)[0] != 0:
        return {"estado": "fallo", "t": t}
    rc, n = _git("rev-list", "--count", "HEAD..@{u}")
    if rc != 0 or not n.isdigit():
        return {"estado": "fallo", "t": t}
    cambios = []
    if int(n) > 0:
        cambios = [l for l in _git("log", "--pretty=%s", "-5", "HEAD..@{u}")[1].splitlines() if l][:5]
    sucio = bool(_git("status", "--porcelain", "--untracked-files=no")[1])
    return {"estado": "ok", "t": t, "atras": int(n), "cambios": cambios, "sucio": sucio}
