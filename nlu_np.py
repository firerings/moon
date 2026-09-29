# -*- coding: utf-8 -*-
"""
Modulo NLU (Python puro, sin numpy ni PyTorch): INTENT CLASSIFICATION + SLOT FILLING.

Uso:
    from nlu import NLU
    modelo = NLU("modelo_nlu.npz")
    resultado = modelo.procesar("mandale a la pura que te amo")
    # {
    #   'intent': 'enviar_mensaje',
    #   'confianza': 0.97,
    #   'tokens': ['mandale', 'a', 'la', 'pura', 'que', 'te', 'amo'],
    #   'slots': ['O', 'O', 'B-CONTACTO', 'I-CONTACTO', 'O', 'B-MENSAJE', 'I-MENSAJE']
    # }
"""
import array
import ast
import json
import math
import struct
import zipfile
from operator import mul

PAD_TOKEN = "<pad>"
UNK_TOKEN = "<unk>"
CALL_INTENT_KEYWORDS = {"llama", "llamame", "llamá", "llamen", "marca", "llamada", "llamar"}
CANAL_TOKENS = {"whatsapp"}


_PUNT = str.maketrans({c: " " for c in "¿?¡!,.;:\"()"})


def tokenize(texto):
    return texto.lower().translate(_PUNT).split()


def _sin_acentos(t):
    import unicodedata
    return "".join(c for c in unicodedata.normalize("NFKD", t) if not unicodedata.combining(c))


def _es_verbode_llamada(tokens):
    return any(token in CALL_INTENT_KEYWORDS for token in tokens)


def _normalizar_tags_bio(tags):
    """Corrige etiquetas I-* que no tienen un bloque B-* válido anterior."""
    normalizados = []
    tipo_actual = None

    for tag in tags:
        if tag.startswith("B-"):
            tipo_actual = tag[2:]
            normalizados.append(tag)
        elif tag.startswith("I-"):
            tipo = tag[2:]
            if tipo_actual != tipo:
                normalizados.append(f"B-{tipo}")
                tipo_actual = tipo
            else:
                normalizados.append(tag)
        else:
            normalizados.append(tag)
            tipo_actual = None

    return normalizados


def _extraer_entidades_slots(tokens, tags):
    """Convierte bloques BIO contiguos en entidades de texto estructuradas."""
    entidades = {"app": [], "contacto": [], "mensaje": [], "canal": []}
    tipo_actual = None
    tokens_actuales = []

    def guardar_entidad():
        if tipo_actual and tokens_actuales:
            entidades[tipo_actual].append(" ".join(tokens_actuales))

    for token, tag in zip(tokens, tags):
        if tag.startswith("B-"):
            guardar_entidad()
            tipo_actual = tag[2:].lower()
            tokens_actuales = [token]
        elif tag.startswith("I-") and tipo_actual == tag[2:].lower():
            tokens_actuales.append(token)
        else:
            guardar_entidad()
            tipo_actual = None
            tokens_actuales = []

    guardar_entidad()
    return entidades


def _sig(x):
    return 1.0 / (1.0 + math.exp(-x)) if x > -30.0 else 0.0


def _leer_npy(datos):
    """Lee un .npy (float32 o texto) sin numpy. Devuelve (forma, valores | texto)."""
    if datos[:6] != b"\x93NUMPY":
        raise ValueError("no es un .npy")
    if datos[6] == 1:
        n, ini = struct.unpack("<H", datos[8:10])[0], 10
    else:
        n, ini = struct.unpack("<I", datos[8:12])[0], 12
    cab = ast.literal_eval(datos[ini:ini + n].decode("latin1"))
    cuerpo = datos[ini + n:]
    if cab["descr"].startswith("<U"):
        return cab["shape"], cuerpo[:4 * int(cab["descr"][2:])].decode("utf-32-le")
    if cab["descr"] != "<f4" or cab["fortran_order"]:
        raise ValueError("formato no soportado: %s" % cab["descr"])
    v = array.array("f")
    v.frombytes(cuerpo)
    return cab["shape"], v


def _cargar_pesos(ruta):
    pesos, meta = {}, None
    with zipfile.ZipFile(ruta) as z:
        for nombre in z.namelist():
            forma, v = _leer_npy(z.read(nombre))
            clave = nombre[:-4]
            if clave == "meta":
                meta = json.loads(v)
            elif len(forma) == 2:
                c = forma[1]
                pesos[clave] = [v[i * c:(i + 1) * c].tolist() for i in range(forma[0])]
            else:
                pesos[clave] = v.tolist()
    return meta, pesos


def _matvec(filas, v):
    return [sum(map(mul, f, v)) for f in filas]


def _lstm_dir(x, w_ih, w_hh, b, reverso=False):
    """Una direccion de una LSTM de PyTorch (orden de puertas: i, f, g, o)."""
    n, h = len(x), len(w_hh[0])
    hs = [None] * n
    ht = [0.0] * h
    ct = [0.0] * h
    for t in (range(n - 1, -1, -1) if reverso else range(n)):
        a, c = _matvec(w_ih, x[t]), _matvec(w_hh, ht)
        g = [a[k] + c[k] + b[k] for k in range(4 * h)]
        ct = [_sig(g[h + k]) * ct[k] + _sig(g[k]) * math.tanh(g[2 * h + k]) for k in range(h)]
        ht = [_sig(g[3 * h + k]) * math.tanh(ct[k]) for k in range(h)]
        hs[t] = ht
    return hs, ht


def _softmax(v):
    m = max(v)
    e = [math.exp(x - m) for x in v]
    s = sum(e)
    return [x / s for x in e]


