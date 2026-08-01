import requests
import time
import sys
import os

CLOUD_URL = "https://voice-changer-service-ffboj7vvya-el.a.run.app"
FILE_PATH = "/home/ganapathiraj/Code/Android/VoiceChanger/input/appa_1.mp4"

if not os.path.exists(FILE_PATH):
    print(f"File not found: {FILE_PATH}")
    sys.exit(1)

print(f"1. Connecting to Cloud Backend ({CLOUD_URL})...")
resp = requests.get(f"{CLOUD_URL}/profiles")
print("Profiles endpoint response:", resp.status_code, resp.json())

print("\n2. Submitting appa_1.mp4 for Speaker Diarization / Analysis...")
with open(FILE_PATH, "rb") as f:
    files = {"file": ("appa_1.mp4", f, "video/mp4")}
    resp = requests.post(f"{CLOUD_URL}/diarize/preview/submit", files=files)

print("Preview submission response:", resp.status_code, resp.json())
preview_id = resp.json()["preview_id"]

print(f"\n3. Polling preview status for preview_id: {preview_id}...")
start_time = time.time()
preview_data = None
while True:
    res = requests.get(f"{CLOUD_URL}/diarize/preview/status/{preview_id}")
    data = res.json()
    status = data.get("status")
    pct = data.get("progress_percent", 0.0)
    step = data.get("step", "")
    elapsed = round(time.time() - start_time, 1)
    print(f"  [{elapsed}s] Progress: {pct}% | Step: {step}")
    if status == "completed":
        preview_data = data
        break
    elif status == "failed":
        print("Preview failed:", data.get("error"))
        sys.exit(1)
    time.sleep(2)

speaker_a = preview_data["speaker_a"]
speaker_b = preview_data["speaker_b"]

print("\nSpeaker Diarization Stats:")
print(f"  - Speaker A (id=0): {speaker_a['speech_percent']}% speech ({speaker_a['duration_seconds']}s)")
print(f"  - Speaker B (id=1): {speaker_b['speech_percent']}% speech ({speaker_b['duration_seconds']}s)")

# Choose speaker with least talk time to be preserved
if speaker_a["speech_percent"] < speaker_b["speech_percent"]:
    preserve_cluster = 0
    preserved_speaker_name = "Speaker A"
    least_pct = speaker_a["speech_percent"]
else:
    preserve_cluster = 1
    preserved_speaker_name = "Speaker B"
    least_pct = speaker_b["speech_percent"]

print(f"\nSelected Speaker to PRESERVE: {preserved_speaker_name} (cluster={preserve_cluster}) with least talk time ({least_pct}%)")

print("\n4. Submitting Full Audio Conversion Job to Cloud Backend...")
with open(FILE_PATH, "rb") as f:
    files = {"file": ("appa_1.mp4", f, "video/mp4")}
    data = {
        "preserve_speaker_cluster": preserve_cluster,
        "target_profile_name": "tamil_female",
        "threshold": 0.84,
        "target_gender": "auto"
    }
    resp = requests.post(f"{CLOUD_URL}/jobs/submit", files=files, data=data)

print("Job submission response:", resp.status_code, resp.json())
job_id = resp.json()["job_id"]

print(f"\n5. Polling conversion job status for job_id: {job_id}...")
start_time = time.time()
job_data = None
while True:
    res = requests.get(f"{CLOUD_URL}/jobs/{job_id}")
    data = res.json()
    status = data.get("status")
    pct = data.get("progress_percent", 0.0)
    step = data.get("step", "")
    eta = data.get("eta_seconds", 0.0)
    elapsed = round(time.time() - start_time, 1)
    print(f"  [{elapsed}s] Progress: {pct}% | ETA: {eta}s | Step: {step}")
    if status == "completed":
        job_data = data
        break
    elif status == "failed":
        print("Job failed:", data.get("error"))
        sys.exit(1)
    time.sleep(3)

print("\nConversion Job Completed!")
download_url = f"{CLOUD_URL}{job_data['download_url']}"
print(f"Download URL: {download_url}")

output_local_path = "/home/ganapathiraj/Code/Android/VoiceChanger/output/appa_1_cloud_converted_least_speaker.mp3"
os.makedirs("/home/ganapathiraj/Code/Android/VoiceChanger/output", exist_ok=True)
print(f"Downloading converted audio file to {output_local_path}...")
r = requests.get(download_url)
with open(output_local_path, "wb") as f:
    f.write(r.content)

print(f"Successfully saved converted file! Size: {os.path.getsize(output_local_path)} bytes.")
