import os
import tempfile
import subprocess
import soundfile as sf
import numpy as np
import librosa
from pydub import AudioSegment

try:
    import torch
    import speechbrain as sb
    from speechbrain.inference.speaker import EncoderClassifier
    SPEECHBRAIN_AVAILABLE = True
except ImportError:
    SPEECHBRAIN_AVAILABLE = False

try:
    from pyannote.audio import Pipeline
    PYANNOTE_AVAILABLE = True
except ImportError:
    PYANNOTE_AVAILABLE = False


_speaker_encoder = None
_pyannote_pipeline = None


def get_speaker_encoder():
    """
    Lazy-loads and caches SpeechBrain ECAPA-TDNN embedding encoder model.
    """
    global _speaker_encoder
    if _speaker_encoder is None:
        if SPEECHBRAIN_AVAILABLE:
            device = "cpu"
            print(f"[SpeakerUtils] Loading SpeechBrain ECAPA-TDNN model on device: {device}...")
            _speaker_encoder = EncoderClassifier.from_hparams(
                source="speechbrain/spkrec-ecapa-voxceleb",
                savedir="models/spkrec-ecapa-voxceleb",
                run_opts={"device": device}
            )
        else:
            print("[SpeakerUtils] Warning: SpeechBrain unavailable.")
    return _speaker_encoder


def extract_embedding(audio_data, sr=16000):
    """
    Extracts a 192-dimensional speaker embedding vector from raw mono audio numpy array.
    """
    encoder = get_speaker_encoder()
    if encoder is not None:
        try:
            tensor = torch.tensor(audio_data).unsqueeze(0)
            with torch.no_grad():
                emb = encoder.encode_batch(tensor)
                emb = emb.squeeze().cpu().numpy()
            return emb
        except Exception as e:
            print(f"[SpeakerUtils] SpeechBrain extraction error: {e}")
            
    # Fallback to spectral feature statistics (MFCCs + Delta statistics)
    mfcc = librosa.feature.mfcc(y=audio_data, sr=sr, n_mfcc=20)
    delta = librosa.feature.delta(mfcc)
    stats = np.hstack([np.mean(mfcc, axis=1), np.std(mfcc, axis=1), np.mean(delta, axis=1), np.std(delta, axis=1)])
    return stats


def extract_embeddings_batch(audio_segments_list, sr=16000):
    """
    Ultra-fast batch extraction of 192d speaker embeddings using mini-batched PyTorch passes.
    """
    encoder = get_speaker_encoder()
    if encoder is not None and len(audio_segments_list) > 0:
        try:
            target_len = int(2.0 * sr)
            tensors = []
            for seg in audio_segments_list:
                a = seg[:target_len]
                if len(a) < target_len:
                    a = np.pad(a, (0, target_len - len(a)))
                tensors.append(torch.tensor(a, dtype=torch.float32))
            
            all_embs = []
            batch_size = 16
            for i in range(0, len(tensors), batch_size):
                sub_batch = torch.stack(tensors[i:i + batch_size])
                with torch.no_grad():
                    emb = encoder.encode_batch(sub_batch)
                    emb = emb.squeeze(1).cpu().numpy()
                    if len(emb.shape) == 1:
                        emb = np.expand_dims(emb, axis=0)
                    all_embs.append(emb)
            return np.vstack(all_embs)
        except Exception as e:
            print(f"[SpeakerUtils] Batch extraction exception: {e}")
            
    return np.array([extract_embedding(s[:int(2.0 * sr)], sr=sr) for s in audio_segments_list])


def cosine_similarity(emb1, emb2):
    """
    Computes cosine similarity between two embedding vectors (-1.0 to +1.0).
    """
    if emb1 is None or emb2 is None or len(emb1) == 0 or len(emb2) == 0:
        return 0.0
    if len(emb1) != len(emb2):
        min_dim = min(len(emb1), len(emb2))
        emb1 = emb1[:min_dim]
        emb2 = emb2[:min_dim]
    norm1 = np.linalg.norm(emb1)
    norm2 = np.linalg.norm(emb2)
    if norm1 == 0 or norm2 == 0:
        return 0.0
    return float(np.dot(emb1, emb2) / (norm1 * norm2))


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


def fast_vad_split(y, top_db=30, frame_length=2048, hop_length=512):
    """
    Vectorized RMS energy VAD thresholding for split detection.
    """
    rms = librosa.feature.rms(y=y, frame_length=frame_length, hop_length=hop_length)[0]
    db = librosa.amplitude_to_db(rms, ref=np.max)
    non_silent = db > -top_db
    
    if not np.any(non_silent):
        return [(0, len(y))]
        
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
    if len(y) == 0:
        return y
        
    try:
        from scipy.signal import butter, sosfilt
        sos = butter(4, [highpass_cutoff, lowpass_cutoff], btype='bandpass', fs=sr, output='sos')
        filtered_y = sosfilt(sos, y)
        
        try:
            import noisereduce as nr
            reduced_y = nr.reduce_noise(y=filtered_y, sr=sr, stationary=True, prop_decrease=0.75)
            return reduced_y.astype(np.float32)
        except ImportError:
            stft = librosa.stft(filtered_y, n_fft=1024, hop_length=256)
            magnitude, phase = librosa.magphase(stft)
            noise_floor = np.percentile(magnitude, 10, axis=1, keepdims=True)
            mask = magnitude > (noise_floor * 1.4)
            clean_magnitude = magnitude * mask.astype(np.float32)
            clean_stft = clean_magnitude * phase
            clean_y = librosa.istft(clean_stft, hop_length=256, length=len(y))
            return clean_y.astype(np.float32)
    except Exception as e:
        print(f"[NoiseFilter] Exception during noise suppression: {e}")
        return y


