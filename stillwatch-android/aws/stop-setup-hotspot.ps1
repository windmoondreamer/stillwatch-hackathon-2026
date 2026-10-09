$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
$ownerFile=Join-Path (Split-Path $PSScriptRoot -Parent) '.private\setup-wifi.json'
if(-not (Test-Path -LiteralPath $ownerFile)){throw 'No StillWatch setup-session record'}
$owner=Get-Content -LiteralPath $ownerFile -Raw|ConvertFrom-Json
if($owner.ssid -ne 'StillWatch-Setup'){throw 'Not the setup session created by this task'}
$profile=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$manager=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
if($manager.TetheringOperationalState -eq 'On'){
 $op=$manager.StopTetheringAsync()
 $method=[System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'}|Select-Object -First 1
 $task=$method.MakeGenericMethod([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult]).Invoke($null,@($op));if(-not $task.Wait(30000)){throw 'Stop timeout'}
 Write-Output ('StillWatch setup hotspot: '+$task.Result.Status)
}
