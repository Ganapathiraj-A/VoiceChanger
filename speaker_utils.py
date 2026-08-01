import os
import tempfile
import subprocess
import torch
import numpy as np
import librosa
import soundfile as sf
from pydub import AudioSegment

device = "cpu"
_encoder_classifier = None


def get_speaker_encoder():
    """
    Lazy loader for SpeechBrain ECAPA-TDNN speaker embedding classifier.
    """
    global _encoder_classifier
    if _encoder_classifier is None:
        try:
            from speechbrain.inference.speaker import EncoderClassifier
            print(f"[SpeakerUtils] Loading SpeechBrain ECAPA-TDNN model on device: {device}...")
            _encoder_classifier = EncoderClassifier.from_hparams(
                source="speechbrain/spkrec-ecapa-voxceleb",
                savedir="pretrained_models/spkrec-ecapa-voxceleb",
                run_opts={"device": device}
            )
        except Exception as e:
            print(f"[SpeakerUtils] Warning: SpeechBrain model loading error: {e}")
            _encoder_classifier = None
    return _encoder_classifier


def load_audio_mono(audio_path, target_sr=16000):
    try:
        audio_seg = AudioSegment.from_file(audio_path).set_frame_rate(target_sr).set_channels(1)
        samples = np.array(audio_seg.get_array_of_samples(), dtype=np.float32) / 32768.0
        return samples, target_sr
    except Exception:
        y, sr = librosa.load(audio_path, sr=target_sr, mono=True)
        return y, sr


def load_target_profile(path="target_speaker_profile.npy"):
    if os.path.exists(path):
        return np.load(path)
    return np.zeros(195, dtype=np.float32)


def extract_embedding(audio_path_or_ndarray, sr=16000):
    """
    Extracts a speaker embedding vector combining SpeechBrain ECAPA-TDNN 192-dim vector
    with normalized pitch F0 statistics to sharply separate distinct speakers.
    """
    encoder = get_speaker_encoder()
    emb_np = None
    if encoder is not None:
        try:
            if isinstance(audio_path_or_ndarray, str):
                signal, fs = load_audio_mono(audio_path_or_ndarray, target_sr=16000)
            else:
                signal = audio_path_or_ndarray
                fs = sr
            
            wav_tensor = torch.tensor(signal, dtype=torch.float32).unsqueeze(0).to(device)
            with torch.no_grad():
                embeddings = encoder.encode_batch(wav_tensor)
                emb_np = embeddings.squeeze().cpu().numpy()
                norm = np.linalg.norm(emb_np)
                if norm > 0:
                    emb_np = emb_np / norm
        except Exception as e:
            print(f"[SpeakerUtils] SpeechBrain embedding extraction fallback due to: {e}")
    
    if isinstance(audio_path_or_ndarray, str):
        y, sr = load_audio_mono(audio_path_or_ndarray, target_sr=16000)
    else:
        y = audio_path_or_ndarray
        sr = 16000

    # Extract pitch (F0) feature
    try:
        import parselmouth
        from parselmouth.praat import call
        sound = parselmouth.Sound(y, sampling_frequency=sr)
        pitch = sound.to_pitch(pitch_floor=75.0, pitch_ceiling=400.0)
        mean_p = call(pitch, "Get mean", 0, 0, "Hertz")
        std_p = call(pitch, "Get standard deviation", 0, 0, "Hertz")
        if np.isnan(mean_p): mean_p = 0.0
        if np.isnan(std_p): std_p = 0.0
    except Exception:
        mean_p, std_p = 0.0, 0.0

    pitch_feat = np.array([mean_p / 300.0 * 3.0, std_p / 100.0 * 1.5])
    
    if emb_np is not None:
        combined = np.hstack([emb_np, pitch_feat])
        return combined / np.linalg.norm(combined)

    # Fallback to acoustic feature vector (MFCC + Delta + Pitch stats)
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=20)
    mfcc_mean = np.mean(mfcc, axis=1)
    mfcc_std = np.std(mfcc, axis=1)
    delta = librosa.feature.delta(mfcc)
    delta_mean = np.mean(delta, axis=1)
    
    feature_vec = np.hstack([mfcc_mean, mfcc_std, delta_mean, pitch_feat])
    norm = np.linalg.norm(feature_vec)
    if norm > 0:
        feature_vec = feature_vec / norm
    return feature_vec


