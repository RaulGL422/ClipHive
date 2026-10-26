<#
.SYNOPSIS
    Arranca ClipHive Server.
.DESCRIPTION
    embedded (defecto) — Postgres volátil embebido. No necesita variables de entorno.
    demo               — embedded con datos inventados (trabajos, clips, publicaciones,
                         estadísticas) para ver el superadmin con contenido.
    normal           — Postgres real. Si existe un .env en la raíz del repo, lo
                         carga en el entorno del proceso (sin pisar variables ya
                         definidas; los valores vacíos se ignoran). Requiere
                         CLIPHIVE_DB_URL, CLIPHIVE_DB_USER, CLIPHIVE_DATA_DIR,
                         CLIPHIVE_URL_SIGNING_KEY y CLIPHIVE_ENCRYPTION_KEY
                         (las mismas que comprueba el servidor al arrancar).
.PARAMETER Mode
    "embedded" (default), "demo" o "normal"
.EXAMPLE
    .\run-server.ps1
    .\run-server.ps1 -Mode demo
    .\run-server.ps1 -Mode normal
#>
param(
    [ValidateSet("embedded", "demo", "normal")]
    [string]$Mode = "embedded"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# ---------------------------------------------------------------------------
# JDK 25
# ---------------------------------------------------------------------------
$jdkCandidates = @(
    "$env:USERPROFILE\.jdks\openjdk-25",
    "C:\Program Files\Java\openjdk-25",
    "$env:LOCALAPPDATA\Programs\openjdk-25"
)
# IntelliJ descarga los JDK en ~/.jdks con nombres como ms-25.0.2 o temurin-25
$jdkCandidates += Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "*25*" -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName }
# Eclipse Temurin (winget install EclipseAdoptium.Temurin.25.JDK) se instala como jdk-25.x.y-hotspot
$jdkCandidates += Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter "jdk-25*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { $_.FullName }
$javaHome = $null
foreach ($c in $jdkCandidates) {
    if (Test-Path "$c\bin\java.exe") { $javaHome = $c; break }
}
if (-not $javaHome) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $javaHome = $env:JAVA_HOME
        # Versión desde el fichero "release" del JDK (JAVA_VERSION="25.0.2")
        $javaVersion = $null
        $releaseFile = Join-Path $javaHome "release"
        if (Test-Path $releaseFile) {
            $m = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
            if ($m) { $javaVersion = $m.Matches[0].Groups[1].Value }
        }
        if (-not $javaVersion) {
            Write-Warning "JDK 25 no encontrado en rutas conocidas; usando JAVA_HOME=$javaHome, cuya versión no se puede comprobar. ClipHive necesita Java 25."
        } elseif ($javaVersion -notmatch '^25(\.|$)') {
            Write-Error "JAVA_HOME=$javaHome es Java $javaVersion, y ClipHive necesita Java 25. Instálalo (winget install EclipseAdoptium.Temurin.25.JDK) o apunta JAVA_HOME a un JDK 25."
        } else {
            Write-Host "Usando JAVA_HOME=$javaHome (Java $javaVersion)"
        }
    } else {
        Write-Error "No se encuentra JDK 25. Instálalo (winget install EclipseAdoptium.Temurin.25.JDK) o define JAVA_HOME."
    }
}

# ---------------------------------------------------------------------------
# Maven 3.9.9 — descarga a ~/.m2/wrapper/dists si no existe
# ---------------------------------------------------------------------------
$mavenVersion = "3.9.9"
$mavenBase    = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-$mavenVersion-bin"
$mavenDir     = "$mavenBase\apache-maven-$mavenVersion"
$mvnCmd       = "$mavenDir\bin\mvn.cmd"

if (-not (Test-Path $mvnCmd)) {
    Write-Host "Maven $mavenVersion no encontrado — descargando..."
    New-Item -ItemType Directory -Force -Path $mavenBase | Out-Null
    $zip = "$env:TEMP\apache-maven-$mavenVersion-bin.zip"
    Invoke-WebRequest "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $mavenBase -Force
    Remove-Item $zip
    Write-Host "Maven descargado en $mavenDir"
}

