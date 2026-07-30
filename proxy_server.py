import os
import subprocess
import time
import requests
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

app = FastAPI(title="VoiceChanger Secure Cloud Relay")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

TARGET_CLOUD_URL = "https://voice-changer-service-ffboj7vvya-el.a.run.app"
cached_token = ""
token_expiry = 0

def get_auth_token():
    global cached_token, token_expiry
    now = time.time()
    if not cached_token or now >= token_expiry - 60:
        try:
            tok = subprocess.check_output(["gcloud", "auth", "print-identity-token"]).decode().strip()
            cached_token = tok
            token_expiry = now + 3000
        except Exception as e:
            print("Token error:", e)
    return cached_token

@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"])
async def relay(request: Request, path: str):
    url = f"{TARGET_CLOUD_URL}/{path}"
    headers = {k: v for k, v in request.headers.items() if k.lower() not in ["host", "content-length"]}
    headers["Authorization"] = f"Bearer {get_auth_token()}"
    
    body = await request.body()
    
    try:
        resp = requests.request(
            method=request.method,
            url=url,
            headers=headers,
            data=body,
            params=request.query_params,
            allow_redirects=False,
            timeout=300
        )
        return Response(
            content=resp.content,
            status_code=resp.status_code,
            headers=dict(resp.headers)
        )
    except Exception as e:
        return Response(content=f"{{\"error\": \"{str(e)}\"}}", status_code=500, media_type="application/json")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8089)
