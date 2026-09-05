# This mutates only temporary firewall state on a disposable GitHub runner.
param([Parameter(Mandatory)][ValidateSet('bb', 'jolt', 'jvm')][string]$CorpusHost)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'Firewall isolation is restricted to disposable GitHub-hosted runners'
}
if (-not $IsWindows) { throw 'Windows runner required' }

# Launch the actual runtime directly. In particular, do not firewall a CLI
# launcher while leaving its JVM child able to connect.
switch ($CorpusHost) {
    'bb' {
        $runtime = (Get-Command bb.exe -CommandType Application).Source
        $prefix = @('--config', 'corpus-bb.edn', '-m')
    }
    'jolt' {
        $runtime = (Get-Command jolt.exe -CommandType Application).Source
        $prefix = @('-M:db-corpus-offline:db-corpus-command')
    }
    'jvm' {
        $runtime = (Resolve-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe')).Path
        $classpath = & clojure -Spath -M:db-corpus-offline
        if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the offline JVM classpath' }
        if ($classpath -isnot [string] -or [string]::IsNullOrWhiteSpace($classpath)) {
            throw 'Expected exactly one resolved classpath line'
        }
        $prefix = @('-cp', $classpath, 'clojure.main', '-m')
    }
}
$env:HEGEL_LIBHEGEL_LIBRARY = 'deliberately-missing-db-corpus-engine'
function Invoke-CorpusCommand([string[]]$CommandArgs) {
    & $runtime @prefix @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "Corpus subprocess failed: $LASTEXITCODE" }
}

Invoke-CorpusCommand @('jolt.aspect-packs.db.network-probe', 'open')
if ((Get-Service MpsSvc).Status -ne 'Running') { throw 'Windows Firewall service must be running' }
$profiles = @(Get-NetFirewallProfile | Select-Object Name, Enabled)
$ruleName = 'db-corpus-' + [guid]::NewGuid().ToString('N')
$rule = $null
try {
    foreach ($profile in $profiles) {
        Set-NetFirewallProfile -Name $profile.Name -Enabled True
    }
    $rule = New-NetFirewallRule -Name $ruleName -DisplayName $ruleName `
        -Direction Outbound -Action Block -Program $runtime -Profile Any -Enabled True
    Invoke-CorpusCommand @('jolt.aspect-packs.db.network-probe', 'blocked')
    Invoke-CorpusCommand @('jolt.aspect-packs.db.corpus-offline-runner')
} finally {
    # Never select broad rule collections for deletion. Restore profile state
    # even if removing this exact owned rule fails.
    try {
        if ($null -ne $rule) { Remove-NetFirewallRule -InputObject $rule }
    } finally {
        foreach ($profile in $profiles) {
            Set-NetFirewallProfile -Name $profile.Name -Enabled $profile.Enabled
        }
    }
}
Invoke-CorpusCommand @('jolt.aspect-packs.db.network-probe', 'open')
