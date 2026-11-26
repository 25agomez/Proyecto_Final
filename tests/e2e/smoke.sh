#!/usr/bin/env bash
#
# smoke.sh — Prueba de humo rápida para verificar el estado de los servicios.
# Verifica disponibilidad de endpoints, autenticación, optimizador y base de datos.
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EDGE_URL="${EDGE_URL:-http://localhost:8000}"
PASS_COUNT=0
FAIL_COUNT=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

check() {
  local name="$1"
  local url="$2"
  local expected_status="${3:-200}"
  local auth_header="${4:-}"
  
  local -a args=(-sS -o /dev/null -w "%{http_code}" --connect-timeout 5)
  if [[ -n "$auth_header" ]]; then
    args+=(-H "$auth_header")
  fi
  
  local status
  if status=$(curl "${args[@]}" "$url" 2>/dev/null); then
    if [[ "$status" == "$expected_status" ]]; then
      printf "  [%b✓%b] %-45s (HTTP %s)\n" "$GREEN" "$NC" "$name" "$status"
      PASS_COUNT=$((PASS_COUNT + 1))
      return 0
    else
      printf "  [%b✗%b] %-45s (esperado %s, obtenido %s)\n" "$RED" "$NC" "$name" "$expected_status" "$status"
      FAIL_COUNT=$((FAIL_COUNT + 1))
      return 1
    fi
  else
    printf "  [%b✗%b] %-45s (conexión fallida a %s)\n" "$RED" "$NC" "$name" "$url"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    return 1
  fi
}

printf "\n%b======================================================================%b\n" "$BLUE" "$NC"
printf "  %bLogisticaPortuaria CR — Prueba de Humo (Smoke Test)%b\n" "$BLUE" "$NC"
printf "%b======================================================================%b\n\n" "$BLUE" "$NC"

printf "%b1. Verificación de Backend Spring Boot (%s)%b\n" "$YELLOW" "$BASE_URL" "$NC"
check "Actuator Health Check" "$BASE_URL/actuator/health" 200

# Obtener token de operador
OPERATOR_TOKEN=""
if TOKEN_RESP=$(curl -sS -X POST "$BASE_URL/api/v1/auth/token?username=operador&password=operador" 2>/dev/null); then
  OPERATOR_TOKEN=$(python3 -c "import json,sys; print(json.loads(sys.argv[1]).get('access_token',''))" "$TOKEN_RESP" 2>/dev/null || true)
fi

if [[ -n "$OPERATOR_TOKEN" ]]; then
  printf "  [%b✓%b] %-45s\n" "$GREEN" "$NC" "Autenticación Dev (Token JWT obtenido)"
  PASS_COUNT=$((PASS_COUNT + 1))
  AUTH_HDR="Authorization: Bearer $OPERATOR_TOKEN"
else
  printf "  [%b✗%b] %-45s\n" "$RED" "$NC" "Autenticación Dev (No se pudo obtener token)"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  AUTH_HDR=""
fi

check "Swagger UI Docs" "$BASE_URL/swagger-ui.html" 200
check "API OpenAPI Spec" "$BASE_URL/v3/api-docs" 200
check "Consulta de Slots de Patio" "$BASE_URL/api/v1/yard/slots?size=5" 200 "$AUTH_HDR"
check "Consulta de Contenedores" "$BASE_URL/api/v1/containers?size=5" 200 "$AUTH_HDR"
check "Métricas del Optimizador Stacking" "$BASE_URL/api/v1/optimizer/metrics" 200 "$AUTH_HDR"
check "Heatmap de Congestión" "$BASE_URL/api/v1/congestion/heatmap" 200 "$AUTH_HDR"
check "Listado de Vehículos de Flota" "$BASE_URL/api/v1/fleet/vehicles" 200 "$AUTH_HDR"
check "KPIs de Flota" "$BASE_URL/api/v1/fleet/kpis" 200 "$AUTH_HDR"

printf "\n%b2. Verificación de Edge OCR Service (%s)%b\n" "$YELLOW" "$EDGE_URL" "$NC"
if curl -sS --connect-timeout 2 "$EDGE_URL/healthz" >/dev/null 2>&1; then
  check "Edge OCR Healthz" "$EDGE_URL/healthz" 200
  check "Edge OCR Metrics" "$EDGE_URL/metrics" 200
else
  printf "  [%b-%b] %-45s (servicio no levantado; modo opcional en local)\n" "$YELLOW" "$NC" "Edge OCR Service"
fi

printf "\n%b======================================================================%b\n" "$BLUE" "$NC"
printf "  Resumen de Smoke Test: %b%d aprobados%b, %b%d fallidos%b\n" \
  "$GREEN" "$PASS_COUNT" "$NC" "$([ "$FAIL_COUNT" -gt 0 ] && echo "$RED" || echo "$GREEN")" "$FAIL_COUNT" "$NC"
printf "%b======================================================================%b\n\n" "$BLUE" "$NC"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
