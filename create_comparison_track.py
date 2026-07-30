import os
import sys
import numpy as np
import soundfile as sf
from pydub import AudioSegment

def generate_beep(frequency_hz=880, duration_sec=0.25, sample_rate=16000, amplitude=0.4):
    """Generates a clean sine wave audio beep with fade in/out."""
    t = np.linspace(0, duration_sec, int(sample_rate * duration_sec), False)
    sine = amplitude * np.sin(2 * np.pi * frequency_hz * t)
    
    # 5ms fade in and out to avoid clicks
    fade_samples = int(0.005 * sample_rate)
    fade_in = np.linspace(0, 1, fade_samples)
    fade_out = np.linspace(1, 0, fade_samples)
    sine[:fade_samples] *= fade_in
    sine[-fade_samples:] *= fade_out
    
    return sine

def create_comparison_file(input_a_path, input_b_path, output_path, switch_interval_sec=10.0):
    """
    Creates a single comparison MP3 file that alternates between:
    - Track A (Original) for 10 seconds
    - High Beep (880 Hz)
    - Track B (Converted) for 10 seconds
    - Low Beep (440 Hz)
    """
    print(f"[Comparison Generator] Loading Track A: {input_a_path}")
    print(f"[Comparison Generator] Loading Track B: {input_b_path}")
    
    # Load audio tracks as 16kHz mono numpy arrays
    seg_a = AudioSegment.from_file(input_a_path).set_frame_rate(16000).set_channels(1)
    seg_b = AudioSegment.from_file(input_b_path).set_frame_rate(16000).set_channels(1)
    
    y_a = np.array(seg_a.get_array_of_samples(), dtype=np.float32) / 32768.0
    y_b = np.array(seg_b.get_array_of_samples(), dtype=np.float32) / 32768.0
    
    sr = 16000
    min_len = min(len(y_a), len(y_b))
    
    y_a = y_a[:min_len]
    y_b = y_b[:min_len]
    
    beep_to_b = generate_beep(frequency_hz=880, duration_sec=0.25, sample_rate=sr) # High beep before B
    beep_to_a = generate_beep(frequency_hz=440, duration_sec=0.25, sample_rate=sr) # Low beep before A
    
    out_samples = []
    chunk_samples = int(switch_interval_sec * sr)
    
    pos = 0
    seg_idx = 0
    
    while pos < min_len:
        end_pos = min(pos + chunk_samples, min_len)
        
        if seg_idx % 2 == 0:
            # Track A (Original)
            out_samples.append(y_a[pos:end_pos])
            if end_pos < min_len:
                out_samples.append(beep_to_b) # Beep before switching to B
        else:
            # Track B (Converted)
            out_samples.append(y_b[pos:end_pos])
            if end_pos < min_len:
                out_samples.append(beep_to_a) # Beep before switching to A
                
        pos = end_pos
        seg_idx += 1
        
    final_y = np.concatenate(out_samples)
    
    # Ensure output directory exists
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    temp_wav = output_path + ".temp.wav"
    sf.write(temp_wav, final_y, sr)
    
    # Convert to MP3
    out_audio = AudioSegment.from_wav(temp_wav)
    out_audio.export(output_path, format="mp3", bitrate="192k")
    
    if os.path.exists(temp_wav):
        os.remove(temp_wav)
        
    print(f"[✓] Created comparison MP3: {output_path} (Duration: {len(final_y)/sr:.1f}s)")

if __name__ == "__main__":
    track_a = "Sample/original_WhatsApp Audio 2026-07-28 at 10.43.52.mp4"
    track_b = "output/original_WhatsApp Audio 2026-07-28 at 10.43.52.mp3"
    out_file = "test/comparison_alternating_10s.mp3"
    
    if len(sys.argv) >= 3:
        track_a = sys.argv[1]
        track_b = sys.argv[2]
    if len(sys.argv) >= 4:
        out_file = sys.argv[3]
        
    create_comparison_file(track_a, track_b, out_file)
