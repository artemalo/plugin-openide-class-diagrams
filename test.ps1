param([string]$IdeHome = 'C:\Program Files\OpenIDE\OpenIDE')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1') -IdeHome $IdeHome
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    & (Join-Path $IdeHome 'jbr\bin\javac.exe') -encoding UTF-8 --release 21 -cp build/classes -d build/test-classes src/test/java/ru/student/diagram/DiagramTests.java src/test/java/ru/student/diagram/ArrowTests.java
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & (Join-Path $IdeHome 'jbr\bin\java.exe') '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp 'build/classes;build/test-classes' ru.student.diagram.DiagramTests build/verification
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
    & (Join-Path $IdeHome 'jbr\bin\java.exe') '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp 'build/classes;build/test-classes' ru.student.diagram.ArrowTests build/verification
    if ($LASTEXITCODE -ne 0) { throw 'Arrow tests failed' }
} finally { Pop-Location }
