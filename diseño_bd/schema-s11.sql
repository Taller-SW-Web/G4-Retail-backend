-- =============================================================================
-- MIGRACIÓN S11 (HITO 4) — CAMBIOS, VALES DE COMPRA (F9) Y COBROS POR TURNO (F8)
-- Marketplace Multicanal de Artículos Deportivos — Retail (Grupo 4) — Ciclo 2026-II
-- Responsable: Angie (DBA)
--
-- Requiere haber ejecutado antes diseño/schema.sql (depende de RET_CAJA_SESION y
-- RET_SOLICITUD_CAMBIO_MOSTRADOR). Es idempotente: se puede ejecutar más de una vez.
--
-- Cambios:
--   1. RET_SOLICITUD_CAMBIO_MOSTRADOR: + boleta_numero, + tienda_id, + estado de la solicitud
--   2. RET_VALE_COMPRA (nueva): vale VALE-2026-XXXXX con saldo a favor, vence a los 30 días
--   3. Secuencia y función generadora del código de vale
--   4. Índices sobre boleta_numero, cliente_id y tienda_id
--   5. RLS y políticas (mismo criterio que el resto de tablas RET_*)
--   6. RET_COBRO_TURNO (nueva): cobro de la venta asociado al turno, para el efectivo del corte Z
--
-- Reglas de la guía S11: boleta con <= 7 días naturales (RF-23); vale con vencimiento a 30 días
-- y estados EMITIDO | CANJEADO | ANULADO (RF-25).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. RET_SOLICITUD_CAMBIO_MOSTRADOR: boleta de origen y tienda
--    Las columnas se agregan NULL para no romper filas existentes; la API (F9) las exige.
--    No hay FK a una tabla de ventas: el comprobante lo emite Retail pero el pedido vive en
--    Ventas (G5), por eso solo se indexa y se valida el formato.
-- -----------------------------------------------------------------------------
ALTER TABLE RET_SOLICITUD_CAMBIO_MOSTRADOR
    ADD COLUMN IF NOT EXISTS boleta_numero VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS tienda_id     TEXT        NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cambio_boleta_formato') THEN
        ALTER TABLE RET_SOLICITUD_CAMBIO_MOSTRADOR
            ADD CONSTRAINT chk_cambio_boleta_formato
            CHECK (boleta_numero IS NULL OR boleta_numero ~ '^[BF][0-9]{3}-[0-9]{8}$');
    END IF;
END $$;

-- Las solicitudes ya existentes heredan la tienda de su turno de caja.
UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR s
   SET tienda_id = c.tienda_id
  FROM RET_CAJA_SESION c
 WHERE s.caja_sesion_id = c.id_sesion
   AND s.tienda_id IS NULL;

-- -----------------------------------------------------------------------------
-- 2. SECUENCIA Y GENERADOR DEL CÓDIGO DE VALE  (VALE-2026-00001, VALE-2026-00002, ...)
--    El correlativo es global y no se reinicia por año: el año solo forma parte del código.
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS seq_ret_vale_compra START WITH 1 INCREMENT BY 1;

CREATE OR REPLACE FUNCTION fn_generar_codigo_vale()
RETURNS VARCHAR AS $$
    SELECT 'VALE-' || to_char(CURRENT_DATE, 'YYYY') || '-' || lpad(nextval('seq_ret_vale_compra')::text, 5, '0');
$$ LANGUAGE sql;

-- -----------------------------------------------------------------------------
-- 3. TABLA: RET_VALE_COMPRA
--    Vale de compra / nota de crédito térmica emitida tras un cambio aprobado (F9, RF-25).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_VALE_COMPRA (
    id_vale                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo_vale             VARCHAR(20) NOT NULL UNIQUE DEFAULT fn_generar_codigo_vale()
                            CHECK (codigo_vale ~ '^VALE-[0-9]{4}-[0-9]{5,}$'),
    solicitud_cambio_id     UUID NOT NULL,
    boleta_numero           VARCHAR(20) NOT NULL
                            CHECK (boleta_numero ~ '^[BF][0-9]{3}-[0-9]{8}$'),
    cliente_id              UUID NOT NULL,  -- Beneficiario. Ref. externa lógica: Módulo Seguridad
    tienda_id               TEXT NOT NULL,
    monto_a_favor           DECIMAL(12, 2) NOT NULL CHECK (monto_a_favor > 0),
    saldo_disponible        DECIMAL(12, 2) NOT NULL CHECK (saldo_disponible >= 0),
    fecha_emision           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_vencimiento       TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days'),
    estado                  VARCHAR(20) NOT NULL DEFAULT 'EMITIDO'
                            CHECK (estado IN ('EMITIDO', 'CANJEADO', 'ANULADO')),
    fecha_canje             TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vale_solicitud_cambio
        FOREIGN KEY (solicitud_cambio_id) REFERENCES RET_SOLICITUD_CAMBIO_MOSTRADOR(id_solicitud) ON DELETE RESTRICT,
    CONSTRAINT chk_vale_saldo_no_excede_monto CHECK (saldo_disponible <= monto_a_favor),
    CONSTRAINT chk_vale_vencimiento_posterior CHECK (fecha_vencimiento > fecha_emision),
    -- Un vale canjeado no tiene saldo y deja constancia de cuándo se usó
    CONSTRAINT chk_vale_canje_coherente CHECK (
        (estado = 'CANJEADO' AND saldo_disponible = 0 AND fecha_canje IS NOT NULL)
        OR (estado <> 'CANJEADO' AND fecha_canje IS NULL)
    )
);

