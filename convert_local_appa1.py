import os
import time
from process_pipeline import process_audio_file
from speaker_utils import segment_audio_vad, extract_embeddings_batch
from sklearn.cluster import KMeans
import numpy as np

input_file = "/home/ganapathiraj/Downloads/appa_1.mp4"
output_file = "/home/ganapathiraj/Code/Android/VoiceChanger/output/appa_1_local_converted_least_speaker.mp3"

print("1. Running local speaker diarization on appa_1.mp4...")
segments, full_y, sr = segment_audio_vad(input_file)
valid_segments = [s for s in segments if (len(s['audio_data']) / sr) >= 0.4]

audio_list = [seg['audio_data'] for seg in valid_segments]
embeddings_arr = extract_embeddings_batch(audio_list, sr=sr)

kmeans = KMeans(n_clusters=2, random_state=42, n_init=20).fit(embeddings_arr)
cluster_labels = kmeans.labels_

durations = np.array([seg['end_sec'] - seg['start_sec'] for seg in valid_segments])
tot_dur = np.sum(durations)

dur_0 = np.sum(durations[cluster_labels == 0])
dur_1 = np.sum(durations[cluster_labels == 1])

print(f"Speaker A (cluster 0): {dur_0:.1f}s ({dur_0/tot_dur*100:.1f}%)")
print(f"Speaker B (cluster 1): {dur_1:.1f}s ({dur_1/tot_dur*100:.1f}%)")

least_cluster = 0 if dur_0 < dur_1 else 1
print(f"Preserving Speaker Cluster {least_cluster} (least talk time).")

print("\n2. Executing local voice conversion pipeline...")
start = time.time()
process_audio_file(
    input_file=input_file,
    output_file=output_file,
    target_profile_name="tamil_female",
    preserve_speaker_cluster=least_cluster,
    target_gender="female"
)

print(f"\nLocal conversion finished in {time.time() - start:.1f}s!")
print(f"Saved to: {output_file} (Size: {os.path.getsize(output_file)} bytes)")