def cosine_similarity(emb1, emb2):
    """
    Computes cosine similarity between two embedding vectors.
    """
    emb1 = np.asarray(emb1).flatten()
    emb2 = np.asarray(emb2).flatten()
    if len(emb1) != len(emb2):
        # Truncate or pad if dimensions mismatch
        min_len = min(len(emb1), len(emb2))
        emb1 = emb1[:min_len]
        emb2 = emb2[:min_len]
    
    norm1 = np.linalg.norm(emb1)
    norm2 = np.linalg.norm(emb2)
    if norm1 == 0 or norm2 == 0:
        return 0.0
    return float(np.dot(emb1, emb2) / (norm1 * norm2))


def fast_vad_split(y, top_db=30, frame_length=2048, hop_length=2048):
    """
    Instant 1D Reshaped NumPy RMS energy VAD splitter (0.006s for 6.5 min audio).
    """
    if len(y) < hop_length:
        return [(0, len(y))]
    
    num_frames = len(y) // hop_length
    y_trunc = y[:num_frames * hop_length].reshape(num_frames, hop_length)
    rms = np.sqrt(np.mean(y_trunc ** 2, axis=1))
    rms_db = 20.0 * np.log10(np.maximum(rms, 1e-7))
    ref = np.max(rms_db)
    non_silent = rms_db > (ref - top_db)
    
    if not np.any(non_silent):
        return []

    edges = np.diff(non_silent.astype(int))
    starts = np.where(edges == 1)[0] + 1
    ends = np.where(edges == -1)[0] + 1
    
    if non_silent[0]:
        starts = np.r_[0, starts]
    if non_silent[-1]:
        ends = np.r_[ends, len(non_silent)]
        
    intervals = []
    for s, e in zip(starts, ends):
        sample_start = s * hop_length
        sample_end = min(e * hop_length, len(y))
        intervals.append((sample_start, sample_end))
    return intervals


def segment_audio_vad(audio_path, top_db=30, min_speech_duration_ms=300, max_chunk_duration_sec=2.5):
    """
    Splits an audio file into voiced segments, and sub-chunks long speech bursts 
    into 2.0-second windows so rapid back-to-back conversational turn switches are classified accurately.
    Returns a list of dicts: [{'start_sec': float, 'end_sec': float, 'audio_data': np.ndarray, 'sr': int}]
    """
    try:
        import uuid
        temp_wav = os.path.join(tempfile.gettempdir(), f"vad_{uuid.uuid4().hex}.wav")
        cmd = ["ffmpeg", "-y", "-vn", "-i", audio_path, "-ac", "1", "-ar", "16000", temp_wav]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0 and os.path.exists(temp_wav):
            y, sr = sf.read(temp_wav)
            try: os.remove(temp_wav)
            except Exception: pass
            print(f"[VAD] Successfully extracted 16kHz mono WAV via FFmpeg ({len(y)} samples)", flush=True)
        else:
            print(f"[VAD] FFmpeg error: {res.stderr[:200]}", flush=True)
            y, sr = librosa.load(audio_path, sr=16000, mono=True)
    except Exception as e:
        print(f"[VAD] Extraction exception: {e}", flush=True)
        y, sr = librosa.load(audio_path, sr=16000, mono=True)

    intervals = fast_vad_split(y, top_db=top_db, frame_length=2048, hop_length=512)
    
    segments = []
    chunk_samples = int(2.0 * sr)
    min_samples = int((min_speech_duration_ms / 1000.0) * sr)

    for start, end in intervals:
        dur_sec = (end - start) / sr
        if dur_sec <= max_chunk_duration_sec:
            if end - start >= min_samples:
                segments.append({
                    'start_sec': start / sr,
                    'end_sec': end / sr,
                    'audio_data': y[start:end],
                    'sr': sr
                })
        else:
            # Sub-chunk long speech bursts into 2-second sub-windows
            for sub_s in range(start, end, chunk_samples):
                sub_e = min(sub_s + chunk_samples, end)
                if sub_e - sub_s >= int(0.5 * sr):
                    segments.append({
                        'start_sec': sub_s / sr,
                        'end_sec': sub_e / sr,
                        'audio_data': y[sub_s:sub_e],
                        'sr': sr
                    })
                
    return segments, y, sr


