<#
.SYNOPSIS
    Starts the RS3OS server.

.DESCRIPTION
    Runs the server with the feature set this project has built and tested.
    Most gameplay lives behind an -Dopennxt.experiment.* switch so that a
    half-finished system can be turned off without touching code; the defaults
    below turn on everything that works.

.PARAMETER Cache
    Where the game cache lives. Defaults to data\cache inside the repository.
    Point this at an existing cache to avoid a second multi-gigabyte download.

.PARAMETER Flags
    Extra -D switches appended after the defaults. The JVM takes the last value
    for a repeated property, so this overrides anything set below.

.PARAMETER Memory
    JVM maximum heap. Default 3g.

.EXAMPLE
    .\run.ps1

.EXAMPLE
    .\run.ps1 -Cache 'D:\rs3cache' -Flags '-Dopennxt.experiment.npcs.aggro=off'
#>
[CmdletBinding()]
param(
    [string] $Cache,
    [string] $Flags = '',
    [string] $Memory = '3g'
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
Set-Location $Root

$exe = Join-Path $Root 'build\install\rs3os\bin\rs3os.bat'
if (-not (Test-Path $exe)) {
    Write-Host 'The server is not built yet. Run .\setup.ps1 first.' -ForegroundColor Yellow
    exit 1
}

if (-not $Cache) { $Cache = Join-Path $Root 'data\cache' }
if (-not (Test-Path $Cache)) {
    Write-Host "No cache at $Cache" -ForegroundColor Yellow
    Write-Host 'Download one with:  .\setup.ps1 -Step cache' -ForegroundColor Yellow
    exit 1
}

foreach ($f in 'rsa.toml', 'server.toml') {
    if (-not (Test-Path (Join-Path $Root "data\config\$f"))) {
        Write-Host "Missing data\config\$f - run .\setup.ps1 first." -ForegroundColor Yellow
        exit 1
    }
}

# Interfaces and dispatch.
#   world.interfaces=all      open every gameframe panel, not just the HUD
#   world.skipInterfaces=653  653 is the one panel that breaks the frame
#   loc.dispatch / npc.dispatch
#                             route clicks on scenery and NPCs into content
#                             handlers (doors, banks, fishing spots, "Talk to")
#
# Not set here on purpose: -Dopennxt.experiment.ui.armPanels. It arms drag,
# resize and close (mask bits 18/19/21/23) on several hundred components, so
# holding the left mouse button starts a panel drag and hides the frame. Its
# own default is off; add it back only when working on panel layout.
$interfaces = @(
    '-Dopennxt.world.interfaces=all'
    '-Dopennxt.world.skipInterfaces=653'
    '-Dopennxt.experiment.loc.dispatch=true'
    '-Dopennxt.experiment.npc.dispatch=true'
)

# Gameplay.
#   combat=true          hit splats, damage, death, drops, combat xp
#   sendStats=true       push skill levels and xp to the skills panel
#   doors.swing=turn     doors swing on their hinge rather than sliding
#   banks.ui=true        the bank window, deposits and withdrawals
#   npcs.aggro=4         aggressive NPCs; 4 is only the fallback scan radius,
#                        per-NPC aggression comes from the seed data
$gameplay = @(
    '-Dopennxt.experiment.combat=true'
    '-Dopennxt.experiment.sendStats=true'
    '-Dopennxt.experiment.doors.swing=turn'
    '-Dopennxt.experiment.banks.ui=true'
    '-Dopennxt.experiment.npcs.aggro=4'
    # The demo drop litters the spawn tile with four ground items.
    '-Dopennxt.experiment.groundItems.demo=false'
)

$jvm = @(
    "-Xmx$Memory"
    "`"-Dopennxt.cache=$Cache`""
    '-Dopennxt.diag=true'
    '-Dorg.slf4j.simpleLogger.logFile=System.out'
) + $interfaces + $gameplay

if ($Flags) { $jvm += ($Flags -split '\s+' | Where-Object { $_ }) }

# The generated start script reads JVM options from this variable.
$env:RS3OS_OPTS = $jvm -join ' '

Write-Host "Starting RS3OS  (cache: $Cache)" -ForegroundColor Cyan
& $exe run-server
exit $LASTEXITCODE
