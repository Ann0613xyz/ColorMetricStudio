param([ValidateSet('all','image','installer')][string]$Stage='all')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskRoot=(Resolve-Path '.').Path
$taskVersion='0.1.0'
$taskDisplayName='ColorMetric Studio'
$taskBuild=Join-Path $taskRoot "build-$taskVersion"
$taskRelease=[IO.Path]::GetFullPath((Join-Path $taskRoot "../../outputs/ColorMetricStudio-$taskVersion"))
$taskJdk=(Get-Command javac).Source | Split-Path | Split-Path
if ($Stage -ne 'installer') {
    New-Item -ItemType Directory -Force -Path "$taskBuild/classes","$taskBuild/input","$taskBuild/app-image","$taskBuild/temp",$taskRelease | Out-Null
    $taskSources=Get-ChildItem -LiteralPath "$taskRoot/src" -Filter '*.java' | ForEach-Object { $_.FullName }
    & "$taskJdk/bin/javac.exe" -encoding UTF-8 -cp "$taskRoot/lib/ij.jar" -d "$taskBuild/classes" $taskSources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    & "$taskJdk/bin/java.exe" -cp "$taskBuild/classes;$taskRoot/lib/ij.jar" peri.App --self-test "$taskBuild/test-output"
    if ($LASTEXITCODE -ne 0) { throw 'Measurement tests failed' }
    [IO.File]::WriteAllText("$taskBuild/manifest.mf","Manifest-Version: 1.0`r`nMain-Class: peri.App`r`nClass-Path: ij.jar`r`nImplementation-Title: ColorMetric Studio`r`nImplementation-Version: $taskVersion`r`n`r`n",[Text.UTF8Encoding]::new($false))
    & "$taskJdk/bin/jar.exe" "-J-Djava.io.tmpdir=$taskBuild/temp" --create --file "$taskBuild/input/ColorMetricStudio.jar" --manifest "$taskBuild/manifest.mf" -C "$taskBuild/classes" .
    if ($LASTEXITCODE -ne 0) { throw 'JAR build failed' }
    Copy-Item -LiteralPath "$taskRoot/lib/ij.jar","$taskRoot/lib/ImageJ-LICENSE.txt","$taskRoot/LICENSE.txt","$taskRoot/THIRD-PARTY.txt","$taskRoot/UserGuide.html" -Destination "$taskBuild/input" -Force
    Copy-Item -LiteralPath "$taskRoot/tools/wix/LICENSE.TXT" -Destination "$taskBuild/input/WiX-LICENSE.txt" -Force
    Copy-Item -LiteralPath "$taskBuild/test-output/demo.pips" -Destination "$taskBuild/input/demo.pips" -Force
    $taskImage=Join-Path "$taskBuild/app-image" $taskDisplayName
    if (Test-Path -LiteralPath $taskImage) { throw "Existing app-image found. Use a fresh build directory or archive the prior app-image: $taskImage" }
    & "$taskJdk/bin/jpackage.exe" '-J-Duser.language=en' '-J-Duser.country=US' --type app-image --name $taskDisplayName --app-version $taskVersion --vendor 'ColorMetric Studio Project' --description 'Offline multimode skin-image color and affected-area analysis with embedded ImageJ' --input "$taskBuild/input" --main-jar ColorMetricStudio.jar --main-class peri.App --dest "$taskBuild/app-image" --icon "$taskRoot/app.ico" --add-modules java.desktop,java.xml,java.logging --java-options '-Xmx2048m' --jlink-options '--strip-debug --no-man-pages --no-header-files'
    if ($LASTEXITCODE -ne 0) { throw 'App-image build failed' }
}
if ($Stage -ne 'image') {
    $env:Path="$taskRoot/tools/wix;$env:Path"
    New-Item -ItemType Directory -Force -Path $taskRelease | Out-Null
    & "$taskJdk/bin/jpackage.exe" '-J-Duser.language=en' '-J-Duser.country=US' --type exe --name $taskDisplayName --app-version $taskVersion --vendor 'ColorMetric Studio Project' --description 'Offline multimode skin-image color and affected-area analysis with embedded ImageJ' --app-image "$taskBuild/app-image/$taskDisplayName" --dest $taskRelease --win-per-user-install --win-dir-chooser --win-shortcut --win-menu --win-menu-group 'ColorMetric Studio' --win-upgrade-uuid 'f0c076b3-4ba3-4c74-9e87-d8791f2e6412' --license-file "$taskRoot/LICENSE.txt"
    if ($LASTEXITCODE -ne 0) { throw 'EXE installer build failed' }
    Move-Item -LiteralPath "$taskRelease/$taskDisplayName-$taskVersion.exe" -Destination "$taskRelease/ColorMetricStudio-$taskVersion.exe"
    & "$taskJdk/bin/jpackage.exe" '-J-Duser.language=en' '-J-Duser.country=US' --type msi --name $taskDisplayName --app-version $taskVersion --vendor 'ColorMetric Studio Project' --description 'Offline multimode skin-image color and affected-area analysis with embedded ImageJ' --app-image "$taskBuild/app-image/$taskDisplayName" --dest $taskRelease --win-per-user-install --win-dir-chooser --win-shortcut --win-menu --win-menu-group 'ColorMetric Studio' --win-upgrade-uuid 'f0c076b3-4ba3-4c74-9e87-d8791f2e6412' --license-file "$taskRoot/LICENSE.txt"
    if ($LASTEXITCODE -ne 0) { throw 'MSI installer build failed' }
    Move-Item -LiteralPath "$taskRelease/$taskDisplayName-$taskVersion.msi" -Destination "$taskRelease/ColorMetricStudio-$taskVersion.msi"
}
Write-Output "Build complete: $taskRelease"
