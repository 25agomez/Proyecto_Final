#!/usr/bin/env bash
#
# demo_gate_flow.sh — simula un ingreso y una salida por puerta usando solo curl
# y muestra los tiempos calculados por la bitacora digital (visit_log).
#
# Uso:
#   ./infra/scripts/demo_gate_flow.sh
#   BASE_URL=http://localhost:8080 EDGE_API_KEY=... ./infra/scripts/demo_gate_flow.sh
#
# Requisitos: curl y python3 (para leer el JSON). El backend debe estar
# levantado con el perfil `dev` (usuarios en memoria y token en
# POST /api/v1/auth/token) y con la base migrada por Flyway (V1 + V2).
#
# Variables de entorno:
#   BASE_URL        URL del backend (por defecto http://localhost:8080)
#   YARD_ID         patio de la demo (por defecto el patio del seed)
#   GATE_IN/GATE_OUT puertas del seed
#   EDGE_API_KEY    clave del borde; vacia = ingesta abierta (perfil dev)
#   DEMO_PLATE      matricula a usar (por defecto una unica por ejecucion)
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
YARD_ID="${YARD_ID:-11111111-1111-7111-8111-111111111111}"
GATE_IN="${GATE_IN:-33333333-3333-7333-8333-333333333331}"
GATE_OUT="${GATE_OUT:-33333333-3333-7333-8333-333333333332}"
EDGE_API_KEY="${EDGE_API_KEY:-}"
RUN_ID="$(date +%H%M%S)"
PLATE="${DEMO_PLATE:-DM${RUN_ID}}"

TOKEN_SUPERVISOR=""
TOKEN_OPERADOR=""
RESPONSE=""
HTTP_STATUS=""
ELAPSED_MS=""

hr() { printf '%s\n' "----------------------------------------------------------------------"; }
title() { hr; printf '  %s\n' "$*"; hr; }
now_iso() {
  python3 - <<'PY'
from datetime import datetime, timezone
print(datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"))
PY
}

# json_field <json> <dotted.path> → prints the value ("" when null)
json_field() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

value = json.loads(sys.argv[1])
for part in sys.argv[2].split("."):
    value = value[int(part)] if part.isdigit() else value[part]
print("" if value is None else value)
PY
}

# request <METHOD> <path> <token> [body] → sets RESPONSE, HTTP_STATUS, ELAPSED_MS
request() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local tmp status seconds
  local -a args

  tmp="$(mktemp)"
  args=(-sS -X "$method" "$BASE_URL$path" -o "$tmp" -w '%{http_code} %{time_total}\n')
  if [[ -n "$token" ]]; then
    args+=(-H "Authorization: Bearer $token")
  fi
  if [[ -n "$EDGE_API_KEY" && "$path" == "/api/v1/gate-events" ]]; then
    args+=(-H "X-Edge-Api-Key: $EDGE_API_KEY")
  fi
  if [[ -n "$body" ]]; then
    args+=(-H "Content-Type: application/json" -d "$body")
  fi

  read -r status seconds < <(curl "${args[@]}")
  RESPONSE="$(cat "$tmp")"
  rm -f "$tmp"
  HTTP_STATUS="$status"
  ELAPSED_MS="$(python3 -c "print(round(float('${seconds}') * 1000))")"

  if [[ "$status" -ge 400 ]]; then
    printf 'ERROR HTTP %s en %s %s\n%s\n' "$status" "$method" "$path" "$RESPONSE" >&2
    return 1
  fi
}

# reading <kind> <text> <confidence> [isoValid] <eventId>
reading_body() {
  local kind="$1" text="$2" confidence="$3" iso="$4" event_id="$5"
  python3 - "$YARD_ID" "$GATE_IN" "$kind" "$text" "$confidence" "$iso" "$event_id" <<'PY'
import json
import sys
from datetime import datetime, timezone

yard, gate, kind, text, confidence, iso, event_id = sys.argv[1:8]
body = {
    "eventId": event_id,
    "yardId": yard,
    "gateId": gate,
    "timestamp": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "kind": kind,
    "rawText": text,
    "normalizedText": text,
    "confidence": float(confidence),
    "corrected": False,
}
if iso:
    body["isoValid"] = iso == "true"
print(json.dumps(body))
PY
}

transition() {
  local visit_id="$1" target="$2"
  request POST "/api/v1/visits/${visit_id}/transitions" "$TOKEN_OPERADOR" "{\"targetState\":\"$target\"}"
  printf '  -> %-14s estado=%-14s (%s ms)\n' \
    "$target" "$(json_field "$RESPONSE" state)" "$ELAPSED_MS"
}

