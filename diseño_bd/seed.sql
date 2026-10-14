-- =============================================================================
-- DATOS SEMILLA — BASE DE DATOS RETAIL (GRUPO 4)
-- Requiere haber ejecutado antes schema.sql (Supabase / PostgreSQL 15+)
--
-- Alcance: solo las tablas RET_* del módulo Retail. Los identificadores de
-- tienda, vendedor, cliente, producto, variante SKU y pedido son UUID lógicos de otros
-- módulos (Seguridad, Productos, Ventas): aquí se usan UUID fijos de prueba (mocks).
--
-- Datos coherentes entre sí:
--   * Tienda TIENDA-MIRAFLORES = tienda_id 11111111-1111-1111-1111-111111111111
--     (identificador lógico; la tabla de tiendas pertenece a otro módulo).
--   * 2 cajas = códigos de terminal POS-01 y POS-02.
--   * 6 días de historial (ayer hacia atrás), en las 2 cajas; todos los turnos
--     están cerrados (CERRADA u OBSERVADA), no hay turnos abiertos.
--   * Cada turno cerrado: saldo_sistema = fondo inicial + ventas en efectivo
--     + ingresos menores - gastos, y las ventas coinciden con los registros
--     VENTA de la auditoría.
--   * La auditoría de APERTURA/CIERRE la genera el trigger de RET_CAJA_SESION
--     en uso normal; durante la carga se desactiva para sembrar el historial
--     con sus fechas reales (y se vuelve a activar al final).
--
-- Idempotente: se puede ejecutar varias veces (ON CONFLICT DO NOTHING).
-- =============================================================================

BEGIN;

-- Desactivar temporalmente la auditoría automática (se reactiva antes del COMMIT)
ALTER TABLE RET_CAJA_SESION DISABLE TRIGGER trg_ret_caja_sesion_audit_ins;
ALTER TABLE RET_CAJA_SESION DISABLE TRIGGER trg_ret_caja_sesion_audit_upd;

