import os
import soundfile as sf
import numpy as np
import librosa
from gtts import gTTS
from speaker_utils import extract_embedding, load_audio_mono

PROFILES_DIR = "target_profiles"
os.makedirs(PROFILES_DIR, exist_ok=True)

print(f"==================================================")
print(f"  GENERATING TARGET SPEAKER PROFILES LIBRARY")
print(f"==================================================")

samples_config = {
    "tamil_female": {
        "text": "வணக்கம், இது குரல் மாற்றத்திற்கான தமிழ் பெண் குரல் மாதிரி ஆகும்.",
        "lang": "ta",
        "tld": "com"
    },
    "tamil_male": {
        "text": "வணக்கம், இது குரல் மாற்றத்திற்கான தமிழ் ஆண் குரல் மாதிரி ஆகும்.",
        "lang": "ta",
        "tld": "co.in"
    },
    "english_female": {
        "text": "Hello, this is a sample reference recording for English female voice conversion.",
        "lang": "en",
        "tld": "com"
    },
    "english_male": {
        "text": "Hello, this is a sample reference recording for English male voice conversion.",
        "lang": "en",
        "tld": "co.uk"
    }
}

for profile_name, cfg in samples_config.items():
    mp3_path = os.path.join(PROFILES_DIR, f"{profile_name}.mp3")
    npy_path = os.path.join(PROFILES_DIR, f"{profile_name}.npy")
    
    print(f"\n[+] Processing profile: {profile_name} ({cfg['lang']} - {cfg['tld']})...")
    
    # 1. Synthesize reference speech track
    tts = gTTS(text=cfg['text'], lang=cfg['lang'], tld=cfg['tld'])
    tts.save(mp3_path)
    print(f"   -> Saved reference audio: {mp3_path}")
    
    # 2. Extract SpeechBrain ECAPA-TDNN embedding vector
    y, sr = load_audio_mono(mp3_path, target_sr=16000)
    emb = extract_embedding(y, sr=sr)
    
    # 3. Save profile vector
    np.save(npy_path, emb)
    print(f"   [✓] Saved target profile vector: {npy_path} (Shape: {emb.shape})")

print("\n==================================================")
print(f"🎉 Target Profiles Library Generation Complete!")
print(f"   Profiles stored in: {os.path.abspath(PROFILES_DIR)}")
print("==================================================")
