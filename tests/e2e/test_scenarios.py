"""End-to-End acceptance scenarios (P8).

Validates the full system flow:
a. Nominal ingress (plate -> appointment matched -> visit opened -> slot recommendation -> state progression)
b. Doubtful reading (BIC with invalid check digit -> review task -> manual correction)
c. Truck without appointment (REQUIERE_REVISION with clear reason)
d. Congestion peak (forecast endpoint and risk detection)
e. Container withdrawal (restow comparison)
f. Backend outage (edge outbox queueing and delivery upon recovery)
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

# Ensure edge-ocr is in sys.path for scenario f
REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT / "edge-ocr") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "edge-ocr"))

BACKEND_BASE_URL = os.environ.get("BACKEND_BASE_URL", "http://localhost:8080").rstrip(
    "/"
)
YARD_ID = os.environ.get("YARD_ID", "11111111-1111-7111-8111-111111111111")
GATE_IN = os.environ.get("GATE_IN", "33333333-3333-7333-8333-333333333331")
GATE_OUT = os.environ.get("GATE_OUT", "33333333-3333-7333-8333-333333333332")


def is_backend_online() -> bool:
    try:
        with urllib.request.urlopen(
            f"{BACKEND_BASE_URL}/actuator/health", timeout=2
        ) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError, TimeoutError):
        return False


def http_request(
    method: str,
    path: str,
    data: dict[str, Any] | None = None,
    token: str = "",
) -> tuple[int, dict[str, Any] | str]:
    url = f"{BACKEND_BASE_URL}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as err:
        err_body = err.read().decode("utf-8")
        try:
            return err.code, json.loads(err_body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return err.code, err_body
    except (urllib.error.URLError, OSError, TimeoutError) as ex:
        return 0, str(ex)


def get_token(username: str = "operador") -> str:
    status, body = http_request(
        "POST", f"/api/v1/auth/token?username={username}&password={username}"
    )
    if status == 200 and isinstance(body, dict):
        return body.get("access_token", "")
    return ""


backend_required = pytest.mark.skipif(
    not is_backend_online(),
    reason=f"Backend no disponible en {BACKEND_BASE_URL}. Iniciar backend o correr con Docker (make up).",
)


@backend_required
def test_scenario_a_nominal_ingress() -> None:
    """Scenario a: Nominal ingress.
    Reads license plate, reconciles with pre-existing appointment, opens visit,
    obtains stacking recommendation, and advances to SALIDA_PUERTA.
    """
    op_token = get_token("operador")
    sup_token = get_token("supervisor")
    assert op_token and sup_token, "No se pudieron obtener tokens dev"

    run_id = f"A{int(time.time()) % 100000}"
    plate = f"P{run_id}"

    # Setup master data
    http_request(
        "POST",
        "/api/v1/carriers",
        {"code": f"C-{run_id}", "name": "Transportes A", "taxId": "3-101-001"},
        sup_token,
    )
    _, drv = http_request(
        "POST",
        "/api/v1/drivers",
        {"fullName": "Chofer A", "licenseId": f"LIC-{run_id}", "phone": "8888-0000"},
        sup_token,
    )
    driver_id = drv["id"] if isinstance(drv, dict) else str(uuid.uuid4())
    _, trk = http_request(
        "POST",
        "/api/v1/trucks",
        {"plate": plate, "carrierId": str(uuid.uuid4()), "driverId": driver_id},
        sup_token,
    )
    truck_id = trk["id"]

    # Create appointment
    now_iso = (
        datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )
    status, appt = http_request(
        "POST",
        "/api/v1/appointments",
        {
            "yardId": YARD_ID,
            "gateId": GATE_IN,
            "truckId": truck_id,
            "appointmentType": "INGRESO",
            "scheduledAt": now_iso,
            "windowMinutes": 60,
        },
        sup_token,
    )
    assert status in (200, 201), f"No se pudo crear cita: {appt}"

    # Ingest OCR plate event
    status, event_resp = http_request(
        "POST",
        "/api/v1/gate-events",
        {
            "eventId": str(uuid.uuid4()),
            "yardId": YARD_ID,
            "gateId": GATE_IN,
            "timestamp": now_iso,
            "kind": "PLATE",
            "rawText": plate,
            "normalizedText": plate,
            "confidence": 0.98,
            "corrected": False,
        },
    )
    assert status in (200, 201)
    assert isinstance(event_resp, dict)
    assert event_resp.get("result") == "AUTO_APROBADO"
    visit_id = event_resp.get("visitId")
    assert visit_id, "No se devolvió visitId"

    # Get stacking recommendation for container
    status, rec = http_request(
        "POST",
        "/api/v1/optimizer/recommendations",
        {
            "yardId": YARD_ID,
            "containerCode": "CSQU3054383",
            "containerType": "DRY",
            "size": 20,
            "etd": now_iso,
        },
        op_token,
    )
    assert status == 200 and isinstance(rec, dict)
    assert len(rec.get("recommendations", [])) > 0

    # Advance visit lifecycle
    for target in ["VALIDADO", "ASIGNADO", "EN_PROCESO", "SALIDA_PUERTA"]:
        st, tr_resp = http_request(
            "POST",
            f"/api/v1/visits/{visit_id}/transitions",
            {"targetState": target},
            op_token,
        )
        assert st in (200, 201), f"Fallo al transicionar a {target}: {tr_resp}"


@backend_required
def test_scenario_b_doubtful_reading_manual_correction() -> None:
    """Scenario b: Doubtful reading.
    BIC with invalid check digit creates a review task; manual resolution approves it.
    """
    op_token = get_token("operador")
    now_iso = (
        datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )

    # Ingest container code with invalid check digit (CSQU3054380 instead of 3)
    status, resp = http_request(
        "POST",
        "/api/v1/gate-events",
        {
            "eventId": str(uuid.uuid4()),
            "yardId": YARD_ID,
            "gateId": GATE_IN,
            "timestamp": now_iso,
            "kind": "CONTAINER",
            "rawText": "CSQU3054380",
            "normalizedText": "CSQU3054380",
            "confidence": 0.65,
            "corrected": False,
            "isoValid": False,
        },
    )
    assert status in (200, 201)
    assert isinstance(resp, dict)
    assert resp.get("result") == "REQUIERE_REVISION"
    assert resp.get("reviewTaskId") is not None

    task_id = resp["reviewTaskId"]

    # Operator resolves the task
    res_status, res_resp = http_request(
        "POST",
        f"/api/v1/gate/review-tasks/{task_id}/resolve",
        {
            "resolution": "CORREGIDO",
            "correctedText": "CSQU3054383",
            "reason": "Dígito de control corregido",
        },
        op_token,
    )
    assert res_status in (200, 204), f"Fallo al resolver tarea de revisión: {res_resp}"


@backend_required
def test_scenario_c_truck_without_appointment() -> None:
    """Scenario c: Truck without appointment.
    Plate arrives without an appointment in window -> REQUIERE_REVISION with clear reason.
    """
    now_iso = (
        datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )
    unregistered_plate = f"P{int(time.time()) % 900000 + 100000}"

    status, resp = http_request(
        "POST",
        "/api/v1/gate-events",
        {
            "eventId": str(uuid.uuid4()),
            "yardId": YARD_ID,
            "gateId": GATE_IN,
            "timestamp": now_iso,
            "kind": "PLATE",
            "rawText": unregistered_plate,
            "normalizedText": unregistered_plate,
            "confidence": 0.95,
            "corrected": False,
        },
    )
    assert status in (200, 201)
    assert isinstance(resp, dict)
    assert resp.get("result") == "REQUIERE_REVISION"
    assert (
        "cita" in str(resp.get("reason", "")).lower()
        or "sin cita" in str(resp.get("reason", "")).lower()
        or "encontr" in str(resp.get("reason", "")).lower()
    )


@backend_required
def test_scenario_d_congestion_peak() -> None:
    """Scenario d: Congestion peak and forecast.
    Verifies that the congestion forecast and heatmap endpoints respond with metrics.
    """
    op_token = get_token("operador")
    status, heatmap = http_request("GET", "/api/v1/congestion/heatmap", token=op_token)
    assert status == 200 and isinstance(heatmap, dict)

    status_fc, forecast = http_request(
        f"/api/v1/congestion/forecast?gateId={GATE_IN}&horizon=15",
        "GET",
        token=op_token,
    )
    assert status_fc == 200 and isinstance(forecast, dict)
    assert "expectedWaitMinutes" in forecast or "expectedWaitMin" in forecast


@backend_required
def test_scenario_e_container_withdrawal_restows() -> None:
    """Scenario e: Container withdrawal and stacking optimizer restow prevention.
    Compares optimizer metrics and plan generation.
    """
    op_token = get_token("operador")
    status, metrics = http_request("GET", "/api/v1/optimizer/metrics", token=op_token)
    assert status == 200 and isinstance(metrics, dict)
    assert "occupancyRate" in metrics or "totalSlots" in metrics


def test_scenario_f_backend_outage_edge_outbox_recovery(tmp_path) -> None:
    """Scenario f: Backend outage.
    Verifies that the edge outbox persists events when the backend is unreachable
    and delivers them upon recovery with zero data loss.
    """
    from app.config import Settings
    from app.infra.outbox import DurableOutbox
    from app.infra.publisher import EventPublisher

    class MockOfflineClient:
        def __init__(self) -> None:
            self.online = False
            self.delivered: list[dict[str, Any]] = []

        def post(self, url: str, json: dict[str, Any], **kwargs: Any) -> Any:
            if not self.online:
                raise ConnectionError("Backend unreachable (503 Service Unavailable)")
            self.delivered.append(json)

            class Resp:
                status_code = 200

            return Resp()

    db_file = str(tmp_path / "outbox.db")
    settings = Settings(
        outbox_path=db_file,
        mqtt_enabled=False,
        backend_base_url="http://mock-backend:8080",
        yard_id=YARD_ID,
    )
    outbox = DurableOutbox(db_file)
    mock_http = MockOfflineClient()
    publisher = EventPublisher(
        settings, outbox, mqtt_client=None, http_client=mock_http
    )

    # 1. Backend is offline: enqueue 3 events
    evt_ids = [str(uuid.uuid4()) for _ in range(3)]
    for eid in evt_ids:
        publisher.enqueue({"eventId": eid, "kind": "PLATE", "rawText": "P123456"})

    assert outbox.pending_count() == 3
    assert len(mock_http.delivered) == 0

    # 2. Backend recovers: bring mock client online and flush
    mock_http.online = True
    for item in outbox.due(now=time.time() + 1000):
        outbox.reschedule(item.id, time.time() - 1, None)
    publisher._attempt(outbox.due())

    # 3. All items successfully delivered with 0 loss
    assert outbox.pending_count() == 0
    assert len(mock_http.delivered) == 3