-- -----------------------------------------------------------------------------
-- 1. RET_PERSONAL_TIENDA — 6 colaboradores (uno inactivo)
-- -----------------------------------------------------------------------------
INSERT INTO RET_PERSONAL_TIENDA (id_personal, usuario_id, tienda_id, codigo_vendedor, perfil_tienda, activo) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'VEN-001', 'VENDEDOR',   TRUE),
 ('aaaaaaaa-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'CAJ-001', 'CAJERO',     TRUE),
 ('aaaaaaaa-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'CAJ-002', 'CAJERO',     TRUE),
 ('aaaaaaaa-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'SUP-001', 'SUPERVISOR', TRUE),
 ('aaaaaaaa-0000-0000-0000-000000000005', 'bbbbbbbb-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'VEN-002', 'VENDEDOR',   TRUE),
 ('aaaaaaaa-0000-0000-0000-000000000006', 'bbbbbbbb-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'CAJ-003', 'CAJERO',     FALSE)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Base de cálculo de turnos y ventas (tablas temporales, se eliminan al COMMIT)
--   d = días atrás (1 = ayer), t = caja (1 = POS-01, 2 = POS-02)
--   Cajero: POS-01 -> CAJ-001 (bbbb..02), POS-02 -> CAJ-002 (bbbb..03)
--   3 ventas en efectivo por turno; monto = 100 + 12.5*d + 20*t + 35.5*k
-- -----------------------------------------------------------------------------
CREATE TEMP TABLE seed_venta ON COMMIT DROP AS
SELECT d, t, k,
       ROUND((100 + 12.5 * d + 20 * t + 35.5 * k)::numeric, 2) AS monto
FROM generate_series(1, 6) d, generate_series(1, 2) t, generate_series(1, 3) k;

CREATE TEMP TABLE seed_turno ON COMMIT DROP AS
SELECT d, t,
       CASE WHEN t = 1 THEN 200.00 ELSE 150.00 END                      AS fondo,
       (SELECT SUM(monto) FROM seed_venta v WHERE v.d = x.d AND v.t = x.t) AS ventas,
       CASE WHEN d = 2 AND t = 2 THEN -10.00     -- faltante
            WHEN d = 3 AND t = 1 THEN  5.50      -- sobrante
            ELSE 0.00 END                                               AS diferencia
FROM (SELECT d, t FROM generate_series(1, 6) d, generate_series(1, 2) t) x;

-- -----------------------------------------------------------------------------
-- 2. RET_CAJA_SESION — 12 turnos cerrados (10 CERRADA + 2 OBSERVADA)
--    saldo_sistema = fondo + ventas + 50.00 (ingreso menor) - 25.00 (gasto)
-- -----------------------------------------------------------------------------
INSERT INTO RET_CAJA_SESION
 (id_sesion, tienda_id, terminal_pos_codigo, vendedor_id, fecha_hora_apertura, saldo_inicial_efectivo,
  fecha_hora_cierre, saldo_final_declarado, saldo_final_sistema, diferencia_saldo, estado, observaciones_cierre)
SELECT
  format('cccccccc-0000-0000-%s-%s', lpad(d::text, 4, '0'), lpad(t::text, 12, '0'))::uuid,
  '11111111-1111-1111-1111-111111111111',
  CASE WHEN t = 1 THEN 'POS-01' ELSE 'POS-02' END,
  CASE WHEN t = 1 THEN 'bbbbbbbb-0000-0000-0000-000000000002'::uuid ELSE 'bbbbbbbb-0000-0000-0000-000000000003'::uuid END,
  date_trunc('day', CURRENT_TIMESTAMP) - make_interval(days => d) + INTERVAL '9 hours',
  fondo,
  date_trunc('day', CURRENT_TIMESTAMP) - make_interval(days => d) + INTERVAL '21 hours',
  fondo + ventas + 25.00 + diferencia,
  fondo + ventas + 25.00,
  diferencia,
  CASE WHEN diferencia <> 0 THEN 'OBSERVADA' ELSE 'CERRADA' END,
  CASE WHEN diferencia = 0 THEN NULL
       WHEN diferencia < 0 THEN 'Faltante de S/ ' || abs(diferencia) || ' en arqueo ciego; pendiente de revisión del supervisor.'
       ELSE 'Sobrante de S/ ' || diferencia || ' en arqueo ciego; pendiente de revisión del supervisor.' END
FROM seed_turno
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. RET_MOVIMIENTO_CAJA — por cada turno: +50.00 sencillo y -25.00 insumos
-- -----------------------------------------------------------------------------
INSERT INTO RET_MOVIMIENTO_CAJA (id_movimiento, caja_sesion_id, tipo_movimiento, monto, motivo, autorizado_por_supervisor, fecha_hora)
SELECT format('dddddddd-0000-%s-%s-%s', lpad(d::text, 4, '0'), lpad(t::text, 4, '0'), lpad('1', 12, '0'))::uuid,
       format('cccccccc-0000-0000-%s-%s', lpad(d::text, 4, '0'), lpad(t::text, 12, '0'))::uuid,
       'INGRESO_MENOR', 50.00, 'Reposición de sencillo (monedas) desde tesorería', 'SUP-001',
       date_trunc('day', CURRENT_TIMESTAMP) - make_interval(days => d) + INTERVAL '13 hours'
FROM seed_turno
UNION ALL
SELECT format('dddddddd-0000-%s-%s-%s', lpad(d::text, 4, '0'), lpad(t::text, 4, '0'), lpad('2', 12, '0'))::uuid,
       format('cccccccc-0000-0000-%s-%s', lpad(d::text, 4, '0'), lpad(t::text, 12, '0'))::uuid,
       'SALIDA_GASTO', 25.00, 'Compra de bolsas y cinta para empaque', 'SUP-001',
       date_trunc('day', CURRENT_TIMESTAMP) - make_interval(days => d) + INTERVAL '16 hours'
FROM seed_turno
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. RET_AUDITORIA_OPERACIONES — APERTURA, VENTA y CIERRE de cada turno
-- -----------------------------------------------------------------------------
-- APERTURA (una por turno)
INSERT INTO RET_AUDITORIA_OPERACIONES
 (id_auditoria, tienda_id, usuario_id, caja_sesion_id, terminal_pos_codigo, accion, estado, referencia_id, detalle, detalle_json, fecha_hora)
SELECT format('15151515-%s-%s-0001-%s', lpad(d::text, 4, '0'), lpad(t::text, 4, '0'), lpad('0', 12, '0'))::uuid,
       s.tienda_id, s.vendedor_id, s.id_sesion, s.terminal_pos_codigo, 'APERTURA', 'EXITOSO', NULL,
       'Apertura de turno con fondo fijo S/ ' || s.saldo_inicial_efectivo,
       jsonb_build_object('saldo_inicial', s.saldo_inicial_efectivo),
       s.fecha_hora_apertura
FROM seed_turno x
JOIN RET_CAJA_SESION s ON s.id_sesion = format('cccccccc-0000-0000-%s-%s', lpad(x.d::text, 4, '0'), lpad(x.t::text, 12, '0'))::uuid
ON CONFLICT DO NOTHING;

-- VENTA exitosa en efectivo (3 por turno)
INSERT INTO RET_AUDITORIA_OPERACIONES
 (id_auditoria, tienda_id, usuario_id, caja_sesion_id, terminal_pos_codigo, accion, estado, referencia_id, detalle, detalle_json, fecha_hora)
SELECT format('15151515-%s-%s-0002-%s', lpad(v.d::text, 4, '0'), lpad(v.t::text, 4, '0'), lpad(v.k::text, 12, '0'))::uuid,
       s.tienda_id, s.vendedor_id, s.id_sesion, s.terminal_pos_codigo, 'VENTA', 'EXITOSO',
       format('66666666-0000-%s-%s-%s', lpad(v.d::text, 4, '0'), lpad(v.t::text, 4, '0'), lpad(v.k::text, 12, '0'))::uuid,
       'Venta en efectivo, boleta emitida',
       jsonb_build_object('monto', v.monto, 'medio_pago', 'EFECTIVO'),
       s.fecha_hora_apertura + make_interval(hours => v.k * 3)
FROM seed_venta v
JOIN RET_CAJA_SESION s ON s.id_sesion = format('cccccccc-0000-0000-%s-%s', lpad(v.d::text, 4, '0'), lpad(v.t::text, 12, '0'))::uuid
ON CONFLICT DO NOTHING;

-- VENTA fallida (cobro con tarjeta rechazado) en los turnos observados
INSERT INTO RET_AUDITORIA_OPERACIONES
 (id_auditoria, tienda_id, usuario_id, caja_sesion_id, terminal_pos_codigo, accion, estado, referencia_id, detalle, detalle_json, fecha_hora)
SELECT format('15151515-%s-%s-0002-%s', lpad(x.d::text, 4, '0'), lpad(x.t::text, 4, '0'), lpad('99', 12, '0'))::uuid,
       s.tienda_id, s.vendedor_id, s.id_sesion, s.terminal_pos_codigo, 'VENTA', 'FALLIDO', NULL,
       'Cobro con tarjeta rechazado por el POS',
       jsonb_build_object('monto', 189.90, 'medio_pago', 'TARJETA', 'motivo', 'RECHAZADA_POR_EMISOR'),
       s.fecha_hora_apertura + INTERVAL '5 hours'
FROM seed_turno x
JOIN RET_CAJA_SESION s ON s.id_sesion = format('cccccccc-0000-0000-%s-%s', lpad(x.d::text, 4, '0'), lpad(x.t::text, 12, '0'))::uuid
WHERE x.diferencia <> 0
ON CONFLICT DO NOTHING;

-- CIERRE (una por turno)
INSERT INTO RET_AUDITORIA_OPERACIONES
 (id_auditoria, tienda_id, usuario_id, caja_sesion_id, terminal_pos_codigo, accion, estado, referencia_id, detalle, detalle_json, fecha_hora)
SELECT format('15151515-%s-%s-0003-%s', lpad(x.d::text, 4, '0'), lpad(x.t::text, 4, '0'), lpad('0', 12, '0'))::uuid,
       s.tienda_id, s.vendedor_id, s.id_sesion, s.terminal_pos_codigo, 'CIERRE',
       CASE WHEN s.estado = 'OBSERVADA' THEN 'OBSERVADO' ELSE 'EXITOSO' END, NULL,
       'Cierre Z: declarado S/ ' || s.saldo_final_declarado || ', sistema S/ ' || s.saldo_final_sistema,
       jsonb_build_object('saldo_declarado', s.saldo_final_declarado, 'saldo_sistema', s.saldo_final_sistema, 'diferencia', s.diferencia_saldo),
       s.fecha_hora_cierre
FROM seed_turno x
JOIN RET_CAJA_SESION s ON s.id_sesion = format('cccccccc-0000-0000-%s-%s', lpad(x.d::text, 4, '0'), lpad(x.t::text, 12, '0'))::uuid
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. RET_CARRITO_ESPERA — carritos suspendidos (probadores)
-- -----------------------------------------------------------------------------
INSERT INTO RET_CARRITO_ESPERA
 (id_carrito_espera, tienda_id, vendedor_id, cliente_id, alias_ticket, subtotal_estimado, fecha_creacion, fecha_expiracion_reserva, estado) VALUES
 ('eeeeeeee-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', 'ffffffff-0000-0000-0000-000000000001',
  'Cliente polo azul - probador 2', 379.80, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '14 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '14 hours 30 minutes', 'REANUDADO'),
 ('eeeeeeee-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', NULL,
  'Señora zapatillas running', 249.90, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '3 days' + INTERVAL '11 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '3 days' + INTERVAL '11 hours 30 minutes', 'EXPIRADO'),
 ('eeeeeeee-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000002', 'ffffffff-0000-0000-0000-000000000002',
  'Joven short + medias', 89.80, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '12 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '12 hours 30 minutes', 'REANUDADO'),
 ('eeeeeeee-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000005', 'ffffffff-0000-0000-0000-000000000003',
  'Familia camisetas club', 239.70, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '16 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '16 hours 45 minutes', 'EXPIRADO'),
 ('eeeeeeee-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000005', NULL,
  'Caballero buzo y casaca', 320.00, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '4 days' + INTERVAL '10 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '4 days' + INTERVAL '10 hours 30 minutes', 'EXPIRADO')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 6. RET_CARRITO_ESPERA_ITEM — ítems (producto_id/variante_sku_id: UUID mock del módulo Productos)
--    subtotal_estimado de cada carrito = suma(cantidad * precio_unitario)
-- -----------------------------------------------------------------------------
INSERT INTO RET_CARRITO_ESPERA_ITEM
 (id_item, carrito_espera_id, producto_id, variante_sku_id, nombre_producto, talla_color, cantidad, precio_unitario) VALUES
 -- Carrito 1: 2*79.90 + 59.90 + 160.10 = 379.80
 ('99999999-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000001', '77777777-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000001', 'Polo Dry-Fit Hombre',      'M / Azul',    2, 79.90),
 ('99999999-0000-0000-0000-000000000002', 'eeeeeeee-0000-0000-0000-000000000001', '77777777-0000-0000-0000-000000000002', '88888888-0000-0000-0000-000000000002', 'Short Deportivo Training', 'L / Negro',   1, 59.90),
 ('99999999-0000-0000-0000-000000000003', 'eeeeeeee-0000-0000-0000-000000000001', '77777777-0000-0000-0000-000000000004', '88888888-0000-0000-0000-000000000004', 'Casaca Rompevientos',      'M / Gris',    1, 160.10),
 -- Carrito 2: 249.90
 ('99999999-0000-0000-0000-000000000004', 'eeeeeeee-0000-0000-0000-000000000002', '77777777-0000-0000-0000-000000000003', '88888888-0000-0000-0000-000000000003', 'Zapatillas Running Pro',   '38 / Blanco', 1, 249.90),
 -- Carrito 3: 59.90 + 2*14.95 = 89.80
 ('99999999-0000-0000-0000-000000000005', 'eeeeeeee-0000-0000-0000-000000000003', '77777777-0000-0000-0000-000000000002', '88888888-0000-0000-0000-000000000005', 'Short Deportivo Training', 'S / Rojo',    1, 59.90),
 ('99999999-0000-0000-0000-000000000006', 'eeeeeeee-0000-0000-0000-000000000003', '77777777-0000-0000-0000-000000000005', '88888888-0000-0000-0000-000000000006', 'Medias Deportivas (par)',  'U / Blanco',  2, 14.95),
 -- Carrito 4: 3*79.90 = 239.70
 ('99999999-0000-0000-0000-000000000007', 'eeeeeeee-0000-0000-0000-000000000004', '77777777-0000-0000-0000-000000000006', '88888888-0000-0000-0000-000000000007', 'Camiseta Club Oficial',    'S / Rojo',    1, 79.90),
 ('99999999-0000-0000-0000-000000000008', 'eeeeeeee-0000-0000-0000-000000000004', '77777777-0000-0000-0000-000000000006', '88888888-0000-0000-0000-000000000008', 'Camiseta Club Oficial',    'M / Rojo',    1, 79.90),
 ('99999999-0000-0000-0000-000000000009', 'eeeeeeee-0000-0000-0000-000000000004', '77777777-0000-0000-0000-000000000006', '88888888-0000-0000-0000-000000000009', 'Camiseta Club Oficial',    'L / Rojo',    1, 79.90),
 -- Carrito 5: 140.00 + 180.00 = 320.00
 ('99999999-0000-0000-0000-000000000010', 'eeeeeeee-0000-0000-0000-000000000005', '77777777-0000-0000-0000-000000000007', '88888888-0000-0000-0000-000000000010', 'Buzo Deportivo Fleece',    'L / Azul',    1, 140.00),
 ('99999999-0000-0000-0000-000000000011', 'eeeeeeee-0000-0000-0000-000000000005', '77777777-0000-0000-0000-000000000004', '88888888-0000-0000-0000-000000000011', 'Casaca Rompevientos',      'L / Negro',   1, 180.00)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 7. RET_SOLICITUD_CAMBIO_MOSTRADOR — cambios en tienda (4: 2 aprobados, 2 rechazados)
--    pedido_id_origen reutiliza pedidos mock de las ventas sembradas
-- -----------------------------------------------------------------------------
INSERT INTO RET_SOLICITUD_CAMBIO_MOSTRADOR
 (id_solicitud, caja_sesion_id, pedido_id_origen, variante_sku_devuelta_id, cliente_id, motivo_cambio,
  inspeccion_etiquetas, inspeccion_sin_uso, inspeccion_empaque, estado_aprobacion, vale_temporal_codigo, monto_acreditado, fecha_inspeccion) VALUES
 ('12121212-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0001-000000000001', '66666666-0000-0001-0001-000000000001', '88888888-0000-0000-0000-000000000001',
  'ffffffff-0000-0000-0000-000000000001', 'CAMBIO_TALLA', TRUE, TRUE, TRUE, 'APROBADO_EN_TIENDA', 'VALE-MIR-0001', 79.90, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '11 hours 0 minutes'),
 ('12121212-0000-0000-0000-000000000002', 'cccccccc-0000-0000-0002-000000000002', '66666666-0000-0002-0002-000000000001', '88888888-0000-0000-0000-000000000003',
  'ffffffff-0000-0000-0000-000000000002', 'CAMBIO_MODELO', TRUE, FALSE, TRUE, 'RECHAZADO', NULL, 0.00, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '15 hours 0 minutes'),
 ('12121212-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0003-000000000001', '66666666-0000-0003-0001-000000000002', '88888888-0000-0000-0000-000000000004',
  'ffffffff-0000-0000-0000-000000000003', 'FALLA_FABRICA', TRUE, TRUE, TRUE, 'APROBADO_EN_TIENDA', 'VALE-MIR-0002', 160.10, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '3 days' + INTERVAL '12 hours 0 minutes'),
 ('12121212-0000-0000-0000-000000000004', 'cccccccc-0000-0000-0001-000000000002', '66666666-0000-0001-0002-000000000003', '88888888-0000-0000-0000-000000000002',
  'ffffffff-0000-0000-0000-000000000002', 'CAMBIO_TALLA', FALSE, TRUE, FALSE, 'RECHAZADO', NULL, 0.00, date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '17 hours 0 minutes')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 8. RET_INCIDENCIA_INVENTARIO — mermas / cuarentena (codigo_barras: EAN-13 escaneado de prueba)
-- -----------------------------------------------------------------------------
INSERT INTO RET_INCIDENCIA_INVENTARIO
 (id_incidencia, tienda_id, vendedor_reporta_id, variante_sku_id, codigo_barras, tipo_falla, detalle_observacion, evidencia_foto_url, estado_cuarentena, fecha_reporte) VALUES
 ('13131313-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000001',
  '7750000000011', 'MANCHADO_PROBADOR', 'Polo con mancha de maquillaje tras uso en probador.', NULL, 'EN_CUARENTENA', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '3 days' + INTERVAL '10 hours 30 minutes'),
 ('13131313-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000002', '88888888-0000-0000-0000-000000000004',
  '7750000000042', 'COSTURA_ROTA', 'Costura del cierre lateral abierta.', 'https://example.com/evidencias/costura-001.jpg', 'DERIVADO_ALMACEN', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '12 hours 0 minutes'),
 ('13131313-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000004', '88888888-0000-0000-0000-000000000003',
  '7750000000035', 'EXTRAVIO_NO_UBICADO', 'Par de zapatillas no ubicado en conteo de piso.', NULL, 'RECHAZADO', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '5 days' + INTERVAL '17 hours 0 minutes'),
 ('13131313-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000005', '88888888-0000-0000-0000-000000000007',
  '7750000000073', 'DEFECTO_FABRICA', 'Estampado del escudo despegado en la camiseta.', 'https://example.com/evidencias/estampado-002.jpg', 'DERIVADO_ALMACEN', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '4 days' + INTERVAL '11 hours 0 minutes'),
 ('13131313-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000010',
  '7750000000100', 'MANCHADO_PROBADOR', 'Buzo con marca de grasa en la manga.', NULL, 'DESCARTADO', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '16 hours 0 minutes')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 9. RET_CONTINGENCIA_OFFLINE_LOG — ventas emitidas sin conexión (5)
--     firma_hash_seguridad = SHA-256 hex (64 caracteres) del identificador de venta
-- -----------------------------------------------------------------------------
INSERT INTO RET_CONTINGENCIA_OFFLINE_LOG
 (id_log, tienda_id, terminal_pos_codigo, venta_local_uuid, payload_json_orden, firma_hash_seguridad,
  estado_sincronizacion, fecha_emision_offline, fecha_sincronizacion, error_detalle) VALUES
 ('14141414-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'POS-01', 'OFF-POS01-20260001',
  '{"store_code":"TIENDA-MIRAFLORES","items":[{"barcode":"7750000000011","cantidad":1,"precio":79.90}],"medio_pago":"EFECTIVO","total":79.90}'::jsonb,
  encode(digest('OFF-POS01-20260001', 'sha256'), 'hex'),
  'RESINCRONIZADO', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '11 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '12 hours 0 minutes', NULL),
 ('14141414-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'POS-02', 'OFF-POS02-20260001',
  '{"store_code":"TIENDA-MIRAFLORES","items":[{"barcode":"7750000000035","cantidad":1,"precio":249.90}],"medio_pago":"TARJETA","total":249.90}'::jsonb,
  encode(digest('OFF-POS02-20260001', 'sha256'), 'hex'),
  'PENDIENTE', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '13 hours 0 minutes', NULL, NULL),
 ('14141414-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'POS-02', 'OFF-POS02-20260002',
  '{"store_code":"TIENDA-MIRAFLORES","items":[{"barcode":"7750000000042","cantidad":2,"precio":160.10}],"medio_pago":"EFECTIVO","total":320.20}'::jsonb,
  encode(digest('OFF-POS02-20260002', 'sha256'), 'hex'),
  'CONFLICTO', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '16 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '17 hours 0 minutes', 'Stock insuficiente reportado por Ventas (G5) al resincronizar.'),
 ('14141414-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'POS-01', 'OFF-POS01-20260002',
  '{"store_code":"TIENDA-MIRAFLORES","items":[{"barcode":"7750000000073","cantidad":3,"precio":79.90}],"medio_pago":"EFECTIVO","total":239.70}'::jsonb,
  encode(digest('OFF-POS01-20260002', 'sha256'), 'hex'),
  'RESINCRONIZADO', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '10 hours 0 minutes', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '2 days' + INTERVAL '11 hours 0 minutes', NULL),
 ('14141414-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'POS-01', 'OFF-POS01-20260003',
  '{"store_code":"TIENDA-MIRAFLORES","items":[{"barcode":"7750000000100","cantidad":1,"precio":140.00}],"medio_pago":"TARJETA","total":140.00}'::jsonb,
  encode(digest('OFF-POS01-20260003', 'sha256'), 'hex'),
  'PENDIENTE', date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '1 days' + INTERVAL '15 hours 0 minutes', NULL, NULL)
ON CONFLICT DO NOTHING;

-- Reactivar la auditoría automática
ALTER TABLE RET_CAJA_SESION ENABLE TRIGGER trg_ret_caja_sesion_audit_ins;
ALTER TABLE RET_CAJA_SESION ENABLE TRIGGER trg_ret_caja_sesion_audit_upd;

COMMIT;