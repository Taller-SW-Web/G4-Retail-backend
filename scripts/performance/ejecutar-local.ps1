$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $backend
$proceso = $null
try {
    if (Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue) {
        throw 'El puerto 18080 está ocupado; no se medirá un proceso ajeno'
    }
    & ./mvnw.cmd -B verify
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo verificar/empaquetar el backend' }
    New-Item -ItemType Directory -Force target/performance | Out-Null
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
    $proceso = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -ArgumentList @(
        '-jar', 'target/retail-0.0.1-SNAPSHOT.jar', '--spring.profiles.active=local,mock',
        '--retail.integracion.ventas.habilitado=false', '--retail.integracion.ventas.pickup.modo=memoria',
        '--retail.integracion.ventas.pedidos.modo=memoria', '--spring.jpa.show-sql=false',
        '--logging.level.pe.edu.unmsm.fisi.retail=WARN', '--server.port=18080'
    ) -RedirectStandardOutput target/performance/servidor.log -RedirectStandardError target/performance/servidor-error.log
    $listo = $false
    for ($intento = 0; $intento -lt 60; $intento++) {
        if ($proceso.HasExited) { throw 'El servidor terminó antes de la carga; revisar servidor-error.log' }
        try {
            $respuesta = Invoke-WebRequest 'http://127.0.0.1:18080/api/v1/retail/health' -UseBasicParsing -TimeoutSec 2
            if ($respuesta.StatusCode -eq 200) { $listo = $true; break }
        } catch { Start-Sleep -Seconds 2 }
    }
    if (!$listo) { throw 'El backend no quedó disponible en 18080' }
    # Solo el proceso hijo recibe el destino local, sin alterar variables de la sesión del usuario.
    & node --input-type=module -e "import { ejecutar } from './scripts/performance/pos.mjs'; const ok = await ejecutar({ ...process.env, PERF_MODE: 'local', PERF_BASE_URL: 'http://127.0.0.1:18080' }); process.exitCode = ok ? 0 : 1;"
    if ($LASTEXITCODE -ne 0) { throw 'La carga no aprobó; revisar target/performance/' }
} finally {
    if ($proceso -and !$proceso.HasExited) { Stop-Process -Id $proceso.Id }
    Pop-Location
}
