-- Datos minimos para el perfil 'local'. Son un extracto de diseño_bd/seed.sql (Angie),
-- reducido a los dos usuarios que pide la seccion 6 del contrato v0.4 para la Semana 8:
-- un vendedor y un supervisor. La base real en Supabase se carga con el seed completo.
INSERT INTO RET_PERSONAL_TIENDA
    (id_personal, usuario_id, tienda_id, codigo_vendedor, perfil_tienda, activo, created_at, updated_at)
VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001',
     '11111111-1111-1111-1111-111111111111', 'VEN-001', 'VENDEDOR', TRUE,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'b9e222a5-cf86-4c95-9611-891886160821',
     '11111111-1111-1111-1111-111111111111', 'VEN-002', 'VENDEDOR', TRUE,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('aaaaaaaa-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000004',
     '11111111-1111-1111-1111-111111111111', 'SUP-001', 'SUPERVISOR', TRUE,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
