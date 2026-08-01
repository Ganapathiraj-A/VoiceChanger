import os
from pydub import AudioSegment
from pydub.generators import Sine

input_original_path = "/home/ganapathiraj/Code/Android/VoiceChanger/input/appa_1.mp4"
input_converted_path = "/home/ganapathiraj/Code/Android/VoiceChanger/output/appa_1_cloud_converted_speaker_a.mp3"
output_path = "/home/ganapathiraj/Code/Android/VoiceChanger/output/appa_1_alternating_10s_with_beeps.mp3"

if not os.path.exists(input_original_path) or not os.path.exists(input_converted_path):
    print("Required input or converted file not found.")
    exit(1)

print("Loading original and converted audio files...")
orig = AudioSegment.from_file(input_original_path)
conv = AudioSegment.from_file(input_converted_path)

# Boost volume by +4.0 dB for louder, clearer playback
orig = orig + 4.0
conv = conv + 4.0

# Generate a crisp 350ms 880Hz beep tone (A5 pitch) with gentle fade out
beep = Sine(880).to_audio_segment(duration=350) - 3.0  # Slightly softer beep so it doesn't hurt ears
beep = beep.fade_in(20).fade_out(40)

# Match audio length
min_dur_ms = min(len(orig), len(conv))
orig = orig[:min_dur_ms]
conv = conv[:min_dur_ms]

chunk_ms = 10000  # 10 seconds
final_audio = AudioSegment.empty()

pos_ms = 0
chunk_count = 0

print(f"Slicing audio into 10s alternating blocks with transition BEEPs...")
while pos_ms < min_dur_ms:
    end_ms = min(pos_ms + chunk_ms, min_dur_ms)
    
    if chunk_count % 2 == 0:
        segment = orig[pos_ms:end_ms]
        label = "Original Audio"
    else:
        segment = conv[pos_ms:end_ms]
        label = "Converted Audio"
        
    print(f"  - Slice {chunk_count + 1}: [{pos_ms/1000.0:.1f}s - {end_ms/1000.0:.1f}s] -> {label}")
    
    if len(final_audio) > 0:
        final_audio += beep
        
    final_audio += segment
    
    pos_ms = end_ms
    chunk_count += 1

print(f"\nExporting alternating MP3 with transition BEEPs to: {output_path}...")
os.makedirs(os.path.dirname(output_path), exist_ok=True)
final_audio.export(output_path, format="mp3", bitrate="192k")

print(f"[✓] Successfully created beep-guided alternating track! File size: {os.path.getsize(output_path)} bytes.")