# ---------------------------------------------------------------------------
title "0. Esperando el backend en $BASE_URL"
# ---------------------------------------------------------------------------
for _ in $(seq 1 30); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    printf '  backend arriba\n'
    break
  fi
  sleep 1
done
curl -fsS "$BASE_URL/actuator/health" >/dev/null || {
  printf 'El backend no responde en %s (¿docker compose up -d backend?)\n' "$BASE_URL" >&2
  exit 1
}

# ---------------------------------------------------------------------------
title "1. Tokens del perfil dev (usuarios en memoria)"
# ---------------------------------------------------------------------------
request POST "/api/v1/auth/token?username=supervisor&password=supervisor"
TOKEN_SUPERVISOR="$(json_field "$RESPONSE" access_token)"
request POST "/api/v1/auth/token?username=operador&password=operador"
TOKEN_OPERADOR="$(json_field "$RESPONSE" access_token)"
printf '  token de supervisor y de operador obtenidos\n'

# ---------------------------------------------------------------------------
title "2. Datos maestros de la demo (transportista, conductor y camiones)"
# ---------------------------------------------------------------------------
request POST "/api/v1/carriers" "$TOKEN_SUPERVISOR" \
  "{\"code\":\"DEMO-${RUN_ID}\",\"name\":\"Transportes Demo ${RUN_ID}\",\"taxId\":\"3-101-${RUN_ID}\"}"
CARRIER_ID="$(json_field "$RESPONSE" id)"

request POST "/api/v1/drivers" "$TOKEN_SUPERVISOR" \
  "{\"fullName\":\"Conductor Demo ${RUN_ID}\",\"licenseId\":\"LIC-${RUN_ID}\",\"phone\":\"8000-0000\"}"
DRIVER_ID="$(json_field "$RESPONSE" id)"

request POST "/api/v1/trucks" "$TOKEN_SUPERVISOR" \
  "{\"plate\":\"$PLATE\",\"carrierId\":\"$CARRIER_ID\",\"driverId\":\"$DRIVER_ID\"}"
TRUCK_IN_ID="$(json_field "$RESPONSE" id)"

request POST "/api/v1/trucks" "$TOKEN_SUPERVISOR" \
  "{\"plate\":\"${PLATE}X\",\"carrierId\":\"$CARRIER_ID\",\"driverId\":\"$DRIVER_ID\"}"
TRUCK_OUT_ID="$(json_field "$RESPONSE" id)"

printf '  camion de ingreso %s (matricula %s)\n' "$TRUCK_IN_ID" "$PLATE"
printf '  camion de salida  %s (matricula %sX)\n' "$TRUCK_OUT_ID" "$PLATE"

# Un contenedor del seed que este dentro del patio: se retira en la salida.
request GET "/api/v1/containers?status=IN_YARD&size=1" "$TOKEN_OPERADOR"
CONTAINER_CODE="$(json_field "$RESPONSE" content.0.code)"
printf '  contenedor a retirar: %s\n' "$CONTAINER_CODE"

# ---------------------------------------------------------------------------
title "3. Citas: ingreso del camion y retiro del contenedor"
# ---------------------------------------------------------------------------
SCHEDULED_AT="$(now_iso)"
request POST "/api/v1/appointments" "$TOKEN_SUPERVISOR" \
  "{\"yardId\":\"$YARD_ID\",\"gateId\":\"$GATE_IN\",\"truckId\":\"$TRUCK_IN_ID\",\"appointmentType\":\"INGRESO\",\"scheduledAt\":\"$SCHEDULED_AT\",\"windowMinutes\":60}"
APPOINTMENT_IN="$(json_field "$RESPONSE" id)"

request POST "/api/v1/appointments" "$TOKEN_SUPERVISOR" \
  "{\"yardId\":\"$YARD_ID\",\"gateId\":\"$GATE_OUT\",\"truckId\":\"$TRUCK_OUT_ID\",\"appointmentType\":\"RETIRO\",\"scheduledAt\":\"$SCHEDULED_AT\",\"windowMinutes\":60,\"containerCode\":\"$CONTAINER_CODE\"}"
APPOINTMENT_OUT="$(json_field "$RESPONSE" id)"

printf '  cita de ingreso %s\n' "$APPOINTMENT_IN"
printf '  cita de retiro  %s\n' "$APPOINTMENT_OUT"

