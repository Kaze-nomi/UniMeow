$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
$project = "unimeow-outbox-it-local-$PID"
try {
    docker compose -p $project -f outbox/compose.test.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic test stack failed to start' }
    $env:OUTBOX_IT_JDBC_URL = 'jdbc:postgresql://localhost:25432/outbox_test'
    $env:OUTBOX_IT_KAFKA = 'localhost:29092'
    .\gradlew.bat :outbox:integrationTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Outbox integration checks failed' }
} finally {
    docker compose -p $project -f outbox/compose.test.yaml down
    Remove-Item Env:OUTBOX_IT_JDBC_URL -ErrorAction SilentlyContinue
    Remove-Item Env:OUTBOX_IT_KAFKA -ErrorAction SilentlyContinue
    Pop-Location
}
