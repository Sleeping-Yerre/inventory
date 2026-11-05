param([string]$Device='emulator-5554')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if($Device -notmatch '^emulator-\d+$'){throw 'These tests run only on a disposable emulator.'}
$java=(Get-ChildItem -LiteralPath '.\.tools\java' -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME=$java
$sdk=Join-Path $PSScriptRoot '.tools\sdk'
$bt=Join-Path $sdk 'build-tools\37.0.0'
$platform=Join-Path $sdk 'platforms\android-37.0\android.jar'
$test=Join-Path $PSScriptRoot 'build\qa'
foreach($path in @($test,(Join-Path $test 'classes'),(Join-Path $test 'dex'))){New-Item -ItemType Directory -Force -Path $path | Out-Null}
& (Join-Path $bt 'aapt2.exe') link -I $platform --manifest '.\tests\AndroidManifest.xml' -o (Join-Path $test 'base.apk')
if($LASTEXITCODE){throw 'Test resources failed'}
& (Join-Path $java 'bin\javac.exe') -encoding UTF-8 --release 17 -classpath ($platform+';'+(Join-Path $PSScriptRoot 'build\classes.jar')) -d (Join-Path $test 'classes') '.\tests\SelfTestRunner.java'
if($LASTEXITCODE){throw 'Test compile failed'}
& (Join-Path $java 'bin\jar.exe') '-J-Djava.io.tmpdir=./build' --create --file (Join-Path $test 'classes.jar') -C (Join-Path $test 'classes') '.'
& (Join-Path $bt 'd8.bat') --release --min-api 26 --lib $platform --classpath (Join-Path $PSScriptRoot 'build\classes.jar') --output (Join-Path $test 'dex') (Join-Path $test 'classes.jar')
if($LASTEXITCODE){throw 'Test dex failed'}
& (Join-Path $java 'bin\jar.exe') '-J-Djava.io.tmpdir=./build' --update --file (Join-Path $test 'base.apk') -C (Join-Path $test 'dex') classes.dex
& (Join-Path $bt 'zipalign.exe') -f -p 4 (Join-Path $test 'base.apk') (Join-Path $test 'aligned.apk')
$securePassword=(Get-Content -Raw -LiteralPath '.\.signing\password.dpapi').Trim() | ConvertTo-SecureString
$env:MANGALENS_SIGNING_PASSWORD=[Net.NetworkCredential]::new('', $securePassword).Password
try{& (Join-Path $bt 'apksigner.bat') sign --ks '.\.signing\mangalens-release.jks' --ks-key-alias mangalens --ks-pass env:MANGALENS_SIGNING_PASSWORD --out (Join-Path $test 'tests.apk') (Join-Path $test 'aligned.apk')}finally{Remove-Item Env:MANGALENS_SIGNING_PASSWORD -ErrorAction SilentlyContinue}
if($LASTEXITCODE){throw 'Test signing failed'}
$adb=Join-Path $sdk 'platform-tools\adb.exe'
& $adb -s $Device install -r '.\dist\MangaLens-Android.apk'
if($LASTEXITCODE){throw 'App install failed'}
& $adb -s $Device install -r (Join-Path $test 'tests.apk')
if($LASTEXITCODE){throw 'Test install failed'}
$result=& $adb -s $Device shell am instrument -w 'app.mangalens.android.qa/app.mangalens.android.SelfTestRunner'
$result | Tee-Object -FilePath (Join-Path $test 'results.txt')
if(($result -join "`n") -notmatch 'SUCCESS \d+ checks'){throw 'Android self checks failed'}
