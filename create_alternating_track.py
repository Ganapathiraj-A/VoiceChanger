import os
from pydub import AudioSegment

input_original_path = "/home/ganapathiraj/Code/Android/VoiceChanger/input/appa_1.mp4"
input_converted_path = "/home/ganapathiraj/Code/Android/VoiceChanger/output/appa_1_cloud_converted_speaker_a.mp3"
output_path = "/home/ganapathiraj/Code/Android/VoiceChanger/output/appa_1_alternating_10s_comparison.mp3"

if not os.path.exists(input_original_path) or not os.path.exists(input_converted_path):
    print("Required input or converted file not found.")
    exit(1)

print("Loading original and converted audio files...")
orig = AudioSegment.from_file(input_original_path)
conv = AudioSegment.from_file(input_converted_path)

# Boost volume by +4.0 dB for maximum clarity and louder playback
orig = orig + 4.0
conv = conv + 4.0

# Match lengths
min_dur_ms = min(len(orig), len(conv))
orig = orig[:min_dur_ms]
conv = conv[:min_dur_ms]

chunk_ms = 10000  # 10 seconds
alternating_audio = AudioSegment.empty()

pos_ms = 0
chunk_count = 0

print(f"Slicing audio into 10s alternating blocks (Total Duration: {min_dur_ms / 1000.0:.1f}s)...")
while pos_ms < min_dur_ms:
    end_ms = min(pos_ms + chunk_ms, min_dur_ms)
    
    if chunk_count % 2 == 0:
        # Original block
        segment = orig[pos_ms:end_ms]
        label = "Original Audio"
    else:
        # Converted block
        segment = conv[pos_ms:end_ms]
        label = "Converted Audio"
        
    print(f"  - Slice {chunk_count + 1}: [{pos_ms/1000.0:.1f}s - {end_ms/1000.0:.1f}s] -> {label}")
    alternating_audio += segment
    
    pos_ms = end_ms
    chunk_count += 1

print(f"\nExporting alternating MP3 to: {output_path}...")
os.makedirs(os.path.dirname(output_path), exist_ok=True)
alternating_audio.export(output_path, format="mp3", bitrate="192k")

print(f"[✓] Created alternating track successfully! File size: {os.path.getsize(output_path)} bytes.")
