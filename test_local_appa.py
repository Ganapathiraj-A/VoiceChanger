import os
import time
import librosa
import librosa.display
import soundfile as sf
import numpy as np
import matplotlib.pyplot as plt
from process_pipeline import process_audio_file

input_file = "input/appa_1.mp4"
output_file_converted = "output/appa_1_converted_female.mp3"
plot_file = "output/appa_1_spectrogram.png"

os.makedirs("output", exist_ok=True)

print(f"==================================================")
print(f"  LOCAL DIAGNOSTIC TEST: {input_file}")
print(f"==================================================")

# Run full Voice Conversion (without target profile to test conversion)
start_time = time.time()
process_audio_file(
    input_file=input_file,
    output_file=output_file_converted,
    target_embedding=np.zeros(195, dtype=np.float32),  # Force conversion test
    target_gender="female"
)
elapsed_time = time.time() - start_time
print(f"\n[+] Pipeline Processing Completed in: {elapsed_time:.2f} seconds!")

# Extract Acoustic Metrics
print("\n[+] Extracting Acoustic & Pitch Metrics...")
y_in, sr_in = librosa.load(input_file, sr=16000, mono=True)
y_out, sr_out = librosa.load(output_file_converted, sr=16000, mono=True)

# Pitch (F0) Analysis using fast YIN algorithm
f0_in = librosa.yin(y_in, fmin=75, fmax=500, sr=sr_in)
f0_out = librosa.yin(y_out, fmin=75, fmax=500, sr=sr_out)

mean_f0_in = np.mean(f0_in)
mean_f0_out = np.mean(f0_out)

# Spectral Centroid (Timbre/Formant Proxy)
cent_in = np.mean(librosa.feature.spectral_centroid(y=y_in, sr=sr_in))
cent_out = np.mean(librosa.feature.spectral_centroid(y=y_out, sr=sr_out))

# RMS Energy
rms_out = np.mean(librosa.feature.rms(y=y_out))

print(f"\n--------------------------------------------------")
print(f"  ACOUSTIC DIAGNOSTIC REPORT")
print(f"--------------------------------------------------")
print(f" • Input File Duration:    {len(y_in)/sr_in:.2f} s ({len(y_in)} samples)")
print(f" • Output File Duration:   {len(y_out)/sr_out:.2f} s")
print(f" • Processing Time:        {elapsed_time:.2f} seconds")
print(f" • Original Pitch (F0):    {mean_f0_in:.1f} Hz")
print(f" • Converted Pitch (F0):   {mean_f0_out:.1f} Hz (Shift: {mean_f0_out - mean_f0_in:+.1f} Hz)")
print(f" • Original Spectral Centroid:  {cent_in:.1f} Hz")
print(f" • Converted Spectral Centroid: {cent_out:.1f} Hz")
print(f" • Signal RMS Energy:      {rms_out:.4f}")

# Generate Comparison Plot
print("\n[+] Generating Comparison Spectrogram Plot...")
plt.figure(figsize=(12, 8))

# Subplot 1: Original Input Spectrogram
plt.subplot(2, 2, 1)
S_in = librosa.amplitude_to_db(np.abs(librosa.stft(y_in[:int(10*sr_in)])), ref=np.max)
librosa.display.specshow(S_in, sr=sr_in, x_axis='time', y_axis='hz')
plt.colorbar(format='%+2.0f dB')
plt.title("Original Input Audio (appa_1.mp4)")

# Subplot 2: Converted Output Spectrogram
plt.subplot(2, 2, 2)
S_out = librosa.amplitude_to_db(np.abs(librosa.stft(y_out[:int(10*sr_out)])), ref=np.max)
librosa.display.specshow(S_out, sr=sr_out, x_axis='time', y_axis='hz')
plt.colorbar(format='%+2.0f dB')
plt.title("Converted Output Audio (appa_1_converted_female.mp3)")

# Subplot 3: Original Waveform
plt.subplot(2, 2, 3)
librosa.display.waveshow(y_in[:int(10*sr_in)], sr=sr_in, color='blue')
plt.title("Original Waveform (First 10s)")
plt.xlabel("Time (s)")

# Subplot 4: Converted Waveform
plt.subplot(2, 2, 4)
librosa.display.waveshow(y_out[:int(10*sr_out)], sr=sr_out, color='purple')
plt.title("Converted Waveform (First 10s)")
plt.xlabel("Time (s)")

plt.tight_layout()
plt.savefig(plot_file, dpi=150)
plt.close()

print(f"[✓] Saved visualization comparison plot to: {plot_file}")