def generate_speaker_previews(audio_path: str, output_dir: str, progress_callback=None):
    """
    Diarizes an input audio recording into 2 speakers and extracts representative
    5-second audio preview clips for Speaker A (Cluster 0) and Speaker B (Cluster 1).
    Returns dict with stats and file paths for speaker_a and speaker_b sample clips.
    """
    os.makedirs(output_dir, exist_ok=True)
    if progress_callback: progress_callback(10.0, "Extracting voice activity (VAD)...")
    segments, full_y, sr = segment_audio_vad(audio_path)
    valid_segs = [s for s in segments if len(s['audio_data'])/sr >= 0.3]
    
    if len(valid_segs) == 0:
        valid_segs = segments
        
    preview_segs = valid_segs[:40] if len(valid_segs) > 40 else valid_segs
    durations = np.array([s['end_sec'] - s['start_sec'] for s in preview_segs])
    total_dur = np.sum(durations)
    
    if progress_callback: progress_callback(25.0, f"Extracting embeddings for {len(preview_segs)} segments...")
    embeddings = []
    for idx, s in enumerate(preview_segs):
        emb = extract_embedding(s['audio_data'][:int(2.0*sr)], sr=sr)
        embeddings.append(emb)
        if progress_callback and len(preview_segs) > 0:
            pct = 25.0 + ((idx + 1) / len(preview_segs)) * 55.0
            progress_callback(pct, f"Analyzing voice features ({idx+1}/{len(preview_segs)})...")

    embeddings = np.array(embeddings)
    
    if progress_callback: progress_callback(85.0, "Clustering speakers (Speaker A vs Speaker B)...")
    from sklearn.cluster import KMeans
    n_clusters = min(2, len(preview_segs))
    if n_clusters >= 2:
        km = KMeans(n_clusters=2, random_state=42, n_init=20).fit(embeddings)
        cluster_labels = km.labels_
        # Canonical cluster alignment: Cluster 0 is ALWAYS the first speaker who speaks in the recording
        if len(cluster_labels) > 0 and cluster_labels[0] != 0:
            cluster_labels = 1 - cluster_labels
    else:
        cluster_labels = np.zeros(len(preview_segs), dtype=int)

    spk_audio = {0: [], 1: []}
    spk_durs = {0: 0.0, 1: 0.0}
    
    for i, seg in enumerate(preview_segs):
        cid = cluster_labels[i]
        spk_durs[cid] += (seg['end_sec'] - seg['start_sec'])
        if (len(spk_audio[cid]) * (1.0 / sr)) < 6.0:  # Max 6s sample clip
            spk_audio[cid].append(seg['audio_data'])

    pct_0 = round((spk_durs[0] / total_dur) * 100.0, 1) if total_dur > 0 else 50.0
    pct_1 = round((spk_durs[1] / total_dur) * 100.0, 1) if total_dur > 0 else 50.0

    if progress_callback: progress_callback(92.0, "Generating sample MP3 audio clips...")
    audio_a = np.concatenate(spk_audio[0]) if len(spk_audio[0]) > 0 else full_y[:int(5.0*sr)]
    audio_b = np.concatenate(spk_audio[1]) if len(spk_audio[1]) > 0 else full_y[:int(5.0*sr)]

    path_a = os.path.join(output_dir, "speaker_a.mp3")
    path_b = os.path.join(output_dir, "speaker_b.mp3")

    wav_a = os.path.join(output_dir, "speaker_a.wav")
    wav_b = os.path.join(output_dir, "speaker_b.wav")
    
    sf.write(wav_a, audio_a, sr)
    sf.write(wav_b, audio_b, sr)

    try:
        AudioSegment.from_wav(wav_a).export(path_a, format="mp3", bitrate="128k")
        AudioSegment.from_wav(wav_b).export(path_b, format="mp3", bitrate="128k")
    except Exception:
        sf.write(path_a, audio_a, sr)
        sf.write(path_b, audio_b, sr)

    if progress_callback: progress_callback(100.0, "Speaker analysis completed!")

    return {
        "speaker_a_pct": pct_0,
        "speaker_b_pct": pct_1,
        "speaker_a_dur_s": round(spk_durs[0], 1),
        "speaker_b_dur_s": round(spk_durs[1], 1),
        "speaker_a_path": path_a,
        "speaker_b_path": path_b
    }
