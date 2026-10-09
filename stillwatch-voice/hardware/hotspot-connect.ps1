$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringSessionAccessPointConfiguration,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.TetheringWiFiBand,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.TetheringWiFiAuthenticationKind,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
function Wait-WinRT($operation, $resultType) {
    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    $task = $method.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait(30000)) { throw 'Hotspot operation timed out' }
    return $task.Result
}
$profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$manager = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
if ($manager.TetheringOperationalState -ne 'Off') { throw 'An existing hotspot is running; leave it unchanged.' }
$session = New-Object Windows.Networking.NetworkOperators.NetworkOperatorTetheringSessionAccessPointConfiguration
$session.Ssid = 'StillWatch-ESP'
$randomBytes = New-Object byte[] 24
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($randomBytes)
$rng.Dispose()
$session.Passphrase = [Convert]::ToBase64String($randomBytes)
$session.Band = [Windows.Networking.NetworkOperators.TetheringWiFiBand]::TwoPointFourGigahertz
$session.AuthenticationKind = [Windows.Networking.NetworkOperators.TetheringWiFiAuthenticationKind]::Wpa2
$bandSupported = Wait-WinRT $session.IsBandSupportedAsync($session.Band) ([bool])
if (-not $bandSupported) { throw '2.4 GHz hotspot is unavailable' }
$result = Wait-WinRT $manager.StartTetheringAsync($session) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
Write-Output ('Hotspot start: ' + $result.Status)
if ($result.Status -ne 'Success') { throw 'Could not start the temporary hotspot' }
# The per-session secret stays in memory and is sent through stdin, never saved.
$provision = @{ command = 'provision'; ssid = $session.Ssid; password = $session.Passphrase } | ConvertTo-Json -Compress
$python = 'C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
try {
    $response = ($provision | & $python (Join-Path $PSScriptRoot 'improv.py')) | ConvertFrom-Json
    $response | ConvertTo-Json -Depth 5
    if (-not $response.ok) { throw 'ESP32 provisioning failed' }
    $address = $null
    foreach ($value in $response.urls) {
        $url = [Uri]$value
        if ($url.Query -match '(?:\?|&)target=(\d+(?:\.\d+){3})(?:&|$)') { $address = $Matches[1] }
    }
    if ($address) {
        foreach ($resource in @('capabilities', 'health')) {
            try {
                $probe = Invoke-WebRequest -UseBasicParsing -Uri ("http://${address}:62587/espectre/v1/$resource") -Headers @{ Origin = 'https://test.espectre.dev' } -TimeoutSec 4
                Write-Output ("Direct $resource status: " + $probe.StatusCode)
                Write-Output $probe.Content
            } catch {
                if ($_.Exception.Response) {
                    Write-Output ("Direct $resource status: " + [int]$_.Exception.Response.StatusCode)
                    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                    Write-Output $reader.ReadToEnd()
                    $reader.Dispose()
                } else { Write-Output ("Direct $resource unavailable") }
            }
        }
        $connectBody = @{ ip = $address } | ConvertTo-Json -Compress
        $connected = $false
        for ($attempt = 0; $attempt -lt 4; $attempt++) {
            Start-Sleep -Seconds 3
            try {
                Invoke-RestMethod 'http://127.0.0.1:3210/api/device/connect' -Method Post -ContentType 'application/json' -Body $connectBody -TimeoutSec 10 | ConvertTo-Json -Depth 5
                $connected = $true
                break
            } catch {
                if ($_.Exception.Response) {
                    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                    Write-Output ('Sensor API: ' + $reader.ReadToEnd())
                    $reader.Dispose()
                }
            }
        }
        if (-not $connected) { throw 'Sensor API did not become available' }
    }
} catch {
    # A failed trial must not leave a new hotspot active.
    $null = Wait-WinRT $manager.StopTetheringAsync() ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
    throw
} finally { $provision = $null; $session = $null }
