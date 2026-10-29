[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$ClusterName = 'fitzza-prod',
    [string]$Region = 'ap-northeast-2',
    [string]$AwsProfile = 'fitzza',
    [string]$Namespace = 'fitzza-monitoring',
    [string]$ReleaseName = 'fitzza-monitoring',
    [string]$ChartVersion = '92.14.1',
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Checked {
    param(
        [Parameter(Mandatory)] [string]$Command,
        [Parameter(Mandatory)] [string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$valuesFile = Join-Path $repoRoot 'infra/k8s/observability/prometheus/values.yaml'
if (-not (Test-Path -LiteralPath $valuesFile -PathType Leaf)) {
    throw "Values file not found: $valuesFile"
}

foreach ($commandName in @('helm', 'kubectl', 'aws')) {
    if (-not (Get-Command $commandName -ErrorAction SilentlyContinue)) {
        throw "$commandName is required."
    }
}

Write-Host "Target cluster: $ClusterName ($Region)"
Write-Host "Namespace: $Namespace; release: $ReleaseName; chart: $ChartVersion"
Write-Host 'No AWS or Kubernetes resource is changed until -Apply is provided.'

Invoke-Checked aws @('eks', 'update-kubeconfig', '--name', $ClusterName, '--region', $Region, '--profile', $AwsProfile)
$context = (& kubectl config current-context).Trim()
if (-not $context) {
    throw 'kubectl current-context is empty.'
}
Write-Host "kubectl context: $context"

Invoke-Checked kubectl @('wait', '--for=condition=Ready', 'node', '--all', '--timeout=10m')
Invoke-Checked kubectl @('get', 'storageclass', 'fitzza-gp3-retain')
$storageClass = (& kubectl get storageclass fitzza-gp3-retain -o json | ConvertFrom-Json)
if ($storageClass.reclaimPolicy -ne 'Retain') {
    throw "fitzza-gp3-retain must use Retain, found $($storageClass.reclaimPolicy)."
}
if ($storageClass.volumeBindingMode -ne 'WaitForFirstConsumer') {
    throw "fitzza-gp3-retain must use WaitForFirstConsumer, found $($storageClass.volumeBindingMode)."
}

Invoke-Checked kubectl @('get', 'nodes', '-o', 'wide')
Invoke-Checked kubectl @('describe', 'nodes')
Invoke-Checked kubectl @('-n', 'fitzza', 'get', 'pods', '-o', 'wide')

try {
    Invoke-Checked kubectl @('get', 'namespace', $Namespace)
} catch {
    throw "Namespace '$Namespace' must exist before the server-side dry-run; create it during an approved window before running this preflight."
}

try {
    Invoke-Checked kubectl @('-n', $Namespace, 'get', 'secret', 'fitzza-grafana-admin')
} catch {
    throw 'Create fitzza-grafana-admin from the approved secret store before installation; this script never creates credentials.'
}

Invoke-Checked helm @('repo', 'add', 'prometheus-community', 'https://prometheus-community.github.io/helm-charts', '--force-update')
Invoke-Checked helm @('repo', 'update')

$renderRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("fitzza-monitoring-render-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $renderRoot | Out-Null
try {
    Invoke-Checked helm @(
        'template', $ReleaseName, 'prometheus-community/kube-prometheus-stack',
        '--version', $ChartVersion,
        '--namespace', $Namespace,
        '--create-namespace',
        '--values', $valuesFile,
        '--output-dir', $renderRoot
    )

    $manifestRoot = Join-Path $renderRoot 'kube-prometheus-stack'
    Invoke-Checked kubectl @('apply', '--dry-run=server', '--namespace', $Namespace, '--filename', $manifestRoot)
    Write-Host "Rendered and server-side validated: $manifestRoot"

    if (-not $Apply) {
        Write-Host 'Dry-run complete. Re-run with -Apply during the approved operating window to install.'
        return
    }

    if (-not $PSCmdlet.ShouldProcess("$Namespace/$ReleaseName", 'Install or upgrade kube-prometheus-stack')) {
        return
    }

    Invoke-Checked helm @(
        'upgrade', '--install', $ReleaseName, 'prometheus-community/kube-prometheus-stack',
        '--version', $ChartVersion,
        '--namespace', $Namespace,
        '--create-namespace',
        '--values', $valuesFile,
        '--wait', '--timeout', '10m'
    )
    Invoke-Checked kubectl @('-n', $Namespace, 'get', 'pods,svc,pvc')
    Invoke-Checked kubectl @('-n', $Namespace, 'get', 'svc', '-o', 'jsonpath={range .items[*]}{.metadata.name}{"="}{.spec.type}{"\n"}{end}')
} finally {
    if (Test-Path -LiteralPath $renderRoot) {
        Remove-Item -LiteralPath $renderRoot -Recurse -Force
    }
}
