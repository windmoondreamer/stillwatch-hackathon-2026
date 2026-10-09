$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringSessionAccessPointConfiguration,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.TetheringWiFiBand,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.TetheringWiFiAuthenticationKind,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
function Wait-WinRT($operation,$type){
 $method=[System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'}|Select-Object -First 1
 $task=$method.MakeGenericMethod($type).Invoke($null,@($operation));if(-not $task.Wait(30000)){throw 'Hotspot timeout'};return $task.Result
}
$profile=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$manager=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
if($manager.TetheringOperationalState -ne 'Off'){throw 'An existing hotspot is active; preserving it.'}
$config=New-Object Windows.Networking.NetworkOperators.NetworkOperatorTetheringSessionAccessPointConfiguration
$config.Ssid='StillWatch-Setup'
$bytes=New-Object byte[] 24;$rng=[System.Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose()
$config.Passphrase=[Convert]::ToBase64String($bytes)
$config.Band=[Windows.Networking.NetworkOperators.TetheringWiFiBand]::TwoPointFourGigahertz
$config.AuthenticationKind=[Windows.Networking.NetworkOperators.TetheringWiFiAuthenticationKind]::Wpa2
$result=Wait-WinRT $manager.StartTetheringAsync($config) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
if($result.Status -ne 'Success'){throw "Hotspot start failed: $($result.Status)"}
$private=Join-Path (Split-Path $PSScriptRoot -Parent) '.private'
@{ssid=$config.Ssid;password=$config.Passphrase}|ConvertTo-Json -Compress|Set-Content -LiteralPath (Join-Path $private 'setup-wifi.json') -Encoding UTF8
$params=@{command='provision';port='COM4';ssid=$config.Ssid;password=$config.Passphrase}|ConvertTo-Json -Compress
$python='C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$improv=Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'stillwatch-voice\hardware\improv.py'
$response=($params|& $python $improv)|ConvertFrom-Json
if(-not $response.ok){throw 'Hardware Wi-Fi setup failed'}
$address=$null;foreach($url in $response.urls){if(([Uri]$url).Query -match 'target=(\d+(?:\.\d+){3})'){$address=$Matches[1]}}
if(-not $address){throw 'Missing ESP32 address'}
@{ip=$address;port='COM4';ssid=$config.Ssid}|ConvertTo-Json -Compress|Set-Content -LiteralPath (Join-Path $private 'hardware.json') -Encoding UTF8
Write-Output "ESP32 temporary setup address: $address"
