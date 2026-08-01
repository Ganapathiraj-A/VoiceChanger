import os
import time
import librosa
import numpy as np
import soundfile as sf
from process_pipeline import process_audio_file
from voice_converter import convert_gender_auto

input_file = "input/appa_1.mp4"
profiles = ["tamil_female", "tamil_male", "english_female", "english_male"]

os.makedirs("output", exist_ok=True)

print("==================================================")
print("  TESTING FULL VOICE CONVERSION ON ALL PROFILES")
print(f"  Input File: {input_file}")
print("==================================================")

results = []

for prof in profiles:
    out_file = f"output/appa_1_{prof}_full.mp3"
    print(f"\n[+] Processing full conversion for target profile: {prof}...")
    
    start_t = time.time()
    # Execute high-clarity TD-PSOLA voice conversion for the full track
    target_g = "female" if "female" in prof else "male"
    
    # Extract mono 16kHz WAV from input_file
    y, sr = librosa.load(input_file, sr=16000, mono=True)
    temp_wav = f"output/temp_{prof}.wav"
    temp_out_wav = f"output/temp_{prof}_out.wav"
    sf.write(temp_wav, y, sr)
    
    convert_gender_auto(temp_wav, temp_out_wav, target_gender=target_g)
    
    # Export to MP3
    y_conv, sr_conv = librosa.load(temp_out_wav, sr=16000, mono=True)
    sf.write(out_file, y_conv, sr_conv)
    
    # Cleanup temp WAVs
    if os.path.exists(temp_wav): os.remove(temp_wav)
    if os.path.exists(temp_out_wav): os.remove(temp_out_wav)
    
    elapsed = time.time() - start_t
    
    # Calculate acoustic metrics
    f0_out = librosa.yin(y_conv, fmin=75, fmax=500, sr=sr_conv)
    mean_f0 = np.mean(f0_out)
    centroid = np.mean(librosa.feature.spectral_centroid(y=y_conv, sr=sr_conv))
    
    results.append({
        "profile": prof,
        "output_file": out_file,
        "elapsed_s": round(elapsed, 2),
        "mean_f0_hz": round(mean_f0, 1),
        "centroid_hz": round(centroid, 1),
        "duration_s": round(len(y_conv)/sr_conv, 2)
    })

print("\n--------------------------------------------------")
print("  SUMMARY OF ALL TARGET PROFILE CONVERSIONS")
print("--------------------------------------------------")
for r in results:
    print(f" • Profile: {r['profile']:<15} | File: {r['output_file']} | Time: {r['elapsed_s']}s | F0: {r['mean_f0_hz']} Hz | Centroid: {r['centroid_hz']} Hz")
print("--------------------------------------------------")
