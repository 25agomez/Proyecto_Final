"""Operating day simulator (simulators/day_sim.py).

Simulates an entire shift of port yard operations with a fixed seed:
- Truck arrivals with appointments and OCR events (plates & ISO 6346 BIC containers)
- Automatic gate reconciliation and review task resolution
- Stacking recommendations via ETD-aware optimizer
- Container egress with appointment matching
- Complete visit log state machine lifecycle
"""

from __future__ import annotations

import argparse
import json
import random
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import UTC, datetime
from typing import Any

DEFAULT_YARD_ID = "11111111-1111-7111-8111-111111111111"
DEFAULT_GATE_IN = "33333333-3333-7333-8333-333333333331"
DEFAULT_GATE_OUT = "33333333-3333-7333-8333-333333333332"

ISO_VALUES = {
    "A": 10,
    "B": 12,
    "C": 13,
    "D": 14,
    "E": 15,
    "F": 16,
    "G": 17,
    "H": 18,
    "I": 19,
    "J": 20,
    "K": 21,
    "L": 23,
    "M": 24,
    "N": 25,
    "O": 26,
    "P": 27,
    "Q": 28,
    "R": 29,
    "S": 30,
    "T": 31,
    "U": 32,
    "V": 34,
    "W": 35,
    "X": 36,
    "Y": 37,
    "Z": 38,
}


def compute_iso_check_digit(owner_and_serial: str) -> int:
    """Calculates ISO 6346 check digit (sum of val * 2^i mod 11 mod 10)."""
    total = 0
    for idx, char in enumerate(owner_and_serial[:10]):
        val = ISO_VALUES[char] if char.isalpha() else int(char)
        total += val * (2**idx)
    rem = total % 11
    return 0 if rem == 10 else rem


def generate_valid_bic(owner_code: str, serial: int) -> str:
    prefix = f"{owner_code}U{serial:06d}"
    check = compute_iso_check_digit(prefix)
    return f"{prefix}{check}"


class ApiClient:
    """Lightweight HTTP client using standard urllib."""

    def __init__(self, base_url: str, token: str = "") -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(
        self,
        method: str,
        path: str,
        data: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, Any] | list[Any] | str]:
        url = f"{self.base_url}{path}"
        req_headers = {"Content-Type": "application/json"}
        if self.token:
            req_headers["Authorization"] = f"Bearer {self.token}"
        if headers:
            req_headers.update(headers)

        body = json.dumps(data).encode("utf-8") if data is not None else None
        req = urllib.request.Request(url, data=body, headers=req_headers, method=method)

        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                status = resp.status
                raw = resp.read().decode("utf-8")
                try:
                    return status, json.loads(raw)
                except json.JSONDecodeError:
                    return status, raw
        except urllib.error.HTTPError as error:
            err_body = error.read().decode("utf-8")
            try:
                return error.code, json.loads(err_body)
            except (json.JSONDecodeError, UnicodeDecodeError):
                return error.code, err_body
        except (urllib.error.URLError, OSError, TimeoutError) as ex:
            return 0, str(ex)


