param([string]$IdeHome = 'C:\Program Files\OpenIDE\OpenIDE')
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskDescriptor = [xml](Get-Content -LiteralPath (Join-Path $taskRoot 'src/main/resources/META-INF/plugin.xml') -Raw)
$taskVersion = [string]$taskDescriptor.'idea-plugin'.version
if ($taskVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected a numeric release version in plugin.xml' }
$taskNotes = Join-Path $taskRoot ('docs/releases/v' + $taskVersion + '.md')
if (-not (Test-Path -LiteralPath $taskNotes)) { throw 'Create release notes before packaging' }

& (Join-Path $taskRoot 'test.ps1') -IdeHome $IdeHome
& (Join-Path $taskRoot 'integration-test.ps1') -IdeHome $IdeHome

$taskDist = Join-Path $taskRoot 'dist'
$taskPluginZip = Join-Path $taskDist ('class-diagrams-' + $taskVersion + '.zip')
$taskSourcesZip = Join-Path $taskDist ('class-diagrams-' + $taskVersion + '-sources.zip')
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Verify the exact archive that will be attached to GitHub Release.
$taskOuter = [IO.Compression.ZipFile]::OpenRead($taskPluginZip)
try {
    $taskJarEntry = $taskOuter.GetEntry('class-diagrams/lib/class-diagrams.jar')
    if ($null -eq $taskJarEntry) { throw 'Plugin ZIP has an unexpected structure' }
    $taskMemory = [IO.MemoryStream]::new()
    $taskStream = $taskJarEntry.Open()
    try { $taskStream.CopyTo($taskMemory) } finally { $taskStream.Dispose() }
    $taskMemory.Position = 0
    $taskJar = [IO.Compression.ZipArchive]::new($taskMemory,[IO.Compression.ZipArchiveMode]::Read)
    try {
        $taskReader = [IO.StreamReader]::new($taskJar.GetEntry('META-INF/plugin.xml').Open())
        try { $taskPackedDescriptor = [xml]$taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
        if ([string]$taskPackedDescriptor.'idea-plugin'.version -ne $taskVersion) { throw 'Plugin version mismatch' }
        if (@($taskJar.Entries | Where-Object { $_.FullName -match 'ProbeStarter|DiagramTests|ArrowTests|\.java$' }).Count -ne 0) {
            throw 'Test or source files accidentally included in the installed plugin'
        }
        if ($null -eq $taskJar.GetEntry('ru/student/diagram/ui/EdgeRouter.class')) { throw 'Routing implementation missing from release' }
    } finally { $taskJar.Dispose(); $taskMemory.Dispose() }
} finally { $taskOuter.Dispose() }

# Explicit allowlist: no SDK, local profile, lab project, .git directory or generated files.
$taskInputs = @('src','docs','.github','README.md','CHANGELOG.md','RELEASING.md',
    '.gitignore','.gitattributes','build.ps1','test.ps1','test-core.ps1','integration-test.ps1','package-release.ps1')
if (Test-Path -LiteralPath (Join-Path $taskRoot 'LICENSE')) { $taskInputs += 'LICENSE' }
if (Test-Path -LiteralPath $taskSourcesZip) { Remove-Item -LiteralPath $taskSourcesZip }
$taskSourceArchive = [IO.Compression.ZipFile]::Open($taskSourcesZip,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($taskInput in $taskInputs) {
        $taskItem = Get-Item -LiteralPath (Join-Path $taskRoot $taskInput) -Force
        $taskFiles = if ($taskItem.PSIsContainer) { Get-ChildItem -LiteralPath $taskItem.FullName -Recurse -File -Force } else { @($taskItem) }
        foreach ($taskFile in $taskFiles) {
            $taskEntryName = $taskFile.FullName.Substring($taskRoot.Length + 1).Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskSourceArchive,$taskFile.FullName,$taskEntryName,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
} finally { $taskSourceArchive.Dispose() }
$taskChecksumLines = foreach ($taskFile in @($taskPluginZip,$taskSourcesZip)) {
    (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($taskFile)
}
[IO.File]::WriteAllLines((Join-Path $taskDist ('SHA256SUMS-' + $taskVersion + '.txt')),$taskChecksumLines,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $taskNotes -Destination (Join-Path $taskDist ('RELEASE-NOTES-' + $taskVersion + '.md')) -Force
Write-Output ('Release ' + $taskVersion + ' prepared in: ' + $taskDist)
