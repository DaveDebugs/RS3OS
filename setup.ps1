<#
.SYNOPSIS
    Sets up an RS3OS server from a fresh clone: build, keys, client, cache, database.

.DESCRIPTION
    Runs every setup step that has not already been done, in order, and stops at
    the first failure with an explanation. Safe to run again -- finished steps
    are detected and skipped, so an interrupted download can be resumed by
    simply running the script a second time.

    Steps, in order:

      check     Java 21 and Python 3 are present
      build     compile the server            (gradlew installDist)
      config    data/config/server.toml       (from server.example.toml)
      keys      data/config/rsa.toml          (run-tool rsa-key-generator)
      client    data/clients/<build>/win64/original/  (your own client binary)
      patch     the client trusts your key    (run-tool client-patcher)
      cache     data/cache/                   (run-tool cache-downloader)
      database  data/rs3.sqlite               (run-tool db-builder)
      world     collision and placements      (run-tool map-builder)
      seed      data/seed/npc_cache.json      (tools/seed/seed_from_cache.py)

.PARAMETER Client
    Path to a RuneScape NXT client executable you already have. Required the
    first time unless you pass -DownloadClient. Nothing is downloaded from
    Jagex on your behalf without you asking for it.

.PARAMETER DownloadClient
    Fetch the client from Jagex with run-tool client-downloader instead of
    supplying your own. The build it fetches is whatever Jagex is serving now,
    which may not be the build this server targets.

.PARAMETER Hostname
    The address your client will connect to. Default 127.0.0.1 (this machine).

.PARAMETER Step
    Run one step only, by name. Useful for retrying a single failure.

.PARAMETER Force
    Redo steps even if their output already exists.

.EXAMPLE
    .\setup.ps1 -Client 'C:\Program Files\Jagex\RuneScape\rs2client.exe'

.EXAMPLE
    .\setup.ps1 -Step cache
#>
[CmdletBinding()]
param(
    [string] $Client,
    [switch] $DownloadClient,
    [string] $Hostname = '127.0.0.1',
    [string] $Cache,
    [ValidateSet('check', 'build', 'config', 'keys', 'client', 'patch', 'cache', 'database', 'world', 'seed')]
    [string] $Step,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
Set-Location $Root

# The build this server targets. Also written into data/config/server.toml.
$Build = 949
$BinaryType = 'win64'

# --------------------------------------------------------------------------
# output helpers
# --------------------------------------------------------------------------
$script:StepNo = 0
function Write-Step($name, $text) {
    $script:StepNo++
    Write-Host ''
    Write-Host ("[{0}] {1}" -f $script:StepNo, $text) -ForegroundColor Cyan
}
function Write-Ok($text)   { Write-Host "    OK    $text" -ForegroundColor Green }
function Write-Skip($text) { Write-Host "    skip  $text" -ForegroundColor DarkGray }
function Write-Info($text) { Write-Host "          $text" -ForegroundColor Gray }
function Fail($text) {
    Write-Host ''
    Write-Host "FAILED: $text" -ForegroundColor Red
    exit 1
}

function Invoke-Rs3os {
    <#  Runs the built server CLI and fails loudly on a non-zero exit.  #>
    param([string[]] $Arguments, [string] $What)

    $exe = Join-Path $Root 'build\install\rs3os\bin\rs3os.bat'
    if (-not (Test-Path $exe)) {
        Fail "the server is not built yet. Run: .\setup.ps1 -Step build"
    }
    Write-Info "run-tool: $($Arguments -join ' ')"
    # The generated launcher expands its options unquoted, so a value
    # containing a space has to carry its own quotes.
    if ($script:CachePath) { $env:RS3OS_OPTS = '"-Dopennxt.cache=' + $script:CachePath + '"' }
    & $exe @Arguments
    if ($LASTEXITCODE -ne 0) { Fail "$What (exit code $LASTEXITCODE)" }
}

function Should-Run($name) {
    if ($Step) { return $Step -eq $name }
    return $true
}

# --------------------------------------------------------------------------
# 1. prerequisites
# --------------------------------------------------------------------------
if (Should-Run 'check') {
    Write-Step 'check' 'Checking prerequisites'

    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Fail @"
Java is not on your PATH.

Install a JDK 21 (Temurin is the usual choice) and reopen this terminal:
    https://adoptium.net/temurin/releases/?version=21
"@
    }

    # `java -version` prints to stderr; 2>&1 folds it into the pipeline.
    $vLine = (& java -version 2>&1 | Select-Object -First 1) -as [string]
    if ($vLine -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -lt 21) {
            Fail "Java $major found, but this project needs Java 21 or newer.`n  $vLine"
        }
        Write-Ok "Java $major"
    } else {
        Write-Info "could not parse the Java version from: $vLine"
        Write-Info "continuing anyway"
    }

    $py = Get-Command python -ErrorAction SilentlyContinue
    if ($py) { Write-Ok 'Python 3 (needed only to stage your own client)' }
    else     { Write-Info 'Python not found - needed only if you supply your own client binary' }
}

