param(
    [ValidatePattern('^[1-9][0-9]*[MG]$')][string]$MinMemory = '1G',
    [ValidatePattern('^[1-9][0-9]*[MG]$')][string]$MaxMemory = '4G'
)

$ErrorActionPreference = 'Stop'
$serverDirectory = Join-Path $PSScriptRoot 'server'
$paperPath = Join-Path $serverDirectory 'paper-26.3-246.jar'
$propertiesPath = Join-Path $serverDirectory 'server.properties'
$pluginsDirectory = Join-Path $serverDirectory 'plugins'
$serverPort = 25565
if (Test-Path -LiteralPath $propertiesPath) {
    foreach ($propertyLine in Get-Content -LiteralPath $propertiesPath) {
        if ($propertyLine -cmatch '^\s*server-port\s*=\s*(.*?)\s*$') {
            if (-not [int]::TryParse($Matches[1], [ref]$serverPort) -or $serverPort -lt 1 -or $serverPort -gt 65535) {
                throw 'Invalid server-port in server\server.properties. Use a port from 1 to 65535.'
            }
        }
    }
}
if (-not (Test-Path -LiteralPath $paperPath)) {
    throw "Paper jar not found: $paperPath"
}
$eulaPath = Join-Path $serverDirectory 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath) -or
    -not (Select-String -LiteralPath $eulaPath -Pattern '^\s*eula\s*=\s*true\s*$' -Quiet)) {
    throw 'Read https://aka.ms/MinecraftEULA. If you agree, set eula=true in server\eula.txt, then start again.'
}

$javaCandidates = @((Join-Path $env:ProgramFiles 'Java\jdk-25.0.2\bin\java.exe'))
if ($env:JAVA_HOME) { $javaCandidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) { $javaCandidates += $pathJava.Source }
$javaPath = $javaCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $javaPath) { throw 'Java 25 is required. Install it and set JAVA_HOME.' }

# Java writes -version to stderr; use a process to avoid PowerShell 5 native stderr errors.
$versionInfo = New-Object System.Diagnostics.ProcessStartInfo
$versionInfo.FileName = $javaPath
$versionInfo.Arguments = '-version'
$versionInfo.UseShellExecute = $false
$versionInfo.CreateNoWindow = $true
$versionInfo.RedirectStandardError = $true
$versionProcess = [System.Diagnostics.Process]::Start($versionInfo)
$versionText = $versionProcess.StandardError.ReadToEnd()
$versionProcess.WaitForExit()
$versionProcess.Dispose()
if ($versionText -notmatch 'version "25(?:\.|\")') {
    throw "Java 25 is required. Selected Java: $javaPath"
}

# Do not change deployed jars while this server is still running.
$portProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $serverPort)
try {
    $portProbe.Start()
} catch {
    throw "Port $serverPort is already in use. Stop the existing server with its console command 'stop' before starting InveCat."
} finally {
    $portProbe.Stop()
}

$builtJar = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'build\libs') -Filter 'InveCat-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($builtJar) {
    New-Item -ItemType Directory -Path $pluginsDirectory -Force | Out-Null
    Get-ChildItem -LiteralPath $pluginsDirectory -Filter 'InveCat-*.jar' |
        Where-Object { $_.Name -ne $builtJar.Name } | Remove-Item -Force
    Copy-Item -LiteralPath $builtJar.FullName -Destination $pluginsDirectory -Force
    Write-Host "Plugin: $($builtJar.Name)"
} elseif (-not (Get-ChildItem -LiteralPath $pluginsDirectory -Filter 'InveCat-*.jar' -ErrorAction SilentlyContinue)) {
    throw 'InveCat jar not found. Run build.ps1 first.'
}

$lanAddresses = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -ExpandProperty IPAddress)

Push-Location $serverDirectory
try {
    Write-Host "InveCat | Paper 26.3 build 246 | localhost:$serverPort"
    foreach ($address in $lanAddresses) { Write-Host "  LAN: ${address}:$serverPort" }
    Write-Host "  Friends outside your network need TCP $serverPort forwarded."
    Write-Host 'Stop safely with the console command: stop'
    & $javaPath "-Xms$MinMemory" "-Xmx$MaxMemory" '-Dfile.encoding=UTF-8' '--enable-native-access=ALL-UNNAMED' '-jar' $paperPath '--nogui'
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE. See server\logs\latest.log." }
} finally {
    Pop-Location
}
