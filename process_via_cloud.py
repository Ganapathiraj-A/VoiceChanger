import os
import sys
import glob
import time
import subprocess
import requests

CLOUD_SERVICE_URL = "https://voice-changer-service-ffboj7vvya-el.a.run.app"

def get_gcloud_token():
    try:
        token = subprocess.check_output(["gcloud", "auth", "print-identity-token"], text=True).strip()
        return token
    except Exception as e:
        print(f"[!] Warning: Could not fetch gcloud token: {e}")
        return ""

def process_file_with_progress(input_file, output_file, is_comparison=False):
    token = get_gcloud_token()
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
        
    print(f"\n[🚀 Submitting File] '{os.path.basename(input_file)}' to GCP Cloud API...")
    
    with open(input_file, "rb") as f:
        files = {"file": (os.path.basename(input_file), f, "application/octet-stream")}
        data = {
            "threshold": "0.84",
            "target_gender": "auto",
            "is_comparison": "true" if is_comparison else "false"
        }
        
        response = requests.post(f"{CLOUD_SERVICE_URL}/jobs/submit", headers=headers, files=files, data=data)
        
    if response.status_code != 202:
        print(f"[x] Error submitting job ({response.status_code}): {response.text}")
        return False
        
    res_data = response.json()
    job_id = res_data["job_id"]
    print(f"[✓] Job created on GCP! Job ID: {job_id}")
    
    # Poll job status with live ETA and progress bar
    while True:
        status_res = requests.get(f"{CLOUD_SERVICE_URL}/jobs/{job_id}", headers=headers)
        if status_res.status_code != 200:
            print(f"[x] Error polling job status ({status_res.status_code})")
            time.sleep(2)
            continue
            
        status_data = status_res.json()
        status = status_data["status"]
        pct = status_data["progress_percent"]
        eta = status_data["eta_seconds"]
        elapsed = status_data["elapsed_seconds"]
        step = status_data["step"]
        
        # Format live progress display
        bar_len = 30
        filled = int(bar_len * (pct / 100.0))
        bar = "█" * filled + "-" * (bar_len - filled)
        
        sys.stdout.write(f"\r  [{bar}] {pct:5.1f}% | Elapsed: {elapsed:4.1f}s | ETA: {eta:4.1f}s | {step[:40]:<40}")
        sys.stdout.flush()
        
        if status == "completed":
            print(f"\n[✓] Job {job_id} Completed in {elapsed:.1f}s!")
            download_url = status_data["download_url"]
            dl_res = requests.get(f"{CLOUD_SERVICE_URL}{download_url}", headers=headers)
            if dl_res.status_code == 200:
                with open(output_file, "wb") as out_f:
                    out_f.write(dl_res.content)
                print(f"[✓] File downloaded: {output_file}")
                return True
            else:
                print(f"[x] Download error ({dl_res.status_code})")
                return False
                
        elif status == "failed":
            print(f"\n[x] Job {job_id} Failed: {status_data.get('error')}")
            return False
            
        time.sleep(1.5)

def process_all_files(input_dir="input", output_dir="output", test_dir="test"):
    os.makedirs(output_dir, exist_ok=True)
    os.makedirs(test_dir, exist_ok=True)
    
    input_files = (
        glob.glob(os.path.join(input_dir, "*.mp3")) +
        glob.glob(os.path.join(input_dir, "*.wav")) +
        glob.glob(os.path.join(input_dir, "*.mp4")) +
        glob.glob(os.path.join(input_dir, "*.m4a"))
    )
    
    print(f"==================================================")
    print(f"  GCP Cloud Run Voice Changer (With Real-Time ETA)")
    print(f"==================================================")
    print(f"Target Service: {CLOUD_SERVICE_URL}")
    print(f"Input Files Found: {len(input_files)}\n")
    
    for input_file in input_files:
        base_name = os.path.splitext(os.path.basename(input_file))[0]
        output_file = os.path.join(output_dir, f"{base_name}.mp3")
        comparison_file = os.path.join(test_dir, f"comparison_{base_name}.mp3")
        
        # Process converted track
        process_file_with_progress(input_file, output_file, is_comparison=False)
        
        # Process comparison track
        process_file_with_progress(input_file, comparison_file, is_comparison=True)

if __name__ == "__main__":
    process_all_files()
