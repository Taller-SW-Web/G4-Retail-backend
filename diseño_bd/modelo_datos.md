# Diseño de Base de Datos — Módulo Retail (G4)
## Modelo Entidad-Relación y Diccionario de Datos (Hito 2)

**Proyecto:** Marketplace Multicanal de Artículos Deportivos  
**Canal:** Retail — Venta en Mostrador (Grupo 4)  
**Patrón Arquitectónico:** Database-per-Service (Microservicio desacoplado)  
**Motor Objetivo:** PostgreSQL 15+ / MySQL 8.0+  

---

## 1. Principio de Diseño y Aislamiento de Microservicio

En concordancia con el patrón **Database-per-Service**, el microservicio Retail posee su propio esquema de base de datos relacional independiente. 

> [!IMPORTANT]
> **Desacoplamiento de Entidades Foráneas:**  
> Los atributos `tienda_id`, `vendedor_id`, `cliente_id`, `producto_id`, `variante_sku_id` y `pedido_id_origen` son **identificadores lógicos desacoplados** (UUIDs provistos por los módulos de Seguridad, Productos o Ventas vía API REST). No se definen como `FOREIGN KEY` físicas a nivel de motor SQL hacia bases de datos externas, garantizando alta cohesión, autonomía de despliegue y tolerancia a fallos.

---

## 2. Diagrama Entidad-Relación (Mermaid)

El siguiente diagrama representa las **8 tablas del modelo de datos de Retail** y sus relaciones internas directas:

