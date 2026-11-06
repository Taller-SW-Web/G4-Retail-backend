-- Ejecutar después de crear RET_CAJA_SESION. Guarda solo ventas confirmadas por G5.
CREATE SEQUENCE IF NOT EXISTS ret_comprobante_b001_seq START WITH 1 MAXVALUE 99999999 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS ret_comprobante_f001_seq START WITH 1 MAXVALUE 99999999 NO CYCLE;

CREATE TABLE IF NOT EXISTS ret_venta (
    id_venta UUID PRIMARY KEY,
    caja_sesion_id UUID NOT NULL REFERENCES ret_caja_sesion (id_sesion),
    tienda_id VARCHAR(50) NOT NULL,
    pedido_id TEXT NOT NULL UNIQUE,
    serie VARCHAR(4) NOT NULL CHECK (serie IN ('B001', 'F001')),
    correlativo INTEGER NOT NULL CHECK (correlativo BETWEEN 1 AND 99999999),
    fecha_emision TIMESTAMP WITH TIME ZONE NOT NULL,
    total NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    UNIQUE (serie, correlativo)
);

CREATE TABLE IF NOT EXISTS ret_venta_item (
    venta_id UUID NOT NULL REFERENCES ret_venta (id_venta),
    numero_linea INTEGER NOT NULL CHECK (numero_linea > 0),
    sku TEXT NOT NULL,
    cantidad INTEGER NOT NULL CHECK (cantidad > 0),
    precio_unitario_pagado NUMERIC(12, 2) NOT NULL CHECK (precio_unitario_pagado >= 0),
    PRIMARY KEY (venta_id, numero_linea)
);

-- El acceso se realiza desde el backend de Retail. Las tablas no tienen políticas públicas.
ALTER TABLE ret_venta ENABLE ROW LEVEL SECURITY;
ALTER TABLE ret_venta_item ENABLE ROW LEVEL SECURITY;
