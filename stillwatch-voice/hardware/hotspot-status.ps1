Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
$networkProfile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
Write-Output ('Network profile: ' + $networkProfile.ProfileName)
Write-Output ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::GetTetheringCapabilityFromConnectionProfile($networkProfile))
$tetherManager = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($networkProfile)
Write-Output ('Hotspot state: ' + $tetherManager.TetheringOperationalState)