# ---------------------------------------------------------------------------
# Instalar módulos en repo local si protocol aún no está
# ---------------------------------------------------------------------------
$protocolJar = "$env:USERPROFILE\.m2\repository\io\github\raulgl422\cliphive\protocol\0.1.0-SNAPSHOT\protocol-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $protocolJar)) {
    Write-Host "Módulo protocol no instalado — ejecutando install -DskipTests..."
    $env:JAVA_HOME = $javaHome
    & $mvnCmd -f "$root\pom.xml" install -DskipTests
}

# ---------------------------------------------------------------------------
# Validar variables de entorno para modo normal
# ---------------------------------------------------------------------------
if ($Mode -eq "normal") {
    # .env de la raíz del repo: rellena las variables que no estén ya definidas
    # (las del entorno mandan). Solo en modo normal: en embedded las variables de
    # .env (p. ej. CLIPHIVE_WORKER_BIND) pisarían la configuración del perfil.
    $envFile = Join-Path $root ".env"
    if (Test-Path $envFile) {
        $loaded = 0
        foreach ($line in Get-Content -Path $envFile -Encoding UTF8) {
            $t = $line.Trim()
            if ($t -eq "" -or $t.StartsWith("#")) { continue }
            $eq = $t.IndexOf("=")
            if ($eq -lt 1) { continue }
            $name  = $t.Substring(0, $eq).Trim()
            $value = $t.Substring($eq + 1).Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                                          ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            # Vacío = sin definir (como en .env.example); no pisa el entorno
            if ($value -eq "" -or [Environment]::GetEnvironmentVariable($name)) { continue }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
            $loaded++
        }
        Write-Host "Cargadas $loaded variable(s) de $envFile"
    }

    # Las que StartupConfigCheck exige; el servidor valida además su formato al arrancar
    $missing = @()
    foreach ($v in @("CLIPHIVE_DB_URL", "CLIPHIVE_DB_USER", "CLIPHIVE_DATA_DIR",
                     "CLIPHIVE_URL_SIGNING_KEY", "CLIPHIVE_ENCRYPTION_KEY")) {
        if (-not [Environment]::GetEnvironmentVariable($v)) { $missing += $v }
    }
    if ($missing.Count -gt 0) {
        Write-Error "Faltan variables de entorno para modo normal: $($missing -join ', ')`nDefínelas o ponlas en $envFile (ver .env.example y docs/DESIGN.md sección 13.1)."
    }
}

# ---------------------------------------------------------------------------
# Arrancar
# ---------------------------------------------------------------------------
$env:JAVA_HOME = $javaHome

$runArgs = @("-f", "$root\pom.xml", "spring-boot:run", "-pl", "server")
if ($Mode -ne "normal") {
    # -Pembedded adds src/embedded (embedded Postgres, test admin, demo data), which the production jar doesn't carry
    $runArgs += "-Pembedded"
    $runArgs += $(if ($Mode -eq "demo") { "-Dspring-boot.run.profiles=embedded,demo" } else { "-Dspring-boot.run.profiles=embedded" })
}

$dbInfo = if ($Mode -eq "normal") { $env:CLIPHIVE_DB_URL }
          elseif ($Mode -eq "demo") { "Postgres embebido (volátil) con datos de demo" }
          else { "Postgres embebido (volátil)" }

Write-Host ""
Write-Host "  ClipHive Server — modo $Mode"
Write-Host "  JAVA_HOME : $javaHome"
Write-Host "  Maven     : $mvnCmd"
Write-Host "  BD        : $dbInfo"
Write-Host "  Puerto    : $( if ($env:CLIPHIVE_ADMIN_PORT) { $env:CLIPHIVE_ADMIN_PORT } else { '8080 (defecto)' } )"
Write-Host ""

& $mvnCmd @runArgs
