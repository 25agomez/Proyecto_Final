-- V2__seed_demo.sql
-- LogisticaPortuaria CR - demo yard (deterministic, synthetic data only).
-- 1 yard, 6 blocks, 630 slots, 110 containers, gates, appointments, fleet.

-- ---------------------------------------------------------------------------
-- Yard, blocks and gates
-- ---------------------------------------------------------------------------

INSERT INTO yard (id, code, name, timezone, address)
VALUES ('11111111-1111-7111-8111-111111111111', 'DEMO', 'Patio Demo Caribe', 'America/Costa_Rica', 'Puerto Caldera, Guanacaste');

INSERT INTO yard_block (id, yard_id, code, max_tier, reefer_only, hazmat_only)
VALUES
    ('22222222-2222-7222-8222-222222222221', '11111111-1111-7111-8111-111111111111', 'A', 3, TRUE,  FALSE),
    ('22222222-2222-7222-8222-222222222222', '11111111-1111-7111-8111-111111111111', 'B', 3, FALSE, FALSE),
    ('22222222-2222-7222-8222-222222222223', '11111111-1111-7111-8111-111111111111', 'C', 3, FALSE, FALSE),
    ('22222222-2222-7222-8222-222222222224', '11111111-1111-7111-8111-111111111111', 'D', 3, FALSE, FALSE),
    ('22222222-2222-7222-8222-222222222225', '11111111-1111-7111-8111-111111111111', 'E', 3, FALSE, FALSE),
    ('22222222-2222-7222-8222-222222222226', '11111111-1111-7111-8111-111111111111', 'F', 3, FALSE, TRUE);

-- Slot layout per block: bays 1,3,5,7,9 are 40ft slots (each one spans its bay
-- and the next one); bays 11 and 12 are 20ft slots.
-- 5 rows x 3 tiers x 7 slots = 105 slots/block, 630 for the six blocks.
INSERT INTO yard_slot (block_id, bay, row, tier, size_ft, slot_type, has_power, capacity_teu)
SELECT b.id,
       s.bay,
       r.rn,
       t.tier,
       CASE WHEN s.bay <= 10 THEN 40 ELSE 20 END,
       CASE WHEN b.code = 'A' THEN 'reefer' WHEN b.code = 'F' THEN 'hazmat' ELSE 'dry' END,
       (b.code = 'A'),
       CASE WHEN s.bay <= 10 THEN 2.0 ELSE 1.0 END
FROM yard_block b
         CROSS JOIN (SELECT generate_series(1, 12) AS bay) s
         CROSS JOIN (SELECT generate_series(1, 5) AS rn) r
         CROSS JOIN (SELECT generate_series(1, 3) AS tier) t
WHERE (s.bay <= 10 AND s.bay % 2 = 1) OR s.bay >= 11;

INSERT INTO gate (id, yard_id, code, name, kind, lanes)
VALUES ('33333333-3333-7333-8333-333333333331', '11111111-1111-7111-8111-111111111111', 'G1', 'Puerta 1 - Ingreso', 'IN', 3),
       ('33333333-3333-7333-8333-333333333332', '11111111-1111-7111-8111-111111111111', 'G2', 'Puerta 2 - Salida', 'OUT', 2);

-- ---------------------------------------------------------------------------
-- Containers: valid ISO 6346 codes (check digit computed by the DB)
-- ---------------------------------------------------------------------------

INSERT INTO container (code, size_ft, container_type, status, weight_kg, client_code, eta, etd, requires_power)
SELECT 'LRGU' || lpad(i::text, 6, '0') || bic_check_digit('LRGU' || lpad(i::text, 6, '0'))::text,
       CASE WHEN i % 3 = 0 THEN 40 ELSE 20 END,
       'dry',
       'IN_YARD',
       8000 + (i * 137) % 22000,
       'CLI-' || (1 + i % 5),
       now() - interval '2 days',
       now() + ((i % 72) || ' hours')::interval,
       FALSE
FROM generate_series(1, 110) AS i;

-- ---------------------------------------------------------------------------
-- Placements: 90 containers on tier 1, 20 on tier 2 (support is enforced by trigger)
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE tmp_tier1 ON COMMIT DROP AS
WITH slots_t1 AS (SELECT s.id AS slot_id,
                         s.size_ft,
                         s.block_id,
                         s.row,
                         s.bay,
                         ROW_NUMBER() OVER (PARTITION BY s.size_ft ORDER BY b.code, s.row, s.bay) AS rn
                  FROM yard_slot s
                           JOIN yard_block b ON b.id = s.block_id
                  WHERE s.tier = 1),
     cont AS (SELECT id,
                     size_ft,
                     ROW_NUMBER() OVER (PARTITION BY size_ft ORDER BY code) AS rn
              FROM container
              -- Containers 1..90 go on tier 1; the serial is compared numerically because
              -- the trailing check digit would break a plain string comparison.
              WHERE substring(code FROM 5 FOR 6)::INT <= 90)
SELECT c.id AS container_id, s.slot_id, s.block_id, s.row, s.bay, s.size_ft
FROM slots_t1 s
         JOIN cont c ON c.size_ft = s.size_ft AND c.rn = s.rn;

INSERT INTO container_position (container_id, slot_id, placed_at)
SELECT container_id, slot_id, now() - interval '48 hours'
FROM tmp_tier1;