# --------------------------------------------------------------------------
# 2. build
# --------------------------------------------------------------------------
if (Should-Run 'build') {
    Write-Step 'build' 'Building the server'

    $exe = Join-Path $Root 'build\install\rs3os\bin\rs3os.bat'
    if ((Test-Path $exe) -and -not $Force) {
        Write-Skip 'already built (pass -Force to rebuild)'
    } else {
        Write-Info 'this takes a few minutes the first time'
        & (Join-Path $Root 'gradlew.bat') installDist --console=plain -q
        if ($LASTEXITCODE -ne 0) { Fail "the build failed (exit code $LASTEXITCODE)" }
        Write-Ok 'built to build\install\rs3os\'
    }
}

# --------------------------------------------------------------------------
# 3. configuration
# --------------------------------------------------------------------------
if (Should-Run 'config') {
    Write-Step 'config' 'Writing data\config\server.toml'

    $cfg = Join-Path $Root 'data\config\server.toml'
    if ((Test-Path $cfg) -and -not $Force) {
        Write-Skip 'server.toml already exists (pass -Force to overwrite)'
    } else {
        $text = @"
# Where your client will look for this server. 127.0.0.1 means this machine.
hostname = "$Hostname"

# The NXT build this server speaks. The cache and the client must match it.
build = $Build

configUrl = "http://${Hostname}/jav_config.ws?binaryType=2"

[networking.ports]
game = 43594
http = 80
https = 443
"@
        Set-Content -Path $cfg -Value $text -Encoding UTF8
        Write-Ok "hostname = $Hostname, build = $Build"
    }

    $mods = Join-Path $Root 'data\config\mods.json'
    if (-not (Test-Path $mods)) {
        Copy-Item (Join-Path $Root 'data\config\mods.example.json') $mods
        Write-Ok 'mods.json created - edit it to give your account admin rights'
    }
}

# --------------------------------------------------------------------------
# 4. RSA keys
# --------------------------------------------------------------------------
if (Should-Run 'keys') {
    Write-Step 'keys' 'Generating your RSA key pair'

    $rsa = Join-Path $Root 'data\config\rsa.toml'
    if ((Test-Path $rsa) -and -not $Force) {
        Write-Skip 'rsa.toml already exists'
        Write-Info 'regenerating it would invalidate any client you have already patched'
    } else {
        Invoke-Rs3os @('run-tool', 'rsa-key-generator') 'the key generator failed'
        Write-Ok 'data\config\rsa.toml written'
        Write-Info 'this is a PRIVATE KEY. It is gitignored. Never publish it.'
    }
}

# --------------------------------------------------------------------------
# 5. the client
# --------------------------------------------------------------------------
if (Should-Run 'client') {
    Write-Step 'client' 'Staging the game client'

    $orig = Join-Path $Root "data\clients\$Build\$BinaryType\original"
    if ((Test-Path $orig) -and (Get-ChildItem $orig -Filter *.exe -ErrorAction SilentlyContinue) -and -not $Force) {
        Write-Skip 'a client is already staged'
    } elseif ($DownloadClient) {
        Invoke-Rs3os @('run-tool', 'client-downloader') 'the client downloader failed'
        Write-Ok 'client downloaded'
    } elseif ($Client) {
        if (-not (Test-Path $Client)) { Fail "no such file: $Client" }
        & python (Join-Path $Root 'tools\stage_client.py') $Client --build $Build --type $BinaryType
        if ($LASTEXITCODE -ne 0) { Fail 'staging the client failed' }
        Write-Ok "staged $(Split-Path $Client -Leaf)"
    } else {
        Fail @"
No client supplied.

RS3OS patches a client you already have; it does not ship one. Point it at your
own copy of the RuneScape NXT executable:

    .\setup.ps1 -Client 'C:\Program Files\Jagex\RuneScape\rs2client.exe'

or let the built-in downloader fetch one from Jagex:

    .\setup.ps1 -DownloadClient
"@
    }
}

# --------------------------------------------------------------------------
# 6. patch the client
# --------------------------------------------------------------------------
if (Should-Run 'patch') {
    Write-Step 'patch' 'Patching the client to trust your key'

    $patched = Join-Path $Root "data\clients\$Build\$BinaryType\compressed"
    if ((Test-Path $patched) -and -not $Force) {
        Write-Skip 'a patched client already exists (pass -Force to redo)'
    } else {
        Invoke-Rs3os @('run-tool', 'client-patcher') 'the client patcher failed'
        Write-Ok 'the client now trusts your RSA key and points at your server'
    }
}

