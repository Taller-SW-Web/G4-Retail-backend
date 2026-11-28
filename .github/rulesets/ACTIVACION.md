# Activacion posterior a la publicacion

Los JSON representan el estado deseado activo; no se han aplicado remotamente.
Completar primero los pushes normales a main de esta fase, validar los runs y
confirmar los nombres efectivos de los check-runs del PR antes de activar.

Required checks declarados para main y develop:

- Security Analysis / Security Policy Result
- Security Analysis / pr-gate

Se conservan ambos nombres. Security Policy Result ahora depende del gate integral
pr-gate y exige success y decision=GO. No usar el prefijo Delivery del workflow CD
como contexto requerido para PR. Confirmar en GitHub que los dos contextos existen
en un PR real; no activar un contexto inexistente que bloquee todas las fusiones.

Activar los rulesets solo con autorizacion administrativa posterior, conservando
revision, code owners, prohibicion de force push y de borrado. Esta fase no aplica
cambios administrativos ni bypass. Los JSON locales no demuestran proteccion remota.
