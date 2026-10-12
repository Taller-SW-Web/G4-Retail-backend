/**
 * Capa de entrada HTTP: endpoints REST, mapeo de rutas y codigos de estado.
 *
 * <p>Esta capa esta <strong>vacia a proposito</strong>. La guia de trabajo de la Semana 7 prohibe
 * programar un endpoint antes de que Miguel y Maylle confirmen que esta aprobado en
 * {@code specs/api-contracts.md}, y el tablero de la seccion 8 del contrato v0.4 marca todavia
 * como PROPUESTO los cuatro endpoints asignados a Cristhian:</p>
 *
 * <ul>
 *   <li>{@code GET /api/v1/retail/perfil} (F1)</li>
 *   <li>{@code GET /api/v1/retail/health} (F10)</li>
 *   <li>{@code GET /api/v1/retail/catalogo} (F2)</li>
 *   <li>{@code GET /api/v1/retail/catalogo/codigo-barras/{codigo}} (F2)</li>
 * </ul>
 *
 * <p>Las rutas que aparecen en la guia ({@code /auth/login} y {@code /productos}) quedaron
 * desplazadas por las decisiones D-02 y D-03 del contrato: el login lo atiende Seguridad (G7) y
 * la API propia usa el prefijo unico {@code /api/v1/retail}.</p>
 */
package pe.edu.unmsm.fisi.retail.controller;
