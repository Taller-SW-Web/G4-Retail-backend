-- F10 / RF-28. Extensión aditiva del log existente; ejecutar antes del backend F10.
CREATE SEQUENCE IF NOT EXISTS ret_contingencia_comprobante_seq START WITH 1 INCREMENT BY 1;
ALTER TABLE RET_CONTINGENCIA_OFFLINE_LOG ADD COLUMN IF NOT EXISTS turno_id UUID;
ALTER TABLE RET_CONTINGENCIA_OFFLINE_LOG ADD COLUMN IF NOT EXISTS vendedor_id UUID;
ALTER TABLE RET_CONTINGENCIA_OFFLINE_LOG ADD COLUMN IF NOT EXISTS numero_comprobante BIGINT;
ALTER TABLE RET_CONTINGENCIA_OFFLINE_LOG ADD COLUMN IF NOT EXISTS pedido_id VARCHAR(100);
CREATE UNIQUE INDEX IF NOT EXISTS uq_contingencia_comprobante ON RET_CONTINGENCIA_OFFLINE_LOG(numero_comprobante);
-- Los estados existentes se conservan: CONFLICTO se expone como REQUIERE_REVISION.
-- Las filas históricas sin turno/vendedor requieren conciliación manual; no se atribuyen a otro usuario.
