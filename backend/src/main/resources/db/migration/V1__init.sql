-- V1__init.sql
-- LogisticaPortuaria CR - core data model (PostgreSQL 16)
-- Rules: UUID v7 primary keys, timestamptz (UTC), snake_case, indexed FKs.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

-- UUID v7 (RFC 9562): time ordered. PostgreSQL 16 has no native uuidv7().
CREATE OR REPLACE FUNCTION uuid_generate_v7() RETURNS uuid
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    v_ms    BIGINT;
    v_bytes BYTEA;
BEGIN
    v_ms    := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
    -- 48 bits of timestamp (6 bytes) followed by 10 random bytes: a UUID is 16 bytes.
    v_bytes := decode(lpad(to_hex(v_ms), 12, '0'), 'hex') || gen_random_bytes(10);
    v_bytes := set_byte(v_bytes, 6, (get_byte(v_bytes, 6) & 0x0F) | 0x70); -- version 7
    v_bytes := set_byte(v_bytes, 8, (get_byte(v_bytes, 8) & 0x3F) | 0x80); -- variant 10
    RETURN encode(v_bytes, 'hex')::uuid;
END;
$$;

-- ISO 6346 character value: A=10, B=12, C=13 ... (multiples of 11 are skipped).
CREATE OR REPLACE FUNCTION bic_char_value(c TEXT) RETURNS INTEGER
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE UPPER(c)
        WHEN 'A' THEN 10 WHEN 'B' THEN 12 WHEN 'C' THEN 13 WHEN 'D' THEN 14
        WHEN 'E' THEN 15 WHEN 'F' THEN 16 WHEN 'G' THEN 17 WHEN 'H' THEN 18
        WHEN 'I' THEN 19 WHEN 'J' THEN 20 WHEN 'K' THEN 21 WHEN 'L' THEN 23
        WHEN 'M' THEN 24 WHEN 'N' THEN 25 WHEN 'O' THEN 26 WHEN 'P' THEN 27
        WHEN 'Q' THEN 28 WHEN 'R' THEN 29 WHEN 'S' THEN 30 WHEN 'T' THEN 31
        WHEN 'U' THEN 32 WHEN 'V' THEN 34 WHEN 'W' THEN 35 WHEN 'X' THEN 36
        WHEN 'Y' THEN 37 WHEN 'Z' THEN 38
        WHEN '0' THEN 0  WHEN '1' THEN 1  WHEN '2' THEN 2  WHEN '3' THEN 3
        WHEN '4' THEN 4  WHEN '5' THEN 5  WHEN '6' THEN 6  WHEN '7' THEN 7
        WHEN '8' THEN 8  WHEN '9' THEN 9
        ELSE NULL
    END;
$$;

-- ISO 6346 check digit over the first 10 characters: sum(value * 2^pos) mod 11 mod 10.
CREATE OR REPLACE FUNCTION bic_check_digit(prefix TEXT) RETURNS INTEGER
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    i     INT;
    total INT := 0;
    ch    TEXT;
    val   INT;
BEGIN
    IF length(prefix) <> 10 THEN
        RAISE EXCEPTION 'BIC prefix must have 10 characters, got %', length(prefix);
    END IF;
    FOR i IN 0..9 LOOP
        ch  := substring(prefix FROM i + 1 FOR 1);
        val := bic_char_value(ch);
        IF val IS NULL THEN
            RAISE EXCEPTION 'Invalid BIC character: %', ch;
        END IF;
        total := total + val * (1 << i);
    END LOOP;
    RETURN (total % 11) % 10;
END;
$$;

