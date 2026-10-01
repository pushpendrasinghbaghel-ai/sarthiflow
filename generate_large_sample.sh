#!/bin/bash

# Generate large ISO8583 sample file with millions of transaction pairs
# Usage: bash generate_large_sample.sh <output_file> <num_pairs>
# Example: bash generate_large_sample.sh CIDC_large.txt 1000000

OUTPUT_FILE="${1:-CIDC_large.txt}"
NUM_PAIRS="${2:-100000}"

# Channel mix: UPI (40%), CARD (30%), NET_BANKING (20%), MOBILE_WALLET (10%)
CHANNELS=("UPI" "UPI" "UPI" "UPI" "CARD" "CARD" "CARD" "NET_BANKING" "NET_BANKING" "MOBILE_WALLET")

# Response codes: 000 (success, 95%), 003, 005, 006, 013 (various failures, 5%)
RESPONSE_CODES=("000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "000" "003")

# TAT ranges by channel (in milliseconds)
# UPI: 100-600ms, CARD: 200-900ms, NET_BANKING: 50-300ms, MOBILE_WALLET: 100-500ms

echo "Generating $NUM_PAIRS transaction pairs to $OUTPUT_FILE..."
echo "This may take a few minutes for large files..."

{
  stan=1000000
  
  for ((i=1; i<=NUM_PAIRS; i++)); do
    # Select random channel and response code
    channel_idx=$((RANDOM % ${#CHANNELS[@]}))
    channel="${CHANNELS[$channel_idx]}"
    
    response_idx=$((RANDOM % ${#RESPONSE_CODES[@]}))
    response_code="${RESPONSE_CODES[$response_idx]}"
    
    # Generate random PAN (16 digits)
    pan=$(printf "%016d" $((RANDOM * 10000 + RANDOM)))
    
    # Generate random STAN (6 digits)
    stan=$((stan + 1))
    
    # Generate random base timestamp (within last 24 hours)
    base_timestamp=$(($(date +%s) - RANDOM % 86400))
    base_ms=$(printf "%03d" $((RANDOM % 1000)))
    request_time=$(date -d @$base_timestamp +"%m/%d/%Y %H:%M:%S.$base_ms")
    
    # Generate TAT based on channel
    case $channel in
      UPI)
        tat=$((100 + RANDOM % 500))
        ;;
      CARD)
        tat=$((200 + RANDOM % 700))
        ;;
      NET_BANKING)
        tat=$((50 + RANDOM % 250))
        ;;
      MOBILE_WALLET)
        tat=$((100 + RANDOM % 400))
        ;;
    esac
    
    # Calculate response time
    response_ms=$((tat % 1000))
    response_sec=$((tat / 1000))
    
    response_time=$(date -d "@$base_timestamp + $response_sec seconds + $response_ms milliseconds" +"%m/%d/%Y %H:%M:%S.%N" 2>/dev/null || \
                   printf "%02d/%02d/2026 %02d:%02d:%02d.%03d" \
                   $((base_timestamp / 2592000 % 12 + 1)) \
                   $((base_timestamp / 86400 % 30 + 1)) \
                   $((base_timestamp / 3600 % 24)) \
                   $((base_timestamp / 60 % 60)) \
                   $((base_timestamp % 60)) \
                   $response_ms)
    
    # Request message
    echo "Pid: 22664 Received At: $request_time"
    echo "MessageId: 1200"
    echo "Field 002: $pan"
    echo "Field 011: $stan"
    echo "Field 123: $channel"
    echo "Field 039: "
    echo "=========>"
    
    # Response message
    echo "Pid: 22664 Sent At: $response_time"
    echo "MessageId: 1210"
    echo "Field 002: $pan"
    echo "Field 011: $stan"
    echo "Field 123: $channel"
    echo "Field 039: $response_code"
    echo "=========>"
    
    # Progress indicator every 10k pairs
    if (( i % 10000 == 0 )); then
      echo "  Generated $i / $NUM_PAIRS pairs..." >&2
    fi
  done
} > "$OUTPUT_FILE"

file_size=$(du -h "$OUTPUT_FILE" | cut -f1)
line_count=$(wc -l < "$OUTPUT_FILE")

echo ""
echo "✅ Done!"
echo "   File: $OUTPUT_FILE"
echo "   Size: $file_size"
echo "   Lines: $line_count"
echo "   Transaction pairs: $NUM_PAIRS"
echo ""
echo "Usage:"
echo "  java -Xmx2g -jar target/iso8583-tat-extractor.jar application.conf"
