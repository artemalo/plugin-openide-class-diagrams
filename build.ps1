param([string]$IdeHome = 'C:\Program Files\OpenIDE\OpenIDE')
$ErrorActionPreference = 'Stop'
$taskProject = $PSScriptRoot
$taskBuild = Join-Path $taskProject 'build'
$taskClasses = Join-Path $taskBuild 'classes'
$taskDistribution = Join-Path $taskProject 'dist'
$taskJava = Join-Path $IdeHome 'jbr\bin\java.exe'
$taskJavac = Join-Path $IdeHome 'jbr\bin\javac.exe'
if (-not (Test-Path -LiteralPath $taskJavac)) { throw 'Не найден компилятор в указанной папке OpenIDE.' }
New-Item -ItemType Directory -Force -Path $taskClasses,$taskDistribution | Out-Null
$taskJarRoots = @((Join-Path $IdeHome 'lib'), (Join-Path $IdeHome 'plugins\java\lib'), (Join-Path $IdeHome 'plugins\java\lib\modules'))
$taskClasspath = (($taskJarRoots | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter '*.jar' -File } | Select-Object -ExpandProperty FullName) -join ';')
$taskSources = Get-ChildItem -LiteralPath (Join-Path $taskProject 'src\main\java') -Filter '*.java' -Recurse
$taskArgs = @('-encoding','UTF-8','--release','21','-classpath',('"' + $taskClasspath.Replace('\','/') + '"'),'-d',('"' + $taskClasses.Replace('\','/') + '"'))
$taskArgs += $taskSources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
$taskArgFile = Join-Path $taskBuild 'compiler.args'
[IO.File]::WriteAllLines($taskArgFile, $taskArgs, [Text.UTF8Encoding]::new($false))
& $taskJavac ('@' + $taskArgFile)
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item -Path (Join-Path $taskProject 'src\main\resources\*') -Destination $taskClasses -Recurse -Force
$taskPluginRoot = Join-Path $taskBuild 'package\class-diagrams'
$taskLib = Join-Path $taskPluginRoot 'lib'
New-Item -ItemType Directory -Force -Path $taskLib | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskJar = Join-Path $taskLib 'class-diagrams.jar'
if (Test-Path -LiteralPath $taskJar) { Remove-Item -LiteralPath $taskJar }
[IO.Compression.ZipFile]::CreateFromDirectory($taskClasses, $taskJar)
$taskVersion = ([xml](Get-Content -LiteralPath (Join-Path $taskProject 'src/main/resources/META-INF/plugin.xml') -Raw)).'idea-plugin'.version
$taskZip = Join-Path $taskDistribution ('class-diagrams-' + $taskVersion + '.zip')
if (Test-Path -LiteralPath $taskZip) { Remove-Item -LiteralPath $taskZip }
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $taskBuild 'package'), $taskZip)
Write-Output ('Plugin built: ' + $taskZip)
