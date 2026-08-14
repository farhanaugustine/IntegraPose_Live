param(
    [string[]] $ApkPaths
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $ApkPaths) {
    $ApkPaths = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug') -Filter '*.apk' |
        Select-Object -ExpandProperty FullName
}

$buildTools = Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
$zipAlign = Join-Path $buildTools.FullName 'zipalign.exe'
if (-not (Test-Path -LiteralPath $zipAlign)) {
    throw 'zipalign.exe was not found in the Android SDK.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ElfLoadAlignments {
    param([byte[]] $Bytes)

    if ($Bytes.Length -lt 64 -or
        $Bytes[0] -ne 0x7F -or
        $Bytes[1] -ne 0x45 -or
        $Bytes[2] -ne 0x4C -or
        $Bytes[3] -ne 0x46) {
        throw 'Native library is not a valid ELF file.'
    }
    if ($Bytes[5] -ne 1) {
        throw 'Only little-endian Android ELF files are supported by this checker.'
    }

    $elfClass = $Bytes[4]
    if ($elfClass -eq 2) {
        $programOffset = [BitConverter]::ToUInt64($Bytes, 32)
        $entrySize = [BitConverter]::ToUInt16($Bytes, 54)
        $entryCount = [BitConverter]::ToUInt16($Bytes, 56)
        $alignmentOffset = 48
        $readAlignment = {
            param([byte[]] $Data, [int] $Offset)
            [BitConverter]::ToUInt64($Data, $Offset)
        }
    } elseif ($elfClass -eq 1) {
        $programOffset = [BitConverter]::ToUInt32($Bytes, 28)
        $entrySize = [BitConverter]::ToUInt16($Bytes, 42)
        $entryCount = [BitConverter]::ToUInt16($Bytes, 44)
        $alignmentOffset = 28
        $readAlignment = {
            param([byte[]] $Data, [int] $Offset)
            [uint64] [BitConverter]::ToUInt32($Data, $Offset)
        }
    } else {
        throw "Unsupported ELF class $elfClass."
    }

    $alignments = [System.Collections.Generic.List[uint64]]::new()
    for ($index = 0; $index -lt $entryCount; $index++) {
        $headerOffset = [int] ($programOffset + ($index * $entrySize))
        if ($headerOffset + $entrySize -gt $Bytes.Length) {
            throw 'ELF program header extends beyond the library.'
        }
        $programType = [BitConverter]::ToUInt32($Bytes, $headerOffset)
        if ($programType -eq 1) {
            $alignments.Add((& $readAlignment $Bytes ($headerOffset + $alignmentOffset)))
        }
    }
    return $alignments.ToArray()
}

$failed = $false
foreach ($apkPath in $ApkPaths) {
    $resolvedApk = (Resolve-Path -LiteralPath $apkPath).Path
    & $zipAlign -c -P 16 4 $resolvedApk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[UNALIGNED ZIP] $resolvedApk"
        $failed = $true
    } else {
        Write-Host "[ALIGNED ZIP] $resolvedApk"
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        $nativeEntries = $archive.Entries | Where-Object {
            $_.FullName -match '^lib/(arm64-v8a|x86_64)/.+\.so$'
        }
        foreach ($entry in $nativeEntries) {
            $memory = [System.IO.MemoryStream]::new()
            $stream = $entry.Open()
            try {
                $stream.CopyTo($memory)
            } finally {
                $stream.Dispose()
            }
            try {
                $alignments = Get-ElfLoadAlignments -Bytes $memory.ToArray()
            } finally {
                $memory.Dispose()
            }
            $badAlignments = @($alignments | Where-Object { $_ -lt 16384 })
            $minimum = [uint64] (($alignments | Measure-Object -Minimum).Minimum)
            $displayAlignment = '0x{0:X}' -f $minimum
            if ($alignments.Count -eq 0 -or $badAlignments.Count -gt 0) {
                Write-Host "[UNALIGNED ELF] $($entry.FullName) min LOAD alignment=$displayAlignment"
                $failed = $true
            } else {
                Write-Host "[ALIGNED ELF] $($entry.FullName) min LOAD alignment=$displayAlignment"
            }
        }
    } finally {
        $archive.Dispose()
    }
}

if ($failed) {
    throw 'One or more APKs or 64-bit native libraries failed 16 KB alignment checks.'
}