```mermaid
erDiagram
    RET_CAJA_SESION ||--o{ RET_MOVIMIENTO_CAJA : "1:N registra movimientos"
    RET_CARRITO_ESPERA ||--|{ RET_CARRITO_ESPERA_ITEM : "1:N contiene articulos"
    RET_CAJA_SESION ||--o{ RET_SOLICITUD_CAMBIO_MOSTRADOR : "1:N autoriza tramites"

    RET_CAJA_SESION {
        uuid id_sesion PK "Identificador unico de la sesion de caja"
        uuid tienda_id "ID de la sucursal fisica"
        string terminal_pos_codigo "Codigo identificador del hardware POS (ej: POS-01)"
        uuid vendedor_id "ID logico del cajero/vendedor responsable (Seguridad)"
        timestamp fecha_hora_apertura "Momento exacto de inicio de turno"
        decimal saldo_inicial_efectivo "Monto asignado como fondo fijo inicial"
        timestamp fecha_hora_cierre "Momento de finalizacion del turno"
        decimal saldo_final_declarado "Monto físico contado por cajero (Arqueo Ciego)"
        decimal saldo_final_sistema "Monto calculado por sistema según ventas y movimientos"
        decimal diferencia_saldo "Diferencia: declarado - sistema (sobrante/faltante)"
        string estado "ABIERTA, CERRADA, OBSERVADA"
        text observaciones_cierre "Comentarios ante descuadres o incidencias de turno"
        timestamp created_at "Auditoria de creacion"
        timestamp updated_at "Auditoria de actualizacion"
    }

    RET_MOVIMIENTO_CAJA {
        uuid id_movimiento PK "Identificador unico del movimiento menor"
        uuid caja_sesion_id FK "Referencia a la sesion de caja activa"
        string tipo_movimiento "INGRESO_MENOR, SALIDA_GASTO"
        decimal monto "Valor monetario de la transaccion menor"
        text motivo "Justificacion operativa (compra insumos, sencillo, etc.)"
        string autorizado_por_supervisor "Nombre/credencial del supervisor que aprobo"
        timestamp fecha_hora "Fecha y hora del registro"
    }

    RET_CARRITO_ESPERA {
        uuid id_carrito_espera PK "Identificador unico del carrito suspendido"
        uuid tienda_id "ID de la sucursal fisica"
        uuid vendedor_id "ID logico del vendedor que atiende (Seguridad)"
        uuid cliente_id "ID logico opcional del cliente identificado (Seguridad)"
        string alias_ticket "Nombre descriptivo para recuperacion rapida (ej: Probador 2)"
        decimal subtotal_estimado "Total calculado al momento de la suspension"
        timestamp fecha_creacion "Momento en que se guardo el carrito"
        timestamp fecha_expiracion_reserva "Tiempo limite antes de liberar el stock temporal"
        string estado "SUSPENDIDO, REANUDADO, EXPIRADO"
    }

    RET_CARRITO_ESPERA_ITEM {
        uuid id_item PK "Identificador unico del item en espera"
        uuid carrito_espera_id FK "Referencia al carrito en espera cabecera"
        uuid producto_id "ID logico del producto base (Productos)"
        uuid variante_sku_id "ID logico de la variante SKU concreta (Productos)"
        string nombre_producto "Cache legible del articulo congelado"
        string talla_color "Detalle visible de variante (ej: L / Negro)"
        int cantidad "Unidades seleccionadas por el cliente"
        decimal precio_unitario "Precio vigente al momento de suspender el carrito"
    }

    RET_SOLICITUD_CAMBIO_MOSTRADOR {
        uuid id_solicitud PK "Identificador de la solicitud de cambio"
        uuid caja_sesion_id FK "Sesion de caja donde se efectua el tramite"
        uuid pedido_id_origen "ID logico de la orden original (Ventas)"
        uuid variante_sku_devuelta_id "ID logico de la prenda que devuelve el cliente"
        uuid cliente_id "ID logico del cliente (Seguridad)"
        string motivo_cambio "CAMBIO_TALLA, DEFECTO_FABRICA, DISCONFORMIDAD"
        boolean inspeccion_etiquetas "Check: etiquetas y rotulado intactos"
        boolean inspeccion_sin_uso "Check: prenda limpia sin senales de uso corporal"
        boolean inspeccion_empaque "Check: caja o bolsa original presente"
        string estado_aprobacion "APROBADO_EN_TIENDA, RECHAZADO"
        string vale_temporal_codigo "Codigo del vale/nota de credito para canje inmediato"
        decimal monto_acreditado "Saldo a favor para seleccion de nueva prenda"
        timestamp fecha_inspeccion "Momento en que el vendedor valida la prenda"
    }

    RET_INCIDENCIA_INVENTARIO {
        uuid id_incidencia PK "Identificador unico de la incidencia"
        uuid tienda_id "ID de la sucursal fisica"
        uuid vendedor_reporta_id "ID logico del colaborador que detecta la merma"
        uuid variante_sku_id "ID logico de la prenda afectada (Productos)"
        string codigo_barras "EAN-13 o SKU escaneado de la prenda"
        string tipo_falla "MANCHADO_PROBADOR, COSTURA_ROTA, DESCOLORIDO, EXTRAVIO"
        text detalle_observacion "Explicacion circunstancial del dano o quiebre"
        string evidencia_foto_url "URL o path de fotografia de la prenda danada"
        string estado_cuarentena "EN_CUARENTENA, DERIVADO_ALMACEN, DESCARTADO, RECHAZADO"
        timestamp fecha_reporte "Fecha y hora del aislamiento preventivo"
    }

    RET_CONTINGENCIA_OFFLINE_LOG {
        uuid id_log PK "Identificador unico de la transaccion offline"
        uuid tienda_id "ID de la sucursal fisica"
        string terminal_pos_codigo "Terminal que emitio la venta sin conexion"
        string venta_local_uuid "UUID generado localmente en el navegador (IndexedDB)"
        text payload_json_orden "Payload transaccional completo en formato JSON"
        string firma_hash_seguridad "Hash SHA-256 de integridad para prevenir alteraciones"
        string estado_sincronizacion "PENDIENTE, RESINCRONIZADO, CONFLICTO"
        timestamp fecha_emision_offline "Momento en que se cobro al cliente offline"
        timestamp fecha_sincronizacion "Momento en que se resincronizo con el servidor central"
        text error_detalle "Trazabilidad del motivo de conflicto si fallo la subida"
    }

    RET_PERSONAL_TIENDA {
        uuid id_personal PK "Identificador unico de asignacion de personal"
        uuid usuario_id "ID logico del colaborador (sub)"
        uuid tienda_id "ID de la sucursal fisica"
        string codigo_vendedor "Codigo del colaborador (ej: VEND-1042)"
        string perfil_tienda "VENDEDOR, CAJERO, SUPERVISOR"
        boolean activo "Estado operativo del colaborador"
        timestamp created_at "Auditoria de creacion"
        timestamp updated_at "Auditoria de actualizacion"
    }
    RET_AUDITORIA_OPERACIONES {
        uuid id_auditoria PK "Identificador unico del registro de auditoria"
        uuid tienda_id "ID de la sucursal fisica"
        uuid usuario_id "ID logico del usuario que ejecuta la accion (Seguridad)"
        uuid caja_sesion_id FK "Turno de caja asociado (opcional)"
        string terminal_pos_codigo "Terminal POS de origen"
        string accion "APERTURA, VENTA, CIERRE"
        string estado "EXITOSO, FALLIDO, OBSERVADO"
        uuid referencia_id "ID logico opcional del pedido (Ventas)"
        text detalle "Descripcion de la operacion"
        jsonb detalle_json "Datos estructurados (montos, saldos, medio de pago)"
        timestamp fecha_hora "Fecha y hora de la operacion"
    }

    RET_CAJA_SESION ||--o{ RET_AUDITORIA_OPERACIONES : "genera"
```

