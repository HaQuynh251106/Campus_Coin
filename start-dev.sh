#!/bin/bash

# Campus Coin - Development Startup Script
echo "=========================================================="
echo "          🚀 STARTING CAMPUS COIN FULL-STACK             "
echo "=========================================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Check Java
echo "👉 Checking Java version..."
java -version 2>&1 | head -n 1

# Check Node
echo "👉 Checking Node version..."
node -v

echo ""
echo "Starting Backend (Spring Boot on http://localhost:8080)..."
(cd "$SCRIPT_DIR/backend" && ./mvnw spring-boot:run) &
BACKEND_PID=$!

echo "Starting Frontend (Angular on http://localhost:4200)..."
(cd "$SCRIPT_DIR/frontend" && npm start) &
FRONTEND_PID=$!

trap "echo 'Stopping Campus Coin services...'; kill $BACKEND_PID $FRONTEND_PID; exit" SIGINT SIGTERM

echo "Both services are running in background!"
echo "- Backend API & Swagger: http://localhost:8080/swagger-ui.html"
echo "- H2 Console:            http://localhost:8080/h2-console"
echo "- Frontend UI:           http://localhost:4200"
echo "Press Ctrl+C to stop both services."

wait