# ---------------------------------------------------------------------------
title "4. INGRESO: lectura OCR de la matricula en la puerta"
# ---------------------------------------------------------------------------
request POST "/api/v1/gate-events" "" \
  "$(reading_body PLATE "$PLATE" 0.98 "" "$(python3 -c 'import uuid;print(uuid.uuid4())')")"
VISIT_IN="$(json_field "$RESPONSE" visitId)"
printf '  resultado=%s  idempotente=%s  (%s ms)\n' \
  "$(json_field "$RESPONSE" result)" "$(json_field "$RESPONSE" idempotent)" "$ELAPSED_MS"
printf '  motivo: %s\n' "$(json_field "$RESPONSE" reason)"

request GET "/api/v1/visits/${VISIT_IN}" "$TOKEN_OPERADOR"
printf '  visita=%s estado=%s\n' "$VISIT_IN" "$(json_field "$RESPONSE" state)"
printf '  linea de tiempo: %s\n' \
  "$(python3 -c "import json,sys;print(' -> '.join(e['stage'] for e in json.loads(sys.argv[1])['timeline']))" "$RESPONSE")"

printf '  el camion avanza por el patio:\n'
transition "$VISIT_IN" ASIGNADO
sleep 1
transition "$VISIT_IN" EN_PROCESO
sleep 2
transition "$VISIT_IN" SALIDA_PUERTA

# ---------------------------------------------------------------------------
title "5. SALIDA: lectura del codigo BIC del contenedor (retiro)"
# ---------------------------------------------------------------------------
request POST "/api/v1/gate-events" "" \
  "$(reading_body CONTAINER "$CONTAINER_CODE" 0.97 true "$(python3 -c 'import uuid;print(uuid.uuid4())')")"
VISIT_OUT="$(json_field "$RESPONSE" visitId)"
printf '  resultado=%s  (%s ms)\n' "$(json_field "$RESPONSE" result)" "$ELAPSED_MS"
printf '  motivo: %s\n' "$(json_field "$RESPONSE" reason)"

printf '  el camion retira el contenedor y sale del patio:\n'
transition "$VISIT_OUT" ASIGNADO
sleep 1
transition "$VISIT_OUT" EN_PROCESO
sleep 2
transition "$VISIT_OUT" SALIDA_PUERTA

# ---------------------------------------------------------------------------
title "6. Tiempos calculados por la bitacora digital"
# ---------------------------------------------------------------------------
print_times() {
  local label="$1" visit_id="$2"
  request GET "/api/v1/visits/${visit_id}" "$TOKEN_OPERADOR"
  python3 - "$label" "$RESPONSE" <<'PY'
import json
import sys

label, raw = sys.argv[1], sys.argv[2]
visit = json.loads(raw)


def seconds(value):
    return "NO MEDIDO" if value is None else f"{value} s"


print(f"  {label}")
print(f"    estado final ........ {visit['state']} ({visit['result']})")
print(f"    espera en puerta .... {seconds(visit.get('waitSeconds'))}")
print(f"    tiempo en patio ..... {seconds(visit.get('yardSeconds'))}")
print(f"    tiempo de proceso ... {seconds(visit.get('serviceSeconds'))}")
print(f"    etapas .............. {' -> '.join(e['stage'] for e in visit['timeline'])}")
PY
}

print_times "INGRESO (visita ${VISIT_IN})" "$VISIT_IN"
print_times "SALIDA  (visita ${VISIT_OUT})" "$VISIT_OUT"

# ---------------------------------------------------------------------------
title "7. Comprobante PDF y exportacion CSV"
# ---------------------------------------------------------------------------
PDF_PATH="${TMPDIR:-/tmp}/visita-${VISIT_OUT}.pdf"
curl -fsS -H "Authorization: Bearer $TOKEN_OPERADOR" \
  "$BASE_URL/api/v1/visits/${VISIT_OUT}/comprobante" -o "$PDF_PATH"
printf '  comprobante: %s (%s bytes)\n' "$PDF_PATH" "$(wc -c <"$PDF_PATH")"

CSV_PATH="${TMPDIR:-/tmp}/bitacora-${RUN_ID}.csv"
curl -fsS -H "Authorization: Bearer $TOKEN_OPERADOR" \
  "$BASE_URL/api/v1/visits/export.csv?yardId=${YARD_ID}" -o "$CSV_PATH"
printf '  bitacora CSV: %s (%s filas)\n' "$CSV_PATH" "$(($(wc -l <"$CSV_PATH") - 1))"

hr
printf 'Demo completada: ingreso y salida registrados en la bitacora digital.\n'
hr
