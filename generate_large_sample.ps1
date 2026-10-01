# Generate large ISO8583 sample file with millions of transaction pairs
param(
    [string]$OutputFile = "CIDC_100k.txt",
    [int]$NumPairs = 100000
)

Write-Host "Generating $NumPairs transaction pairs to $OutputFile..." -ForegroundColor Cyan
Write-Host "This may take a few minutes for large files..." -ForegroundColor Yellow
Write-Host ""

$channels = @("UPI", "UPI", "UPI", "UPI", "CARD", "CARD", "CARD", "NET_BANKING", "NET_BANKING", "MOBILE_WALLET")
$responseCodes = @("000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "000", "003")

$stan = 1000000
$startTime = Get-Date

$fileStream = [System.IO.File]::Create($OutputFile)
$writer = [System.IO.StreamWriter]::new($fileStream, [System.Text.Encoding]::UTF8, 65536)

for ($i = 1; $i -le $NumPairs; $i++) {
    $channel = $channels | Get-Random
    $responseCode = $responseCodes | Get-Random
    
    $pan = "{0:d16}" -f (Get-Random -Minimum 1000000000000000 -Maximum 9999999999999999)
    $stan++
    
    $baseTimestamp = (Get-Date).AddSeconds(-(Get-Random -Minimum 0 -Maximum 86400))
    $requestMs = Get-Random -Minimum 0 -Maximum 1000
    $requestTime = $baseTimestamp.AddMilliseconds($requestMs).ToString("MM/dd/yyyy HH:mm:ss.fff")
    
    switch ($channel) {
        "UPI" { $tat = Get-Random -Minimum 100 -Maximum 600 }
        "CARD" { $tat = Get-Random -Minimum 200 -Maximum 900 }
        "NET_BANKING" { $tat = Get-Random -Minimum 50 -Maximum 300 }
        "MOBILE_WALLET" { $tat = Get-Random -Minimum 100 -Maximum 500 }
    }
    
    $responseTime = $baseTimestamp.AddMilliseconds($requestMs + $tat).ToString("MM/dd/yyyy HH:mm:ss.fff")
    
    $writer.WriteLine("Pid: 22664 Received At: $requestTime")
    $writer.WriteLine("MessageId: 1200")
    $writer.WriteLine("Field 002: $pan")
    $writer.WriteLine("Field 011: $stan")
    $writer.WriteLine("Field 123: $channel")
    $writer.WriteLine("Field 039: ")
    $writer.WriteLine("<=========>")
    
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
Write-Host ""
Write-Host "✅ File created: $OutputFile ($([math]::Round($fileSize))MB)" -ForegroundColor Green