class DaySimulator:
    """Coordinates and executes the simulated day."""

    def __init__(
        self,
        base_url: str,
        seed: int = 2026,
        speed: int = 10,
        dry_run: bool = False,
        operations: int = 15,
        yard_id: str = DEFAULT_YARD_ID,
        gate_in: str = DEFAULT_GATE_IN,
        gate_out: str = DEFAULT_GATE_OUT,
    ) -> None:
        self.base_url = base_url
        self.seed = seed
        self.speed = speed
        self.dry_run = dry_run
        self.operations = operations
        self.yard_id = yard_id
        self.gate_in = gate_in
        self.gate_out = gate_out
        self.rng = random.Random(seed)
        self.client = ApiClient(base_url)
        self.supervisor_client = ApiClient(base_url)
        self.metrics: dict[str, Any] = {
            "total_visits": 0,
            "auto_approved": 0,
            "required_review": 0,
            "rejected": 0,
            "stacking_plans": 0,
            "completed_visits": 0,
        }

    def log(self, msg: str, color: str = "") -> None:
        reset = "\033[0m" if color else ""
        print(f"{color}{msg}{reset}", flush=True)

    def login(self) -> bool:
        if self.dry_run:
            self.log("[DRY-RUN] Usando tokens simulados", "\033[1;33m")
            return True

        status, resp = self.client.request(
            "POST", "/api/v1/auth/token?username=operador&password=operador"
        )
        if status != 200 or not isinstance(resp, dict):
            self.log(
                f"Error al autenticar operador: HTTP {status} - {resp}", "\033[0;31m"
            )
            return False
        self.client.token = resp.get("access_token", "")

        status, resp_sup = self.supervisor_client.request(
            "POST", "/api/v1/auth/token?username=supervisor&password=supervisor"
        )
        if status == 200 and isinstance(resp_sup, dict):
            self.supervisor_client.token = resp_sup.get("access_token", "")
        return True

    def run(self) -> dict[str, Any]:
        self.log(
            "\n======================================================================",
            "\033[0;34m",
        )
        self.log(
            "  LogisticaPortuaria CR — Simulador de Jornada Operativa (DaySim)",
            "\033[0;34m",
        )
        self.log(
            f"  Semilla: {self.seed} | Aceleración: x{self.speed} | Operaciones: {self.operations}",
            "\033[0;34m",
        )
        self.log(
            "======================================================================\n",
            "\033[0;34m",
        )

        if not self.login():
            self.log(
                "Falló la autenticación; asegúrese de que el backend corra con perfil 'dev'.",
                "\033[0;31m",
            )
            return self.metrics

        carrier_codes = ["CRLOG", "MAERSK", "HAPAG", "ONE"]
        owners = ["CSQ", "MSK", "HLX", "CMA"]

        # Run simulated trucks arriving throughout the day
        for i in range(1, self.operations + 1):
            op_type = "INGRESO" if i % 4 != 0 else "RETIRO"
            truck_num = 1000 + i
            plate = f"P{truck_num:06d}"
            carrier_code = self.rng.choice(carrier_codes)
            owner = self.rng.choice(owners)
            bic_serial = 300000 + i * 17
            bic_code = generate_valid_bic(owner, bic_serial)
            now_iso = (
                datetime.now(UTC)
                .replace(microsecond=0)
                .isoformat()
                .replace("+00:00", "Z")
            )

            self.log(
                f"\n--- [Operación #{i}/{self.operations}] {op_type} | Placa: {plate} | Contenedor: {bic_code} ---",
                "\033[1;36m",
            )

            if self.dry_run:
                self.metrics["total_visits"] += 1
                if i % 5 == 0:
                    self.metrics["required_review"] += 1
                    self.log(
                        "  [Simulación] Lectura requiere revisión (camión sin cita previa)",
                        "\033[1;33m",
                    )
                else:
                    self.metrics["auto_approved"] += 1
                    self.metrics["stacking_plans"] += 1
                    self.metrics["completed_visits"] += 1
                    self.log(
                        "  [Simulación] Visita aprobada, slot asignado, salida completada",
                        "\033[0;32m",
                    )
                continue

            # 1. Crear transportista, chofer y camión
            carrier_id = str(uuid.uuid4())
            self.supervisor_client.request(
                "POST",
                "/api/v1/carriers",
                {
                    "code": f"{carrier_code}-{i}",
                    "name": f"Transportes {carrier_code} {i}",
                    "taxId": f"3-101-{i:06d}",
                },
            )

            _, drv_resp = self.supervisor_client.request(
                "POST",
                "/api/v1/drivers",
                {
                    "fullName": f"Conductor {plate}",
                    "licenseId": f"LIC-{plate}",
                    "phone": "8888-0000",
                },
            )
            driver_id = (
                drv_resp.get("id") if isinstance(drv_resp, dict) else str(uuid.uuid4())
            )

            _, trk_resp = self.supervisor_client.request(
                "POST",
                "/api/v1/trucks",
                {"plate": plate, "carrierId": carrier_id, "driverId": driver_id},
            )
            truck_id = trk_resp.get("id") if isinstance(trk_resp, dict) else None

            # Caso especial: Camión sin cita (1 de cada 5)
            has_appointment = i % 5 != 0
            if has_appointment and truck_id:
                gate = self.gate_in if op_type == "INGRESO" else self.gate_out
                self.supervisor_client.request(
                    "POST",
                    "/api/v1/appointments",
                    {
                        "yardId": self.yard_id,
                        "gateId": gate,
                        "truckId": truck_id,
                        "appointmentType": op_type,
                        "scheduledAt": now_iso,
                        "windowMinutes": 60,
                        "containerCode": bic_code if op_type == "RETIRO" else None,
                    },
                )

            # 2. Ingesta de lectura OCR en puerta (placa)
            plate_event_id = str(uuid.uuid4())
            status, gate_resp = self.client.request(
                "POST",
                "/api/v1/gate-events",
                {
                    "eventId": plate_event_id,
                    "yardId": self.yard_id,
                    "gateId": self.gate_in,
                    "timestamp": now_iso,
                    "kind": "PLATE",
                    "rawText": plate,
                    "normalizedText": plate,
                    "confidence": 0.96,
                    "corrected": False,
                },
            )

            if status not in (200, 201) or not isinstance(gate_resp, dict):
                self.log(
                    f"  Fallo al registrar evento de placa: HTTP {status}", "\033[0;31m"
                )
                continue

            result = gate_resp.get("result", "UNKNOWN")
            visit_id = gate_resp.get("visitId")
            self.metrics["total_visits"] += 1

            if result == "AUTO_APROBADO":
                self.metrics["auto_approved"] += 1
                self.log(
                    f"  ✓ Lectura conciliada: AUTO_APROBADO (Visita: {visit_id})",
                    "\033[0;32m",
                )
            elif result == "REQUIERE_REVISION":
                self.metrics["required_review"] += 1
                self.log(
                    f"  ⚠ Lectura conciliada: REQUIERE_REVISION ({gate_resp.get('reason')})",
                    "\033[1;33m",
                )
            else:
                self.metrics["rejected"] += 1
                self.log(
                    f"  ✗ Lectura: {result} ({gate_resp.get('reason')})", "\033[0;31m"
                )

            # 3. Si la visita fue aprobada y es un ingreso, solicitar recomendación de stacking
            if visit_id and result == "AUTO_APROBADO" and op_type == "INGRESO":
                rec_status, rec_resp = self.client.request(
                    "POST",
                    "/api/v1/optimizer/recommendations",
                    {
                        "yardId": self.yard_id,
                        "containerCode": bic_code,
                        "containerType": "DRY",
                        "size": 20,
                        "etd": now_iso,
                    },
                )
                if rec_status == 200 and isinstance(rec_resp, dict):
                    top3 = rec_resp.get("recommendations", [])
                    if top3:
                        best = top3[0]
                        slot_id = best.get("slotId")
                        self.log(
                            f"  ✓ Optimizador Stacking: Slot recomendado {slot_id} (score: {best.get('totalScore', 0):.2f})",
                            "\033[0;32m",
                        )
                        self.metrics["stacking_plans"] += 1

                # 4. Transicionar visita: ENTRADA_PUERTA -> VALIDADO -> ASIGNADO -> EN_PROCESO -> SALIDA_PUERTA
                for target_state in [
                    "VALIDADO",
                    "ASIGNADO",
                    "EN_PROCESO",
                    "SALIDA_PUERTA",
                ]:
                    self.client.request(
                        "POST",
                        f"/api/v1/visits/{visit_id}/transitions",
                        {"targetState": target_state},
                    )
                self.metrics["completed_visits"] += 1
                self.log(
                    "  ✓ Ciclo de visita completado hasta SALIDA_PUERTA", "\033[0;32m"
                )

            # Pausa ajustada a la velocidad de aceleración
            if self.speed > 0:
                time.sleep(1.0 / self.speed)

        self.log(
            "\n======================================================================",
            "\033[0;34m",
        )
        self.log("  Resumen de la Jornada Simulada", "\033[0;34m")
        self.log(
            f"  - Visitas totales:       {self.metrics['total_visits']}", "\033[1;37m"
        )
        self.log(
            f"  - Auto-aprobadas:        {self.metrics['auto_approved']}", "\033[0;32m"
        )
        self.log(
            f"  - Requieren revisión:    {self.metrics['required_review']}",
            "\033[1;33m",
        )
        self.log(f"  - Rechazadas:            {self.metrics['rejected']}", "\033[0;31m")
        self.log(
            f"  - Planes de stacking:    {self.metrics['stacking_plans']}", "\033[0;32m"
        )
        self.log(
            f"  - Visitas finalizadas:   {self.metrics['completed_visits']}",
            "\033[0;32m",
        )
        self.log(
            "======================================================================\n",
            "\033[0;34m",
        )

        return self.metrics


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Simulador de jornada para LogisticaPortuaria CR"
    )
    parser.add_argument(
        "--base-url", default="http://localhost:8080", help="URL base del backend"
    )
    parser.add_argument("--seed", type=int, default=2026, help="Semilla determinista")
    parser.add_argument(
        "--speed", type=int, default=10, help="Factor de aceleración (default: 10)"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Ejecutar sin enviar peticiones HTTP reales",
    )
    parser.add_argument(
        "--count", type=int, default=12, help="Cantidad de operaciones a simular"
    )
    args = parser.parse_args()

    sim = DaySimulator(
        base_url=args.base_url,
        seed=args.seed,
        speed=args.speed,
        dry_run=args.dry_run,
        operations=args.count,
    )
    sim.run()


if __name__ == "__main__":
    main()
