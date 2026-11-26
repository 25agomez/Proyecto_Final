"""Fleet telemetry simulator for the demo yard.

Publishes MQTT telemetry for 5-50 vehicles that drive along the aisles of the yard,
with injected faults so the backend alerts can be demonstrated: GPS jumps, low
battery, forbidden-zone entries, speeding, breakdowns and loss of signal.

The simulator is deterministic: the same ``--seed`` produces the same sequence of
messages, and the virtual clock advances one tick per ``--rate`` seconds.

Usage
-----
    python simulators/fleet_sim.py --vehicles 20 --seed 42 --rate 1
    python simulators/fleet_sim.py --vehicles 8 --dry-run --ticks 5

Environment variables (only used when the flags are not given):
``MQTT_HOST``, ``MQTT_PORT``, ``YARD_ID``.

The payload matches ``FleetTelemetry`` of the backend and the contract
``docs/api/mqtt-contracts.md``: coordinates are WGS84 (``lat``/``lon``) and the
backend converts them to the local metric frame with ``YardFrame`` (ADR-013).
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import time
import uuid
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

# The yard frame of the backend (YardFrame.java). Keep both in sync.
ANCHOR_LAT = 9.9908
ANCHOR_LON = -83.0350
METERS_PER_DEGREE_LAT = 111_320.0
METERS_PER_DEGREE_LON = METERS_PER_DEGREE_LAT * math.cos(math.radians(ANCHOR_LAT))

DEFAULT_YARD_ID = "11111111-1111-7111-8111-111111111111"
DEFAULT_HOST = os.environ.get("MQTT_HOST", "localhost")
DEFAULT_PORT = int(os.environ.get("MQTT_PORT", "1883"))

VEHICLE_TYPES = ("MONTACARGAS", "RASTRA", "REACH_STACKER", "TIRADOR")

# Aisles of the demo yard, in meters (x east, y north). The normal route never
# enters the forbidden zone (x 200-260, y 60-100); that fault is injected.
ROUTE = (
    (60.0, 20.0),
    (140.0, 20.0),
    (200.0, 40.0),
    (300.0, 40.0),
    (360.0, 60.0),
    (360.0, 140.0),
    (200.0, 150.0),
    (120.0, 120.0),
    (60.0, 60.0),
)

FORBIDDEN_POINT = (230.0, 80.0)
VALID_CONTAINER = "CSQU3054383"

FAULT_TYPES = ("silence", "battery", "jump", "forbidden", "speed", "breakdown")


def lon_of(x: float) -> float:
    """Converts the local ``x`` (meters) to longitude."""
    return ANCHOR_LON + x / METERS_PER_DEGREE_LON


def lat_of(y: float) -> float:
    """Converts the local ``y`` (meters) to latitude."""
    return ANCHOR_LAT + y / METERS_PER_DEGREE_LAT


def x_of(lon: float) -> float:
    """Converts longitude to the local ``x`` (meters)."""
    return (lon - ANCHOR_LON) * METERS_PER_DEGREE_LON


def y_of(lat: float) -> float:
    """Converts latitude to the local ``y`` (meters)."""
    return (lat - ANCHOR_LAT) * METERS_PER_DEGREE_LAT


def vehicle_id_for(index: int) -> str:
    """Returns the identifier of the ``index`` vehicle (0-based).

    The first eight match the seeded vehicles of ``V2__seed_demo.sql`` so the
    simulator works against the demo database without registering anything.
    """
    if 0 <= index < 8:
        return f"70000000-0000-7000-8000-{index + 1:012d}"
    return str(
        uuid.uuid5(
            uuid.NAMESPACE_URL, f"logisticaportuaria/vehicle/LOAD-{index + 1:02d}"
        )
    )


def route_length(route: Sequence[tuple[float, float]]) -> float:
    """Returns the total length of the route, in meters."""
    return sum(math.dist(route[i], route[i + 1]) for i in range(len(route) - 1))


def point_at(
    route: Sequence[tuple[float, float]], distance: float
) -> tuple[float, float, float]:
    """Returns ``(x, y, heading)`` at ``distance`` meters along the route."""
    remaining = distance % route_length(route)
    for i in range(len(route) - 1):
        (x1, y1), (x2, y2) = route[i], route[i + 1]
        segment = math.dist((x1, y1), (x2, y2))
        if remaining <= segment:
            ratio = remaining / segment if segment else 0.0
            x = x1 + (x2 - x1) * ratio
            y = y1 + (y2 - y1) * ratio
            heading = math.degrees(math.atan2(y2 - y1, x2 - x1)) % 360.0
            return x, y, heading
        remaining -= segment
    x, y = route[-1]
    return x, y, 0.0


@dataclass
class SimVehicle:
    """State of one simulated vehicle."""

    index: int
    code: str
    vehicle_id: str
    vehicle_type: str
    distance: float
    speed_kmh: float
    battery: float
    status: str = "EN_MOVIMIENTO"
    silence_ticks: int = 0
    fault: str | None = None
    fault_until: int = 0
    override: tuple[float, float] | None = None
    sent: int = 0

    def __post_init__(self) -> None:
        self.distance = float(self.distance)

    def position(self) -> tuple[float, float, float]:
        if self.fault == "jump":
            return FORBIDDEN_POINT[0], FORBIDDEN_POINT[1], 0.0
        if self.override is not None:
            return self.override[0], self.override[1], 0.0
        return point_at(ROUTE, self.distance)


class FleetSimulator:
    """Deterministic generator of fleet telemetry."""

    def __init__(self, vehicles: int = 20, seed: int = 42, rate: float = 1.0) -> None:
        if not 5 <= vehicles <= 50:
            raise ValueError("vehicles must be between 5 and 50")
        if rate <= 0:
            raise ValueError("rate must be positive")
        self.vehicles = vehicles
        self.seed = seed
        self.rate = rate
        self.random = random.Random(seed)
        self.tick = 0
        self.start = datetime.now(UTC).replace(microsecond=0)
        self.fleet = [self._vehicle(i) for i in range(vehicles)]

    def _vehicle(self, index: int) -> SimVehicle:
        rng = self.random
        return SimVehicle(
            index=index,
            code=f"VEH-{index + 1:02d}",
            vehicle_id=vehicle_id_for(index),
            vehicle_type=VEHICLE_TYPES[index % len(VEHICLE_TYPES)],
            distance=route_length(ROUTE) * index / self.vehicles,
            speed_kmh=round(rng.uniform(4.0, 9.0), 2),
            battery=round(rng.uniform(55.0, 100.0), 1),
        )

    @property
    def now(self) -> datetime:
        """Virtual clock of the simulator."""
        return self.start + timedelta(seconds=self.tick / self.rate)

    def step(self) -> list[dict]:
        """Advances one tick and returns the messages to publish."""
        self.tick += 1
        self._inject_fault()
        messages: list[dict] = []
        for vehicle in self.fleet:
            if vehicle.silence_ticks > 0:
                vehicle.silence_ticks -= 1
                continue
            self._advance(vehicle)
            messages.append(self.message(vehicle))
        return messages

    def _advance(self, vehicle: SimVehicle) -> None:
        if vehicle.fault_until and self.tick >= vehicle.fault_until:
            vehicle.fault = None
            vehicle.fault_until = 0
            vehicle.override = None
            vehicle.status = "EN_MOVIMIENTO"
            vehicle.speed_kmh = round(self.random.uniform(4.0, 9.0), 2)
        if vehicle.battery < 25.0:
            vehicle.battery = min(100.0, vehicle.battery + 0.5)
        else:
            vehicle.battery = max(5.0, vehicle.battery - 0.05)
        if vehicle.fault == "jump":
            return
        if vehicle.override is not None:
            # Detour towards the forbidden zone at 10 m per tick (the backend
            # rejects jumps over 40 km/h + 5 m, so the approach is gradual).
            ox, oy = vehicle.override
            dx, dy = FORBIDDEN_POINT[0] - ox, FORBIDDEN_POINT[1] - oy
            distance = math.hypot(dx, dy)
            if distance <= 10.0:
                vehicle.override = FORBIDDEN_POINT
            else:
                vehicle.override = (
                    ox + dx / distance * 10.0,
                    oy + dy / distance * 10.0,
                )
            return
        vehicle.distance += vehicle.speed_kmh / 3.6 / self.rate

    def _inject_fault(self) -> None:
        # One fault every 18 ticks, cycling through the types, on a random
        # vehicle that is not already faulted: deterministic for a given seed
        # and it exercises every alert within a few minutes.
        if self.tick % 18 != 0:
            return
        fault = FAULT_TYPES[(self.tick // 18) % len(FAULT_TYPES)]
        candidates = [vehicle for vehicle in self.fleet if vehicle.fault is None]
        if not candidates:
            return
        vehicle = candidates[self.random.randrange(len(candidates))]
        rng = self.random
        if fault == "silence":
            vehicle.silence_ticks = rng.randint(45, 90)
        elif fault == "battery":
            vehicle.battery = float(rng.randint(8, 19))
            vehicle.fault = "battery"
            vehicle.fault_until = self.tick + rng.randint(60, 120)
        elif fault == "jump":
            vehicle.fault = "jump"
            vehicle.fault_until = self.tick + 1
        elif fault == "forbidden":
            vehicle.override = (vehicle.position()[0], vehicle.position()[1])
            vehicle.fault = "forbidden"
            vehicle.fault_until = self.tick + rng.randint(40, 70)
        elif fault == "speed":
            vehicle.fault = "speed"
            vehicle.speed_kmh = round(rng.uniform(22.0, 32.0), 2)
            vehicle.fault_until = self.tick + 3
        elif fault == "breakdown":
            vehicle.fault = "breakdown"
            vehicle.status = "AVERIA"
            vehicle.speed_kmh = 0.0
            vehicle.fault_until = self.tick + rng.randint(20, 40)

    def message(self, vehicle: SimVehicle) -> dict:
        """Builds the telemetry payload of one vehicle."""
        x, y, heading = vehicle.position()
        vehicle.sent += 1
        payload = {
            "vehicleId": vehicle.vehicle_id,
            "ts": self.now.isoformat().replace("+00:00", "Z"),
            "lat": round(lat_of(y), 7),
            "lon": round(lon_of(x), 7),
            "speedKmh": round(vehicle.speed_kmh, 2),
            "headingDeg": round(heading, 1),
            "batteryPct": round(vehicle.battery, 1),
            "fuelPct": None,
            "status": vehicle.status,
        }
        if vehicle.index % 5 == 0:
            payload["containerCode"] = VALID_CONTAINER
        return payload

    def messages(self, ticks: int) -> Iterable[tuple[str, dict]]:
        """Yields ``(topic, payload)`` pairs for ``ticks`` ticks."""
        for _ in range(ticks):
            for payload in self.step():
                yield topic_for(payload["vehicleId"]), payload


def topic_for(vehicle_id: str, yard_id: str = DEFAULT_YARD_ID) -> str:
    """Returns the MQTT topic of the telemetry of a vehicle."""
    return f"yard/{yard_id}/fleet/{vehicle_id}/telemetry"


def publish(client, messages: Iterable[tuple[str, dict]], qos: int = 1) -> int:
    """Publishes the messages with a paho client and returns the count."""
    count = 0
    for topic, payload in messages:
        client.publish(topic, json.dumps(payload), qos=qos)
        count += 1
    return count


def build_parser() -> argparse.ArgumentParser:
    """Returns the command line parser."""
    parser = argparse.ArgumentParser(description="Simulador de telemetria de flota")
    parser.add_argument(
        "--vehicles", type=int, default=20, help="numero de vehiculos (5-50)"
    )
    parser.add_argument("--seed", type=int, default=42, help="semilla fija")
    parser.add_argument(
        "--rate", type=float, default=1.0, help="mensajes por segundo y vehiculo"
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=0.0,
        help="segundos a simular (0 = indefinido)",
    )
    parser.add_argument(
        "--ticks",
        type=int,
        default=0,
        help="numero de ticks (tiene prioridad sobre --duration)",
    )
    parser.add_argument(
        "--yard",
        default=os.environ.get("YARD_ID", DEFAULT_YARD_ID),
        help="identificador del patio",
    )
    parser.add_argument("--host", default=DEFAULT_HOST, help="broker MQTT")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="puerto MQTT")
    parser.add_argument(
        "--qos", type=int, default=1, choices=(0, 1, 2), help="QoS MQTT"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="imprime el JSON en stdout en lugar de publicar",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point of the simulator."""
    args = build_parser().parse_args(argv)
    simulator = FleetSimulator(vehicles=args.vehicles, seed=args.seed, rate=args.rate)
    ticks = args.ticks or (int(args.duration * args.rate) if args.duration else 0)

    if args.dry_run:
        generated = 0
        for topic, payload in simulator.messages(ticks or 1):
            print(json.dumps({"topic": topic, "payload": payload}, ensure_ascii=False))
            generated += 1
        print(f"# {generated} mensajes generados (dry-run)", file=sys.stderr)
        return 0

    try:
        import paho.mqtt.client as mqtt  # type: ignore[import-not-found]
    except ImportError:
        print(
            "paho-mqtt no esta instalado: use --dry-run o instale paho-mqtt",
            file=sys.stderr,
        )
        return 2

    client = mqtt.Client(client_id=f"fleet-sim-{args.seed}")
    client.connect(args.host, args.port)
    client.loop_start()
    try:
        sent = 0
        deadline = time.monotonic() + args.duration if args.duration else None
        while True:
            if ticks and simulator.tick >= ticks:
                break
            if deadline is not None and time.monotonic() >= deadline:
                break
            batch = [
                (topic_for(payload["vehicleId"], args.yard), payload)
                for payload in simulator.step()
            ]
            sent += publish(client, batch, args.qos)
            time.sleep(1.0 / args.rate)
        print(
            f"# {sent} mensajes publicados en {simulator.tick} ticks", file=sys.stderr
        )
    except KeyboardInterrupt:
        print(f"# interrumpido tras {simulator.tick} ticks", file=sys.stderr)
    finally:
        client.loop_stop()
        client.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
