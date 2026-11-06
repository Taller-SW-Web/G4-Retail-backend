-- PostgreSQL/Supabase. Aplicar junto con src/main/resources/db/registro-venta.sql, en una transacción.

ALTER TABLE ret_solicitud_cambio_mostrador
    ALTER COLUMN pedido_id_origen TYPE TEXT USING pedido_id_origen::TEXT,
    ALTER COLUMN variante_sku_devuelta_id TYPE TEXT USING variante_sku_devuelta_id::TEXT,
    ALTER COLUMN cliente_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS vendedor_id UUID,
    ADD COLUMN IF NOT EXISTS boleta_numero VARCHAR(20),
    ADD COLUMN IF NOT EXISTS cantidad INTEGER CHECK (cantidad BETWEEN 1 AND 99),
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64),
    ADD COLUMN IF NOT EXISTS venta_id UUID REFERENCES ret_venta(id_venta);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ret_solicitud_cambio_clave
    ON ret_solicitud_cambio_mostrador (idempotency_key);
CREATE INDEX IF NOT EXISTS ix_ret_solicitud_cambio_boleta_sku
    ON ret_solicitud_cambio_mostrador (boleta_numero, variante_sku_devuelta_id, estado_aprobacion);

CREATE TABLE IF NOT EXISTS ret_vale_compra (
    id_vale UUID PRIMARY KEY,
    codigo_vale VARCHAR(20) NOT NULL UNIQUE,
    solicitud_id UUID NOT NULL UNIQUE REFERENCES ret_solicitud_cambio_mostrador (id_solicitud),
    boleta_numero VARCHAR(20) NOT NULL,
    cliente_id UUID,
    tienda_id TEXT NOT NULL,
    monto NUMERIC(12, 2) NOT NULL CHECK (monto >= 0),
    fecha_emision TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_vencimiento TIMESTAMP WITH TIME ZONE NOT NULL CHECK (fecha_vencimiento > fecha_emision),
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('EMITIDO', 'CANJEADO', 'ANULADO'))
);

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