def suppress_background_noise(y, sr=16000, highpass_cutoff=80.0, lowpass_cutoff=7600.0):
    """
    Suppresses ambient background noise and isolates human speech frequencies:
    1. Butterworth bandpass filter (80 Hz - 7600 Hz) to eliminate DC rumble, wind, and electrical hiss.
    2. Spectral noise gating / reduction to attenuate stationary background noise.
    """
    if len(y) == 0:
        return y
        
    try:
        from scipy.signal import butter, sosfilt
        # 1. Bandpass filter for human vocal frequency range (80 Hz to 7600 Hz)
        sos = butter(4, [highpass_cutoff, lowpass_cutoff], btype='bandpass', fs=sr, output='sos')
        filtered_y = sosfilt(sos, y)
        
        # 2. Spectral noise reduction (using noisereduce or spectral thresholding)
        try:
            import noisereduce as nr
            reduced_y = nr.reduce_noise(y=filtered_y, sr=sr, stationary=True, prop_decrease=0.75)
            return reduced_y.astype(np.float32)
        except ImportError:
            # Fallback STFT spectral gating
            stft = librosa.stft(filtered_y, n_fft=1024, hop_length=256)
            magnitude, phase = librosa.magphase(stft)
            
            # Estimate noise floor from lowest 10th percentile magnitude frames
            noise_floor = np.percentile(magnitude, 10, axis=1, keepdims=True)
            mask = magnitude > (noise_floor * 1.4)
            clean_magnitude = magnitude * mask.astype(np.float32)
            
            clean_stft = clean_magnitude * phase
            clean_y = librosa.istft(clean_stft, hop_length=256, length=len(y))
            return clean_y.astype(np.float32)
    except Exception as e:
        print(f"[NoiseSuppression] Fallback due to: {e}")
        return y.astype(np.float32)


_pyannote_pipeline = None


def get_pyannote_pipeline():
    """
    Lazy loader for pyannote.audio speaker diarization pipeline.
    """
    global _pyannote_pipeline
    if _pyannote_pipeline is None:
        try:
            from pyannote.audio import Pipeline
            hf_token = os.environ.get("HF_TOKEN", None)
            if hf_token:
                print(f"[Pyannote] Loading pyannote/speaker-diarization-3.1 model...")
                _pyannote_pipeline = Pipeline.from_pretrained(
                    "pyannote/speaker-diarization-3.1",
                    use_auth_token=hf_token
                )
                if torch.cuda.is_available():
                    _pyannote_pipeline.to(torch.device("cuda"))
        except Exception as e:
            print(f"[Pyannote] Notice: pyannote pipeline loading deferred or HF_TOKEN missing ({e})")
            _pyannote_pipeline = None
    return _pyannote_pipeline


def diarize_speakers_pyannote(audio_path):
    """
    Runs pyannote.audio speaker diarization to separate Speaker A and Speaker B turns.
    Returns list of dicts: [{'start_sec': float, 'end_sec': float, 'speaker': str}]
    """
    pipeline = get_pyannote_pipeline()
    turns = []
    if pipeline is not None:
        try:
            diarization = pipeline(audio_path)
            for turn, _, speaker in diarization.itertracks(yield_label=True):
                turns.append({
                    'start_sec': turn.start,
                    'end_sec': turn.end,
                    'speaker': speaker
                })
            print(f"[Pyannote] Diarized {len(turns)} speaker turns.")
            return turns
        except Exception as e:
            print(f"[Pyannote] Diarization execution exception: {e}")
    return turns


