/**
 * Logica de negocio: reglas de validacion, calculo de totales e IGV (18%) y orquestacion
 * entre repositorios y adaptadores de integracion.
 *
 * <p>Las operaciones criticas (apertura de caja, registro de ordenes, cobros y cierres con arqueo)
 * se anotan con {@code @Transactional}, segun la seccion 3.3 de {@code AGENTS.md}.</p>
 */
package pe.edu.unmsm.fisi.retail.service;
