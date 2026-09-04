"""
Análisis de las bandas EEG registradas — justificación empírica del denominador.

Responde a una pregunta concreta: ¿qué pasaría si la banda delta entrase en el
cálculo de los índices, en lugar de quedarse solo registrada?

El sistema normaliza cada índice sobre la potencia total de α+β+θ+γ. Delta se
recibe y se guarda en el CSV, pero no entra en ese total. Este script mide qué
efecto tendría incluirla, usando las sesiones ya grabadas.

Uso:
    python analisis_bandas.py                # analiza todo files/*.csv
    python analisis_bandas.py ruta/a/csv     # o los ficheros que se le indiquen

Salida: potencia media por banda, fracción del total que absorbería delta y la
d de Cohen del índice mellow entre pares de estados, con y sin delta. La d de
Cohen mide cuántas desviaciones típicas separan dos grupos: cuanto mayor, más
distinguibles son los estados y mejor clasifica el sistema.
"""
import csv
import glob
import math
import statistics as st
import sys

BANDAS = ("alpha", "beta", "theta", "delta", "gamma")


def cargar(paths):
    """Lee las filas válidas de los CSV, saltando la cabecera '#' del formato v2."""
    filas = []
    for ruta in paths:
        try:
            with open(ruta, encoding="utf-8", errors="replace") as fh:
                lector = csv.DictReader(l for l in fh if not l.startswith("#"))
                for r in lector:
                    try:
                        v = {b: float(r[b]) for b in BANDAS}
                    except (ValueError, KeyError, TypeError):
                        continue
                    # Muestras anteriores a recibir EEG: todas las bandas a cero
                    if all(x == 0 for x in v.values()):
                        continue
                    v["state"] = r.get("state", "")
                    filas.append(v)
        except OSError as e:
            print(f"  aviso: no se pudo leer {ruta} ({e})")
    return filas


def lineal(log_potencia):
    """Las bandas llegan en log10-potencia; los índices se calculan en lineal."""
    return 10 ** log_potencia if log_potencia > 0 else 0.0


def mellow(v, con_delta):
    """Índice de calma: proporción de alpha sobre el total de bandas."""
    lin = {b: lineal(v[b]) for b in BANDAS}
    total = lin["alpha"] + lin["beta"] + lin["theta"] + lin["gamma"]
    if con_delta:
        total += lin["delta"]
    return lin["alpha"] / total if total > 0 else 0.0


def cohen_d(a, b):
    """Separación entre dos grupos en desviaciones típicas."""
    if len(a) < 2 or len(b) < 2:
        return 0.0
    s = math.sqrt(
        ((len(a) - 1) * st.pstdev(a) ** 2 + (len(b) - 1) * st.pstdev(b) ** 2)
        / (len(a) + len(b) - 2)
    )
    return abs(st.mean(a) - st.mean(b)) / s if s > 0 else 0.0


def main():
    paths = sys.argv[1:] or sorted(glob.glob("files/*.csv"))
    filas = cargar(paths)
    if not filas:
        print("No se encontraron muestras válidas.")
        return

    print(f"Muestras analizadas: {len(filas)}  ({len(paths)} ficheros)\n")

    print("Potencia por banda (log10)")
    print(f"  {'banda':<8}{'media':>9}{'desv':>9}{'min':>9}{'max':>9}")
    for b in BANDAS:
        d = [v[b] for v in filas]
        print(f"  {b:<8}{st.mean(d):>9.3f}{st.pstdev(d):>9.3f}{min(d):>9.3f}{max(d):>9.3f}")

    fracciones = []
    for v in filas:
        lin = {b: lineal(v[b]) for b in BANDAS}
        total = sum(lin.values())
        if total > 0:
            fracciones.append(lin["delta"] / total)
    print(
        f"\nSi delta entrase en el denominador absorbería el "
        f"{100 * st.mean(fracciones):.1f} % del total de potencia (media)."
    )

    print("\nSeparación entre estados con el índice mellow (d de Cohen)")
    print(f"  {'comparación':<24}{'actual':>9}{'con delta':>11}{'cambio':>9}")
    pares = (("CALM", "ATTENTION"), ("CALM", "NEUTRAL"), ("NEUTRAL", "ATTENTION"))
    for A, B in pares:
        ds = []
        for con_delta in (False, True):
            ga = [mellow(v, con_delta) for v in filas if v["state"] == A]
            gb = [mellow(v, con_delta) for v in filas if v["state"] == B]
            ds.append(cohen_d(ga, gb))
        if ds[0] == 0:
            continue
        cambio = 100 * (ds[1] - ds[0]) / ds[0]
        print(f"  {A + ' vs ' + B:<24}{ds[0]:>9.2f}{ds[1]:>11.2f}{cambio:>8.0f}%")

    print(
        "\nUna d más baja significa estados menos distinguibles: incluir delta\n"
        "empeora la clasificación en lugar de mejorarla."
    )


if __name__ == "__main__":
    main()
