# -*- coding: utf-8 -*-
"""Convierte modelo_nlu.pt a modelo_nlu.npz SIN necesitar PyTorch (solo numpy).
Uso: python pt_a_npz.py modelo_nlu.pt modelo_nlu.npz"""
import sys, zipfile, pickle, json, collections
import numpy as np

DT = {"FloatStorage": np.float32, "DoubleStorage": np.float64,
      "LongStorage": np.int64, "IntStorage": np.int32, "HalfStorage": np.float16}


class _Storage:
    def __init__(self, dtype):
        self.dtype = dtype


def _rebuild_tensor_v2(storage, offset, size, stride, *a, **k):
    n = int(np.prod(size)) if size else 1
    flat = storage[offset:offset + n] if not isinstance(storage, _Storage) else None
    return np.lib.stride_tricks.as_strided(
        storage[offset:], shape=tuple(size),
        strides=[s * storage.itemsize for s in stride]).copy()


class _U(pickle.Unpickler):
    def __init__(self, f, zf, base):
        super().__init__(f)
        self.zf, self.base = zf, base

    def find_class(self, mod, name):
        if name in DT and mod.startswith("torch"):
            return _Storage(DT[name]) if False else DT[name]
        if name == "_rebuild_tensor_v2":
            return _rebuild_tensor_v2
        if name == "OrderedDict":
            return collections.OrderedDict
        if mod.startswith("torch"):
            return lambda *a, **k: None
        return super().find_class(mod, name)

    def persistent_load(self, pid):
        # ('storage', dtype_class, key, location, numel)
        dtype, key = pid[1], pid[2]
        raw = self.zf.read("%s/data/%s" % (self.base, key))
        return np.frombuffer(raw, dtype=dtype)


def cargar_pt(ruta):
    zf = zipfile.ZipFile(ruta)
    pkl = next(n for n in zf.namelist() if n.endswith("data.pkl"))
    base = pkl[:-len("/data.pkl")]
    return _U(zf.open(pkl), zf, base).load()


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "modelo_nlu.pt"
    dst = sys.argv[2] if len(sys.argv) > 2 else "modelo_nlu.npz"
    ck = cargar_pt(src)
    pesos = {k: np.asarray(v, dtype=np.float32) for k, v in ck["model_state"].items()}
    meta = {"word2idx": ck["word2idx"], "intent2idx": ck["intent2idx"],
            "slot2idx": ck["slot2idx"], "config": ck["config"]}
    np.savez_compressed(dst, meta=np.array(json.dumps(meta, ensure_ascii=False)), **pesos)
    print("OK ->", dst)
    for k, v in pesos.items():
        print(" ", k, v.shape)
