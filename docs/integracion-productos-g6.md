# Integración con Productos y Ofertas (G6)

Qué encontramos al programar el adaptador contra el OpenAPI real que publica G6 en
`G6-Productos-Ofertas-docs/contratos/http/openapi.yaml`, y en qué se aparta de lo que asumía
nuestro contrato (`specs/api-contracts.md` v0.4, §4.2 y §5.2).

Importa porque el mock de la Semana 8 implementa **estas** firmas: si el adaptador real se escribe
contra lo que asumimos y no contra lo que G6 publica, la integración fallará justo al conectarnos.

## Lo que G6 expone de verdad

| Operación | Ruta real | Qué devuelve |
|---|---|---|
| Catálogo | `GET /productos` | `PaginaProductosComercial`, **sin precios** |
| Variantes | `GET /productos/{productoId}/variantes` | Llamada aparte; de aquí salen talla, color y SKU |
| Código de barras | `POST /productos/codigos-barras/resolver` | **Solo el SKU**: `{ "sku": "..." }` |
| Precio | `GET /precios/skus/{sku}` | Precio regular, oferta y moneda |
| Disponibilidad | `GET /inventario/disponibilidad` | Estado por SKU, sin cantidades |

## Las cuatro diferencias

### 1. Una pantalla de catálogo son tres llamadas, no una

Nuestro `GET /retail/catalogo` devuelve nombre, precio y variantes juntos. En G6 eso vive en tres
operaciones distintas: productos, precios por SKU y disponibilidad. La fachada de Retail tiene que
componerlas. No es un detalle menor para el rendimiento del mostrador: conviene cachear precios y
disponibilidad por SKU mientras dure la atención.

### 2. El código de barras no trae el producto

`resolverCodigoBarras` responde **únicamente el SKU**. Para mostrar algo en pantalla hay que ir
después por el producto, su precio y su disponibilidad. Y queda confirmado algo que el contrato ya
advertía: **el código de barras no es el SKU**, son cosas distintas.

### 3. La paginación empieza en 1

G6 pagina desde `pagina = 1`; nuestro contrato expone `page = 0` (§2.7). La conversión la hace el
adaptador, en `FiltroCatalogo.desdePaginacionRetail`, nunca el controlador.

### 4. Los estados de disponibilidad no coinciden

| G6 informa | Retail v0.4 define | Traducción propuesta |
|---|---|---|
| `DISPONIBLE` | `DISPONIBLE` | directa |
| `STOCK_BAJO` | `ULTIMAS_UNIDADES` | directa |
| `AGOTADO` | `AGOTADO_TOTAL` | directa |
| `INDETERMINADO` | *no existe* | **pendiente de decisión** |
| *no existe* | `SOLO_ALMACEN_CENTRAL` | G6 no distingue el almacén central |

**Pendiente para Maylle y Mihael:** `INDETERMINADO` significa, en palabras de G6, que "no hay
abastecimiento único verificable", y su propio contrato aclara que **nunca equivale a agotado**. La
propuesta es mostrarlo como disponible pero sin `stockTienda`, y que el vendedor confirme con el
cliente antes de prometer la entrega. Tratarlo como agotado perdería ventas; tratarlo como
disponible con stock las prometería sin respaldo.

Además, `SOLO_ALMACEN_CENTRAL` no se puede informar con lo que G6 publica hoy. O se elimina de
nuestro contrato, o se le pide a G6 que lo distinga.

## Lo que Retail tiene prohibido llamar

Aunque aparezcan en el OpenAPI de G6, estas operaciones están vedadas para Retail y el adaptador no
debe implementarlas: `/inventario/consumir`, reservas y su confirmación (G4-P0-01), reintegros
(G4-P0-03), conciliaciones offline (G4-P0-02) y cualquier ajuste de stock (G4-P0-05). El stock lo
orquesta Ventas; Inventario es la única fuente de saldos.
