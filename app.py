import os
import time
import uuid
import shutil
import tempfile
import threading
import numpy as np
from fastapi import FastAPI, File, UploadFile, Form, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware

from speaker_utils import extract_embedding, segment_audio_vad
from process_pipeline import process_audio_file
from create_comparison_track import create_comparison_file

app = FastAPI(
    title="Voice Changer Cloud API",
    description="Selective Voice Preservation & Gender Conversion Microservice with Async Progress & ETA",
    version="2.0.0"
)

# Enable CORS for web & mobile clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

PROFILE_PATH = "target_speaker_profile.npy"

# In-memory Job Database for progress tracking
JOBS_DB = {}
JOBS_LOCK = threading.Lock()

@app.on_event("startup")
def startup_event():
    print("[Startup] Pre-warming SpeechBrain ECAPA-TDNN neural model into memory...")
    try:
        from speaker_utils import get_speaker_encoder
        get_speaker_encoder()
        print("[Startup] SpeechBrain neural model pre-warmed successfully!")
    except Exception as e:
        print(f"[Startup] Warning pre-warming model: {e}")

@app.get("/")
def read_root():
    return {
        "service": "Voice Changer Cloud API",
        "version": "2.0.0",
        "status": "running",
        "docs": "/docs",
        "target_profile_present": os.path.exists(PROFILE_PATH)
    }

@app.get("/health")
def health_check():
    return {
        "status": "healthy",
        "profile_loaded": os.path.exists(PROFILE_PATH),
        "active_jobs": len([j for j in JOBS_DB.values() if j["status"] == "processing"])
    }

# ---------------------------------------------------------
# ASYNC JOB MANAGEMENT ENDPOINTS (PROGRESS & ETA)
# ---------------------------------------------------------

def run_job_background(job_id: str, input_path: str, output_path: str, threshold: float, target_gender: str, target_profile_name: str, preserve_speaker_cluster: int, is_comparison: bool):
    target_embedding = None
    if target_profile_name:
        prof_path = os.path.join("target_profiles", f"{target_profile_name}.npy")
        if os.path.exists(prof_path):
            target_embedding = np.load(prof_path)
    if target_embedding is None and os.path.exists(PROFILE_PATH):
        target_embedding = np.load(PROFILE_PATH)
    
    def on_progress(pct: float, step_msg: str):
        with JOBS_LOCK:
            if job_id in JOBS_DB:
                now = time.time()
                elapsed = now - JOBS_DB[job_id]["created_at"]
                
                # Calculate ETA
                if pct > 5.0:
                    total_est = elapsed / (pct / 100.0)
                    eta = max(0.0, round(total_est - elapsed, 1))
                else:
                    eta = 30.0
                    
                JOBS_DB[job_id]["progress_percent"] = round(pct, 1)
                JOBS_DB[job_id]["step"] = step_msg
                JOBS_DB[job_id]["updated_at"] = now
                JOBS_DB[job_id]["elapsed_seconds"] = round(elapsed, 1)
                JOBS_DB[job_id]["eta_seconds"] = eta
                print(f"[JOB {job_id}] [{pct:.1f}%] Elapsed: {elapsed:.1f}s | ETA: {eta:.1f}s | Step: {step_msg}", flush=True)

    try:
        if is_comparison:
            comp_out_path = input_path + "_comp.mp3"
            process_audio_file(
                input_file=input_path,
                output_file=output_path,
                target_embedding=target_embedding,
                target_profile_name=target_profile_name,
                preserve_speaker_cluster=preserve_speaker_cluster,
                similarity_threshold=threshold,
                target_gender=target_gender,
                progress_callback=on_progress
            )
            create_comparison_file(input_path, output_path, comp_out_path)
            output_final = comp_out_path
        else:
            process_audio_file(
                input_file=input_path,
                output_file=output_path,
                target_embedding=target_embedding,
                target_profile_name=target_profile_name,
                preserve_speaker_cluster=preserve_speaker_cluster,
                similarity_threshold=threshold,
                target_gender=target_gender,
                progress_callback=on_progress
            )
            output_final = output_path

        with JOBS_LOCK:
            if job_id in JOBS_DB:
                now = time.time()
                elapsed = now - JOBS_DB[job_id]["created_at"]
                JOBS_DB[job_id]["status"] = "completed"
                JOBS_DB[job_id]["progress_percent"] = 100.0
                JOBS_DB[job_id]["step"] = "Completed successfully!"
                JOBS_DB[job_id]["eta_seconds"] = 0.0
                JOBS_DB[job_id]["elapsed_seconds"] = round(elapsed, 1)
                JOBS_DB[job_id]["output_file"] = output_final
                JOBS_DB[job_id]["download_url"] = f"/jobs/{job_id}/download"

    except Exception as e:
        with JOBS_LOCK:
            if job_id in JOBS_DB:
                JOBS_DB[job_id]["status"] = "failed"
                JOBS_DB[job_id]["error"] = str(e)
                JOBS_DB[job_id]["step"] = f"Error: {str(e)}"

