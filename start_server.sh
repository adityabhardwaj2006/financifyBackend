#!/bin/bash

echo "=========================================="
echo "auditX Financial Analysis Server"
echo "=========================================="
echo ""

# Change to the app directory
cd "$(dirname "$0")"

echo "✓ Starting Flask server..."
echo "✓ Server will run on: http://localhost:5000"
echo ""
echo "Available endpoints:"
echo "  GET  http://localhost:5000/                 (Home)"
echo "  GET  http://localhost:5000/api/health       (Health check)"
echo "  GET  http://localhost:5000/api/sample       (Sample response)"
echo "  POST http://localhost:5000/api/parse        (Upload file)"
echo ""
echo "Test commands:"
echo "  curl http://localhost:5000/api/health"
echo "  curl http://localhost:5000/api/sample"
echo "  curl -X POST -F 'file=@test.csv' http://localhost:5000/api/parse"
echo ""
echo "Press Ctrl+C to stop the server"
echo "=========================================="
echo ""

# Start the server
python3 app.py