---

## 3. Diccionario de Datos Exhaustivo

### Tabla 1: `RET_CAJA_SESION`
Gestiona la apertura, turnos, fondo fijo inicial y el **arqueo ciego de caja (Cierre Z)** en el mostrador.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_sesion` | `UUID` | No | `PRIMARY KEY` | Identificador de la sesión de caja |
| `tienda_id` | `UUID` | No | — | Identificador de la sucursal física |
| `terminal_pos_codigo` | `VARCHAR(20)` | No | — | Código físico de la máquina POS (ej. `POS-01`) |
| `vendedor_id` | `UUID` | No | Ref. externa Seguridad | Cajero o vendedor asignado |
| `fecha_hora_apertura` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Fecha y hora de apertura de turno |
| `saldo_inicial_efectivo`| `DECIMAL(12,2)` | No | `CHECK (saldo_inicial_efectivo >= 0)` | Fondo fijo de apertura (sencillo) |
| `fecha_hora_cierre` | `TIMESTAMP` | Sí | — | Fecha y hora en que se concluye el turno |
| `saldo_final_declarado`| `DECIMAL(12,2)` | Sí | — | Efectivo físico contado en arqueo ciego |
| `saldo_final_sistema` | `DECIMAL(12,2)` | Sí | — | Saldo calculado (Inicial + Ventas - Egresos) |
| `diferencia_saldo` | `DECIMAL(12,2)` | Sí | — | Saldo declarado menos saldo del sistema |
| `estado` | `VARCHAR(20)` | No | `CHECK IN ('ABIERTA', 'CERRADA', 'OBSERVADA')` | Estado del turno de caja |
| `observaciones_cierre` | `TEXT` | Sí | — | Explicación en caso de sobrante o faltante |
| `created_at` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Auditoría |
| `updated_at` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Auditoría |

---

### Tabla 2: `RET_MOVIMIENTO_CAJA`
Registra movimientos menores de efectivo durante el turno (compras de insumos urgentes, ingreso de sencillo auxiliar).

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_movimiento` | `UUID` | No | `PRIMARY KEY` | Identificador del movimiento de caja |
| `caja_sesion_id` | `UUID` | No | `FOREIGN KEY (RET_CAJA_SESION)` | Sesión de caja vinculada |
| `tipo_movimiento` | `VARCHAR(30)` | No | `CHECK IN ('INGRESO_MENOR', 'SALIDA_GASTO')` | Sentido del flujo de efectivo |
| `monto` | `DECIMAL(12,2)` | No | `CHECK (monto > 0)` | Monto dinerario del movimiento |
| `motivo` | `TEXT` | No | — | Justificación operativa del movimiento |
| `autorizado_por_supervisor` | `VARCHAR(100)` | No | — | Nombre o credencial del supervisor aprobador |
| `fecha_hora` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Momento exacto del registro |

---

