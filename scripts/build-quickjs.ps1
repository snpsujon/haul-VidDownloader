# Cross-compiles quickjs-ng's qjs for the app's ABIs with the Android NDK (27) and the SDK's CMake (3.22.1),
# as app/src/main/jniLibs/<abi>/libqjs.so. Usage, from the repo root, with the quickjs-ng source unpacked:
#   .\scripts\build-quickjs.ps1 -Src C:\path\quickjs-0.17.0 -Out app\src\main\jniLibs
param([Parameter(Mandatory)][string]$Src, [Parameter(Mandatory)][string]$Out)
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$cmakeBin = "$sdk\cmake\3.22.1\bin"
$ndk = "$sdk\ndk\27.0.12077973"
$build = Join-Path (Split-Path $Src) "build"
foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64")) {
    $b = "$build\$abi"
    & "$cmakeBin\cmake.exe" -S $Src -B $b -G Ninja "-DCMAKE_MAKE_PROGRAM=$cmakeBin\ninja.exe" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-24 `
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DQJS_BUILD_EXAMPLES=OFF | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "configure failed for $abi" }
    & "$cmakeBin\cmake.exe" --build $b --target qjs_exe 2>&1 | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "build failed for $abi" }
    $dest = "$Out\$abi"
    New-Item -ItemType Directory -Force $dest | Out-Null
    & "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe" -o "$dest\libqjs.so" "$b\qjs"
    "{0}: {1:N0} bytes" -f $abi, (Get-Item "$dest\libqjs.so").Length
}
