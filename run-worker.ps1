<#
.SYNOPSIS
    Compila y arranca ClipHive Worker desde el repo, sin instalador.
.DESCRIPTION
    Sirve para probar cambios del worker antes de generar un .msi.

    Usa la misma configuración que el worker instalado
    (%LOCALAPPDATA%\ClipHive\worker\worker.yml): si no existe, en modo
    escritorio se abre la ventana de configuración.

    ffmpeg, ffprobe y whisper-cli: si no están configurados ni en el PATH, se
    usan los del worker instalado (C:\Program Files\ClipHive Worker\app\bin).
.PARAMETER Headless
    Sin ventana; los logs salen por la consola. Requiere token en worker.yml
    o en CLIPHIVE_WORKER_TOKEN.
.PARAMETER SkipBuild
    No recompila; arranca el último jar de worker\target.
.EXAMPLE
    .\run-worker.ps1
    .\run-worker.ps1 -Headless
    .\run-worker.ps1 -SkipBuild
#>
param(
    [switch]$Headless,
    [switch]$SkipBuild
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
$env:JAVA_HOME = $javaHome

# ---------------------------------------------------------------------------
# Compilar (Maven 3.9.9, igual que run-server.ps1)
# ---------------------------------------------------------------------------
if (-not $SkipBuild) {
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
    }

    Write-Host "Compilando el worker..."
    & $mvnCmd -q -B -f "$root\pom.xml" -pl worker -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { Write-Error "La compilación ha fallado." }
}

$jar = Get-ChildItem "$root\worker\target" -Filter "worker-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "original-*" -and $_.Name -notlike "*-shaded.jar" } |
    Select-Object -First 1
if (-not $jar) {
    Write-Error "No hay jar en worker\target. Ejecuta sin -SkipBuild."
}

# Se ejecuta una copia: Windows bloquea el jar en uso, y así se puede volver a
# compilar (otra consola, IntelliJ) con un worker ya abierto.
$runDir = Join-Path $env:TEMP "cliphive-worker-run"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Get-ChildItem $runDir -Filter "worker-*.jar" -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item $_.FullName -ErrorAction SilentlyContinue }  # las que sigan en uso se quedan
$runJar = Join-Path $runDir ("worker-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".jar")
Copy-Item $jar.FullName $runJar

# ---------------------------------------------------------------------------
# ffmpeg / ffprobe / whisper-cli del worker instalado, si hacen falta
# ---------------------------------------------------------------------------
$installedBin = "C:\Program Files\ClipHive Worker\app\bin"
$tools = @(
    @{ Var = "CLIPHIVE_WORKER_FFMPEG_PATH";  Exe = "ffmpeg.exe" },
    @{ Var = "CLIPHIVE_WORKER_FFPROBE_PATH"; Exe = "ffprobe.exe" },
    @{ Var = "CLIPHIVE_WORKER_WHISPER_PATH"; Exe = "whisper-cli.exe" }
)
foreach ($t in $tools) {
    $alreadySet = [Environment]::GetEnvironmentVariable($t.Var)
    $onPath     = Get-Command $t.Exe -ErrorAction SilentlyContinue
    $bundled    = Join-Path $installedBin $t.Exe
    if (-not $alreadySet -and -not $onPath -and (Test-Path $bundled)) {
        [Environment]::SetEnvironmentVariable($t.Var, $bundled, "Process")
    }
}

# ---------------------------------------------------------------------------
# Arrancar
# ---------------------------------------------------------------------------
$runArgs = @("--enable-native-access=ALL-UNNAMED", "-jar", $runJar)
if ($Headless) { $runArgs += "--headless" }

Write-Host ""
Write-Host "  ClipHive Worker — modo $( if ($Headless) { 'headless' } else { 'escritorio' } )"
Write-Host "  JAVA_HOME : $javaHome"
Write-Host "  Jar       : $($jar.FullName)"
Write-Host "  Log       : $env:LOCALAPPDATA\ClipHive\worker\logs\worker.log"
Write-Host "  Config    : $env:LOCALAPPDATA\ClipHive\worker\worker.yml"
Write-Host "  ffmpeg    : $( if ($env:CLIPHIVE_WORKER_FFMPEG_PATH) { $env:CLIPHIVE_WORKER_FFMPEG_PATH } else { 'PATH / worker.yml' } )"
Write-Host "  whisper   : $( if ($env:CLIPHIVE_WORKER_WHISPER_PATH) { $env:CLIPHIVE_WORKER_WHISPER_PATH } else { 'PATH / worker.yml' } )"
Write-Host ""

& "$javaHome\bin\java.exe" @runArgs
