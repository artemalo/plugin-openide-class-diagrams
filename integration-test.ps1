param([string]$IdeHome = 'C:\Program Files\OpenIDE\OpenIDE')
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskSandbox = Join-Path $taskRoot 'sandbox'
$taskProbe = Join-Path $taskRoot 'build\probe-classes'
$taskPlugins = Join-Path $taskSandbox 'plugins'
$taskFixture = Join-Path $taskSandbox 'fixture'
$taskOut = Join-Path $taskRoot 'build\integration'
New-Item -ItemType Directory -Force -Path $taskProbe,$taskPlugins,$taskFixture,$taskOut,(Join-Path $taskSandbox 'config\options'),(Join-Path $taskSandbox 'system'),(Join-Path $taskSandbox 'log') | Out-Null
Copy-Item -Path (Join-Path $taskRoot 'src\integration\fixture\*') -Destination $taskFixture -Recurse -Force
$taskLabSources = Join-Path (Split-Path $taskRoot -Parent) 'lab1-fraction\src\main\java\ru\student\lab1'
if (Test-Path -LiteralPath $taskLabSources) {
    $taskLabTarget = Join-Path $taskFixture 'src\ru\student\lab1'
    New-Item -ItemType Directory -Force -Path $taskLabTarget | Out-Null
    Copy-Item -Path (Join-Path $taskLabSources '*.java') -Destination $taskLabTarget -Force
}
Copy-Item -LiteralPath (Join-Path $taskRoot 'build\package\class-diagrams') -Destination $taskPlugins -Recurse -Force
$taskClasspath = (Get-Content -LiteralPath (Join-Path $taskRoot 'build\compiler.args'))[5].Trim('"')
$taskJavac = Join-Path $IdeHome 'jbr\bin\javac.exe'
$taskProbeArgs = @('-encoding','UTF-8','--release','21','-cp',('"' + $taskClasspath + ';' + (Join-Path $taskRoot 'build/classes').Replace('\','/') + '"'),'-d',('"' + $taskProbe.Replace('\','/') + '"'),('"' + (Join-Path $taskRoot 'src/integration/java/ru/student/diagram/probe/ProbeStarter.java').Replace('\','/') + '"'))
$taskProbeArgFile = Join-Path $taskRoot 'build\probe.args'
[IO.File]::WriteAllLines($taskProbeArgFile,$taskProbeArgs,[Text.UTF8Encoding]::new($false))
& $taskJavac ('@' + $taskProbeArgFile)
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
New-Item -ItemType Directory -Force -Path (Join-Path $taskProbe 'META-INF'),(Join-Path $taskPlugins 'probe\lib'),(Join-Path $taskFixture '.idea') | Out-Null
$taskXml = '<idea-plugin><id>ru.student.diagram.probe</id><name>Diagram Integration Probe</name><version>1.0</version><idea-version since-build="262" until-build="262.*"/><depends>ru.student.classdiagrams</depends><depends>com.intellij.java</depends><extensions defaultExtensionNs="com.intellij"><appStarter id="diagramProbe" implementation="ru.student.diagram.probe.ProbeStarter"/></extensions></idea-plugin>'
[IO.File]::WriteAllText((Join-Path $taskProbe 'META-INF/plugin.xml'),$taskXml,[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskProbeJar = Join-Path $taskPlugins 'probe\lib\probe.jar'
if (Test-Path -LiteralPath $taskProbeJar) { Remove-Item -LiteralPath $taskProbeJar }
[IO.Compression.ZipFile]::CreateFromDirectory($taskProbe,$taskProbeJar)
[IO.File]::WriteAllText((Join-Path $taskFixture '.idea/modules.xml'),'<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml"/></modules></component></project>')
[IO.File]::WriteAllText((Join-Path $taskFixture '.idea/misc.xml'),'<project version="4"><component name="ProjectRootManager" version="2" languageLevel="JDK_21" project-jdk-name="OpenIDE Runtime" project-jdk-type="JavaSDK"/></project>')
[IO.File]::WriteAllText((Join-Path $taskFixture 'fixture.iml'),'<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" inherit-compiler-output="true"><content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false"/></content><orderEntry type="inheritedJdk"/><orderEntry type="sourceFolder" forTests="false"/></component></module>')
$taskJdkXml = '<application><component name="ProjectJdkTable"><jdk version="2"><name value="OpenIDE Runtime"/><type value="JavaSDK"/><version value="25.0.4"/><homePath value="' + (Join-Path $IdeHome 'jbr').Replace('\','/') + '"/><roots><classPath><root type="composite"><root url="jrt://' + (Join-Path $IdeHome 'jbr').Replace('\','/') + '!/java.base" type="simple"/></root></classPath><sourcePath><root type="composite"/></sourcePath></roots></jdk></component></application>'
[IO.File]::WriteAllText((Join-Path $taskSandbox 'config/options/jdk.table.xml'),$taskJdkXml)
$taskProduct = Get-Content -LiteralPath (Join-Path $IdeHome 'product-info.json') -Raw | ConvertFrom-Json
$taskLaunch = $taskProduct.launch[0]
$taskLaunchArgs = @($taskLaunch.additionalJvmArguments | ForEach-Object { $_.Replace('%IDE_HOME%',$IdeHome).Replace('\','/') })
$taskLaunchArgs += @('-Xmx2048m','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8',('-Didea.home.path=' + $IdeHome.Replace('\','/')))
foreach ($taskKind in @('config','system','plugins','log')) { $taskLaunchArgs += '-Didea.' + $taskKind + '.path=' + (Join-Path $taskSandbox $taskKind).Replace('\','/') }
$taskBoot = ($taskLaunch.bootClassPathJarNames | ForEach-Object { (Join-Path $IdeHome ('lib/' + $_)).Replace('\','/') }) -join ';'
$taskLaunchArgs += @('-classpath',$taskBoot,$taskLaunch.mainClass,'diagramProbe',$taskFixture.Replace('\','/'),$taskOut.Replace('\','/'))
$taskLaunchFile = Join-Path $taskRoot 'build/ide-probe.args'
[IO.File]::WriteAllLines($taskLaunchFile,($taskLaunchArgs | ForEach-Object { '"' + $_ + '"' }),[Text.UTF8Encoding]::new($false))
& (Join-Path $IdeHome 'jbr/bin/java.exe') ('@' + $taskLaunchFile)
if ($LASTEXITCODE -ne 0) { throw 'IDE integration probe failed; see build/integration and sandbox/log' }
