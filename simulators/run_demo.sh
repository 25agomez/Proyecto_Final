#!/usr/bin/env bash
#
# run_demo.sh — Ejecuta la demostración del sistema completo simulando
# operaciones de puerta, conciliación, optimización de stacking y bitácora.
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

printf "\n%b======================================================================%b\n" "$BLUE" "$NC"
printf "  %bLogisticaPortuaria CR — Demostración de Operación de Patio%b\n" "$BLUE" "$NC"
printf "%b======================================================================%b\n\n" "$BLUE" "$NC"

printf "%b1. Verificando disponibilidad del backend en %s...%b\n" "$YELLOW" "$BASE_URL" "$NC"
for _ in $(seq 1 15); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    printf "  [%b✓%b] Backend conectado exitosamente\n" "$GREEN" "$NC"
    break
  fi
  sleep 1
done

if ! curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
  printf "  [%b✗%b] El backend no responde en %s.\n" "$RED" "$NC" "$BASE_URL"
  printf "  Asegúrese de iniciar el backend antes de correr la demo:\n"
  printf "    - Con Docker: make up\n"
  printf "    - Sin Docker: ./backend/mvnw test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=cr.logisticaportuaria.DevServer\n\n"
  exit 1
fi

printf "\n%b2. Ejecutando flujo de ingreso y salida por puerta (curl)...%b\n" "$YELLOW" "$NC"
bash infra/scripts/demo_gate_flow.sh

printf "\n%b3. Ejecutando jornada simulada acelerada (DaySim)...%b\n" "$YELLOW" "$NC"
if command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  "$PYTHON_BIN" simulators/day_sim.py --base-url "$BASE_URL" --speed 20 --count 10
elif [ -f /tmp/opencode/venv/bin/python ]; then
  /tmp/opencode/venv/bin/python simulators/day_sim.py --base-url "$BASE_URL" --speed 20 --count 10
else
  python3 simulators/day_sim.py --base-url "$BASE_URL" --speed 20 --count 10
fi

printf "\n%b======================================================================%b\n" "$GREEN" "$NC"
printf "  %bDemostración completada con éxito.%b\n" "$GREEN" "$NC"
printf "%b======================================================================%b\n\n" "$GREEN" "$NC"
