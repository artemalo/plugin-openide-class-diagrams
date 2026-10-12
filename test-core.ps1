param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JdkHome)) { throw 'Укажите -JdkHome или JAVA_HOME (JDK 21 или новее).' }
$taskJavaSuffix = if (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe')) { '.exe' } else { '' }
$taskClasses = Join-Path $taskRoot 'build/core-test-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSources = @()
foreach ($taskRelative in @('src/main/java/ru/student/diagram/model','src/main/java/ru/student/diagram/ui','src/test/java')) {
    $taskSources += Get-ChildItem -LiteralPath (Join-Path $taskRoot $taskRelative) -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName
}
$taskArgs = @('-encoding','UTF-8','--release','21','-d',('"' + $taskClasses.Replace('\','/') + '"'))
$taskArgs += $taskSources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
$taskArgFile = Join-Path $taskRoot 'build/core-test.args'
[IO.File]::WriteAllLines($taskArgFile,$taskArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JdkHome ('bin/javac' + $taskJavaSuffix)) ('@' + $taskArgFile)
if ($LASTEXITCODE -ne 0) { throw 'Core test compilation failed' }
foreach ($taskTest in @('DiagramTests','ArrowTests')) {
    & (Join-Path $JdkHome ('bin/java' + $taskJavaSuffix)) '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $taskClasses ('ru.student.diagram.' + $taskTest) (Join-Path $taskRoot 'build/core-verification')
    if ($LASTEXITCODE -ne 0) { throw ('Core tests failed: ' + $taskTest) }
}
