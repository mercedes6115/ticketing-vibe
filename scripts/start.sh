#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Ticketing System - Start             ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
  echo -e "${RED}Error: Docker is not running. Please start Docker first.${NC}"
  exit 1
fi

# Navigate to project root
cd "$(dirname "$0")/.."

echo -e "${YELLOW}[1/3] Starting Docker services...${NC}"
docker compose up -d

echo ""
echo -e "${YELLOW}[2/3] Waiting for services to be ready...${NC}"
sleep 10

echo -e "${YELLOW}[3/3] Checking service health...${NC}"

# Check if services are running
if docker compose ps | grep -q "Up"; then
  echo ""
  echo -e "${GREEN}✓ Services started successfully!${NC}"
  echo ""
  echo -e "${GREEN}Service URLs:${NC}"
  echo -e "  • Frontend:  ${GREEN}http://localhost:5173${NC}"
  echo -e "  • Backend:   ${GREEN}http://localhost:8080${NC}"
  echo -e "  • MySQL:     ${GREEN}localhost:3306${NC}"
  echo -e "  • Redis:     ${GREEN}localhost:6379${NC}"
  echo ""
  echo -e "${YELLOW}View logs:${NC} docker compose logs -f"
  echo -e "${YELLOW}View backend logs:${NC} docker compose logs -f backend"
  echo -e "${YELLOW}View frontend logs:${NC} docker compose logs -f frontend"
  echo -e "${YELLOW}Stop services:${NC} ./scripts/stop.sh"
  echo ""
else
  echo -e "${RED}✗ Failed to start services. Check logs with: docker compose logs${NC}"
  exit 1
fi