@app.get("/profiles")
def list_target_profiles():
    target_dir = "target_profiles"
    profiles = []
    if os.path.exists(target_dir):
        for f in sorted(os.listdir(target_dir)):
            if f.endswith(".npy"):
                profiles.append(f.replace(".npy", ""))
    return {
        "status": "success",
        "available_profiles": profiles,
        "count": len(profiles)
    }

PREVIEW_DB = {}

@app.post("/diarize/preview")
async def diarize_preview(file: UploadFile = File(...)):
    """
    Splits uploaded audio into Speaker A and Speaker B, generates 5-second MP3 sample clips
    for both speakers, and returns speech time % so user can choose which speaker to preserve.
    """
    preview_id = f"prev_{uuid.uuid4().hex[:10]}"
    ext = os.path.splitext(file.filename)[1] or ".mp4"
    preview_dir = os.path.join(tempfile.gettempdir(), preview_id)
    os.makedirs(preview_dir, exist_ok=True)
    
    temp_in = os.path.join(preview_dir, f"input{ext}")
    with open(temp_in, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
        
    import asyncio
    from speaker_utils import generate_speaker_previews
    stats = await asyncio.to_thread(generate_speaker_previews, temp_in, preview_dir)
    PREVIEW_DB[preview_id] = {
        "dir": preview_dir,
        "stats": stats
    }
    
    return {
        "status": "success",
        "preview_id": preview_id,
        "speaker_a": {
            "id": 0,
            "label": "Speaker A",
            "speech_percent": stats["speaker_a_pct"],
            "duration_seconds": stats["speaker_a_dur_s"],
            "sample_url": f"/diarize/preview/{preview_id}/speaker_a"
        },
        "speaker_b": {
            "id": 1,
            "label": "Speaker B",
            "speech_percent": stats["speaker_b_pct"],
            "duration_seconds": stats["speaker_b_dur_s"],
            "sample_url": f"/diarize/preview/{preview_id}/speaker_b"
        }
    }

@app.get("/diarize/preview/{preview_id}/{speaker_id}")
def stream_preview_sample(preview_id: str, speaker_id: str):
    preview_dir = os.path.join(tempfile.gettempdir(), preview_id)
    file_name = "speaker_a.mp3" if speaker_id.lower() in ["speaker_a", "0", "a"] else "speaker_b.mp3"
    file_path = os.path.join(preview_dir, file_name)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="Preview sample audio file not found.")
    return FileResponse(file_path, media_type="audio/mpeg", filename=file_name)

