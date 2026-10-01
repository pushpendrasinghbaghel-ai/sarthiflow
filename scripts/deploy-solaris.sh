#!/bin/bash
# Deploy ISO8583 TAT Extractor to Solaris 11
# Usage: ./deploy-solaris.sh <java-home> <target-user> <target-host>

set -e

JAVA_HOME=${1:?'Java home required (e.g., /opt/java/jdk-11)'}
TARGET_USER=${2:?'Target user required (e.g., iso8583)'}
TARGET_HOST=${3:-localhost}

APP_NAME="iso8583-tat-extractor"
APP_VERSION="1.0.0"
JAR_FILE="target/${APP_NAME}.jar"
INSTALL_DIR="/opt/${APP_NAME}"
DATA_DIR="/var/lib/${APP_NAME}"
LOG_DIR="/var/log/${APP_NAME}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}ISO8583 TAT Extractor Deployment${NC}"
echo "========================================="
echo "Java Home:    $JAVA_HOME"
echo "Target User:  $TARGET_USER"
echo "Target Host:  $TARGET_HOST"
echo "Install Dir:  $INSTALL_DIR"
echo "Data Dir:     $DATA_DIR"
echo "Log Dir:      $LOG_DIR"
echo ""

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR not found at $JAR_FILE${NC}"
    echo "Run: mvn clean package"
    exit 1
fi

echo -e "${YELLOW}[1/5] Building package...${NC}"
mvn clean package -q || { echo -e "${RED}Build failed${NC}"; exit 1; }
echo -e "${GREEN}âœ“ Build complete${NC}"

echo -e "${YELLOW}[2/5] Creating directories on $TARGET_HOST...${NC}"
ssh ${TARGET_USER}@${TARGET_HOST} "sudo mkdir -p ${INSTALL_DIR} ${DATA_DIR} ${LOG_DIR} && \
  sudo chown ${TARGET_USER}:${TARGET_USER} ${INSTALL_DIR} ${DATA_DIR} ${LOG_DIR} && \
  sudo chmod 755 ${INSTALL_DIR} ${DATA_DIR} ${LOG_DIR}"
echo -e "${GREEN}âœ“ Directories created${NC}"

echo -e "${YELLOW}[3/5] Uploading JAR and config...${NC}"
scp "$JAR_FILE" "${TARGET_USER}@${TARGET_HOST}:${INSTALL_DIR}/"
scp "src/main/resources/application.conf" "${TARGET_USER}@${TARGET_HOST}:${INSTALL_DIR}/"
echo -e "${GREEN}âœ“ Files uploaded${NC}"

echo -e "${YELLOW}[4/5] Creating systemd service...${NC}"
ssh ${TARGET_USER}@${TARGET_HOST} "sudo tee /etc/systemd/system/iso8583-tat.service > /dev/null" <<EOF
[Unit]
Description=ISO8583 TAT Extractor for Dynatrace
After=network.target

[Service]
Type=simple
ExecStart=${JAVA_HOME}/bin/java -Xmx2g -Xms1g \\
  -jar ${INSTALL_DIR}/${APP_NAME}.jar \\
  ${INSTALL_DIR}/application.conf
WorkingDirectory=${INSTALL_DIR}
User=${TARGET_USER}
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

ssh ${TARGET_USER}@${TARGET_HOST} "sudo systemctl daemon-reload && sudo systemctl enable iso8583-tat"
echo -e "${GREEN}âœ“ Service created${NC}"

echo -e "${YELLOW}[5/5] Instructions for next steps:${NC}"
echo "1. SSH to $TARGET_HOST and edit the config:"
echo "   ssh ${TARGET_USER}@${TARGET_HOST}"
echo "   vi ${INSTALL_DIR}/application.conf"
echo ""
echo "2. Update these values:"
echo "   - log.path: path to SarthiFlow payment logs"
echo "   - bindplane.url: your Dynatrace Bindplane endpoint"
echo ""
echo "3. Start the service:"
echo "   sudo systemctl start iso8583-tat"
echo ""
echo "4. Monitor logs:"
echo "   sudo journalctl -u iso8583-tat -f"
echo ""
echo -e "${GREEN}âœ“ Deployment complete${NC}"

