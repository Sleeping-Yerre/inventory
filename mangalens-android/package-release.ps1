$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$apk=Join-Path $PSScriptRoot 'dist\MangaLens-Android.apk'
if(!(Test-Path -LiteralPath $apk)){throw 'Build the APK first'}
$badge=Get-Content -Raw -LiteralPath '.\dist\apk-manifest.txt'
$version=[regex]::Match($badge,"versionName='([^']+)'").Groups[1].Value
$code=[int][regex]::Match($badge,"versionCode='(\d+)'").Groups[1].Value
if(!$version -or $code -lt 1){throw 'APK version metadata missing'}
$stage=Join-Path $PSScriptRoot ('build\release-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$files=@()
foreach($folder in @('src','res','assets','tests')){$files+=Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $folder) -Recurse -File}
foreach($name in @('AndroidManifest.xml','.gitignore','build.ps1','run-tests.ps1','package-release.ps1','README.md','사용안내.md','검증기록.md')){$files+=Get-Item -LiteralPath (Join-Path $PSScriptRoot $name)}
foreach($file in $files){$relative=[IO.Path]::GetRelativePath($PSScriptRoot,$file.FullName);$target=Join-Path $stage ('mangalens-android\'+$relative);New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $target}
$workflow=Join-Path $stage '.github\workflows\mangalens-android-release.yml'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $workflow) | Out-Null
$workflowSource=Join-Path $PSScriptRoot '.github\workflows\android-release.yml'
if(!(Test-Path -LiteralPath $workflowSource)){$workflowSource=Join-Path (Split-Path -Parent $PSScriptRoot) '.github\workflows\mangalens-android-release.yml'}
Copy-Item -LiteralPath $workflowSource -Destination $workflow
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'downloads'),(Join-Path $stage 'updates') | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $stage 'downloads\MangaLens-Android.apk')
Copy-Item -LiteralPath '.\dist\MangaLens-Android.apk.sha256' -Destination (Join-Path $stage 'downloads\MangaLens-Android.apk.sha256')
$hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$info=[ordered]@{versionName=$version;versionCode=$code;sha256=$hash;apkUrl='https://raw.githubusercontent.com/Sleeping-Yerre/inventory/main/downloads/MangaLens-Android.apk'}
[IO.File]::WriteAllText((Join-Path $stage 'updates\android.json'),($info | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'build\release-stage.txt'),$stage,[Text.UTF8Encoding]::new($false))
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath (Join-Path $PSScriptRoot ('dist\MangaLens-Android-'+$version+'.zip')) -Force
Write-Output ('Release files: '+(Get-ChildItem -LiteralPath $stage -Recurse -File -Force).Count)
Write-Output ('Version: '+$version+' / '+$code)