@app.post("/jobs/submit")
async def submit_job(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    threshold: float = Form(0.84),
    target_gender: str = Form("auto"),
    target_profile_name: str = Form(None),
    preserve_speaker_cluster: int = Form(None),
    is_comparison: bool = Form(False)
):
    """
    Submits an audio conversion job for background processing.
    Returns immediately with a job_id and status_url to poll progress & ETA.
    """
    job_id = f"job_{uuid.uuid4().hex[:10]}"
    ext = os.path.splitext(file.filename)[1] or ".mp4"
    
    temp_in_path = os.path.join(tempfile.gettempdir(), f"{job_id}_in{ext}")
    temp_out_path = os.path.join(tempfile.gettempdir(), f"{job_id}_out.mp3")

    with open(temp_in_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    now = time.time()
    job_info = {
        "job_id": job_id,
        "filename": file.filename,
        "status": "processing",
        "step": "Job submitted. Initializing...",
        "progress_percent": 0.0,
        "created_at": now,
        "updated_at": now,
        "elapsed_seconds": 0.0,
        "eta_seconds": 30.0,
        "output_file": None,
        "download_url": None,
        "error": None
    }

    with JOBS_LOCK:
        JOBS_DB[job_id] = job_info

    # Launch processing in dedicated background thread so main Uvicorn event loop stays 100% responsive
    worker_thread = threading.Thread(
        target=run_job_background,
        args=(job_id, temp_in_path, temp_out_path, threshold, target_gender, target_profile_name, preserve_speaker_cluster, is_comparison),
        daemon=True
    )
    worker_thread.start()

    return JSONResponse(status_code=202, content={
        "job_id": job_id,
        "status": "processing",
        "progress_percent": 0.0,
        "eta_seconds": 30.0,
        "status_url": f"/jobs/{job_id}"
    })

@app.get("/jobs/{job_id}")
def get_job_status(job_id: str):
    """
    Returns real-time status, percentage progress, ETA in seconds, and download link for a job.
    """
    with JOBS_LOCK:
        if job_id not in JOBS_DB:
            raise HTTPException(status_code=404, detail="Job ID not found.")
        
        job = JOBS_DB[job_id].copy()

    if job["status"] == "processing":
        elapsed = round(time.time() - job["created_at"], 1)
        job["elapsed_seconds"] = elapsed
        if job["progress_percent"] > 5.0:
            total_est = elapsed / (job["progress_percent"] / 100.0)
            job["eta_seconds"] = max(0.0, round(total_est - elapsed, 1))

    return {
        "job_id": job["job_id"],
        "filename": job["filename"],
        "status": job["status"],
        "step": job["step"],
        "status_msg": job["step"],
        "progress_percent": job["progress_percent"],
        "elapsed_seconds": job["elapsed_seconds"],
        "eta_seconds": job["eta_seconds"],
        "download_url": job["download_url"],
        "error": job["error"]
    }

@app.get("/jobs/{job_id}/download")
def download_job_result(job_id: str):
    """
    Downloads the converted MP3 output file once status is completed.
    """
    with JOBS_LOCK:
        if job_id not in JOBS_DB:
            raise HTTPException(status_code=404, detail="Job ID not found.")
        job = JOBS_DB[job_id]

    if job["status"] != "completed" or not job["output_file"] or not os.path.exists(job["output_file"]):
        raise HTTPException(status_code=400, detail="Job is not completed yet or file missing.")

    orig_name = os.path.splitext(job["filename"])[0]
    out_filename = f"converted_{orig_name}.mp3"

    return FileResponse(
        path=job["output_file"],
        filename=out_filename,
        media_type="audio/mpeg"
    )

# ---------------------------------------------------------
# SYNCHRONOUS ENDPOINTS (FOR BACKWARD COMPATIBILITY)
# ---------------------------------------------------------

@app.post("/convert")
async def convert_audio(
    file: UploadFile = File(...),
    threshold: float = Form(0.84),
    target_gender: str = Form("auto")
):
    if not os.path.exists(PROFILE_PATH):
        raise HTTPException(status_code=400, detail="Target speaker profile not found.")

    target_embedding = np.load(PROFILE_PATH)
    
    with tempfile.NamedTemporaryFile(delete=False, suffix=os.path.splitext(file.filename)[1]) as temp_in:
        shutil.copyfileobj(file.file, temp_in)
        temp_in_path = temp_in.name

    temp_out_path = temp_in_path + "_converted.mp3"

    try:
        process_audio_file(
            input_file=temp_in_path,
            output_file=temp_out_path,
            target_embedding=target_embedding,
            similarity_threshold=threshold,
            target_gender=target_gender
        )

        return FileResponse(
            path=temp_out_path,
            filename=f"converted_{os.path.splitext(file.filename)[0]}.mp3",
            media_type="audio/mpeg"
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Pipeline processing error: {str(e)}")
    finally:
        if os.path.exists(temp_in_path):
            try: os.remove(temp_in_path)
            except Exception: pass

@app.post("/convert-comparison")
async def convert_comparison(
    file: UploadFile = File(...),
    threshold: float = Form(0.84),
    target_gender: str = Form("auto")
):
    if not os.path.exists(PROFILE_PATH):
        raise HTTPException(status_code=400, detail="Target speaker profile not found.")

    target_embedding = np.load(PROFILE_PATH)
    
    with tempfile.NamedTemporaryFile(delete=False, suffix=os.path.splitext(file.filename)[1]) as temp_in:
        shutil.copyfileobj(file.file, temp_in)
        temp_in_path = temp_in.name

    temp_out_path = temp_in_path + "_converted.mp3"
    temp_comp_path = temp_in_path + "_comparison.mp3"

    try:
        process_audio_file(
            input_file=temp_in_path,
            output_file=temp_out_path,
            target_embedding=target_embedding,
            similarity_threshold=threshold,
            target_gender=target_gender
        )

        create_comparison_file(temp_in_path, temp_out_path, temp_comp_path)

        return FileResponse(
            path=temp_comp_path,
            filename=f"comparison_{os.path.splitext(file.filename)[0]}.mp3",
            media_type="audio/mpeg"
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Comparison pipeline error: {str(e)}")
    finally:
        for p in [temp_in_path, temp_out_path]:
            if os.path.exists(p):
                try: os.remove(p)
                except Exception: pass

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8080))
    uvicorn.run(app, host="0.0.0.0", port=port)