WITH c2 AS (SELECT id,
                   size_ft,
                   ROW_NUMBER() OVER (PARTITION BY size_ft ORDER BY code) AS rn
            FROM container
            WHERE substring(code FROM 5 FOR 6)::INT > 90),
     tier2 AS (SELECT s.id AS slot_id,
                       s.size_ft,
                       s.block_id,
                       s.row,
                       s.bay,
                       ROW_NUMBER() OVER (PARTITION BY s.size_ft ORDER BY t.block_id, t.row, t.bay) AS rn
                FROM yard_slot s
                         JOIN tmp_tier1 t ON t.block_id = s.block_id AND t.row = s.row AND t.bay = s.bay
                WHERE s.tier = 2)
INSERT INTO container_position (container_id, slot_id, placed_at)
SELECT c2.id, tier2.slot_id, now() - interval '24 hours'
FROM tier2
         JOIN c2 ON c2.size_ft = tier2.size_ft AND c2.rn = tier2.rn;

-- Container type follows the block it sits in (reefer block A, hazmat block F).
UPDATE container c
SET container_type = CASE WHEN b.code = 'A' THEN 'reefer' WHEN b.code = 'F' THEN 'hazmat' ELSE 'dry' END,
    requires_power = (b.code = 'A')
FROM container_position cp
         JOIN yard_slot s ON s.id = cp.slot_id
         JOIN yard_block b ON b.id = s.block_id
WHERE cp.container_id = c.id
  AND cp.active;

-- ---------------------------------------------------------------------------
-- Carriers, drivers, trucks and appointments
-- ---------------------------------------------------------------------------

INSERT INTO carrier (id, code, name, tax_id)
VALUES ('44444444-4444-7444-8444-444444444441', 'TRA-01', 'Transportes del Caribe S.A.', '3-101-555001'),
       ('44444444-4444-7444-8444-444444444442', 'TRA-02', 'Carga Nacional S.A.', '3-101-555002'),
       ('44444444-4444-7444-8444-444444444443', 'TRA-03', 'Rastra Pacifico Ltda.', '3-101-555003');

INSERT INTO driver (id, full_name, license_id, phone)
SELECT ('50000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
       'CONDUCTOR DEMO ' || i,
       'LIC-' || i,
       '8000-000' || lpad(i::text, 3, '0')
FROM generate_series(1, 12) AS i;

INSERT INTO truck (id, plate, carrier_id, driver_id)
SELECT ('60000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
       CASE WHEN i % 2 = 0 THEN 'P' || lpad((100000 + i)::text, 6, '0') ELSE chr(65 + (i % 26)) || chr(65 + ((i * 3) % 26)) || chr(65 + ((i * 7) % 26)) || lpad((i % 1000)::text, 3, '0') END,
       (SELECT id FROM carrier ORDER BY code LIMIT 1),
       (SELECT id FROM driver ORDER BY full_name LIMIT 1)
FROM generate_series(1, 12) AS i;

INSERT INTO appointment (yard_id, gate_id, truck_id, appointment_type, scheduled_at, window_minutes, status, container_code)
SELECT '11111111-1111-7111-8111-111111111111',
       (CASE WHEN i % 2 = 0 THEN '33333333-3333-7333-8333-333333333331' ELSE '33333333-3333-7333-8333-333333333332' END)::uuid,
       (SELECT id FROM truck ORDER BY plate LIMIT 1 OFFSET (i - 1)),
       CASE WHEN i % 3 = 0 THEN 'RETIRO' ELSE 'INGRESO' END,
       now() + ((i * 45 - 300) || ' minutes')::interval,
       30,
       CASE WHEN i % 5 = 0 THEN 'PENDIENTE' ELSE 'PENDIENTE' END,
       (SELECT code FROM container ORDER BY code LIMIT 1 OFFSET (i - 1))
FROM generate_series(1, 20) AS i;

-- ---------------------------------------------------------------------------
-- Fleet and geofences
-- ---------------------------------------------------------------------------

INSERT INTO vehicle (id, yard_id, code, vehicle_type, status)
SELECT ('70000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
       '11111111-1111-7111-8111-111111111111',
       'VEH-' || lpad(i::text, 2, '0'),
       CASE WHEN i % 3 = 0 THEN 'RASTRA' ELSE 'MONTACARGAS' END,
       'LIBRE'
FROM generate_series(1, 8) AS i;

INSERT INTO vehicle_last_state (vehicle_id, at, x, y, status)
SELECT v.id,
       now(),
       10.0 * (ROW_NUMBER() OVER (ORDER BY v.code) % 10),
       5.0 * (ROW_NUMBER() OVER (ORDER BY v.code) % 6),
       'LIBRE'
FROM vehicle v;

INSERT INTO geofence (yard_id, name, kind, geojson)
VALUES ('11111111-1111-7111-8111-111111111111', 'Zona de puertas', 'PUERTA',
        '{"type":"Polygon","coordinates":[[[0,0],[120,0],[120,40],[0,40],[0,0]]]}'::jsonb),
       ('11111111-1111-7111-8111-111111111111', 'Zona segregada hazmat', 'HAZMAT',
        '{"type":"Polygon","coordinates":[[[300,0],[420,0],[420,40],[300,40],[300,0]]]}'::jsonb),
       ('11111111-1111-7111-8111-111111111111', 'Area prohibida de mantenimiento', 'PROHIBIDA',
        '{"type":"Polygon","coordinates":[[[200,60],[260,60],[260,100],[200,100],[200,60]]]}'::jsonb);
