$ErrorActionPreference = 'Stop'
$oldPath = $env:PATH
$oldPassword = $env:DATABASE_PASSWORD
$oldSecret = $env:JWT_SECRET_BASE64

try {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Java not found. Configure JAVA_HOME for JDK 21 before starting.'
    }
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $maven) {
        throw 'Maven not found. Add Maven 3.9+ to PATH before starting.'
    }
    if (-not $env:DATABASE_PASSWORD) {
        $securePassword = Read-Host 'PostgreSQL password' -AsSecureString
        $env:DATABASE_PASSWORD = [Net.NetworkCredential]::new('', $securePassword).Password
    }
    if (-not $env:JWT_SECRET_BASE64) {
        $bytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $random.GetBytes($bytes)
            $env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)
        } finally {
            $random.Dispose()
        }
    }
    Write-Host 'Starting Trame. Open http://localhost:8080 when Tomcat is ready. Stop with Ctrl+C.'
    & $maven.Source -B -ntp -f "$PSScriptRoot\pom.xml" spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE. Check the Maven output above."
    }
} finally {
    $env:PATH = $oldPath
    $env:DATABASE_PASSWORD = $oldPassword
    $env:JWT_SECRET_BASE64 = $oldSecret
}