### Tabla 3: `RET_CARRITO_ESPERA`
Permite suspender carritos activos para no retener la cola del POS mientras el cliente se prueba prendas en los probadores.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_carrito_espera` | `UUID` | No | `PRIMARY KEY` | Identificador del carrito suspendido |
| `tienda_id` | `UUID` | No | — | Sucursal donde se origina la atención |
| `vendedor_id` | `UUID` | No | Ref. externa Seguridad | Vendedor que asistió al cliente |
| `cliente_id` | `UUID` | Sí | Ref. externa Seguridad | Cliente identificado (opcional) |
| `alias_ticket` | `VARCHAR(80)` | No | — | Alias visual (ej. `Probador 3 - Casaca Adidas`) |
| `subtotal_estimado` | `DECIMAL(12,2)` | No | `CHECK (subtotal_estimado >= 0)` | Monto preliminar del carrito congelado |
| `fecha_creacion` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Momento de la suspensión |
| `fecha_expiracion_reserva`| `TIMESTAMP` | No | — | Tiempo límite de retención antes de expiración |
| `estado` | `VARCHAR(20)` | No | `CHECK IN ('SUSPENDIDO', 'REANUDADO', 'EXPIRADO')` | Ciclo de vida del carrito en espera |

---

### Tabla 4: `RET_CARRITO_ESPERA_ITEM`
Detalle de artículos contenidos dentro de un carrito puesto en espera.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_item` | `UUID` | No | `PRIMARY KEY` | Identificador del ítem en espera |
| `carrito_espera_id` | `UUID` | No | `FOREIGN KEY (RET_CARRITO_ESPERA)` | Carrito padre |
| `producto_id` | `UUID` | No | Ref. externa Productos | Identificador base del producto |
| `variante_sku_id` | `UUID` | No | Ref. externa Productos | SKU específico (talla y color) |
| `nombre_producto` | `VARCHAR(150)` | No | — | Snapshot del nombre del producto |
| `talla_color` | `VARCHAR(50)` | No | — | Resumen de variante (ej. `M / Azul Marino`) |
| `cantidad` | `INTEGER` | No | `CHECK (cantidad > 0)` | Unidades solicitadas |
| `precio_unitario` | `DECIMAL(12,2)` | No | `CHECK (precio_unitario >= 0)` | Precio unitario congelado |

---

### Tabla 5: `RET_SOLICITUD_CAMBIO_MOSTRADOR`
Gobierna la recepción física e inspección de prendas para cambio en tienda presencial dentro de los 30 días reglamentarios.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_solicitud` | `UUID` | No | `PRIMARY KEY` | Identificador del trámite de cambio |
| `caja_sesion_id` | `UUID` | No | `FOREIGN KEY (RET_CAJA_SESION)` | Caja donde se realiza el proceso |
| `pedido_id_origen` | `UUID` | No | Ref. externa Ventas | Orden original de compra |
| `variante_sku_devuelta_id`| `UUID` | No | Ref. externa Productos | SKU de la prenda que se devuelve |
| `cliente_id` | `UUID` | No | Ref. externa Seguridad | Cliente que solicita el cambio |
| `motivo_cambio` | `VARCHAR(50)` | No | `CHECK IN ('CAMBIO_TALLA', 'CAMBIO_MODELO', 'FALLA_FABRICA')` | Motivo del cambio según RF-24 |
| `inspeccion_etiquetas` | `BOOLEAN` | No | `DEFAULT FALSE` | Etiquetas de fábrica intactas |
| `inspeccion_sin_uso` | `BOOLEAN` | No | `DEFAULT FALSE` | Prenda limpia sin olores ni señales de uso |
| `inspeccion_empaque` | `BOOLEAN` | No | `DEFAULT FALSE` | Empaque o caja original entregada |
| `estado_aprobacion` | `VARCHAR(30)` | No | `CHECK IN ('APROBADO_EN_TIENDA', 'RECHAZADO')` | Decisión del vendedor tras inspección |
| `vale_temporal_codigo` | `VARCHAR(50)` | Sí | — | Código generado para canje inmediato |
| `monto_acreditado` | `DECIMAL(12,2)` | No | `CHECK (monto_acreditado >= 0)` | Monto a favor para elegir nueva prenda |
| `fecha_inspeccion` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Momento de la inspección técnica |

---

### Tabla 6: `RET_INCIDENCIA_INVENTARIO`
Control y registro de mermas físicas, prendas dañadas en probadores o extravíos para su aislamiento en cuarentena.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_incidencia` | `UUID` | No | `PRIMARY KEY` | Identificador de la incidencia |
| `tienda_id` | `UUID` | No | — | Sucursal donde ocurrió el suceso |
| `vendedor_reporta_id`| `UUID` | No | Ref. externa Seguridad | Vendedor que reporta la prenda |
| `variante_sku_id` | `UUID` | No | Ref. externa Productos | SKU de la prenda en mal estado |
| `codigo_barras` | `VARCHAR(50)` | No | — | Código EAN-13 leído por pistola láser |
| `tipo_falla` | `VARCHAR(50)` | No | `CHECK IN ('MANCHADO_PROBADOR', 'COSTURA_ROTA', 'EXTRAVIO_NO_UBICADO', 'DEFECTO_FABRICA')` | Tipo de incidencia física según RF-32 |
| `detalle_observacion` | `TEXT` | No | — | Explicación detallada del estado físico |
| `evidencia_foto_url` | `VARCHAR(255)` | Sí | — | URL fotográfica adjunta como evidencia |
| `estado_cuarentena` | `VARCHAR(30)` | No | `CHECK IN ('EN_CUARENTENA', 'DERIVADO_ALMACEN', 'DESCARTADO', 'RECHAZADO')` | Estado del lote/prenda |
| `fecha_reporte` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Fecha de emisión del acta |