CREATE OR REPLACE FUNCTION bic_is_valid(code TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    expected INT;
    actual   INT;
BEGIN
    IF code IS NULL OR code !~ '^[A-Z]{3}[UJZ][0-9]{7}$' THEN
        RETURN FALSE;
    END IF;
    expected := bic_check_digit(substring(code FROM 1 FOR 10));
    actual   := substring(code FROM 11 FOR 1)::INT;
    RETURN expected = actual;
END;
$$;

-- ---------------------------------------------------------------------------
-- Core master data
-- ---------------------------------------------------------------------------

CREATE TABLE yard (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    timezone    TEXT        NOT NULL DEFAULT 'America/Costa_Rica',
    address     TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE yard_block (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id     UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    code        TEXT        NOT NULL,
    max_tier    INT         NOT NULL DEFAULT 3 CHECK (max_tier > 0),
    reefer_only BOOLEAN     NOT NULL DEFAULT FALSE,
    hazmat_only BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (yard_id, code)
);

CREATE INDEX idx_yard_block_yard ON yard_block (yard_id);

-- A slot is a physical 20ft wide position. A 40ft slot spans two consecutive bays.
CREATE TABLE yard_slot (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    block_id     UUID     NOT NULL REFERENCES yard_block (id) ON DELETE CASCADE,
    bay          INT      NOT NULL CHECK (bay > 0),
    row          INT      NOT NULL CHECK (row > 0),
    tier         INT      NOT NULL CHECK (tier > 0),
    size_ft      INT      NOT NULL DEFAULT 20 CHECK (size_ft IN (20, 40)),
    bay_span     INT      GENERATED ALWAYS AS (size_ft / 20) STORED,
    slot_type    TEXT     NOT NULL DEFAULT 'dry' CHECK (slot_type IN ('dry', 'reefer', 'hazmat')),
    has_power    BOOLEAN  NOT NULL DEFAULT FALSE,
    capacity_teu NUMERIC(4, 1) NOT NULL DEFAULT 1.0 CHECK (capacity_teu > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (block_id, bay, row, tier)
);

CREATE INDEX idx_yard_slot_block ON yard_slot (block_id);
CREATE INDEX idx_yard_slot_lookup ON yard_slot (block_id, row, tier, bay);

CREATE TABLE carrier (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    tax_id      TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE driver (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    full_name   TEXT        NOT NULL,
    license_id  TEXT,
    phone       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE truck (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    plate       TEXT        NOT NULL,
    carrier_id  UUID        REFERENCES carrier (id),
    driver_id   UUID        REFERENCES driver (id),
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plate)
);

CREATE INDEX idx_truck_plate_trgm ON truck USING gin (plate gin_trgm_ops);

CREATE TABLE container (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    code          TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z]{3}[UJZ][0-9]{7}$'),
    size_ft       INT         NOT NULL CHECK (size_ft IN (20, 40)),
    container_type TEXT       NOT NULL DEFAULT 'dry' CHECK (container_type IN ('dry', 'reefer', 'hazmat')),
    status        TEXT        NOT NULL DEFAULT 'IN_YARD'
                  CHECK (status IN ('IN_TRANSIT', 'IN_YARD', 'RESERVED', 'GATE_HOLD', 'OUT', 'UNKNOWN')),
    weight_kg     NUMERIC(10, 2) CHECK (weight_kg IS NULL OR weight_kg >= 0),
    client_code   TEXT,
    eta           TIMESTAMPTZ,
    etd           TIMESTAMPTZ,
    imo_class     TEXT,
    requires_power BOOLEAN    NOT NULL DEFAULT FALSE,
    attributes    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_container_code_trgm ON container USING gin (code gin_trgm_ops);
CREATE INDEX idx_container_status ON container (status);
CREATE INDEX idx_container_etd ON container (etd);
CREATE INDEX idx_container_client ON container (client_code);

-- Enforce ISO 6346 check digit on persisted containers.
CREATE OR REPLACE FUNCTION trg_container_bic_check_digit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT bic_is_valid(NEW.code) THEN
        RAISE EXCEPTION 'Invalid ISO 6346 check digit for container %', NEW.code;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER container_bic_check_digit_trg
    BEFORE INSERT OR UPDATE OF code ON container
    FOR EACH ROW EXECUTE FUNCTION trg_container_bic_check_digit();

-- Container position history: exactly one active row per container.
CREATE TABLE container_position (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    container_id UUID        NOT NULL REFERENCES container (id) ON DELETE CASCADE,
    slot_id      UUID        NOT NULL REFERENCES yard_slot (id),
    placed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at   TIMESTAMPTZ,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_container_position_active_container
    ON container_position (container_id) WHERE active;
CREATE UNIQUE INDEX uq_container_position_active_slot
    ON container_position (slot_id) WHERE active;
CREATE INDEX idx_container_position_slot ON container_position (slot_id);
CREATE INDEX idx_container_position_history ON container_position (container_id, placed_at DESC);

-- A 40ft container occupies two consecutive bays: prevent overlapping active placements.
CREATE OR REPLACE FUNCTION trg_container_position_no_overlap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    s yard_slot%ROWTYPE;
BEGIN
    SELECT * INTO s FROM yard_slot WHERE id = NEW.slot_id;
    IF EXISTS (
        SELECT 1
        FROM container_position cp
        JOIN yard_slot os ON os.id = cp.slot_id
        WHERE cp.active
          AND cp.container_id <> NEW.container_id
          AND os.block_id = s.block_id
          AND os.row = s.row
          AND os.tier = s.tier
          AND os.bay < s.bay + s.bay_span
          AND s.bay < os.bay + os.bay_span
    ) THEN
        RAISE EXCEPTION 'Slot overlap detected for slot %', NEW.slot_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER container_position_no_overlap_trg
    BEFORE INSERT OR UPDATE ON container_position
    FOR EACH ROW EXECUTE FUNCTION trg_container_position_no_overlap();

-- Stack support: every tier above 1 needs the whole footprint of the slot below occupied.
CREATE OR REPLACE FUNCTION trg_container_position_support() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    s       yard_slot%ROWTYPE;
    covered INT;
BEGIN
    SELECT * INTO s FROM yard_slot WHERE id = NEW.slot_id;
    IF s.tier > 1 THEN
        SELECT count(DISTINCT b) INTO covered
        FROM generate_series(s.bay, s.bay + s.bay_span - 1) AS g(b)
        JOIN container_position cp ON cp.active
        JOIN yard_slot os ON os.id = cp.slot_id
              AND os.block_id = s.block_id
              AND os.row = s.row
              AND os.tier = s.tier - 1
        WHERE b BETWEEN os.bay AND os.bay + os.bay_span - 1;
        IF covered < s.bay_span THEN
            RAISE EXCEPTION 'Slot % has no support on tier %', NEW.slot_id, s.tier - 1;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER container_position_support_trg
    BEFORE INSERT OR UPDATE ON container_position
    FOR EACH ROW EXECUTE FUNCTION trg_container_position_support();

-- ---------------------------------------------------------------------------
-- Gates, appointments and visits
-- ---------------------------------------------------------------------------

CREATE TABLE gate (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id     UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    code        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    kind        TEXT        NOT NULL DEFAULT 'BOTH' CHECK (kind IN ('IN', 'OUT', 'BOTH')),
    lanes       INT         NOT NULL DEFAULT 1 CHECK (lanes > 0),
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (yard_id, code)
);

CREATE INDEX idx_gate_yard ON gate (yard_id);

CREATE TABLE appointment (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id         UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    gate_id         UUID        REFERENCES gate (id),
    truck_id        UUID        REFERENCES truck (id),
    appointment_type TEXT       NOT NULL DEFAULT 'INGRESO' CHECK (appointment_type IN ('INGRESO', 'RETIRO')),
    scheduled_at    TIMESTAMPTZ NOT NULL,
    window_minutes  INT         NOT NULL DEFAULT 30 CHECK (window_minutes > 0),
    status          TEXT        NOT NULL DEFAULT 'PENDIENTE'
                    CHECK (status IN ('PENDIENTE', 'EN_CURSO', 'COMPLETADA', 'CANCELADA', 'NO_SHOW')),
    container_code  TEXT,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_appointment_schedule ON appointment (yard_id, scheduled_at);
CREATE INDEX idx_appointment_truck ON appointment (truck_id);
CREATE INDEX idx_appointment_container ON appointment (container_code);

-- Raw OCR reading. event_id provides idempotency for the ingestion endpoint.
CREATE TABLE gate_event (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    event_id       UUID        NOT NULL UNIQUE,
    yard_id        UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    gate_id        UUID        NOT NULL REFERENCES gate (id),
    observed_at    TIMESTAMPTZ NOT NULL,
    kind           TEXT        NOT NULL CHECK (kind IN ('PLATE', 'CONTAINER')),
    raw_text       TEXT,
    normalized_text TEXT,
    confidence     NUMERIC(5, 4) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    iso_valid      BOOLEAN,
    corrected      BOOLEAN     NOT NULL DEFAULT FALSE,
    bbox           JSONB,
    evidence_path  TEXT,
    model_version  TEXT,
    latency_ms     INT         CHECK (latency_ms IS NULL OR latency_ms >= 0),
    source         TEXT        NOT NULL DEFAULT 'EDGE' CHECK (source IN ('EDGE', 'MANUAL', 'API')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_gate_event_time ON gate_event (yard_id, observed_at DESC);
CREATE INDEX idx_gate_event_norm ON gate_event (normalized_text);
CREATE INDEX idx_gate_event_kind ON gate_event (kind, observed_at DESC);
CREATE INDEX idx_gate_event_text_trgm ON gate_event USING gin (normalized_text gin_trgm_ops);

-- Digital logbook: one row per truck visit through the gate.
CREATE TABLE visit_log (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id           UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    gate_id           UUID        NOT NULL REFERENCES gate (id),
    appointment_id    UUID        REFERENCES appointment (id),
    truck_id          UUID        REFERENCES truck (id),
    container_id      UUID        REFERENCES container (id),
    plate             TEXT,
    container_code    TEXT,
    direction         TEXT        NOT NULL CHECK (direction IN ('IN', 'OUT')),
    state             TEXT        NOT NULL DEFAULT 'ENTRADA_PUERTA'
                      CHECK (state IN ('ENTRADA_PUERTA', 'VALIDADO', 'ASIGNADO', 'EN_PROCESO', 'SALIDA_PUERTA', 'CANCELADO')),
    result            TEXT        NOT NULL DEFAULT 'PENDIENTE'
                      CHECK (result IN ('PENDIENTE', 'AUTO_APROBADO', 'REQUIERE_REVISION', 'RECHAZADO')),
    reason            TEXT,
    entered_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    validated_at      TIMESTAMPTZ,
    assigned_at       TIMESTAMPTZ,
    processing_at     TIMESTAMPTZ,
    exited_at         TIMESTAMPTZ,
    wait_seconds      INT,
    yard_seconds      INT,
    service_seconds   INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_visit_log_state ON visit_log (yard_id, state, entered_at DESC);
CREATE INDEX idx_visit_log_plate ON visit_log (plate);
CREATE INDEX idx_visit_log_container ON visit_log (container_code);
CREATE INDEX idx_visit_log_appointment ON visit_log (appointment_id);

CREATE TABLE visit_log_event (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    visit_id    UUID        NOT NULL REFERENCES visit_log (id) ON DELETE CASCADE,
    stage       TEXT        NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       TEXT,
    detail      JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_visit_log_event_visit ON visit_log_event (visit_id, at);

-- Manual review queue for doubtful readings.
CREATE TABLE review_task (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    gate_event_id UUID        NOT NULL REFERENCES gate_event (id) ON DELETE CASCADE,
    visit_id      UUID        REFERENCES visit_log (id) ON DELETE CASCADE,
    yard_id       UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    kind          TEXT        NOT NULL CHECK (kind IN ('PLATE', 'CONTAINER')),
    raw_text      TEXT,
    normalized_text TEXT,
    reason        TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'PENDIENTE'
                  CHECK (status IN ('PENDIENTE', 'APROBADO', 'CORREGIDO', 'RECHAZADO')),
    resolved_text TEXT,
    resolved_by   TEXT,
    resolved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_task_status ON review_task (yard_id, status, created_at);
CREATE UNIQUE INDEX uq_review_task_event_pending
    ON review_task (gate_event_id) WHERE status = 'PENDIENTE';

-- ---------------------------------------------------------------------------
-- Fleet (internal vehicles)
-- ---------------------------------------------------------------------------

CREATE TABLE vehicle (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id     UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    code        TEXT        NOT NULL,
    vehicle_type TEXT       NOT NULL CHECK (vehicle_type IN ('MONTACARGAS', 'RASTRA', 'REACH_STACKER', 'TIRADOR')),
    status      TEXT        NOT NULL DEFAULT 'LIBRE'
                CHECK (status IN ('LIBRE', 'EN_MOVIMIENTO', 'CARGANDO', 'DESCARGANDO', 'AVERIA')),
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (yard_id, code)
);

CREATE INDEX idx_vehicle_yard ON vehicle (yard_id);

CREATE TABLE work_order (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id       UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    vehicle_id    UUID        REFERENCES vehicle (id),
    container_code TEXT,
    order_type    TEXT        NOT NULL CHECK (order_type IN ('INGRESO', 'RETIRO', 'REUBICACION', 'MOVIMIENTO')),
    from_slot_id  UUID        REFERENCES yard_slot (id),
    to_slot_id    UUID        REFERENCES yard_slot (id),
    priority      INT         NOT NULL DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    status        TEXT        NOT NULL DEFAULT 'PENDIENTE'
                  CHECK (status IN ('PENDIENTE', 'ASIGNADA', 'EN_CURSO', 'COMPLETADA', 'CANCELADA')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ
);

CREATE INDEX idx_work_order_status ON work_order (yard_id, status, created_at DESC);
CREATE INDEX idx_work_order_vehicle ON work_order (vehicle_id);

-- Time series of vehicle positions, partitioned by day.
CREATE TABLE vehicle_position (
    id             UUID        NOT NULL DEFAULT uuid_generate_v7(),
    vehicle_id     UUID        NOT NULL REFERENCES vehicle (id) ON DELETE CASCADE,
    at             TIMESTAMPTZ NOT NULL,
    x              NUMERIC(9, 2) NOT NULL,
    y              NUMERIC(9, 2) NOT NULL,
    speed_kmh      NUMERIC(6, 2),
    heading_deg    NUMERIC(6, 2),
    battery_pct    NUMERIC(5, 2),
    fuel_pct       NUMERIC(5, 2),
    status         TEXT,
    work_order_id  UUID        REFERENCES work_order (id),
    container_code TEXT,
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

CREATE TABLE vehicle_position_default PARTITION OF vehicle_position DEFAULT;
CREATE INDEX idx_vehicle_position_ts ON vehicle_position (vehicle_id, at DESC);

CREATE TABLE vehicle_last_state (
    vehicle_id     UUID PRIMARY KEY REFERENCES vehicle (id) ON DELETE CASCADE,
    at             TIMESTAMPTZ NOT NULL,
    x              NUMERIC(9, 2) NOT NULL,
    y              NUMERIC(9, 2) NOT NULL,
    speed_kmh      NUMERIC(6, 2),
    status         TEXT,
    work_order_id  UUID REFERENCES work_order (id),
    container_code TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Creates the daily partitions used by vehicle_position.
CREATE OR REPLACE FUNCTION ensure_vehicle_position_partition(day DATE) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    p_name TEXT := 'vehicle_position_' || to_char(day, 'YYYYMMDD');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = p_name) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF vehicle_position FOR VALUES FROM (%L) TO (%L)',
            p_name, day::timestamptz, (day + 1)::timestamptz);
    END IF;
END;
$$;

-- Geofences: gates, docks, hazmat zones, forbidden areas.
CREATE TABLE geofence (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id     UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    kind        TEXT        NOT NULL CHECK (kind IN ('PUERTA', 'ANDEN', 'HAZMAT', 'PROHIBIDA', 'BLOQUE')),
    geojson     JSONB       NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_geofence_yard ON geofence (yard_id, kind);

-- ---------------------------------------------------------------------------
-- Congestion forecast
-- ---------------------------------------------------------------------------

CREATE TABLE congestion_forecast (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id           UUID        NOT NULL REFERENCES yard (id) ON DELETE CASCADE,
    gate_id           UUID        NOT NULL REFERENCES gate (id) ON DELETE CASCADE,
    horizon_min       INT         NOT NULL CHECK (horizon_min > 0),
    expected_wait_min NUMERIC(6, 2) NOT NULL,
    p10               NUMERIC(6, 2),
    p90               NUMERIC(6, 2),
    queue_length      INT         NOT NULL DEFAULT 0,
    risk_level        TEXT        NOT NULL CHECK (risk_level IN ('BAJO', 'MEDIO', 'ALTO')),
    confidence        TEXT        NOT NULL DEFAULT 'MEDIA' CHECK (confidence IN ('BAJA', 'MEDIA', 'ALTA')),
    drivers           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    model             TEXT        NOT NULL,
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (gate_id, horizon_min, computed_at)
);

CREATE INDEX idx_congestion_forecast_gate ON congestion_forecast (gate_id, computed_at DESC);

-- ---------------------------------------------------------------------------
-- Audit trail
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    yard_id     UUID,
    actor       TEXT        NOT NULL,
    action      TEXT        NOT NULL,
    entity_type TEXT        NOT NULL,
    entity_id   TEXT,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id, at DESC);
CREATE INDEX idx_audit_log_at ON audit_log (at DESC);

-- Keep updated_at consistent.
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER yard_touch_trg BEFORE UPDATE ON yard FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER container_touch_trg BEFORE UPDATE ON container FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER appointment_touch_trg BEFORE UPDATE ON appointment FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER visit_log_touch_trg BEFORE UPDATE ON visit_log FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER review_task_touch_trg BEFORE UPDATE ON review_task FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
