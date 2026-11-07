-- PostgreSQL/Supabase. Complementa diseño_bd/schema-s11.sql (DBA) con lo que F9 necesita y ese script no
-- cambia. Orden, en una transacción:
--   1. diseño_bd/schema.sql                      (esquema base)
--   2. src/main/resources/db/registro-venta.sql  (RET_VENTA y secuencias de comprobante y vale)
--   3. diseño_bd/schema-s11.sql                  (DBA: columnas F9, RET_VALE_COMPRA, RET_COBRO_TURNO)
--   4. este archivo
-- Es idempotente: se puede ejecutar más de una vez.

-- La boleta guarda el pedido de G5 y el SKU como texto (por ejemplo POLTEC-M-BLA), no como UUID.
-- En mostrador el cliente puede no estar identificado (contrato §5.9).
ALTER TABLE ret_solicitud_cambio_mostrador
    ALTER COLUMN pedido_id_origen TYPE TEXT USING pedido_id_origen::TEXT,
    ALTER COLUMN variante_sku_devuelta_id TYPE TEXT USING variante_sku_devuelta_id::TEXT,
    ALTER COLUMN cliente_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS venta_id UUID REFERENCES ret_venta (id_venta);

-- Cuenta las unidades ya cambiadas de una prenda de la boleta (RF-25).
CREATE INDEX IF NOT EXISTS ix_ret_solicitud_cambio_boleta_sku
    ON ret_solicitud_cambio_mostrador (boleta_numero, variante_sku_devuelta_id, estado_aprobacion);

ALTER TABLE ret_venta ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_venta_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_venta_operacion ENABLE ROW LEVEL SECURITY;

-- Se conserva el acceso anterior a las filas históricas. Los cambios de S11 se leen por la API Retail.
DROP POLICY IF EXISTS ret_solicitud_cambio_solo_historico ON ret_solicitud_cambio_mostrador;
CREATE POLICY ret_solicitud_cambio_solo_historico ON ret_solicitud_cambio_mostrador
    AS RESTRICTIVE FOR ALL TO authenticated, anon
    USING (boleta_numero IS NULL) WITH CHECK (boleta_numero IS NULL);
