import os
import glob
import time
import tempfile
import numpy as np
import soundfile as sf
import torch
from pydub import AudioSegment
from tqdm import tqdm

from speaker_utils import (
    segment_audio_vad,
    extract_embedding,
    cosine_similarity,
    suppress_background_noise
)
from voice_converter import convert_gender_auto

torch.set_num_threads(2)

def process_audio_file(
    input_file: str,
    output_file: str,
    target_embedding: np.ndarray = None,
    target_profile_name: str = None,
    preserve_speaker_cluster: int = None,
    similarity_threshold: float = 0.50,
    target_gender: str = "female",
    progress_callback = None
):
    """
    High-Performance Voice Conversion Pipeline:
    1. Background Noise Suppression & Speech Isolation (80Hz-7.6kHz bandpass & spectral gating)
    2. Fast vectorized VAD sample segmentation
    3. Speaker embedding classification per segment against target speaker profile
    4. Smooth 5ms edge crossfading & final MP3 output export
    """
    if target_embedding is None:
        if target_profile_name:
            prof_path = os.path.join("target_profiles", f"{target_profile_name}.npy")
            if os.path.exists(prof_path):
                target_embedding = np.load(prof_path)
                print(f"[Pipeline] Loaded target profile: {prof_path}")
        if target_embedding is None and os.path.exists("target_speaker_profile.npy"):
            target_embedding = np.load("target_speaker_profile.npy")
        
    def _report_progress(pct, msg):
        if progress_callback:
            try: progress_callback(pct, msg)
            except Exception: pass

    _report_progress(5.0, "Running Voice Activity Detection & Noise Suppression...")
    print(f"\n==================================================")
    print(f"  PROCESSING FILE: {os.path.basename(input_file)}")
    print(f"==================================================")
    
    # Load VAD segments
    print("[1/4] Running Voice Activity Detection & Background Noise Filtering...")
    segments, full_y, sr = segment_audio_vad(input_file)
    print(f"   -> Found {len(segments)} speech segments.")
    
    # Apply background noise suppression to isolate human voices
    full_y = suppress_background_noise(full_y, sr=sr)
    
    if len(segments) == 0:
        print("[!] No speech segments detected. Exporting original audio.")
        audio = AudioSegment.from_file(input_file)
        audio.export(output_file, format="mp3", bitrate="192k")
        _report_progress(100.0, "Completed (No speech detected)")
        return
        
    print("[2/4] Classifying speakers and transforming voices...")
    _report_progress(15.0, "Classifying speaker embeddings...")
    
    temp_dir = tempfile.mkdtemp(prefix="voice_changer_")
    out_audio = full_y.copy()
    
    # Filter valid segments >= 0.4 seconds
    valid_segments = [s for s in segments if (len(s['audio_data']) / sr) >= 0.4]
    if len(valid_segments) == 0:
        valid_segments = segments

    has_target_profile = (target_embedding is not None and np.any(target_embedding != 0))
    tot_segs = len(valid_segments)
    classified = []

    if not has_target_profile:
        print("[!] No pre-recorded target voice profile found. Converting all speech segments.")
        for seg in valid_segments:
            classified.append({
                'start_sec': seg['start_sec'],
                'end_sec': seg['end_sec'],
                'is_target': False,
                'sim': 0.0
            })
    else:
        print(f"[!] Pre-recorded target voice profile detected. Preserving target voice & converting remaining speakers.")
        # 1. Extract SpeechBrain ECAPA-TDNN embedding for every valid segment
        segment_embeddings = []
        for seg in valid_segments:
            seg_audio = seg['audio_data']
            seg_emb = extract_embedding(seg_audio[:int(2.0 * sr)], sr=sr)
            segment_embeddings.append(seg_emb)
            
        embeddings_arr = np.array(segment_embeddings)
        sims_arr = np.array([cosine_similarity(emb, target_embedding) for emb in embeddings_arr])
        durations = np.array([seg['end_sec'] - seg['start_sec'] for seg in valid_segments])
        total_speech_dur = np.sum(durations)
        
        # 2. Balanced 2-Speaker Diarization (each speaker >= 10% speech time)
        if len(valid_segments) >= 2:
            try:
                from sklearn.cluster import KMeans
                kmeans = KMeans(n_clusters=2, random_state=42, n_init=20).fit(embeddings_arr)
                cluster_labels = kmeans.labels_
                
                # Calculate mean similarity & speech time for each speaker cluster
                cluster_sims = {}
                for cid in [0, 1]:
                    mask = (cluster_labels == cid)
                    mean_sim = float(np.mean(sims_arr[mask])) if np.any(mask) else 0.0
                    dur = float(np.sum(durations[mask])) if np.any(mask) else 0.0
                    cluster_sims[cid] = (mean_sim, dur)
                    print(f"   -> Speaker Cluster {cid}: Mean Sim = {mean_sim:.3f} | Speech Time = {dur:.1f}s ({dur/total_speech_dur*100:.1f}%)")
                    
                # MANDATORILY assign the cluster with the highest mean similarity as the Target Speaker (or user selected override)
                if preserve_speaker_cluster is not None and preserve_speaker_cluster in [0, 1]:
                    target_cluster_id = preserve_speaker_cluster
                    print(f"[Pipeline] User explicitly selected Speaker Cluster {target_cluster_id} to preserve!")
                else:
                    target_cluster_id = max(cluster_sims, key=lambda k: cluster_sims[k][0])

                target_mask = (cluster_labels == target_cluster_id)
                preserved_dur = cluster_sims[target_cluster_id][1]
                
                print(f"[Pipeline] Mandatorily assigned Target Speaker: Cluster {target_cluster_id} (Mean Sim: {cluster_sims[target_cluster_id][0]:.3f})")
                print(f"[Pipeline] Preserving Cluster {target_cluster_id}: {preserved_dur:.1f}s ({preserved_dur/total_speech_dur*100:.1f}% of total speech)")
            except Exception as e:
                print(f"[Pipeline] Diarization fallback: {e}")
                target_mask = (sims_arr >= similarity_threshold)
        else:
            target_mask = (sims_arr >= similarity_threshold)
            
        for i, seg in enumerate(valid_segments):
            is_target = bool(target_mask[i])
            sim = float(sims_arr[i])
            classified.append({
                'start_sec': seg['start_sec'],
                'end_sec': seg['end_sec'],
                'is_target': is_target,
                'sim': sim
            })

    # Group non-target segments into continuous unbroken transform blocks (0.3s max gap)
    merged_blocks = []
    curr_block = None
    preserved_count = 0

    for seg in classified:
        if seg['is_target']:
            preserved_count += 1
            if curr_block is not None:
                merged_blocks.append(curr_block)
                curr_block = None
        else:
            if curr_block is None:
                curr_block = {'start_sec': seg['start_sec'], 'end_sec': seg['end_sec']}
            else:
                if seg['start_sec'] - curr_block['end_sec'] <= 0.3:
                    curr_block['end_sec'] = seg['end_sec']
                else:
                    merged_blocks.append(curr_block)
                    curr_block = {'start_sec': seg['start_sec'], 'end_sec': seg['end_sec']}

    if curr_block is not None:
        merged_blocks.append(curr_block)

    # Fallback ONLY when no target profile is present and no blocks were created
    if not has_target_profile and len(merged_blocks) == 0 and tot_segs > 0:
        merged_blocks = [{'start_sec': valid_segments[0]['start_sec'], 'end_sec': valid_segments[-1]['end_sec']}]

    print(f"   -> Classification & Selective Preservation Summary:")
    print(f"      - Pre-Recorded Target Voice Preserved: {preserved_count} segments")
    print(f"      - Non-Target Voices Gender Converted: {len(merged_blocks)} blocks")

    print("[3/4] Transforming gender across continuous speech blocks...")
    tot_blocks = len(merged_blocks)
    for i, b in enumerate(tqdm(merged_blocks, desc="Transforming Blocks")):
        _report_progress(35.0 + ((i + 1) / max(1, tot_blocks)) * 55.0, f"Transforming speech blocks ({i+1}/{tot_blocks})...")
        start_sample = int(b['start_sec'] * sr)
        end_sample = int(b['end_sec'] * sr)
        block_audio = full_y[start_sample:end_sample]
        
        seg_wav_in = os.path.join(temp_dir, f"block_{i}_in.wav")
        seg_wav_out = os.path.join(temp_dir, f"block_{i}_out.wav")
        
        sf.write(seg_wav_in, block_audio, sr)
        success = convert_gender_auto(seg_wav_in, seg_wav_out, target_gender=target_gender)
        
        if success and os.path.exists(seg_wav_out):
            trans_y, trans_sr = sf.read(seg_wav_out)
            
            # Smooth 5ms edge crossfade to eliminate boundary clicks
            fade_len = int(0.005 * sr)
            if len(trans_y) > fade_len * 2:
                fade_in = np.linspace(0, 1, fade_len)
                fade_out = np.linspace(1, 0, fade_len)
                trans_y[:fade_len] *= fade_in
                trans_y[-fade_len:] *= fade_out
                
            target_len = end_sample - start_sample
            if len(trans_y) == target_len:
                out_audio[start_sample:end_sample] = trans_y
            elif len(trans_y) > target_len:
                out_audio[start_sample:end_sample] = trans_y[:target_len]
            else:
                out_audio[start_sample : start_sample + len(trans_y)] = trans_y
                
    print("[4/4] Exporting final converted audio track...")
    _report_progress(95.0, "Exporting final converted MP3 audio...")
    temp_full_wav = os.path.join(temp_dir, "final_output.wav")
    sf.write(temp_full_wav, out_audio, sr)
    
    # Export to mp3
    audio = AudioSegment.from_wav(temp_full_wav)
    audio.export(output_file, format="mp3", bitrate="192k")
    _report_progress(100.0, "Completed")
    
    # Cleanup temp files
    try:
        for f in glob.glob(os.path.join(temp_dir, "*")):
            os.remove(f)
        os.rmdir(temp_dir)
    except Exception:
        pass
        
    print(f"[✓] Successfully generated output: {output_file}")
