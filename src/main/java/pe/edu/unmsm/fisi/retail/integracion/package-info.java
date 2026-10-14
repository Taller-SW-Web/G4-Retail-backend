/**
 * Adaptadores hacia los modulos externos: Seguridad (G7), Productos (G6) y Ventas (G5).
 *
 * <p>Cada integracion se define como una interfaz, con dos implementaciones intercambiables por
 * perfil de Spring: el mock de la Semana 8 y el adaptador HTTP real. Pasar de uno a otro debe ser
 * solo configuracion, nunca un cambio de codigo (seccion 6 del contrato v0.4).</p>
 *
 * <p>Los datos semilla de los mocks viven aparte de las tablas propias de Retail, porque no son
 * datos de este modulo (database-per-service).</p>
 */
package pe.edu.unmsm.fisi.retail.integracion;