def _argmax(v):
    return max(range(len(v)), key=v.__getitem__)


class NLU:
    """Red BiLSTM (intencion + entidades) en Python puro: no necesita numpy ni PyTorch."""

    def __init__(self, ruta_modelo="modelo_nlu.npz"):
        meta, self.w = _cargar_pesos(ruta_modelo)
        self.word2idx = meta["word2idx"]
        self.intent2idx = meta["intent2idx"]
        self.slot2idx = meta["slot2idx"]
        self.idx2intent = {v: k for k, v in self.intent2idx.items()}
        self.idx2slot = {v: k for k, v in self.slot2idx.items()}

    def _forward(self, ids):
        w = self.w
        x = [w["embedding.weight"][i] for i in ids]
        bf = [p + q for p, q in zip(w["lstm.bias_ih_l0"], w["lstm.bias_hh_l0"])]
        br = [p + q for p, q in zip(w["lstm.bias_ih_l0_reverse"], w["lstm.bias_hh_l0_reverse"])]
        hf, h_fwd = _lstm_dir(x, w["lstm.weight_ih_l0"], w["lstm.weight_hh_l0"], bf)
        hb, h_bwd = _lstm_dir(x, w["lstm.weight_ih_l0_reverse"], w["lstm.weight_hh_l0_reverse"], br, True)
        oracion = h_fwd + h_bwd
        intent_logits = [s + b for s, b in zip(_matvec(w["intent_head.weight"], oracion), w["intent_head.bias"])]
        slot_logits = [[s + b for s, b in zip(_matvec(w["slot_head.weight"], hf[t] + hb[t]), w["slot_head.bias"])]
                       for t in range(len(ids))]
        return intent_logits, slot_logits

    def procesar(self, texto):
        tokens = tokenize(texto)
        if not tokens:
            return {"intent": "conversar", "confianza": 0.0, "tokens": [], "slots": []}

        ids = [self.word2idx.get(t, self.word2idx.get(_sin_acentos(t), self.word2idx[UNK_TOKEN])) for t in tokens]
        intent_logits, slot_logits = self._forward(ids)
        intent_probs = _softmax(intent_logits)
        intent_idx = _argmax(intent_probs)
        intent_conf = float(intent_probs[intent_idx])
        slot_tags = [self.idx2slot[_argmax(f)] for f in slot_logits]
        slot_tags = _normalizar_tags_bio(slot_tags)

        result = {
            "intent": self.idx2intent[intent_idx],
            "confianza": round(intent_conf, 4),
            "tokens": tokens,
            "slots": slot_tags,
        }

        entidades = _extraer_entidades_slots(tokens, slot_tags)
        for tipo, valores in entidades.items():
            if valores:
                result[tipo] = valores[0]

        canales = []
        canal_actual = []
        for token, tag in zip(tokens, slot_tags):
            if tag == "B-CANAL":
                if canal_actual:
                    canales.append(" ".join(canal_actual))
                canal_actual = [token]
            elif tag == "I-CANAL" and canal_actual:
                canal_actual.append(token)
            elif canal_actual:
                canales.append(" ".join(canal_actual))
                canal_actual = []
        if canal_actual:
            canales.append(" ".join(canal_actual))

        # Fallback sencillo para canales conocidos: permite reconocer
        # WhatsApp aunque el predictor de slots no lo etiquete correctamente.
        if not canales:
            canales = [token for token in tokens if token in CANAL_TOKENS]
        if canales:
            result["canal"] = canales[0]
            if "mensaje" in result:
                mensaje_tokens = result["mensaje"].split()
                canal = result["canal"]
                indice_canal = next(
                    (i for i, token in enumerate(mensaje_tokens) if token == canal),
                    None,
                )
                if indice_canal is not None:
                    inicio = indice_canal - 1 if indice_canal > 0 and mensaje_tokens[indice_canal - 1] == "por" else indice_canal
                    result["mensaje"] = " ".join(
                        mensaje_tokens[:inicio] + mensaje_tokens[indice_canal + 1:]
                    ).strip()

        # Fallback de llamada: si la frase claramente contiene un verbo de
        # llamada y el modelo no está muy seguro, preferimos llamar.
        if result["intent"] != "llamar" and result["confianza"] < 0.85 and _es_verbode_llamada(tokens):
            result["intent"] = "llamar"
            result["confianza"] = round(max(result["confianza"], 0.65), 4)

        # Si la intención es 'llamar', tratar el resto de la frase como
        # candidato de contacto (priorizar contacto sobre mensaje).
        if result["intent"] == "llamar":
            # buscar 'a' o 'al' como inicio del contacto
            start = None
            for i, t in enumerate(tokens):
                if t in ("a", "al"):
                    start = i + 1
                    break
            if start is None:
                # si no hay 'a', tomar desde el segundo token (tras verbo)
                start = 1 if len(tokens) > 1 else 0

            # construir candidato y sobrescribir slots para reflejar contacto
            candidato_tokens = tokens[start:]
            if candidato_tokens:
                contacto_texto = " ".join(candidato_tokens)
                new_slots = ["O"] * len(tokens)
                new_slots[start] = "B-CONTACTO"
                for j in range(start + 1, len(tokens)):
                    new_slots[j] = "I-CONTACTO"
                result["slots"] = new_slots
                result["contacto_candidato"] = contacto_texto
                result["contacto"] = contacto_texto

        return result


if __name__ == "__main__":
    nlu = NLU("modelo_nlu.npz")
    for texto in ["abre chrome", "mandale a la pura que te amo",
                  "llama a mi mama", "hola como estas", "salir"]:
        print(texto, "->", nlu.procesar(texto))
