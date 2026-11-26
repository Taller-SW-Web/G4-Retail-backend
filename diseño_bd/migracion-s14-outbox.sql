-- ---------------------------------------------------------------------------
-- Migración S14: outbox transaccional (contrato v0.5, §2.9).
-- Mecanismo asíncrono entre microservicios: el servicio de negocio guarda aquí, en la
-- misma transacción, el evento que un job envía después al módulo destino con reintentos.
-- Primer uso: reporte de incidencias de F12 a Inventory de Productos (G6), §5.12.
-- Idempotente: se puede ejecutar más de una vez en Supabase.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS RET_OUTBOX_EVENTO (
    id                 UUID         PRIMARY KEY,
    tipo_evento        VARCHAR(60)  NOT NULL,
    agregado_id        VARCHAR(100) NOT NULL,           -- id del recurso de Retail; es la Idempotency-Key
    destino            VARCHAR(30)  NOT NULL,           -- PRODUCTOS_G6
    payload            TEXT         NOT NULL,           -- cuerpo JSON exacto que se envía
    estado             VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    intentos           INTEGER      NOT NULL DEFAULT 0,
    proximo_intento    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ultimo_error       TEXT         NULL,
    referencia_externa VARCHAR(100) NULL,               -- id que devuelve el destino (incidenciaInventoryId)
    correlation_id     VARCHAR(64)  NULL,               -- X-Correlation-Id de la petición de origen
    creado_en          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    enviado_en         TIMESTAMPTZ  NULL,
    CONSTRAINT chk_outbox_estado   CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'FALLIDO')),
    CONSTRAINT chk_outbox_intentos CHECK (intentos >= 0),
    CONSTRAINT chk_outbox_enviado  CHECK (estado <> 'ENVIADO' OR enviado_en IS NOT NULL)
);

-- Un evento por agregado y tipo: registrar dos veces la misma incidencia no lo duplica.
CREATE UNIQUE INDEX IF NOT EXISTS ux_outbox_tipo_agregado ON RET_OUTBOX_EVENTO(tipo_evento, agregado_id);
-- Lo que lee el job en cada pasada.
CREATE INDEX IF NOT EXISTS idx_outbox_pendientes ON RET_OUTBOX_EVENTO(estado, proximo_intento)
    WHERE estado = 'PENDIENTE';

-- Mismo criterio que las demás tablas de Retail en Supabase: solo el backend (service role) accede.
ALTER TABLE RET_OUTBOX_EVENTO ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Consultas de evidencia (sustentación del Hito 5)
-- ---------------------------------------------------------------------------
-- Estado del outbox:
--   SELECT estado, count(*) FROM RET_OUTBOX_EVENTO GROUP BY estado;
-- Últimos envíos con su referencia en Inventory:
--   SELECT tipo_evento, agregado_id, estado, intentos, referencia_externa, creado_en, enviado_en
--     FROM RET_OUTBOX_EVENTO ORDER BY creado_en DESC LIMIT 20;
-- Eventos que requieren revisión:
--   SELECT agregado_id, intentos, ultimo_error FROM RET_OUTBOX_EVENTO WHERE estado = 'FALLIDO';
