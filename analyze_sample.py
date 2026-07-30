import os
import sys
import argparse
import numpy as np
import soundfile as sf
from pydub import AudioSegment
from sklearn.cluster import AgglomerativeClustering
from speaker_utils import segment_audio_vad, extract_embedding, cosine_similarity


def analyze_sample_file(sample_path, output_dir="Sample/extracted_speakers", n_clusters=2, select_speaker=None):
    """
    Analyzes sample audio file, clusters speakers, saves sample audio clips for each speaker,
    and saves target_speaker_profile.npy when a speaker is selected.
    """
    if not os.path.exists(sample_path):
        print(f"[Error] Sample file not found: {sample_path}")
        sys.exit(1)
        
    os.makedirs(output_dir, exist_ok=True)
    print(f"\n==================================================")
    print(f"  ANALYZING SAMPLE AUDIO FILE")
    print(f"  File: {sample_path}")
    print(f"==================================================\n")
    
    print("[1/4] Running Voice Activity Detection (VAD)...")
    segments, full_y, sr = segment_audio_vad(sample_path)
    print(f"   -> Extracted {len(segments)} speech segments.")
    
    if len(segments) < n_clusters:
        print(f"[Warning] Only {len(segments)} segments found. Clustering into {len(segments)} speakers.")
        n_clusters = max(1, len(segments))
        
    print("[2/4] Extracting speaker embedding vectors...")
    embeddings = []
    valid_segments = []
    for i, seg in enumerate(segments):
        emb = extract_embedding(seg['audio_data'], sr=seg['sr'])
        if emb is not None and len(emb) > 0:
            embeddings.append(emb)
            valid_segments.append(seg)
            
    embeddings = np.array(embeddings)
    print(f"   -> Extracted embeddings for {len(embeddings)} segments.")
    
    print(f"[3/4] Clustering into {n_clusters} main speakers...")
    from sklearn.cluster import AgglomerativeClustering
    # Use Ward linkage on neural + pitch embeddings for clear speaker distinction
    clustering = AgglomerativeClustering(
        n_clusters=n_clusters,
        linkage='ward'
    )
    labels = clustering.fit_predict(embeddings)
    
    # Organize segments and speaker embeddings
    speaker_data = {}
    for label in range(n_clusters):
        idx_list = np.where(labels == label)[0]
        if len(idx_list) == 0:
            continue
            
        spk_embs = embeddings[idx_list]
        avg_emb = np.mean(spk_embs, axis=0)
        avg_emb = avg_emb / np.linalg.norm(avg_emb)
        
        # Concatenate top audio segments to form a clear audio sample clip
        spk_audio_chunks = [valid_segments[i]['audio_data'] for i in idx_list[:8]]
        combined_audio = np.concatenate(spk_audio_chunks)
        
        total_duration = sum(valid_segments[i]['end_sec'] - valid_segments[i]['start_sec'] for i in idx_list)
        
        clip_path = os.path.join(output_dir, f"speaker_{label + 1}.mp3")
        
        # Export clip as mp3
        temp_wav = os.path.join(output_dir, f"temp_speaker_{label + 1}.wav")
        sf.write(temp_wav, combined_audio, sr)
        audio_seg = AudioSegment.from_wav(temp_wav)
        audio_seg.export(clip_path, format="mp3", bitrate="192k")
        if os.path.exists(temp_wav):
            os.remove(temp_wav)
            
        speaker_data[label + 1] = {
            'embedding': avg_emb,
            'clip_path': clip_path,
            'duration_sec': total_duration,
            'num_segments': len(idx_list)
        }
        
        print(f"   - Speaker {label + 1}:")
        print(f"     * Total Duration: {total_duration:.1f} seconds across {len(idx_list)} turns")
        print(f"     * Sample Clip: {clip_path}")
        
    print(f"\n==================================================")
    print(f"  SPEAKER EXTRACTION COMPLETE!")
    print(f"  Sample clips saved in: {output_dir}")
    print(f"==================================================\n")
    
    # Handle speaker selection
    target_profile_path = "target_speaker_profile.npy"
    
    if select_speaker is not None:
        if select_speaker in speaker_data:
            chosen_emb = speaker_data[select_speaker]['embedding']
            np.save(target_profile_path, chosen_emb)
            print(f"[✓] Target Speaker {select_speaker} selected and saved to '{target_profile_path}'.")
        else:
            print(f"[x] Invalid speaker number {select_speaker}. Available choices: {list(speaker_data.keys())}")
    else:
        print("Please listen to the sample audio clips:")
        for spk_num, data in speaker_data.items():
            print(f"  • Speaker {spk_num}: {data['clip_path']}")
        print(f"\nTo confirm which speaker voice should be PRESERVED, run:")
        print(f"  python3 analyze_sample.py --sample \"{sample_path}\" --select-speaker <1 or 2>\n")

    return speaker_data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Analyze sample audio and identify speakers for voice preservation.")
    parser.add_argument("--sample", type=str, default="Sample/WhatsApp Audio 2026-07-28 at 10.43.52.mp3", help="Path to sample audio file")
    parser.add_argument("--output-dir", type=str, default="Sample/extracted_speakers", help="Directory to save extracted speaker audio clips")
    parser.add_argument("--n-clusters", type=int, default=2, help="Number of speakers to cluster")
    parser.add_argument("--select-speaker", type=int, default=None, help="Speaker number to preserve (1 or 2)")
    
    args = parser.parse_args()
    analyze_sample_file(
        sample_path=args.sample,
        output_dir=args.output_dir,
        n_clusters=args.n_clusters,
        select_speaker=args.select_speaker
    )
