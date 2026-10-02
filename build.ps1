<#
.SYNOPSIS
    Chay Maven voi JDK 21, khong dung toi bien JAVA_HOME toan cuc cua may.

.DESCRIPTION
    Spring Boot 3.5 khong ho tro JDK 24 tro len. Neu Maven chay tren JDK moi hon,
    no van build duoc nhung in ra mot loat WARNING tu noi bo Maven (Guice, Sisu)
    vi JDK moi siet chat sun.misc.Unsafe va viec sua field final bang reflection.

    Script nay dat JAVA_HOME CHI TRONG PHIEN CHAY NAY roi goi mvnw.cmd.
    Bien moi truong cua may khong bi thay doi, nen cac project khac khong bi anh huong.

.EXAMPLE
    .\build.ps1 test
    .\build.ps1 -q clean compile
    .\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze-all=../ai20k_sample"
#>

$ErrorActionPreference = 'Stop'

# Cac vi tri thuong gap cua JDK 21. Them duong dan cua ban vao day neu khac.
$candidates = @(
    $env:JAVA_HOME_21
    "$env:USERPROFILE\.jdks\jbr-21.0.10"
    "C:\Program Files\Java\jdk-21"
    "C:\Program Files\Eclipse Adoptium\jdk-21"
) | Where-Object { $_ }

# Doc phien ban tu file `release` cua JDK chu KHONG chay `java -version`.
# Ly do: `java -version` ghi ra stderr, ma voi $ErrorActionPreference = 'Stop'
# thi PowerShell bien stderr cua native command thanh loi ket thuc script
# (NativeCommandError) — script chet ngay o buoc do tim JDK.
function Get-JdkVersion([string] $jdkPath) {
    $releaseFile = Join-Path $jdkPath 'release'
    if (Test-Path $releaseFile) {
        $line = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="?([^"]+)"?' |
                Select-Object -First 1
        if ($line) {
            return $line.Matches[0].Groups[1].Value
        }
    }

    # Du phong cho JDK khong co file `release`: ha muc xu ly loi trong dung loi goi nay.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & (Join-Path $jdkPath 'bin\java.exe') -version 2>&1 | Out-String
    }
    catch {
        return $null
    }
    finally {
        $ErrorActionPreference = $previous
    }

    if ($output -match 'version "([^"]+)"') {
        return $Matches[1]
    }
    return $null
}

$jdk21 = $null
foreach ($path in $candidates) {
    if (-not (Test-Path (Join-Path $path 'bin\java.exe'))) {
        continue
    }
    $version = Get-JdkVersion $path
    if ($version -and $version.StartsWith('21.')) {
        $jdk21 = $path
        break
    }
}

if (-not $jdk21) {
    Write-Error @"
Khong tim thay JDK 21 tren may.

Da tim o:
$($candidates -join "`n")

Cach xu ly:
  - Cai JDK 21 (Eclipse Temurin), hoac
  - Dat bien JAVA_HOME_21 tro toi thu muc JDK 21 cua ban:
      `$env:JAVA_HOME_21 = "D:\duong\dan\jdk-21"
"@
}

# Chi dat trong phien nay — khong ghi vao bien moi truong cua may
$env:JAVA_HOME = $jdk21

# Output cua chuong trinh la tieng Viet co dau (MVP muc 4.5). Neu console dang o
# code page cu (437/1258), dau tieng Viet se hien thanh dau hoi. Dat UTF-8 cho
# rieng phien nay roi tra lai nguyen trang khi chay xong.
$previousCodePage = (chcp) -replace '[^0-9]', ''
$previousEncoding = [Console]::OutputEncoding
try {
    $null = chcp 65001
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

    & "$PSScriptRoot\mvnw.cmd" @args
    $exitCode = $LASTEXITCODE
}
finally {
    [Console]::OutputEncoding = $previousEncoding
    if ($previousCodePage) { $null = chcp $previousCodePage }
}

exit $exitCode