---

### Tabla 7: `RET_CONTINGENCIA_OFFLINE_LOG`
Cola de resiliencia para ventas emitidas en contingencia offline (cuando se cae la red o el servicio central).

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_log` | `UUID` | No | `PRIMARY KEY` | Identificador del log de contingencia |
| `tienda_id` | `UUID` | No | — | Sucursal emisora |
| `terminal_pos_codigo` | `VARCHAR(20)` | No | — | Código del terminal POS |
| `venta_local_uuid` | `VARCHAR(64)` | No | `UNIQUE` | UUID de venta generado por IndexedDB en el POS |
| `payload_json_orden` | `JSONB` / `TEXT` | No | — | JSON con el detalle completo de la orden |
| `firma_hash_seguridad` | `VARCHAR(64)` | No | — | Firma SHA-256 para validación de no manipulación |
| `estado_sincronizacion`| `VARCHAR(20)` | No | `CHECK IN ('PENDIENTE', 'RESINCRONIZADO', 'CONFLICTO')` | Estado del proceso de sincronización |
| `fecha_emision_offline`| `TIMESTAMP` | No | — | Fecha y hora en que se concretó en local |
| `fecha_sincronizacion` | `TIMESTAMP` | Sí | — | Momento en que el backend central lo absorbió |
| `error_detalle` | `TEXT` | Sí | — | Detalle de excepción o discrepancia si falló |

---

### Tabla 8: `RET_PERSONAL_TIENDA`
Gestiona la asignación y perfiles operativos del personal dentro de cada sucursal física.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_personal` | `UUID` | No | `PRIMARY KEY` | Identificador único de asignación de personal |
| `usuario_id` | `UUID` | No | `UNIQUE` (Ref. lógica `sub`) | Identificador del usuario en el servicio de autenticación |
| `tienda_id` | `UUID` | No | — | Identificador de la sucursal física |
| `codigo_vendedor` | `VARCHAR(20)` | No | — | Código alfanumérico del colaborador (ej. `VEND-1042`) |
| `perfil_tienda` | `VARCHAR(20)` | No | `CHECK IN ('VENDEDOR', 'CAJERO', 'SUPERVISOR')` | Perfil operativo asignado en la sucursal |
| `activo` | `BOOLEAN` | No | `DEFAULT TRUE` | Estado operativo del colaborador en la tienda |
| `created_at` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Auditoría de creación |
| `updated_at` | `TIMESTAMP` | No | `DEFAULT CURRENT_TIMESTAMP` | Auditoría de actualización |

---

### Tabla 9: `RET_AUDITORIA_OPERACIONES`
Registra la trazabilidad operativa del mostrador: apertura de turno, ventas y cierre, con usuario, terminal y resultado. Es una tabla **de solo inserción** (inmutable): los registros de apertura y cierre los genera automáticamente un trigger sobre `RET_CAJA_SESION`.