# --------------------------------------------------------------------------
# 7. the cache
# --------------------------------------------------------------------------
if (Should-Run 'cache') {
    Write-Step 'cache' 'Downloading the game cache'

    $cacheDir = Join-Path $Root 'data\cache'
    $existing = if (Test-Path $cacheDir) { @(Get-ChildItem $cacheDir -File -ErrorAction SilentlyContinue) } else { @() }
    if ($existing.Count -gt 0 -and -not $Force) {
        Write-Skip "data\cache already holds $($existing.Count) file(s)"
        Write-Info 'run this step again at any time to top it up: .\setup.ps1 -Step cache'
    } else {
        Write-Info 'This is tens of gigabytes and will take a long time.'
        Write-Info 'It resumes where it left off, so interrupting it is safe.'
        Invoke-Rs3os @('run-tool', 'cache-downloader') 'the cache download failed'
        Write-Ok 'cache downloaded'
    }
}

# --------------------------------------------------------------------------
# 8. the definition database
# --------------------------------------------------------------------------
if (Should-Run 'database') {
    Write-Step 'database' 'Building data\rs3.sqlite from the cache'

    $db = Join-Path $Root 'data\rs3.sqlite'
    if ((Test-Path $db) -and -not $Force) {
        Write-Skip 'rs3.sqlite already exists (pass -Force to rebuild)'
    } else {
        $args = @('run-tool', 'db-builder', '--output', 'data/rs3.sqlite')
        if ($Force) { $args += '--force' }
        Invoke-Rs3os $args 'building the database failed'
        Write-Ok 'rs3.sqlite built'
    }
}

# --------------------------------------------------------------------------
# 9. the world: collision, object placements, NPC spawns
# --------------------------------------------------------------------------
if (Should-Run 'world') {
    Write-Step 'world' 'Decoding the world map from the cache'

    $db = Join-Path $Root 'data\rs3.sqlite'
    if (-not (Test-Path $db)) {
        Fail 'data\rs3.sqlite does not exist yet. Run: .\setup.ps1 -Step database'
    }

    # map-builder refuses a populated set unless forced, so let it make that
    # call rather than second-guessing it from a row count here.
    $mapArgs = @('run-tool', 'map-builder', '--database', 'data/rs3.sqlite')
    if ($Force) { $mapArgs += '--force' }
    Write-Info 'terrain, collision, object placements and NPC spawns - a few minutes'
    if ($script:CachePath) { $env:RS3OS_OPTS = '"-Dopennxt.cache=' + $script:CachePath + '"' }
    & (Join-Path $Root 'build\install\rs3os\bin\rs3os.bat') @mapArgs
    if ($LASTEXITCODE -ne 0) {
        if ($Force) { Fail 'building the world map failed' }
        Write-Skip 'the map tables already hold data (pass -Force to rebuild them)'
    } else {
        Write-Ok 'collision, placements and spawns written'
    }
}

# --------------------------------------------------------------------------
# 10. NPC combat seed, from your own cache
# --------------------------------------------------------------------------
if (Should-Run 'seed') {
    Write-Step 'seed' 'Extracting NPC combat data from your database'

    $seed = Join-Path $Root 'data\seed\npc_cache.json'
    if ((Test-Path $seed) -and -not $Force) {
        Write-Skip 'npc_cache.json already exists (pass -Force to rebuild)'
    } else {
        if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
            Fail 'Python 3 is needed for this step but was not found on your PATH.'
        }
        & python (Join-Path $Root 'tools\seed\seed_from_cache.py')
        if ($LASTEXITCODE -ne 0) { Fail 'extracting the NPC seed failed' }
        Write-Ok 'data\seed\npc_cache.json written from your own cache'
    }
}

# --------------------------------------------------------------------------
# done
# --------------------------------------------------------------------------
if (-not $Step) {
    Write-Host ''
    Write-Host 'Setup complete.' -ForegroundColor Green
    Write-Host ''
    Write-Host '  Start the server:' -ForegroundColor White
    Write-Host '      .\run.ps1'
    Write-Host ''
    Write-Host '  Then launch the patched client:' -ForegroundColor White
    Write-Host "      data\clients\$Build\$BinaryType\patched\rs2client.exe"
    Write-Host ''
    Write-Host '  Give yourself admin rights by putting your account name in' -ForegroundColor White
    Write-Host '      data\config\mods.json'
    Write-Host ''
}
