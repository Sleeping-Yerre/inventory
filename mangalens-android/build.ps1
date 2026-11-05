param([string]$SdkRoot='', [string]$JavaRoot='', [int]$VersionCode=1, [string]$VersionName='0.1.0')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if($VersionCode -lt 1 -or $VersionName -notmatch '^\d{1,5}\.\d{1,5}\.\d{1,5}$'){throw 'Invalid release version'}
if(!$JavaRoot){$JavaRoot=(Get-ChildItem -LiteralPath '.\.tools\java' -Directory | Select-Object -First 1).FullName}
if(!$SdkRoot){$SdkRoot=Join-Path $PSScriptRoot '.tools\sdk'}
$env:JAVA_HOME=$JavaRoot
$platform=Join-Path $SdkRoot 'platforms\android-37.0\android.jar'
$buildTools=Join-Path $SdkRoot 'build-tools\37.0.0'
if(!(Test-Path -LiteralPath $platform)){throw 'Android 17 platform missing. See README.md.'}
$build=Join-Path $PSScriptRoot 'build'
$dist=Join-Path $PSScriptRoot 'dist'
$signing=Join-Path $PSScriptRoot '.signing'
foreach($folder in @($build,$dist,$signing,(Join-Path $build 'classes'),(Join-Path $build 'generated'),(Join-Path $build 'dex'))){New-Item -ItemType Directory -Force -Path $folder | Out-Null}
$generatedPackage=Join-Path $build 'generated\app\mangalens\android'
New-Item -ItemType Directory -Force -Path $generatedPackage | Out-Null
[IO.File]::WriteAllText((Join-Path $generatedPackage 'BuildInfo.java'),('package app.mangalens.android; final class BuildInfo { static final String VERSION_NAME="'+$VersionName+'"; }'),[Text.UTF8Encoding]::new($false))
& (Join-Path $buildTools 'aapt2.exe') compile --dir '.\res' -o (Join-Path $build 'resources.zip')
if($LASTEXITCODE){throw 'Resource compile failed'}
& (Join-Path $buildTools 'aapt2.exe') link '-I' $platform '--manifest' '.\AndroidManifest.xml' '--version-code' $VersionCode '--version-name' $VersionName '--java' (Join-Path $build 'generated') '-A' '.\assets' '-o' (Join-Path $build 'resources.apk') (Join-Path $build 'resources.zip')
if($LASTEXITCODE){throw 'Resource link failed'}
$javaFiles=@(Get-ChildItem -LiteralPath '.\src',(Join-Path $build 'generated') -Recurse -Filter '*.java' | ForEach-Object {'"'+(Resolve-Path -LiteralPath $_.FullName -Relative).Replace('\','/')+'"'})
[IO.File]::WriteAllLines((Join-Path $build 'sources.args'),$javaFiles,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaRoot 'bin\javac.exe') '-encoding' 'UTF-8' '--release' '17' '-classpath' $platform '-d' (Join-Path $build 'classes') ('@'+(Join-Path $build 'sources.args'))
if($LASTEXITCODE){throw 'Java compile failed'}
& (Join-Path $JavaRoot 'bin\jar.exe') '-J-Djava.io.tmpdir=./build' '--create' '--file' (Join-Path $build 'classes.jar') '-C' (Join-Path $build 'classes') '.'
if($LASTEXITCODE){throw 'Java package failed'}
& (Join-Path $buildTools 'd8.bat') '--release' '--min-api' '26' '--lib' $platform '--output' (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
if($LASTEXITCODE){throw 'Dex compile failed'}
Copy-Item -LiteralPath (Join-Path $build 'resources.apk') -Destination (Join-Path $build 'unsigned.apk') -Force
& (Join-Path $JavaRoot 'bin\jar.exe') '-J-Djava.io.tmpdir=./build' '--update' '--file' (Join-Path $build 'unsigned.apk') '-C' (Join-Path $build 'dex') 'classes.dex'
if($LASTEXITCODE){throw 'APK assembly failed'}
& (Join-Path $buildTools 'zipalign.exe') '-f' '-p' '4' (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if($LASTEXITCODE){throw 'APK alignment failed'}
$passwordPath=Join-Path $signing 'password.dpapi'
if(!(Test-Path -LiteralPath $passwordPath)){$randomPassword=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N');ConvertTo-SecureString $randomPassword -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordPath -Encoding utf8}
$securePassword=(Get-Content -Raw -LiteralPath $passwordPath).Trim() | ConvertTo-SecureString
$env:MANGALENS_SIGNING_PASSWORD=[Net.NetworkCredential]::new('', $securePassword).Password
try{
    $keyFile=Join-Path $signing 'mangalens-release.jks'
    if(!(Test-Path -LiteralPath $keyFile)){
        & (Join-Path $JavaRoot 'bin\keytool.exe') '-genkeypair' '-noprompt' '-keystore' $keyFile '-storepass:env' 'MANGALENS_SIGNING_PASSWORD' '-keypass:env' 'MANGALENS_SIGNING_PASSWORD' '-alias' 'mangalens' '-keyalg' 'RSA' '-keysize' '3072' '-validity' '10000' '-dname' 'CN=MangaLens Android, OU=Personal App, O=MangaLens' '-storetype' 'PKCS12'
        if($LASTEXITCODE){throw 'Signing key generation failed'}
    }
    $apk=Join-Path $dist 'MangaLens-Android.apk'
    & (Join-Path $buildTools 'apksigner.bat') 'sign' '--ks' $keyFile '--ks-key-alias' 'mangalens' '--ks-pass' 'env:MANGALENS_SIGNING_PASSWORD' '--key-pass' 'env:MANGALENS_SIGNING_PASSWORD' '--out' $apk (Join-Path $build 'aligned.apk')
    if($LASTEXITCODE){throw 'APK signing failed'}
    & (Join-Path $buildTools 'apksigner.bat') 'verify' '--verbose' '--print-certs' $apk | Tee-Object -FilePath (Join-Path $dist 'apk-verification.txt')
    if($LASTEXITCODE){throw 'APK signature validation failed'}
    $hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $dist 'MangaLens-Android.apk.sha256'),$hash+'  MangaLens-Android.apk'+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
    & (Join-Path $buildTools 'aapt2.exe') 'dump' 'badging' $apk | Out-File -LiteralPath (Join-Path $dist 'apk-manifest.txt') -Encoding utf8
    Write-Output $apk
}finally{Remove-Item Env:MANGALENS_SIGNING_PASSWORD -ErrorAction SilentlyContinue}
