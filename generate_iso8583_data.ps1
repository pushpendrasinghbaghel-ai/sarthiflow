param(
    [string]$OutputFile = "ISO8583_test.txt",
    [int]$NumPairs = 100000
)

Write-Host "Generating $NumPairs ISO8583 request/response pairs..." -ForegroundColor Cyan

$channels = @("UPI", "UPI", "UPI", "UPI", "CARD", "CARD", "CARD", "NET_BANKING", "NET_BANKING", "MOBILE_WALLET")
$responseCodes = @("000", "000", "000", "000", "000", "000", "000", "000", "000", "003")

$startTime = Get-Date
$fileStream = [System.IO.File]::Create($OutputFile)
$writer = [System.IO.StreamWriter]::new($fileStream, [System.Text.Encoding]::UTF8, 65536)

for ($i = 1; $i -le $NumPairs; $i++) {
    $channel = $channels | Get-Random
    $responseCode = $responseCodes | Get-Random

    $pan = "{0:d16}" -f (Get-Random -Minimum 1000000000000000 -Maximum 9999999999999999)
    $stan = "{0:d6}" -f (1000000 + $i)

    $baseTimestamp = (Get-Date).AddSeconds(-(Get-Random -Minimum 0 -Maximum 86400))
    $requestMs = Get-Random -Minimum 0 -Maximum 1000

    switch ($channel) {
        "UPI" { $tat = Get-Random -Minimum 100 -Maximum 600 }
        "CARD" { $tat = Get-Random -Minimum 200 -Maximum 900 }
        "NET_BANKING" { $tat = Get-Random -Minimum 50 -Maximum 300 }
        "MOBILE_WALLET" { $tat = Get-Random -Minimum 100 -Maximum 500 }
    }

    $requestTime = $baseTimestamp.AddMilliseconds($requestMs).ToString("MM/dd/yyyy HH:mm:ss.fff")

    $writer.WriteLine("Pid: 22664 Received At: $requestTime")
    $writer.WriteLine("MessageId: 1200")
    $writer.WriteLine("Field 002: $pan")
    $writer.WriteLine("Field 011: $stan")
    $writer.WriteLine("Field 123: $channel")
    $writer.WriteLine("Field 039: ")
    $writer.WriteLine("<=========>")

    $responseTime = $baseTimestamp.AddMilliseconds($requestMs + $tat).ToString("MM/dd/yyyy HH:mm:ss.fff")

    $writer.WriteLine("Pid: 22664 Sent At: $responseTime")
    $writer.WriteLine("MessageId: 1210")
    $writer.WriteLine("Field 002: $pan")
    $writer.WriteLine("Field 011: $stan")
    $writer.WriteLine("Field 123: $channel")
    $writer.WriteLine("Field 039: $responseCode")
    $writer.WriteLine("<=========>")

    if ($i % 10000 -eq 0) {
        Write-Host "  Generated $i / $NumPairs pairs..." -ForegroundColor Green
    }
}

$writer.Flush()
$writer.Dispose()
$fileStream.Dispose()

$fileSize = (Get-Item $OutputFile).Length / 1MB
$elapsed = (Get-Date) - $startTime

Write-Host ""
Write-Host "SUCCESS!" -ForegroundColor Green
Write-Host "File: $OutputFile ($([math]::Round($fileSize))MB)" -ForegroundColor Cyan
Write-Host "Pairs: $NumPairs (REQUEST 1200 + RESPONSE 1210)" -ForegroundColor Cyan
Write-Host "Time: $($elapsed.TotalSeconds)s" -ForegroundColor Cyan
