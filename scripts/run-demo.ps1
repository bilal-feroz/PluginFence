<#
.SYNOPSIS
  Starts a sandbox IntelliJ IDEA with the PluginFence agent attached, the PluginFence plugin and
  Demo Helper 1.0.0 (benign). Use -Update to start with Demo Helper 1.1.0 (suspicious) instead.

.EXAMPLE
  .\scripts\run-demo.ps1              # baseline run (Demo Helper 1.0.0)
  .\scripts\run-demo.ps1 -Update      # update run   (Demo Helper 1.1.0, same sandbox => drift)
  .\scripts\run-demo.ps1 -Update -GradleArgs "-PdemoAutorun=attack","-PdemoExitAfter=20000"
#>
param(
    [switch]$Update,
    [string[]]$GradleArgs = @()
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$task = if ($Update) { "runFenceIdeUpdated" } else { "runFenceIde" }
& .\gradlew.bat $task @GradleArgs
exit $LASTEXITCODE
