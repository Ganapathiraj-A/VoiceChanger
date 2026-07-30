#!/usr/bin/env bash
set -e

echo "=================================================="
echo "  Google Cloud Run Deployment — Voice Changer API  "
echo "=================================================="

# Check gcloud CLI
if ! command -v gcloud &> /dev/null; then
    echo "[x] Error: 'gcloud' CLI is not installed."
    echo "Please install Google Cloud SDK: https://cloud.google.com/sdk"
    exit 1
fi

PROJECT_ID=$(gcloud config get-value project 2>/dev/null || echo "")

if [ -z "$PROJECT_ID" ]; then
    echo "[!] No active GCP project configured."
    read -p "Enter your GCP Project ID: " PROJECT_ID
    gcloud config set project "$PROJECT_ID"
fi

REGION="asia-south1"
SERVICE_NAME="voice-changer-service"
IMAGE_NAME="gcr.io/${PROJECT_ID}/${SERVICE_NAME}"

echo ""
echo "  • GCP Project: $PROJECT_ID"
echo "  • Region:      $REGION"
echo "  • Service:     $SERVICE_NAME"
echo ""

echo "[1/3] Enabling required Google Cloud APIs (Cloud Run, Cloud Build)..."
gcloud services enable \
    run.googleapis.com \
    cloudbuild.googleapis.com \
    artifactregistry.googleapis.com \
    --project "$PROJECT_ID"

echo "[2/3] Building container image on Cloud Build..."
gcloud builds submit --tag "$IMAGE_NAME" --project "$PROJECT_ID"

echo "[3/3] Deploying container to GCP Cloud Run..."
gcloud run deploy "$SERVICE_NAME" \
    --image "$IMAGE_NAME" \
    --platform managed \
    --region "$REGION" \
    --memory 4Gi \
    --cpu 2 \
    --timeout 900s \
    --allow-unauthenticated \
    --project "$PROJECT_ID"

SERVICE_URL=$(gcloud run services describe "$SERVICE_NAME" --platform managed --region "$REGION" --format 'value(status.url)' --project "$PROJECT_ID")

echo ""
echo "=================================================="
echo "  🎉 DEPLOYMENT COMPLETE!"
echo "=================================================="
echo "Live Public API URL:  $SERVICE_URL"
echo "Interactive OpenAPI Docs: $SERVICE_URL/docs"
echo "=================================================="
