-- Ejecutar después de crear RET_CAJA_SESION. Guarda solo ventas confirmadas por G5.
CREATE SEQUENCE IF NOT EXISTS ret_comprobante_b001_seq START WITH 1 MAXVALUE 99999999 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS ret_comprobante_f001_seq START WITH 1 MAXVALUE 99999999 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS ret_vale_codigo_seq START WITH 1 MAXVALUE 99999 NO CYCLE;

CREATE TABLE IF NOT EXISTS ret_venta (
    id_venta UUID PRIMARY KEY,
    caja_sesion_id UUID NOT NULL REFERENCES ret_caja_sesion (id_sesion),
    tienda_id VARCHAR(50) NOT NULL,
    pedido_id TEXT NOT NULL UNIQUE,
    estado_pedido VARCHAR(20) NOT NULL DEFAULT 'PAGADO' CHECK (estado_pedido IN ('PAGADO', 'ENTREGADO')),
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

-- Reserva la clave antes de llamar a Ventas. Guarda la fecha y las líneas calculadas
-- para reenviar el mismo pedido después de un timeout, incluso tras reiniciar Retail.
CREATE TABLE IF NOT EXISTS ret_venta_operacion (
    clave VARCHAR(64) PRIMARY KEY,
    usuario_id UUID NOT NULL,
    huella VARCHAR(64) NOT NULL,
    fecha_operacion TIMESTAMP WITH TIME ZONE NOT NULL,
    lineas TEXT NOT NULL,
    respuesta TEXT
);
