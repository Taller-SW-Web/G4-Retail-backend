-- PostgreSQL/Supabase. Aplicar junto con src/main/resources/db/registro-venta.sql, en una transacción.

ALTER TABLE ret_solicitud_cambio_mostrador
    ALTER COLUMN pedido_id_origen TYPE TEXT USING pedido_id_origen::TEXT,
    ALTER COLUMN variante_sku_devuelta_id TYPE TEXT USING variante_sku_devuelta_id::TEXT,
    ALTER COLUMN cliente_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS vendedor_id UUID,
    ADD COLUMN IF NOT EXISTS boleta_numero VARCHAR(20),
    ADD COLUMN IF NOT EXISTS tienda_id TEXT,
    ADD COLUMN IF NOT EXISTS cantidad INTEGER CHECK (cantidad BETWEEN 1 AND 99),
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64),
    ADD COLUMN IF NOT EXISTS venta_id UUID REFERENCES ret_venta(id_venta);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ret_solicitud_cambio_clave
    ON ret_solicitud_cambio_mostrador (idempotency_key);
CREATE INDEX IF NOT EXISTS ix_ret_solicitud_cambio_boleta_sku
    ON ret_solicitud_cambio_mostrador (boleta_numero, variante_sku_devuelta_id, estado_aprobacion);

CREATE INDEX IF NOT EXISTS ix_ret_solicitud_cambio_tienda
    ON ret_solicitud_cambio_mostrador (tienda_id);

-- Nombres de columna del esquema final de la DBA (06/11/2026). La API envía el código y las fechas;
-- si la base define valores por defecto, solo aplican cuando no se envían.
CREATE TABLE IF NOT EXISTS ret_vale_compra (
    id_vale UUID PRIMARY KEY,
    codigo_vale VARCHAR(20) NOT NULL UNIQUE,
    solicitud_cambio_id UUID NOT NULL UNIQUE REFERENCES ret_solicitud_cambio_mostrador (id_solicitud),
    boleta_numero VARCHAR(20) NOT NULL CHECK (boleta_numero ~ '^[BF][0-9]{3}-[0-9]{8}$'),
    cliente_id UUID,
    tienda_id TEXT NOT NULL,
    monto_a_favor NUMERIC(12, 2) NOT NULL CHECK (monto_a_favor >= 0),
    saldo_disponible NUMERIC(12, 2) NOT NULL CHECK (saldo_disponible >= 0 AND saldo_disponible <= monto_a_favor),
    fecha_emision TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    fecha_vencimiento TIMESTAMP WITH TIME ZONE NOT NULL CHECK (fecha_vencimiento > fecha_emision),
    fecha_canje TIMESTAMP WITH TIME ZONE,
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('EMITIDO', 'CANJEADO', 'ANULADO')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (estado <> 'CANJEADO' OR (saldo_disponible = 0 AND fecha_canje IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_ret_vale_compra_boleta ON ret_vale_compra (boleta_numero);
CREATE INDEX IF NOT EXISTS ix_ret_vale_compra_tienda ON ret_vale_compra (tienda_id);

ALTER TABLE ret_venta ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_venta_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_venta_operacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_vale_compra ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_solicitud_cambio_mostrador ENABLE ROW LEVEL SECURITY;

-- Se conserva el acceso anterior a las filas históricas. Los cambios de S11 se leen por la API Retail.
DROP POLICY IF EXISTS ret_solicitud_cambio_solo_historico ON ret_solicitud_cambio_mostrador;
CREATE POLICY ret_solicitud_cambio_solo_historico ON ret_solicitud_cambio_mostrador
    AS RESTRICTIVE FOR ALL TO authenticated, anon
    USING (boleta_numero IS NULL) WITH CHECK (boleta_numero IS NULL);
