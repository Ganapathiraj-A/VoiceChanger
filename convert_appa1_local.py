import os
import time
import numpy as np
import librosa
from process_pipeline import process_audio_file

input_file = "input/appa_1.mp4"
output_file = "output/appa_1_local_final.mp3"
profile_path = "target_speaker_profile.npy"

print("==================================================")
print("  LOCAL CONVERSION OF appa_1.mp4")
print(f"  Input:   {input_file}")
print(f"  Output:  {output_file}")
print(f"  Profile: {profile_path}")
print("==================================================")

start_t = time.time()
target_emb = np.load(profile_path) if os.path.exists(profile_path) else None

process_audio_file(
    input_file=input_file,
    output_file=output_file,
    target_embedding=target_emb,
    similarity_threshold=0.50,
    target_gender="female"
)

elapsed = time.time() - start_t

y_out, sr_out = librosa.load(output_file, sr=16000, mono=True)
dur_s = len(y_out) / sr_out

print("\n--------------------------------------------------")
print("  LOCAL CONVERSION COMPLETED")
print("--------------------------------------------------")
print(f" • Output File:      {output_file}")
print(f" • Duration:         {dur_s:.2f} seconds")
print(f" • Processing Time:  {elapsed:.2f} seconds")
print("--------------------------------------------------")
