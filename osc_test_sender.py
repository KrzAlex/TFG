"""
osc_test_sender.py — Simulador de Mind Monitor para pruebas sin MUSE

Envía mensajes OSC a la app Android (móvil o Temi) simulando los datos
que enviaría Mind Monitor con la banda MUSE puesta.

Uso:
    pip install python-osc
    python osc_test_sender.py --ip 192.168.1.X --port 5000 --scenario stress

Escenarios disponibles (--scenario):
    stress      → beta alto, mellow bajo     → debería clasificar STRESS
    attention   → concentration alto         → debería clasificar ATTENTION
    calm        → mellow alto, beta bajo     → debería clasificar CALM
    neutral     → valores medios             → debería clasificar NEUTRAL
    sweep       → cicla por todos los estados automáticamente (por defecto)

Para encontrar la IP del móvil:
    Android → Ajustes → WiFi → (toca tu red) → Dirección IP
"""

import argparse
import time
import math
from pythonosc import udp_client

# ── Escenarios de señal EEG ───────────────────────────────────────────────────

SCENARIOS = {
    "stress": {
        "concentration": 0.4,
        "mellow":        0.2,   # mellow bajo → estrés
        "alpha":         [1.2, 1.1, 1.3, 1.2],
        "beta":          [0.8, 0.9, 0.7, 0.8],  # beta alto → estrés
        "theta":         [1.5, 1.4, 1.6, 1.5],
        "delta":         [2.0, 1.9, 2.1, 2.0],
    },
    "attention": {
        "concentration": 0.85,  # concentración alta → atención
        "mellow":        0.45,
        "alpha":         [1.0, 0.9, 1.1, 1.0],
        "beta":          [0.4, 0.5, 0.4, 0.4],
        "theta":         [1.2, 1.1, 1.3, 1.2],
        "delta":         [1.8, 1.7, 1.9, 1.8],
    },
    "calm": {
        "concentration": 0.3,
        "mellow":        0.78,  # mellow alto → calma
        "alpha":         [1.8, 1.7, 1.9, 1.8],
        "beta":          [0.25, 0.3, 0.2, 0.25],  # beta bajo → calma
        "theta":         [1.6, 1.5, 1.7, 1.6],
        "delta":         [2.2, 2.1, 2.3, 2.2],
    },
    "neutral": {
        "concentration": 0.45,
        "mellow":        0.50,
        "alpha":         [1.3, 1.2, 1.4, 1.3],
        "beta":          [0.4, 0.4, 0.4, 0.4],
        "theta":         [1.4, 1.3, 1.5, 1.4],
        "delta":         [2.0, 1.9, 2.1, 2.0],
    },
}

SWEEP_ORDER  = ["neutral", "stress", "neutral", "attention", "neutral", "calm"]
SWEEP_SECS   = 15   # segundos en cada escenario del sweep


def add_noise(value: float, amplitude: float = 0.03) -> float:
    """Añade pequeño ruido para simular señal real."""
    import random
    return max(0.0, min(1.0, value + random.uniform(-amplitude, amplitude)))


def add_noise_list(values: list, amplitude: float = 0.05) -> list:
    import random
    return [v + random.uniform(-amplitude, amplitude) for v in values]


def send_scenario(client: udp_client.SimpleUDPClient, scenario: dict) -> None:
    """Envía un ciclo de mensajes OSC para el escenario dado."""
    client.send_message("/muse/elements/concentration",
                        add_noise(scenario["concentration"]))
    client.send_message("/muse/elements/mellow",
                        add_noise(scenario["mellow"]))
    client.send_message("/muse/elements/alpha_absolute",
                        add_noise_list(scenario["alpha"]))
    client.send_message("/muse/elements/beta_absolute",
                        add_noise_list(scenario["beta"]))
    client.send_message("/muse/elements/theta_absolute",
                        add_noise_list(scenario["theta"]))
    client.send_message("/muse/elements/delta_absolute",
                        add_noise_list(scenario["delta"]))


def run_fixed(client, scenario_name: str, hz: float) -> None:
    """Envía un escenario fijo indefinidamente."""
    scenario = SCENARIOS[scenario_name]
    interval = 1.0 / hz
    print(f"▶ Enviando escenario '{scenario_name}' a {hz} Hz — Ctrl+C para parar\n")
    try:
        while True:
            send_scenario(client, scenario)
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\nDetenido.")


def run_sweep(client, hz: float) -> None:
    """Cicla por todos los escenarios, SWEEP_SECS segundos cada uno."""
    interval  = 1.0 / hz
    print(f"▶ Modo sweep — {SWEEP_SECS}s por escenario — Ctrl+C para parar\n")
    try:
        while True:
            for name in SWEEP_ORDER:
                scenario = SCENARIOS[name]
                deadline = time.time() + SWEEP_SECS
                print(f"  → Escenario: {name.upper():<10} durante {SWEEP_SECS}s")
                while time.time() < deadline:
                    send_scenario(client, scenario)
                    time.sleep(interval)
    except KeyboardInterrupt:
        print("\nDetenido.")


# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Simulador OSC de Mind Monitor para pruebas sin MUSE"
    )
    parser.add_argument("--ip",       default="127.0.0.1",
                        help="IP del dispositivo con la app (default: 127.0.0.1)")
    parser.add_argument("--port",     type=int, default=5000,
                        help="Puerto OSC (default: 5000)")
    parser.add_argument("--scenario", default="sweep",
                        choices=list(SCENARIOS.keys()) + ["sweep"],
                        help="Escenario a enviar (default: sweep)")
    parser.add_argument("--hz",       type=float, default=4.0,
                        help="Frecuencia de envío en Hz (default: 4)")
    args = parser.parse_args()

    print(f"\n{'='*50}")
    print(f"  OSC Test Sender — TFG EEG + Temi")
    print(f"  Destino : {args.ip}:{args.port}")
    print(f"  Escenario: {args.scenario}  |  Frecuencia: {args.hz} Hz")
    print(f"{'='*50}\n")

    client = udp_client.SimpleUDPClient(args.ip, args.port)

    if args.scenario == "sweep":
        run_sweep(client, args.hz)
    else:
        run_fixed(client, args.scenario, args.hz)


if __name__ == "__main__":
    main()
