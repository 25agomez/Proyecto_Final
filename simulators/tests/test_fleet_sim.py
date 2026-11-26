"""Tests of the fleet telemetry simulator."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import fleet_sim


def test_vehicle_count_is_bounded() -> None:
    with pytest.raises(ValueError):
        fleet_sim.FleetSimulator(vehicles=4)
    with pytest.raises(ValueError):
        fleet_sim.FleetSimulator(vehicles=51)
    with pytest.raises(ValueError):
        fleet_sim.FleetSimulator(rate=0)


def test_first_eight_ids_match_the_seed() -> None:
    assert fleet_sim.vehicle_id_for(0) == "70000000-0000-7000-8000-000000000001"
    assert fleet_sim.vehicle_id_for(7) == "70000000-0000-7000-8000-000000000008"
    ids = [fleet_sim.vehicle_id_for(index) for index in range(50)]
    assert len(set(ids)) == 50


def test_same_seed_produces_the_same_messages() -> None:
    first = fleet_sim.FleetSimulator(vehicles=7, seed=7)
    second = fleet_sim.FleetSimulator(vehicles=7, seed=7)
    first_messages = [payload for _, payload in first.messages(30)]
    second_messages = [payload for _, payload in second.messages(30)]
    assert first_messages == second_messages
    other = fleet_sim.FleetSimulator(vehicles=7, seed=8)
    assert [payload for _, payload in other.messages(30)] != first_messages


def test_payload_matches_the_backend_contract() -> None:
    simulator = fleet_sim.FleetSimulator(vehicles=5, seed=42)
    messages = [payload for _, payload in simulator.messages(3)]
    assert len(messages) == 15
    for payload in messages:
        assert set(payload).issuperset(
            {
                "vehicleId",
                "ts",
                "lat",
                "lon",
                "speedKmh",
                "headingDeg",
                "batteryPct",
                "status",
            }
        )
        assert payload["status"] in (
            "LIBRE",
            "EN_MOVIMIENTO",
            "CARGANDO",
            "DESCARGANDO",
            "AVERIA",
        )
        assert -90 <= payload["lat"] <= 90
        assert -180 <= payload["lon"] <= 180
        assert payload["speedKmh"] >= 0
        assert 0 <= payload["batteryPct"] <= 100
        assert fleet_sim.x_of(payload["lon"]) >= 0
        assert fleet_sim.y_of(payload["lat"]) >= 0


def test_normal_movement_is_plausible() -> None:
    simulator = fleet_sim.FleetSimulator(vehicles=5, seed=42)
    previous: dict[str, tuple[float, float]] = {}
    checked = 0
    for _, payload in simulator.messages(17):
        point = (fleet_sim.x_of(payload["lon"]), fleet_sim.y_of(payload["lat"]))
        last = previous.get(payload["vehicleId"])
        if last is not None and payload["status"] != "AVERIA":
            assert math.dist(last, point) < 15.0
            checked += 1
        previous[payload["vehicleId"]] = point
    assert checked > 40


def test_every_fault_type_is_injected() -> None:
    simulator = fleet_sim.FleetSimulator(vehicles=8, seed=42)
    seen: set[str] = set()
    for _ in range(130):
        simulator.step()
        seen.update(vehicle.fault for vehicle in simulator.fleet if vehicle.fault)
    assert {"battery", "jump", "forbidden", "speed", "breakdown"} <= seen
    # Silence does not set a fault; it skips messages of one vehicle.
    assert any(
        vehicle.silence_ticks > 0 or vehicle.sent < simulator.tick
        for vehicle in simulator.fleet
    )


def test_topic_format() -> None:
    assert (
        fleet_sim.topic_for("abc")
        == "yard/" + fleet_sim.DEFAULT_YARD_ID + "/fleet/abc/telemetry"
    )


def test_dry_run_prints_json(capsys: pytest.CaptureFixture[str]) -> None:
    assert fleet_sim.main(["--vehicles", "5", "--ticks", "2", "--dry-run"]) == 0
    out = capsys.readouterr()
    lines = [line for line in out.out.splitlines() if line.strip()]
    assert len(lines) == 10
    for line in lines:
        message = json.loads(line)
        assert message["topic"].endswith("/telemetry")
        assert message["payload"]["vehicleId"]
