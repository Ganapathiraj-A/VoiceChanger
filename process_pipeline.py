import os
import sys
import glob
import tempfile
import argparse
import numpy as np
import soundfile as sf
from pydub import AudioSegment
from tqdm import tqdm

from speaker_utils import segment_audio_vad, extract_embedding, cosine_similarity
from voice_converter import convert_gender_auto, transform_gender_praat


def process_audio_file(
    input_file,
    output_file,
    target_embedding,
    similarity_threshold=0.50,
    target_gender="auto",
    progress_callback=None
):
    """
    Processes a single audio conversation file:
    - Preserves target person's voice (similarity >= threshold).
    - Changes gender of other person's voice (similarity < threshold).
    """
    def _report_progress(pct, msg):
        if progress_callback is not None:
            try: progress_callback(pct, msg)
            except Exception: pass

    _report_progress(5.0, "Segmenting audio into speech bursts...")
    print(f"\n==================================================")
    print(f"  PROCESSING FILE: {os.path.basename(input_file)}")
    print(f"==================================================")
    
    # Load VAD segments
    print("[1/4] Running Voice Activity Detection...")
    segments, full_y, sr = segment_audio_vad(input_file)
    print(f"   -> Found {len(segments)} speech segments.")
    
    if len(segments) == 0:
        print("[!] No speech segments detected. Copying original file.")
        audio = AudioSegment.from_file(input_file)
        audio.export(output_file, format="mp3", bitrate="192k")
        _report_progress(100.0, "Completed (No speech detected)")
        return
        
    print("[2/4] Classifying speakers and transforming voices...")
    _report_progress(15.0, "Classifying speakers...")
    
    # Prepare temporary output directory for chunks
    temp_dir = tempfile.mkdtemp(prefix="voice_changer_")
    processed_audio_segments = []
    
    current_sample = 0
    full_length = len(full_y)
    
    # Copy full audio array for editing or stitching
    out_audio = full_y.copy()
    
    preserved_count = 0
    transformed_count = 0
    
    # Classify segments
    classified = []
    tot_segs = len(segments)
    for i, seg in enumerate(tqdm(segments, desc="Classifying Speakers")):
        _report_progress(15.0 + ((i + 1) / max(1, tot_segs)) * 20.0, f"Classifying speaker embeddings ({i+1}/{tot_segs})...")
        seg_audio = seg['audio_data']
        dur = len(seg_audio) / sr
        
        # Fast embedding extraction on central 3.0s window if segment is long
        if dur > 3.0:
            center_sample = len(seg_audio) // 2
            half_win = int(1.5 * sr)
            s_start = max(0, center_sample - half_win)
            s_end = min(len(seg_audio), center_sample + half_win)
            seg_emb = extract_embedding(seg_audio[s_start:s_end], sr=sr)
        else:
            seg_emb = extract_embedding(seg_audio, sr=sr)
            
        sim = cosine_similarity(seg_emb, target_embedding)
        is_target = (sim >= similarity_threshold)
        classified.append({
            'start_sec': seg['start_sec'],
            'end_sec': seg['end_sec'],
            'is_target': is_target,
            'sim': sim
        })

    # Group non-target segments into continuous unbroken transform blocks
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
                # Merge if gap between non-target speech turns is <= 1.5 seconds
                if seg['start_sec'] - curr_block['end_sec'] <= 1.5:
                    curr_block['end_sec'] = seg['end_sec']
                else:
                    merged_blocks.append(curr_block)
                    curr_block = {'start_sec': seg['start_sec'], 'end_sec': seg['end_sec']}

    if curr_block is not None:
        merged_blocks.append(curr_block)

    print(f"   -> Classification Summary:")
    print(f"      - Target Voice Preserved: {preserved_count} segments")
    print(f"      - Continuous Transform Blocks: {len(merged_blocks)} blocks")

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
                
    print(f"   -> Speaker Classification Summary:")
    print(f"      - Target Voice Preserved: {preserved_count} segments")
    print(f"      - Other Voice Transformed: {transformed_count} segments")
    
    print("[3/4] Exporting final converted audio track...")
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
    
    # Generate test comparison file in test/ folder
    try:
        from create_comparison_track import create_comparison_file
        test_dir = "test"
        base_name = os.path.splitext(os.path.basename(output_file))[0]
        test_comp_file = os.path.join(test_dir, f"comparison_{base_name}.mp3")
        create_comparison_file(input_file, output_file, test_comp_file)
        print(f"[✓] Test Comparison MP3 ready: {test_comp_file}")
    except Exception as e:
        print(f"[!] Warning: Could not generate test comparison file: {e}")


def run_pipeline(
    input_dir="input",
    output_dir="output",
    profile_path="target_speaker_profile.npy",
    similarity_threshold=0.84,
    target_gender="auto"
):
    """
    Scans input_dir for mp3 files and processes them into output_dir if output does not exist.
    """
    os.makedirs(input_dir, exist_ok=True)
    os.makedirs(output_dir, exist_ok=True)
    
    if not os.path.exists(profile_path):
        print(f"\n[x] Error: Target speaker profile '{profile_path}' not found!")
        print(f"Please run 'python3 analyze_sample.py' first and select the person's voice to preserve.\n")
        return
        
    target_embedding = np.load(profile_path)
    print(f"[✓] Loaded target speaker profile from '{profile_path}' (Vector dim: {len(target_embedding)})")
    
    input_files = (
        glob.glob(os.path.join(input_dir, "*.mp3")) +
        glob.glob(os.path.join(input_dir, "*.wav")) +
        glob.glob(os.path.join(input_dir, "*.mp4")) +
        glob.glob(os.path.join(input_dir, "*.m4a"))
    )
    
    if len(input_files) == 0:
        print(f"\n[!] No audio files (.mp3, .wav, .mp4, .m4a) found in '{input_dir}' folder.")
        print(f"Place your conversation MP3 files in '{input_dir}/' and run this script again.\n")
        return
        
    print(f"\nFound {len(input_files)} file(s) in '{input_dir}/':")
    for f in input_files:
        print(f"  - {os.path.basename(f)}")
        
    for input_file in input_files:
        base_name = os.path.splitext(os.path.basename(input_file))[0]
        output_file = os.path.join(output_dir, f"{base_name}.mp3")
        
        if os.path.exists(output_file):
            print(f"\n[->] Skipping '{base_name}.mp3' (Output already exists in '{output_dir}/').")
            continue
            
        process_audio_file(
            input_file=input_file,
            output_file=output_file,
            target_embedding=target_embedding,
            similarity_threshold=similarity_threshold,
            target_gender=target_gender
        )
        
    print(f"\n==================================================")
    print(f"  PIPELINE PROCESSING COMPLETE!")
    print(f"==================================================\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Batch audio processing pipeline for selective voice preservation & gender conversion.")
    parser.add_argument("--input-dir", type=str, default="input", help="Directory with input audio files")
    parser.add_argument("--output-dir", type=str, default="output", help="Directory for output audio files")
    parser.add_argument("--profile", type=str, default="target_speaker_profile.npy", help="Path to saved target speaker embedding profile")
    parser.add_argument("--threshold", type=float, default=0.84, help="Cosine similarity threshold for speaker preservation (default 0.84)")
    parser.add_argument("--target-gender", type=str, default="auto", help="Gender conversion target ('auto', 'female', 'male')")
    
    args = parser.parse_args()
    run_pipeline(
        input_dir=args.input_dir,
        output_dir=args.output_dir,
        profile_path=args.profile,
        similarity_threshold=args.threshold,
        target_gender=args.target_gender
    )
