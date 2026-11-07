-- =============================================================================
-- MIGRACIÓN S11 (HITO 4) — CAMBIOS, VALES DE COMPRA (F9) Y COBROS POR TURNO (F8)
-- Marketplace Multicanal de Artículos Deportivos — Retail (Grupo 4) — Ciclo 2026-II
-- Responsable: Angie (DBA)
--
-- Requiere haber ejecutado antes diseño/schema.sql (depende de RET_CAJA_SESION y
-- RET_SOLICITUD_CAMBIO_MOSTRADOR). Es idempotente: se puede ejecutar más de una vez.
--
-- Cambios unificados con F5 y F9:
--   1. RET_SOLICITUD_CAMBIO_MOSTRADOR: + boleta_numero, + tienda_id, + vendedor_id, + cantidad, + idempotency_key
--   2. RET_VALE_COMPRA (nueva): alineada con F9 (solicitud_id, monto, cliente_id nullable, vigencia 30 días)
--   3. Secuencia y función generadora del código de vale (VALE-AAAA-NNNNN)
--   4. Índices sobre boleta_numero, cliente_id, tienda_id, turno y pedido
--   5. RLS y políticas (mismo criterio que el resto de tablas RET_*)
--   6. RET_COBRO_TURNO (nueva): cobro de la venta asociado al turno, para el efectivo del corte Z (F8)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. RET_SOLICITUD_CAMBIO_MOSTRADOR: boleta de origen, tienda y campos F9
-- -----------------------------------------------------------------------------
ALTER TABLE RET_SOLICITUD_CAMBIO_MOSTRADOR
    ADD COLUMN IF NOT EXISTS boleta_numero VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS tienda_id     TEXT        NULL,
    ADD COLUMN IF NOT EXISTS vendedor_id   UUID        NULL,
    ADD COLUMN IF NOT EXISTS cantidad      INTEGER     NULL CHECK (cantidad IS NULL OR (cantidad BETWEEN 1 AND 99)),
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64) NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cambio_boleta_formato') THEN
        ALTER TABLE RET_SOLICITUD_CAMBIO_MOSTRADOR
            ADD CONSTRAINT chk_cambio_boleta_formato
            CHECK (boleta_numero IS NULL OR boleta_numero ~ '^[BF][0-9]{3}-[0-9]{8}$');
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ret_solicitud_cambio_clave
    ON RET_SOLICITUD_CAMBIO_MOSTRADOR (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Las solicitudes ya existentes heredan la tienda de su turno de caja si no la tienen
UPDATE RET_SOLICITUD_CAMBIO_MOSTRADOR s
   SET tienda_id = c.tienda_id
  FROM RET_CAJA_SESION c
 WHERE s.caja_sesion_id = c.id_sesion
   AND s.tienda_id IS NULL;

-- -----------------------------------------------------------------------------
-- 2. SECUENCIA Y GENERADOR DEL CÓDIGO DE VALE (VALE-2026-00001, VALE-2026-00002, ...)
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS seq_ret_vale_compra START WITH 1 INCREMENT BY 1 MAXVALUE 99999;

CREATE OR REPLACE FUNCTION fn_generar_codigo_vale()
RETURNS VARCHAR AS $$
    SELECT 'VALE-' || to_char(CURRENT_DATE, 'YYYY') || '-' || lpad(nextval('seq_ret_vale_compra')::text, 5, '0');
$$ LANGUAGE sql;

-- -----------------------------------------------------------------------------
-- 3. TABLA: RET_VALE_COMPRA (Unificada con especificación e implementación F9)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_VALE_COMPRA (
    id_vale                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo_vale             VARCHAR(20) NOT NULL UNIQUE DEFAULT fn_generar_codigo_vale()
                            CHECK (codigo_vale ~ '^VALE-[0-9]{4}-[0-9]{5,}$'),
    solicitud_id            UUID NOT NULL UNIQUE,
    boleta_numero           VARCHAR(20) NOT NULL
                            CHECK (boleta_numero ~ '^[BF][0-9]{3}-[0-9]{8}$'),
    cliente_id              UUID NULL,  -- Opcional para atención en mostrador (F9)
    tienda_id               TEXT NOT NULL,
    monto                   DECIMAL(12, 2) NOT NULL CHECK (monto >= 0),
    fecha_emision           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_vencimiento       TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days'),
    estado                  VARCHAR(20) NOT NULL DEFAULT 'EMITIDO'
                            CHECK (estado IN ('EMITIDO', 'CANJEADO', 'ANULADO')),
    fecha_canje             TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vale_solicitud
        FOREIGN KEY (solicitud_id) REFERENCES RET_SOLICITUD_CAMBIO_MOSTRADOR(id_solicitud) ON DELETE RESTRICT,
    CONSTRAINT chk_vale_vencimiento_mayor
        CHECK (fecha_vencimiento > fecha_emision),
    CONSTRAINT chk_vale_canje_coherente
        CHECK ((estado = 'CANJEADO' AND fecha_canje IS NOT NULL) OR (estado <> 'CANJEADO' AND fecha_canje IS NULL))
);

DROP TRIGGER IF EXISTS trg_ret_vale_compra_updated_at ON RET_VALE_COMPRA;
CREATE TRIGGER trg_ret_vale_compra_updated_at
    BEFORE UPDATE ON RET_VALE_COMPRA
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- -----------------------------------------------------------------------------
-- 4. ÍNDICES DE RENDIMIENTO Y CONSULTA S11
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_cambio_boleta_numero  ON RET_SOLICITUD_CAMBIO_MOSTRADOR(boleta_numero);
CREATE INDEX IF NOT EXISTS idx_cambio_cliente        ON RET_SOLICITUD_CAMBIO_MOSTRADOR(cliente_id);
CREATE INDEX IF NOT EXISTS idx_cambio_tienda_fecha   ON RET_SOLICITUD_CAMBIO_MOSTRADOR(tienda_id, fecha_inspeccion DESC);

CREATE INDEX IF NOT EXISTS idx_vale_boleta_numero    ON RET_VALE_COMPRA(boleta_numero);
CREATE INDEX IF NOT EXISTS idx_vale_cliente_estado   ON RET_VALE_COMPRA(cliente_id, estado);
CREATE INDEX IF NOT EXISTS idx_vale_tienda_estado    ON RET_VALE_COMPRA(tienda_id, estado);
CREATE INDEX IF NOT EXISTS idx_vale_vencimiento      ON RET_VALE_COMPRA(fecha_vencimiento) WHERE estado = 'EMITIDO';

-- -----------------------------------------------------------------------------
-- 5. ROW LEVEL SECURITY — RET_VALE_COMPRA
-- -----------------------------------------------------------------------------
ALTER TABLE RET_VALE_COMPRA ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_vale_compra' AND policyname = 'ret_vale_compra_auth_policy') THEN
        CREATE POLICY "ret_vale_compra_auth_policy" ON RET_VALE_COMPRA FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 6. TABLA: RET_COBRO_TURNO (F8 + F5)
--    Registro de caja de lo cobrado en el turno, para el corte Z y arqueo ciego.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_COBRO_TURNO (
    id_cobro                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caja_sesion_id          UUID NOT NULL,
    idempotency_key         VARCHAR(64) NOT NULL UNIQUE,
    pedido_id               VARCHAR(40) NULL,
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