| Campo | Tipo SQL | Nulo | Restricciones / Valores | Descripción |
| :--- | :--- | :---: | :--- | :--- |
| `id_auditoria` | `UUID` | No | `PRIMARY KEY` | Identificador único del registro de auditoría |
| `tienda_id` | `UUID` | No | — | Identificador de la sucursal física |
| `usuario_id` | `UUID` | No | — (Ref. lógica Seguridad) | Usuario que ejecutó la operación |
| `caja_sesion_id` | `UUID` | Sí | `FOREIGN KEY` → `RET_CAJA_SESION(id_sesion)` `ON DELETE RESTRICT` | Turno de caja asociado |
| `terminal_pos_codigo` | `VARCHAR(20)` | No | — | Terminal POS de origen (ej. `POS-01`) |
| `accion` | `VARCHAR(20)` | No | `CHECK IN ('APERTURA', 'VENTA', 'CIERRE')` | Tipo de operación registrada |
| `estado` | `VARCHAR(20)` | No | `DEFAULT 'EXITOSO'`, `CHECK IN ('EXITOSO', 'FALLIDO', 'OBSERVADO')` | Resultado de la operación |
| `referencia_id` | `UUID` | Sí | — (Ref. lógica Ventas) | Pedido asociado, si aplica |
| `detalle` | `TEXT` | Sí | — | Descripción o motivo de la operación |
| `detalle_json` | `JSONB` | Sí | — | Datos estructurados de la operación (montos, saldos, medio de pago) |
| `fecha_hora` | `TIMESTAMPTZ` | No | `DEFAULT CURRENT_TIMESTAMP` | Momento en que ocurrió la operación |

---

## 4. Índices de Rendimiento Recomendados

```sql
-- Consultas rápidas por estado de sesión de caja y vendedor
CREATE INDEX idx_caja_sesion_tienda_vendedor ON RET_CAJA_SESION(tienda_id, vendedor_id, estado);

-- Búsqueda de carritos suspendidos activos en mostrador
CREATE INDEX idx_carrito_espera_tienda_estado ON RET_CARRITO_ESPERA(tienda_id, estado);

-- Consulta de colas de sincronización pendientes
CREATE INDEX idx_offline_log_estado ON RET_CONTINGENCIA_OFFLINE_LOG(tienda_id, estado_sincronizacion);

-- Búsqueda de incidencias activas en cuarentena
CREATE INDEX idx_incidencia_tienda_estado ON RET_INCIDENCIA_INVENTARIO(tienda_id, estado_cuarentena);

-- Resolución rápida de perfiles operativos del personal de tienda
CREATE INDEX idx_personal_tienda_usuario ON RET_PERSONAL_TIENDA(usuario_id, tienda_id, activo);

-- Consulta cronológica de auditoría por tienda y por usuario/acción
CREATE INDEX idx_auditoria_tienda_fecha ON RET_AUDITORIA_OPERACIONES(tienda_id, fecha_hora DESC);
CREATE INDEX idx_auditoria_usuario_accion ON RET_AUDITORIA_OPERACIONES(usuario_id, accion);
CREATE INDEX idx_auditoria_caja_sesion ON RET_AUDITORIA_OPERACIONES(caja_sesion_id);
```

---

## 5. Triggers de Auditoría y Políticas de Seguridad (RLS)

1. **Triggers de actualización automática (`updated_at`)** en `RET_CAJA_SESION` y `RET_PERSONAL_TIENDA`.
2. **Auditoría automática** (`fn_auditar_caja_sesion`):
   - `trg_ret_caja_sesion_audit_ins`: al abrir un turno (`INSERT` en `RET_CAJA_SESION`) registra una `APERTURA` en `RET_AUDITORIA_OPERACIONES`.
   - `trg_ret_caja_sesion_audit_upd`: al cerrar un turno (`ABIERTA` → `CERRADA`/`OBSERVADA`) registra un `CIERRE` (`EXITOSO` u `OBSERVADO`).
3. **Auditoría inmutable** (`fn_auditoria_inmutable`):
   - `trg_ret_auditoria_inmutable` y `trg_ret_auditoria_no_truncate` bloquean `UPDATE`, `DELETE` y `TRUNCATE` sobre `RET_AUDITORIA_OPERACIONES` (solo inserción). La FK hacia `RET_CAJA_SESION` es `ON DELETE RESTRICT`, por lo que un turno con rastro de auditoría no se puede borrar.
4. **Row Level Security (RLS)**:
   - Todas las 9 tablas tienen RLS habilitado (`ENABLE ROW LEVEL SECURITY`); `RET_AUDITORIA_OPERACIONES` solo admite `SELECT` e `INSERT`.