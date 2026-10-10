-- =============================================================================
-- ESQUEMA DDL FÍSICO — BASE DE DATOS RETAIL (GRUPO 4)
-- Marketplace Multicanal de Artículos Deportivos — Ciclo 2026-II
-- Optimizado y listo para Supabase (PostgreSQL 15+)
-- =============================================================================

-- 0. EXTENSIONES
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- -----------------------------------------------------------------------------
-- FUNCIÓN AUXILIAR: Actualización automática de timestamp (updated_at)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- -----------------------------------------------------------------------------
-- 1. TABLA: RET_CAJA_SESION
-- Control de turnos, fondo fijo y arqueo ciego en mostrador (F8)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_CAJA_SESION (
    id_sesion               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tienda_id               UUID NOT NULL,
    terminal_pos_codigo     VARCHAR(20) NOT NULL,
    vendedor_id             UUID NOT NULL, -- Ref. externa lógica: Módulo Seguridad
    fecha_hora_apertura     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    saldo_inicial_efectivo  DECIMAL(12, 2) NOT NULL CHECK (saldo_inicial_efectivo >= 0),
    fecha_hora_cierre       TIMESTAMPTZ NULL,
    saldo_final_declarado   DECIMAL(12, 2) NULL, -- Arqueo ciego digitado por cajero
    saldo_final_sistema     DECIMAL(12, 2) NULL, -- Calculado por sistema
    diferencia_saldo        DECIMAL(12, 2) NULL, -- Diferencia: declarado - sistema
    estado                  VARCHAR(20) NOT NULL DEFAULT 'ABIERTA' 
                            CHECK (estado IN ('ABIERTA', 'CERRADA', 'OBSERVADA')),
    observaciones_cierre    TEXT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TRIGGER IF EXISTS trg_ret_caja_sesion_updated_at ON RET_CAJA_SESION;
CREATE TRIGGER trg_ret_caja_sesion_updated_at
    BEFORE UPDATE ON RET_CAJA_SESION
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- -----------------------------------------------------------------------------
-- 2. TABLA: RET_MOVIMIENTO_CAJA
-- Movimientos menores de efectivo durante el turno (F8)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_MOVIMIENTO_CAJA (
    id_movimiento           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caja_sesion_id          UUID NOT NULL,
    tipo_movimiento         VARCHAR(30) NOT NULL 
                            CHECK (tipo_movimiento IN ('INGRESO_MENOR', 'SALIDA_GASTO')),
    monto                   DECIMAL(12, 2) NOT NULL CHECK (monto > 0),
    motivo                  TEXT NOT NULL,
    autorizado_por_supervisor VARCHAR(100) NOT NULL,
    fecha_hora              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_movimiento_caja_sesion 
        FOREIGN KEY (caja_sesion_id) REFERENCES RET_CAJA_SESION(id_sesion) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- 3. TABLA: RET_CARRITO_ESPERA
-- Suspensión y retención temporal de carritos para probadores (F4)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_CARRITO_ESPERA (
    id_carrito_espera       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tienda_id               UUID NOT NULL,
    vendedor_id             UUID NOT NULL, -- Ref. externa lógica: Módulo Seguridad
    cliente_id              UUID NULL,     -- Ref. externa lógica opcional: Módulo Seguridad
    alias_ticket            VARCHAR(80) NOT NULL,
    subtotal_estimado       DECIMAL(12, 2) NOT NULL DEFAULT 0 CHECK (subtotal_estimado >= 0),
    fecha_creacion          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_expiracion_reserva TIMESTAMPTZ NOT NULL,
    estado                  VARCHAR(20) NOT NULL DEFAULT 'SUSPENDIDO'
                            CHECK (estado IN ('SUSPENDIDO', 'REANUDADO', 'EXPIRADO'))
);

-- -----------------------------------------------------------------------------
-- 4. TABLA: RET_CARRITO_ESPERA_ITEM
-- Detalle de artículos dentro del carrito suspendido (F4)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_CARRITO_ESPERA_ITEM (
    id_item                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    carrito_espera_id       UUID NOT NULL,
    producto_id             UUID NOT NULL, -- Ref. externa lógica: Módulo Productos
    variante_sku_id         UUID NOT NULL, -- Ref. externa lógica: Módulo Productos
    nombre_producto         VARCHAR(150) NOT NULL,
    talla_color             VARCHAR(50) NOT NULL,
    cantidad                INTEGER NOT NULL CHECK (cantidad > 0),
    precio_unitario         DECIMAL(12, 2) NOT NULL CHECK (precio_unitario >= 0),
    CONSTRAINT fk_item_carrito_espera 
        FOREIGN KEY (carrito_espera_id) REFERENCES RET_CARRITO_ESPERA(id_carrito_espera) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- 5. TABLA: RET_SOLICITUD_CAMBIO_MOSTRADOR
-- Recepción e inspección física de prendas para cambio en tienda (F9)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_SOLICITUD_CAMBIO_MOSTRADOR (
    id_solicitud            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caja_sesion_id          UUID NOT NULL,
    pedido_id_origen        UUID NOT NULL, -- Ref. externa lógica: Módulo Ventas
    variante_sku_devuelta_id UUID NOT NULL, -- Ref. externa lógica: Módulo Productos
    cliente_id              UUID NOT NULL, -- Ref. externa lógica: Módulo Seguridad
    motivo_cambio           VARCHAR(50) NOT NULL
                            CHECK (motivo_cambio IN ('CAMBIO_TALLA', 'CAMBIO_MODELO', 'FALLA_FABRICA')),
    inspeccion_etiquetas    BOOLEAN NOT NULL DEFAULT FALSE,
    inspeccion_sin_uso      BOOLEAN NOT NULL DEFAULT FALSE,
    inspeccion_empaque      BOOLEAN NOT NULL DEFAULT FALSE,
    estado_aprobacion       VARCHAR(30) NOT NULL 
                            CHECK (estado_aprobacion IN ('APROBADO_EN_TIENDA', 'RECHAZADO')),
    vale_temporal_codigo    VARCHAR(50) NULL,
    monto_acreditado        DECIMAL(12, 2) NOT NULL DEFAULT 0 CHECK (monto_acreditado >= 0),
    fecha_inspeccion        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cambio_caja_sesion 
        FOREIGN KEY (caja_sesion_id) REFERENCES RET_CAJA_SESION(id_sesion) ON DELETE RESTRICT
);

-- -----------------------------------------------------------------------------
-- 6. TABLA: RET_INCIDENCIA_INVENTARIO
-- Reporte y cuarentena de prendas dañadas o mermas en mostrador (F12)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_INCIDENCIA_INVENTARIO (
    id_incidencia           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tienda_id               UUID NOT NULL,
    vendedor_reporta_id     UUID NOT NULL, -- Ref. externa lógica: Módulo Seguridad
    variante_sku_id         UUID NOT NULL, -- Ref. externa lógica: Módulo Productos
    codigo_barras           VARCHAR(50) NOT NULL,
    tipo_falla              VARCHAR(50) NOT NULL
                            CHECK (tipo_falla IN ('MANCHADO_PROBADOR', 'COSTURA_ROTA', 'EXTRAVIO_NO_UBICADO', 'DEFECTO_FABRICA')),
    detalle_observacion     TEXT NOT NULL,
    evidencia_foto_url      VARCHAR(255) NULL,
    estado_cuarentena       VARCHAR(30) NOT NULL DEFAULT 'EN_CUARENTENA'
                            CHECK (estado_cuarentena IN ('EN_CUARENTENA', 'DERIVADO_ALMACEN', 'DESCARTADO', 'RECHAZADO')),
    fecha_reporte           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- 7. TABLA: RET_CONTINGENCIA_OFFLINE_LOG
-- Cola de sincronización y resiliencia para ventas sin conexión (F10)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_CONTINGENCIA_OFFLINE_LOG (
    id_log                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tienda_id               UUID NOT NULL,
    terminal_pos_codigo     VARCHAR(20) NOT NULL,
    venta_local_uuid        VARCHAR(64) NOT NULL UNIQUE,
    payload_json_orden      JSONB NOT NULL,
    firma_hash_seguridad    VARCHAR(64) NOT NULL,
    estado_sincronizacion   VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE'
                            CHECK (estado_sincronizacion IN ('PENDIENTE', 'RESINCRONIZADO', 'CONFLICTO')),
    fecha_emision_offline   TIMESTAMPTZ NOT NULL,
    fecha_sincronizacion    TIMESTAMPTZ NULL,
    error_detalle           TEXT NULL
);

-- -----------------------------------------------------------------------------
-- 8. TABLA: RET_PERSONAL_TIENDA
-- Perfiles y roles operativos del personal dentro de la sucursal (F1, F8)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_PERSONAL_TIENDA (
    id_personal             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id              UUID NOT NULL UNIQUE, -- Identificador lógico del usuario ('sub' auth)
    tienda_id               UUID NOT NULL,
    codigo_vendedor         VARCHAR(20) NOT NULL,
    perfil_tienda           VARCHAR(20) NOT NULL 
                            CHECK (perfil_tienda IN ('VENDEDOR', 'CAJERO', 'SUPERVISOR')),
    activo                  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TRIGGER IF EXISTS trg_ret_personal_tienda_updated_at ON RET_PERSONAL_TIENDA;
CREATE TRIGGER trg_ret_personal_tienda_updated_at
    BEFORE UPDATE ON RET_PERSONAL_TIENDA
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- -----------------------------------------------------------------------------
-- 9. TABLA: RET_AUDITORIA_OPERACIONES
-- Trazabilidad operativa: quién hizo qué, cuándo y desde qué terminal (F1, F5, F8)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RET_AUDITORIA_OPERACIONES (
    id_auditoria            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tienda_id               UUID NOT NULL,
    usuario_id              UUID NOT NULL, -- Ref. externa lógica: Módulo Seguridad
    caja_sesion_id          UUID NULL,
    terminal_pos_codigo     VARCHAR(20) NOT NULL,
    accion                  VARCHAR(20) NOT NULL
                            CHECK (accion IN ('APERTURA', 'VENTA', 'CIERRE')),
    estado                  VARCHAR(20) NOT NULL DEFAULT 'EXITOSO'
                            CHECK (estado IN ('EXITOSO', 'FALLIDO', 'OBSERVADO')),
    referencia_id           UUID NULL,     -- Ref. externa lógica opcional: pedido (Módulo Ventas)
    detalle                 TEXT NULL,
    detalle_json            JSONB NULL,    -- Datos estructurados (montos, saldos, medio de pago)
    fecha_hora              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auditoria_caja_sesion
        FOREIGN KEY (caja_sesion_id) REFERENCES RET_CAJA_SESION(id_sesion) ON DELETE RESTRICT
);

-- -----------------------------------------------------------------------------
-- 10. TRIGGERS DE AUDITORÍA
-- (a) APERTURA y CIERRE se registran solos al abrir/cerrar un turno en RET_CAJA_SESION
-- (b) RET_AUDITORIA_OPERACIONES es de solo inserción: bloquea UPDATE, DELETE y TRUNCATE
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_auditar_caja_sesion()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO RET_AUDITORIA_OPERACIONES
            (tienda_id, usuario_id, caja_sesion_id, terminal_pos_codigo, accion, estado, detalle, detalle_json, fecha_hora)
        VALUES
            (NEW.tienda_id, NEW.vendedor_id, NEW.id_sesion, NEW.terminal_pos_codigo, 'APERTURA', 'EXITOSO',
             'Apertura de turno con fondo fijo S/ ' || NEW.saldo_inicial_efectivo,
             jsonb_build_object('saldo_inicial', NEW.saldo_inicial_efectivo),
             NEW.fecha_hora_apertura);
    ELSIF TG_OP = 'UPDATE' AND OLD.estado = 'ABIERTA' AND NEW.estado IN ('CERRADA', 'OBSERVADA') THEN
        INSERT INTO RET_AUDITORIA_OPERACIONES
            (tienda_id, usuario_id, caja_sesion_id, terminal_pos_codigo, accion, estado, detalle, detalle_json, fecha_hora)
        VALUES
            (NEW.tienda_id, NEW.vendedor_id, NEW.id_sesion, NEW.terminal_pos_codigo, 'CIERRE',
             CASE WHEN NEW.estado = 'OBSERVADA' THEN 'OBSERVADO' ELSE 'EXITOSO' END,
             'Cierre Z: declarado S/ ' || COALESCE(NEW.saldo_final_declarado::text, '-') ||
             ', sistema S/ ' || COALESCE(NEW.saldo_final_sistema::text, '-'),
             jsonb_build_object('saldo_declarado', NEW.saldo_final_declarado,
                                'saldo_sistema', NEW.saldo_final_sistema,
                                'diferencia', NEW.diferencia_saldo),
             COALESCE(NEW.fecha_hora_cierre, CURRENT_TIMESTAMP));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ret_caja_sesion_audit_ins ON RET_CAJA_SESION;
CREATE TRIGGER trg_ret_caja_sesion_audit_ins
    AFTER INSERT ON RET_CAJA_SESION
    FOR EACH ROW
    EXECUTE FUNCTION fn_auditar_caja_sesion();

DROP TRIGGER IF EXISTS trg_ret_caja_sesion_audit_upd ON RET_CAJA_SESION;
CREATE TRIGGER trg_ret_caja_sesion_audit_upd
    AFTER UPDATE ON RET_CAJA_SESION
    FOR EACH ROW
    EXECUTE FUNCTION fn_auditar_caja_sesion();

CREATE OR REPLACE FUNCTION fn_auditoria_inmutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'RET_AUDITORIA_OPERACIONES es de solo inserción: % no permitido', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ret_auditoria_inmutable ON RET_AUDITORIA_OPERACIONES;
CREATE TRIGGER trg_ret_auditoria_inmutable
    BEFORE UPDATE OR DELETE ON RET_AUDITORIA_OPERACIONES
    FOR EACH ROW
    EXECUTE FUNCTION fn_auditoria_inmutable();

DROP TRIGGER IF EXISTS trg_ret_auditoria_no_truncate ON RET_AUDITORIA_OPERACIONES;
CREATE TRIGGER trg_ret_auditoria_no_truncate
    BEFORE TRUNCATE ON RET_AUDITORIA_OPERACIONES
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_auditoria_inmutable();

-- -----------------------------------------------------------------------------
-- 11. ÍNDICES DE CONSULTA FRECUENTE
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_caja_sesion_tienda_vendedor ON RET_CAJA_SESION(tienda_id, vendedor_id, estado);
CREATE INDEX IF NOT EXISTS idx_carrito_espera_tienda_estado ON RET_CARRITO_ESPERA(tienda_id, estado);
CREATE INDEX IF NOT EXISTS idx_offline_log_estado ON RET_CONTINGENCIA_OFFLINE_LOG(tienda_id, estado_sincronizacion);
CREATE INDEX IF NOT EXISTS idx_incidencia_tienda_estado ON RET_INCIDENCIA_INVENTARIO(tienda_id, estado_cuarentena);
CREATE INDEX IF NOT EXISTS idx_personal_tienda_usuario ON RET_PERSONAL_TIENDA(usuario_id, tienda_id, activo);
CREATE INDEX IF NOT EXISTS idx_auditoria_tienda_fecha ON RET_AUDITORIA_OPERACIONES(tienda_id, fecha_hora DESC);
CREATE INDEX IF NOT EXISTS idx_auditoria_usuario_accion ON RET_AUDITORIA_OPERACIONES(usuario_id, accion);
CREATE INDEX IF NOT EXISTS idx_auditoria_caja_sesion ON RET_AUDITORIA_OPERACIONES(caja_sesion_id);

-- -----------------------------------------------------------------------------
-- 12. ROW LEVEL SECURITY (RLS) & POLÍTICAS EN SUPABASE
-- -----------------------------------------------------------------------------
ALTER TABLE RET_CAJA_SESION ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_MOVIMIENTO_CAJA ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_CARRITO_ESPERA ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_CARRITO_ESPERA_ITEM ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_SOLICITUD_CAMBIO_MOSTRADOR ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_INCIDENCIA_INVENTARIO ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_CONTINGENCIA_OFFLINE_LOG ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_PERSONAL_TIENDA ENABLE ROW LEVEL SECURITY;
ALTER TABLE RET_AUDITORIA_OPERACIONES ENABLE ROW LEVEL SECURITY;

-- Políticas permisivas por defecto para clientes autenticados (Supabase Auth / JWT)
DO $$ 
BEGIN
    -- RET_CAJA_SESION
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_caja_sesion' AND policyname = 'ret_caja_sesion_auth_policy') THEN
        CREATE POLICY "ret_caja_sesion_auth_policy" ON RET_CAJA_SESION FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_MOVIMIENTO_CAJA
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_movimiento_caja' AND policyname = 'ret_movimiento_caja_auth_policy') THEN
        CREATE POLICY "ret_movimiento_caja_auth_policy" ON RET_MOVIMIENTO_CAJA FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_CARRITO_ESPERA
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_carrito_espera' AND policyname = 'ret_carrito_espera_auth_policy') THEN
        CREATE POLICY "ret_carrito_espera_auth_policy" ON RET_CARRITO_ESPERA FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_CARRITO_ESPERA_ITEM
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_carrito_espera_item' AND policyname = 'ret_carrito_espera_item_auth_policy') THEN
        CREATE POLICY "ret_carrito_espera_item_auth_policy" ON RET_CARRITO_ESPERA_ITEM FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_SOLICITUD_CAMBIO_MOSTRADOR
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_solicitud_cambio_mostrador' AND policyname = 'ret_solicitud_cambio_mostrador_auth_policy') THEN
        CREATE POLICY "ret_solicitud_cambio_mostrador_auth_policy" ON RET_SOLICITUD_CAMBIO_MOSTRADOR FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_INCIDENCIA_INVENTARIO
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_incidencia_inventario' AND policyname = 'ret_incidencia_inventario_auth_policy') THEN
        CREATE POLICY "ret_incidencia_inventario_auth_policy" ON RET_INCIDENCIA_INVENTARIO FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_CONTINGENCIA_OFFLINE_LOG
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_contingencia_offline_log' AND policyname = 'ret_contingencia_offline_log_auth_policy') THEN
        CREATE POLICY "ret_contingencia_offline_log_auth_policy" ON RET_CONTINGENCIA_OFFLINE_LOG FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;

    -- RET_PERSONAL_TIENDA
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_personal_tienda' AND policyname = 'ret_personal_tienda_auth_policy') THEN
        CREATE POLICY "ret_personal_tienda_auth_policy" ON RET_PERSONAL_TIENDA FOR ALL TO authenticated USING (true) WITH CHECK (true);
    END IF;
    -- RET_AUDITORIA_OPERACIONES
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ret_auditoria_operaciones' AND policyname = 'ret_auditoria_operaciones_auth_policy') THEN
        -- Auditoría: solo lectura e inserción (nunca UPDATE/DELETE)
        CREATE POLICY "ret_auditoria_operaciones_auth_policy" ON RET_AUDITORIA_OPERACIONES FOR SELECT TO authenticated USING (true);
        CREATE POLICY "ret_auditoria_operaciones_insert_policy" ON RET_AUDITORIA_OPERACIONES FOR INSERT TO authenticated WITH CHECK (true);
    END IF;

END $$;
