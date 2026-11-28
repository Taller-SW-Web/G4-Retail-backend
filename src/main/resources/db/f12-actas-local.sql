-- Solo perfil local (H2). En Supabase esto lo crea diseño/schema-s14-f12.sql de la DBA.
-- La secuencia del acta de merma y la tabla de incidencias, si la entidad de F12 todavia no la creo.
CREATE SEQUENCE IF NOT EXISTS ret_acta_merma_seq START WITH 1 INCREMENT BY 1 MAXVALUE 9999;

CREATE TABLE IF NOT EXISTS RET_INCIDENCIA_INVENTARIO (
    id_incidencia           UUID PRIMARY KEY,
    tienda_id               VARCHAR(100) NOT NULL,
    vendedor_reporta_id     UUID NOT NULL,
    variante_sku_id         VARCHAR(100) NOT NULL,
    codigo_barras           VARCHAR(50) NOT NULL,
    tipo_falla              VARCHAR(50) NOT NULL,
    detalle_observacion     VARCHAR(1000) NOT NULL,
    evidencia_foto_url      VARCHAR(255),
    estado_cuarentena       VARCHAR(30) NOT NULL DEFAULT 'EN_CUARENTENA',
    fecha_reporte           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nombre_producto         VARCHAR(150),
    idempotency_key         VARCHAR(64),
    incidencia_inventory_id VARCHAR(100),
    acta_id                 UUID,
    intentos_reporte        INTEGER NOT NULL DEFAULT 0,
    ultimo_error_reporte    VARCHAR(1000),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Igual que en Supabase: una prenda con acta ya salio de la cuarentena.
    CONSTRAINT chk_incidencia_acta_coherente
        CHECK (acta_id IS NULL OR estado_cuarentena IN ('DERIVADO_ALMACEN', 'BAJA_DEFINITIVA'))
);
