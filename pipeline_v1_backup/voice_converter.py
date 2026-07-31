import os
import tempfile
import soundfile as sf
import numpy as np

try:
    import parselmouth
    from parselmouth.praat import call
    PARSELMOUTH_AVAILABLE = True
except ImportError:
    PARSELMOUTH_AVAILABLE = False


def transform_gender_praat(
    audio_path,
    output_path,
    pitch_semitones=8.0,
    formant_ratio=1.20,
    min_pitch=75.0,
    max_pitch=500.0
):
    """
    Transforms the voice gender using Praat pitch and formant modification.
    """
    if not PARSELMOUTH_AVAILABLE:
        raise RuntimeError("parselmouth is not installed. Please install praat-parselmouth.")
    
    sound = parselmouth.Sound(audio_path)
    mean_pitch = detect_average_pitch(audio_path, min_pitch=min_pitch, max_pitch=max_pitch)
    
    # Fallback default mean pitch if unvoiced
    if mean_pitch <= 0:
        mean_pitch = 150.0
        
    # Calculate pitch factor from semitones
    pitch_factor = 2.0 ** (pitch_semitones / 12.0)
    new_pitch_median = mean_pitch * pitch_factor
    
    try:
        manipulated = call(
            sound,
            "Change gender",
            min_pitch,
            max_pitch,
            formant_ratio,
            new_pitch_median,
            pitch_factor,
            1.0             # Keep original duration
        )
        
        # Save output
        manipulated.save(output_path, "WAV")
        return True
    except Exception as e:
        print(f"[VoiceConverter] Praat conversion error: {e}")
        sound.save(output_path, "WAV")
        return False


def detect_average_pitch(audio_path, min_pitch=75.0, max_pitch=500.0):
    """
    Detects the mean fundamental pitch (F0) of an audio segment using Praat.
    Returns:
        float: Mean pitch in Hz (0 if unvoiced/silent).
    """
    if not PARSELMOUTH_AVAILABLE:
        return 0.0
    try:
        sound = parselmouth.Sound(audio_path)
        pitch = sound.to_pitch(pitch_floor=min_pitch, pitch_ceiling=max_pitch)
        mean_pitch = call(pitch, "Get mean", 0, 0, "Hertz")
        if np.isnan(mean_pitch):
            return 0.0
        return float(mean_pitch)
    except Exception:
        return 0.0


def convert_gender_auto(audio_path, output_path, target_gender="auto"):
    """
    Auto-detects gender based on pitch and converts to opposite gender (or requested target_gender).
    Male pitch range is typically ~85-165 Hz -> Shifts to Female (+8 semitones, 1.20 formant ratio).
    Female pitch range is typically ~165-255 Hz -> Shifts to Male (-8 semitones, 0.80 formant ratio).
    """
    mean_pitch = detect_average_pitch(audio_path)
    
    if target_gender == "auto":
        if mean_pitch > 0 and mean_pitch < 165:
            # Male voice detected -> Convert to Female
            pitch_semitones = +8.0
            formant_ratio = 1.20
            print(f"[VoiceConverter] Auto-detected Male ({mean_pitch:.1f} Hz) -> Converting to Female (+8 semitones)")
        else:
            # Female voice detected -> Convert to Male
            pitch_semitones = -8.0
            formant_ratio = 0.80
            print(f"[VoiceConverter] Auto-detected Female ({mean_pitch:.1f} Hz) -> Converting to Male (-8 semitones)")
    elif target_gender.lower() in ["female", "f"]:
        pitch_semitones = +8.0
        formant_ratio = 1.20
    elif target_gender.lower() in ["male", "m"]:
        pitch_semitones = -8.0
        formant_ratio = 0.80
    else:
        pitch_semitones = +8.0
        formant_ratio = 1.20
        
    return transform_gender_praat(
        audio_path,
        output_path,
        pitch_semitones=pitch_semitones,
        formant_ratio=formant_ratio
    )
