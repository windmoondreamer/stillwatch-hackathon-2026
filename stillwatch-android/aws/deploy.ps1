# Current pilot deployment. AWS profile/Region/stack are defined in bootstrap.py.
$ErrorActionPreference='Stop'
$nodeCommand=Get-Command node -ErrorAction SilentlyContinue
$pythonCommand=Get-Command python -ErrorAction SilentlyContinue
$node=if($nodeCommand){$nodeCommand.Source}else{Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'}
$python=if($pythonCommand){$pythonCommand.Source}else{Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'}
if(!(Test-Path -LiteralPath $node) -or !(Test-Path -LiteralPath $python)){throw 'Node.js and Python are required. See PILOT.md.'}
& $node (Join-Path $PSScriptRoot 'build-template.mjs')
if($LASTEXITCODE -ne 0){throw 'Template generation failed.'}
& $python (Join-Path $PSScriptRoot 'bootstrap.py')
if($LASTEXITCODE -ne 0){throw 'Pilot deployment failed.'}
Write-Output 'Pilot deployment completed. Rebuild the APK with the generated public aws-config.properties.'
