#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${RED}========================================${NC}"
echo -e "${RED}  Ticketing System - Flush             ${NC}"
echo -e "${RED}========================================${NC}"
echo ""
echo -e "${RED}WARNING: This will remove all data volumes!${NC}"
echo -e "${YELLOW}This includes:${NC}"
echo -e "  • MySQL database data"
echo -e "  • Redis cache data"
echo -e "  • Gradle cache"
echo ""
read -p "Are you sure you want to continue? (yes/no): " -r
echo

if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
  echo "Operation cancelled."
  exit 0
fi

# Navigate to project root
cd "$(dirname "$0")/.."

echo -e "${YELLOW}Stopping services and removing volumes...${NC}"
docker compose down --volumes

echo ""
echo -e "${GREEN}✓ All services stopped and data volumes removed!${NC}"
echo ""
echo -e "${YELLOW}To start fresh:${NC} ./scripts/start.sh"
echo ""
