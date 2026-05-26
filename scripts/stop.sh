#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Ticketing System - Stop              ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Navigate to project root
cd "$(dirname "$0")/.."

echo -e "${YELLOW}Stopping Docker services...${NC}"
docker compose down

echo ""
echo -e "${GREEN}✓ Services stopped successfully!${NC}"
echo ""
echo -e "${YELLOW}Note:${NC} Data volumes are preserved."
echo -e "${YELLOW}To remove data:${NC} ./scripts/flush.sh"
echo ""
