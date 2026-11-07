-- =============================================================================
-- DATOS SEMILLA S11 — COBROS POR TURNO Y VALES DE COMPRA (RETAIL, GRUPO 4)
-- Complementa a diseño_bd/seed.sql (no lo modifica).
--
-- Requiere, en este orden:  schema.sql  ->  schema-s11.sql  ->  seed.sql  ->  este archivo.
--
-- Qué siembra, coherente con los 12 turnos cerrados de seed.sql:
--   1. RET_COBRO_TURNO: un cobro CONFIRMADO por cada una de las 36 ventas en efectivo que seed.sql
--      registra en auditoría (3 por turno). Mismo monto, misma hora. El cliente paga redondeado
--      a la decena superior y el resto es el vuelto. Así, para cada turno:
--        fondo + SUM(cobros en efectivo) + ingresos - egresos = saldo_final_sistema guardado.
--   2. Boleta y tienda de las 4 solicitudes de cambio sembradas (boleta del pedido de origen).
--   3. RET_VALE_COMPRA: un vale por cada cambio APROBADO (uno EMITIDO y otro CANJEADO), con
--      vigencia de 30 días desde la inspección.
--
-- Idempotente: se puede ejecutar varias veces (ON CONFLICT DO NOTHING / UPDATE solo si es NULL).
-- No toca turnos ni auditoría, por lo que no dispara los triggers de RET_CAJA_SESION.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. RET_COBRO_TURNO — un cobro por cada venta en efectivo sembrada
--    n = correlativo global 1..36 -> pedido PED-2026-000nn y boleta B001-000000nn
-- -----------------------------------------------------------------------------
INSERT INTO RET_COBRO_TURNO
 (id_cobro, caja_sesion_id, idempotency_key, pedido_id, comprobante_serie, comprobante_numero,
  medio_pago, monto_total, monto_recibido, vuelto, estado, created_at, updated_at)
SELECT
  format('16161616-0000-%s-%s-%s', lpad(v.d::text, 4, '0'), lpad(v.t::text, 4, '0'), lpad(v.k::text, 12, '0'))::uuid,
  s.id_sesion,
  format('seed-s11-%s-%s-%s', v.d, v.t, v.k),
  'PED-2026-' || lpad(v.n::text, 5, '0'),
  'B001',
  lpad(v.n::text, 8, '0'),
  'EFECTIVO',
  v.monto,
  v.recibido,
  v.recibido - v.monto,
  'CONFIRMADO',
  s.fecha_hora_apertura + make_interval(hours => v.k * 3),
  s.fecha_hora_apertura + make_interval(hours => v.k * 3)
FROM (
  SELECT d, t, k,
         ROUND((100 + 12.5 * d + 20 * t + 35.5 * k)::numeric, 2)               AS monto,
         CEIL(ROUND((100 + 12.5 * d + 20 * t + 35.5 * k)::numeric, 2) / 10) * 10 AS recibido,
         (d - 1) * 6 + (t - 1) * 3 + k                                          AS n
  FROM generate_series(1, 6) d, generate_series(1, 2) t, generate_series(1, 3) k
) v
JOIN RET_CAJA_SESION s
  ON s.id_sesion = format('cccccccc-0000-0000-%s-%s', lpad(v.d::text, 4, '0'), lpad(v.t::text, 12, '0'))::uuid
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. Boleta y tienda de las solicitudes de cambio sembradas (columnas añadidas en S11)
--    La boleta es la del pedido de origen: pedido_id_origen = 66666666-0000-<d>-<t>-<k>.
-- -----------------------------------------------------------------------------
UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR SET boleta_numero = 'B001-00000001'
 WHERE id_solicitud = '12121212-0000-0000-0000-000000000001' AND boleta_numero IS NULL;  -- d=1 t=1 k=1
UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR SET boleta_numero = 'B001-00000010'
 WHERE id_solicitud = '12121212-0000-0000-0000-000000000002' AND boleta_numero IS NULL;  -- d=2 t=2 k=1
UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR SET boleta_numero = 'B001-00000014'
 WHERE id_solicitud = '12121212-0000-0000-0000-000000000003' AND boleta_numero IS NULL;  -- d=3 t=1 k=2
UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR SET boleta_numero = 'B001-00000006'
 WHERE id_solicitud = '12121212-0000-0000-0000-000000000004' AND boleta_numero IS NULL;  -- d=1 t=2 k=3

UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR r
   SET tienda_id = c.tienda_id
  FROM RET_CAJA_SESION c
 WHERE r.caja_sesion_id = c.id_sesion
   AND r.tienda_id IS NULL;

-- -----------------------------------------------------------------------------
-- 3. RET_VALE_COMPRA — un vale por cada cambio APROBADO (30 días de vigencia)
--    codigo_vale lo genera la secuencia (VALE-AAAA-NNNNN); el primero queda EMITIDO y el segundo CANJEADO.
-- -----------------------------------------------------------------------------
INSERT INTO RET_VALE_COMPRA
 (id_vale, solicitud_cambio_id, boleta_numero, cliente_id, tienda_id, monto_a_favor, saldo_disponible,
  fecha_emision, fecha_vencimiento, estado, fecha_canje)
SELECT '17171717-0000-0000-0000-000000000001', r.id_solicitud, r.boleta_numero, r.cliente_id, r.tienda_id,
       r.monto_acreditado, r.monto_acreditado,
       r.fecha_inspeccion, r.fecha_inspeccion + INTERVAL '30 days', 'EMITIDO', NULL
  FROM RET_SOLICITUD_CAMBIO_MOSTRADOR r
 WHERE r.id_solicitud = '12121212-0000-0000-0000-000000000001'
   AND r.estado_aprobacion = 'APROBADO_EN_TIENDA'
ON CONFLICT DO NOTHING;

INSERT INTO RET_VALE_COMPRA
 (id_vale, solicitud_cambio_id, boleta_numero, cliente_id, tienda_id, monto_a_favor, saldo_disponible,
  fecha_emision, fecha_vencimiento, estado, fecha_canje)
SELECT '17171717-0000-0000-0000-000000000002', r.id_solicitud, r.boleta_numero, r.cliente_id, r.tienda_id,
       r.monto_acreditado, 0.00,
       r.fecha_inspeccion, r.fecha_inspeccion + INTERVAL '30 days', 'CANJEADO', r.fecha_inspeccion + INTERVAL '1 day'
  FROM RET_SOLICITUD_CAMBIO_MOSTRADOR r
 WHERE r.id_solicitud = '12121212-0000-0000-0000-000000000003'
   AND r.estado_aprobacion = 'APROBADO_EN_TIENDA'
ON CONFLICT DO NOTHING;

COMMIT;
