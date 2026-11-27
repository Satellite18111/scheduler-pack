$ErrorActionPreference = "Stop"
$root = "E:\项目\bilibili\scheduler-web"
$jdk  = "C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\bin"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
$env:PATH = "$jdk;$env:PATH"
$bt   = "E:\bili-build\android-sdk\build-tools\37.0.0"
$jar  = "E:\bili-build\android-sdk\platforms\android-37\android.jar"

Remove-Item -Recurse -Force "$root\gen","$root\classes","$root\out" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$root\gen","$root\classes","$root\out" | Out-Null

# 1. keystore（沿用原 debug 签名，覆盖升级可保留 root 授权与偏好）
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jdk\keytool.exe" -genkeypair -v -keystore $ks -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android `
        -dname "CN=Android Debug,O=Android,C=US"
}

# 2. 资源与 assets 暂存到 ASCII 临时目录（aapt2 --dir 不枚举中文路径）
$stageRes = Join-Path $env:TEMP "spres"
$stageAst = Join-Path $env:TEMP "spassets"
Remove-Item -Recurse -Force $stageRes,$stageAst -ErrorAction SilentlyContinue
Copy-Item -Recurse "$root\res" $stageRes
Copy-Item -Recurse "$root\assets" $stageAst
& "$bt\aapt2.exe" compile --dir $stageRes -o "$root\out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 3. aapt2 link（assets 不走 -A：Windows 下 aapt2 会把分隔符写成反斜杠，改由脚本按正斜杠写入）
& "$bt\aapt2.exe" link -I $jar --manifest "$root\AndroidManifest.xml" `
    -R "$root\out\res.zip" --java "$root\gen" -o "$root\out\base.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 4. javac
$src = Get-ChildItem -Recurse "$root\src" -Filter *.java | ForEach-Object FullName
$rjava = Get-ChildItem -Recurse "$root\gen" -Filter *.java | ForEach-Object FullName
& "$jdk\javac.exe" --release 17 -cp $jar -d "$root\classes" ($src + $rjava)
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 5. d8
$cls = Get-ChildItem -Recurse "$root\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $jar --output "$root\out" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 6. 写入 assets（正斜杠路径）与 classes.dex（不压缩）
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$root\out\base.apk", 'Update')
$assetBase = "$root\assets"
Get-ChildItem -Recurse -File $assetBase | ForEach-Object {
    $rel = $_.FullName.Substring($assetBase.Length + 1).Replace('\','/')
    $entry = $zip.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::Optimal)
    $w = $entry.Open()
    [System.IO.File]::OpenRead($_.FullName).CopyTo($w)
    $w.Dispose()
    Write-Host "asset: assets/$rel"
}
$entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::NoCompression)
$dst = $entry.Open()
[System.IO.File]::OpenRead("$root\out\classes.dex").CopyTo($dst)
$dst.Dispose(); $zip.Dispose()

# 7. zipalign + sign
& "$bt\zipalign.exe" -f 4 "$root\out\base.apk" "$root\out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$root\调度包-2.1.0.apk" "$root\out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Get-ChildItem "$root\调度包-2.1.0.apk" | Select-Object Name,Length

