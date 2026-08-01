import os
import requests
from speaker_utils import generate_speaker_previews

print("==================================================")
print("  TESTING DIARIZATION PREVIEW GENERATION")
print("==================================================")

input_file = "input/appa_1.mp4"
output_dir = "output/preview_test"

stats = generate_speaker_previews(input_file, output_dir)

print("\n--- PREVIEW RESULTS ---")
print(f" • Speaker A Speech Time: {stats['speaker_a_pct']}% ({stats['speaker_a_dur_s']}s)")
print(f" • Speaker B Speech Time: {stats['speaker_b_pct']}% ({stats['speaker_b_dur_s']}s)")
print(f" • Speaker A Audio Sample: {stats['speaker_a_path']} (Exists: {os.path.exists(stats['speaker_a_path'])})")
print(f" • Speaker B Audio Sample: {stats['speaker_b_path']} (Exists: {os.path.exists(stats['speaker_b_path'])})")
print("==================================================")