-- Una solicitud de cambio emite como máximo un vale (evita doble emisión / fraude por doble canje)
CREATE UNIQUE INDEX IF NOT EXISTS uq_vale_solicitud_cambio ON RET_VALE_COMPRA(solicitud_cambio_id);

DROP TRIGGER IF EXISTS trg_ret_vale_compra_updated_at ON RET_VALE_COMPRA;
CREATE TRIGGER trg_ret_vale_compra_updated_at
    BEFORE UPDATE ON RET_VALE_COMPRA
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- -----------------------------------------------------------------------------
-- 4. ÍNDICES
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_cambio_boleta_numero  ON RET_SOLICITUD_CAMBIO_MOSTRADOR(boleta_numero);
CREATE INDEX IF NOT EXISTS idx_cambio_cliente        ON RET_SOLICITUD_CAMBIO_MOSTRADOR(cliente_id);
CREATE INDEX IF NOT EXISTS idx_cambio_tienda_fecha   ON RET_SOLICITUD_CAMBIO_MOSTRADOR(tienda_id, fecha_inspeccion DESC);

CREATE INDEX IF NOT EXISTS idx_vale_boleta_numero    ON RET_VALE_COMPRA(boleta_numero);
CREATE INDEX IF NOT EXISTS idx_vale_cliente_estado   ON RET_VALE_COMPRA(cliente_id, estado);
CREATE INDEX IF NOT EXISTS idx_vale_tienda_estado    ON RET_VALE_COMPRA(tienda_id, estado);
CREATE INDEX IF NOT EXISTS idx_vale_vencimiento      ON RET_VALE_COMPRA(fecha_vencimiento) WHERE estado = 'EMITIDO';

-- -----------------------------------------------------------------------------
-- 5. ROW LEVEL SECURITY
-- -----------------------------------------------------------------------------
ALTER TABLE RET_VALE_COMPRA ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_vale_compra' AND policyname = 'ret_vale_compra_auth_policy') THEN
        CREATE POLICY "ret_vale_compra_auth_policy" ON RET_VALE_COMPRA FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 6. TABLA: RET_COBRO_TURNO
--    Cobro de una venta asociado al turno de caja, para que el efectivo cuente en el arqueo
--    y en el Reporte Z (F8 + F5).
--
--    Es un REGISTRO DE CAJA, no la venta: Retail no guarda pedidos ni pagos (viven en Ventas, G5).
--    Aqui solo queda cuanto entro a la gaveta en el turno, con el pedido de G5 como referencia
--    logica (sin FK, igual que boleta_numero).
--
--    - idempotency_key UNICA: reintentar una venta con la misma llave no crea otro cobro.
--    - El efectivo de la gaveta suma monto_total (el vuelto ya salio) de los cobros en EFECTIVO
--      cuyo estado no sea ANULADO; los pendientes y en revision cuentan porque el dinero ya esta
--      en la gaveta.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_COBRO_TURNO (
    id_cobro                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caja_sesion_id          UUID NOT NULL,
    idempotency_key         VARCHAR(64) NOT NULL UNIQUE,
    pedido_id               VARCHAR(40) NULL,   -- PED-AAAA-NNNNN de G5 (o el de contingencia local)
    comprobante_serie       VARCHAR(4)  NULL,
    comprobante_numero      VARCHAR(20) NULL,
    medio_pago              VARCHAR(20) NOT NULL
                            CHECK (medio_pago IN ('EFECTIVO', 'TARJETA_POS')),
    monto_total             DECIMAL(12, 2) NOT NULL CHECK (monto_total > 0),
    monto_recibido          DECIMAL(12, 2) NOT NULL,
    vuelto                  DECIMAL(12, 2) NOT NULL DEFAULT 0 CHECK (vuelto >= 0),
    estado                  VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE_CONFIRMACION'
                            CHECK (estado IN ('PENDIENTE_CONFIRMACION', 'CONFIRMADO',
                                              'EN_REVISION_SUPERVISOR', 'ANULADO')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cobro_caja_sesion
        FOREIGN KEY (caja_sesion_id) REFERENCES RET_CAJA_SESION(id_sesion) ON DELETE RESTRICT,
    CONSTRAINT chk_cobro_vuelto_coherente
        CHECK (monto_recibido >= monto_total AND vuelto = monto_recibido - monto_total)
);

DROP TRIGGER IF EXISTS trg_ret_cobro_turno_updated_at ON RET_COBRO_TURNO;
CREATE TRIGGER trg_ret_cobro_turno_updated_at
    BEFORE UPDATE ON RET_COBRO_TURNO
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE INDEX IF NOT EXISTS idx_cobro_turno_estado ON RET_COBRO_TURNO(caja_sesion_id, estado);
CREATE INDEX IF NOT EXISTS idx_cobro_pedido       ON RET_COBRO_TURNO(pedido_id) WHERE pedido_id IS NOT NULL;

ALTER TABLE RET_COBRO_TURNO ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_cobro_turno' AND policyname = 'ret_cobro_turno_auth_policy') THEN
        CREATE POLICY "ret_cobro_turno_auth_policy" ON RET_COBRO_TURNO FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;
END $$;
