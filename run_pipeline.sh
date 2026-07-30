#!/bin/bash

# Navigate to script directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Activate Python Virtual Environment
if [ -d ".venv" ]; then
    source .venv/bin/activate
else
    echo "[x] Error: Virtual environment .venv not found!"
    exit 1
fi

# Run python pipeline
python3 process_pipeline.py "$@"
