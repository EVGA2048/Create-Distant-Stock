"""F1 smooth descent revision. Generate files only; never play audio."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import subprocess
import wave
import numpy as np

HERE = Path(__file__).resolve().parent
V2 = HERE.parent / 'wall-sounder-audio-v2'
spec = importlib.util.spec_from_file_location('audio_v2', V2 / 'generate_audio.py')
v2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v2)
SR = v2.SR


def smooth(x, seconds):
    n = round(seconds * SR) | 1
    padded = np.pad(x, n // 2, mode='edge')
    cumulative = np.concatenate(([0.], np.cumsum(padded)))
    return (cumulative[n:] - cumulative[:-n]) / n


def descent_range(x):
    rms = [np.sqrt(np.mean(x[round(t*SR):round((t+.04)*SR)]**2))
           for t in np.arange(2.05, 3.38, .025)]
    return round(float(20*np.log10(max(rms)/min(rms))), 3)


def tail_fade(x, t, start=3.46, end=3.62):
    """End the siren inside the falling sweep, with no audible low-note tail.

    Even starting the release at 3.84 s left the 3.84--3.90 s window near full
    loudness, so the ear still heard a distinct ending note.  Fade while the
    pitch is still descending and reach exact silence as it arrives at the
    floor.  The rest of the 4.15 s clip is intentionally digital silence.
    """
    y = x.copy()
    u = np.clip((t-start)/(end-start), 0, 1)
    release = .5 + .5*np.cos(np.pi*u)
    release[t < start] = 1
    release[t >= end] = 0
    y *= release
    return y


def main():
    originals = {str(p.relative_to(V2)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for folder in ('single_cycle', 'manual_listen')
                 for p in (V2 / folder).glob('*')}
    t = np.arange(round(4.15*SR))/SR
    # Nearly symmetric sweep: 1.65 s up, 1.65 s down.  The old F1 spent
    # ~2.7 s climbing but only ~0.85 s falling, which made the descent sound
    # like it suddenly dropped out from under the rise.
    freq = np.interp(t, [0, .30, 1.95, 3.60, 4.15], [760, 800, 1540, 760, 760])
    # Round the pitch-slope corners without resetting the oscillator phase.
    freq = smooth(smooth(freq, .06), .06)
    assert np.all(np.diff(freq[(t >= 1.98) & (t <= 3.58)]) <= 1e-7)
    phase = 2*np.pi*np.cumsum(freq)/SR
    tone = np.sin(phase) + .21*np.sin(2*phase) + .045*np.sin(3*phase)
    raw = v2.speaker(v2.edges(tone, .075, .14), .48, 13)
    # Preserve the approved speaker character; flatten only the falling sweep's
    # slow loudness dip/rebound. Freeze compensation before the fade-out.
    envelope = np.sqrt(np.maximum(smooth(smooth(raw*raw, .06), .06), 1e-12))
    target = np.median(envelope[(t >= 2.75) & (t <= 2.90)])
    gain = target/envelope
    gain[t >= 3.42] = gain[round(3.42*SR)]
    weight = np.clip((t-1.83)/.14, 0, 1)
    weight = weight*weight*(3-2*weight)
    raw *= np.exp(weight*np.log(np.clip(gain, .5, 2)))
    # Release *inside* the last part of the downward sweep.  Waiting until the
    # 760 Hz floor is reached still reads as an extra tail to the ear.
    raw = tail_fade(raw, t)
    x = v2.level(raw)
    assert x[0] == x[-1] == 0
    assert abs(np.mean(x)) < .0001
    assert np.max(np.abs(x)) <= v2.PEAK_LIMIT
    assert descent_range(x) < .35
    name = 'f1_fire_sweep_smooth'
    single = HERE/'single_cycle'; preview_dir = HERE/'manual_listen'
    single.mkdir(exist_ok=True); preview_dir.mkdir(exist_ok=True)
    wav = single/(name+'.wav'); ogg = single/(name+'.ogg')
    v2.write_wav(wav, x)
    ffmpeg = shutil.which('ffmpeg')
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(wav),
                    '-c:a', 'libvorbis', '-q:a', '5', str(ogg)], check=True)
    result = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-i', str(ogg),
                             '-f', 'f32le', '-ac', '1', '-ar', str(SR), 'pipe:1'],
                            capture_output=True, check=True)
    decoded = np.frombuffer(result.stdout, dtype='<f4')
    assert np.max(np.abs(decoded)) < .115
    assert descent_range(decoded) < .4
    preview = np.zeros(round(11.65*SR))
    for start in (2., 6.5):
        offset = round(start*SR); preview[offset:offset+len(x)] = x
    assert not np.any(preview[:2*SR])
    v2.write_wav(preview_dir/(name+'_preview.wav'), preview)
    with wave.open(str(V2/'single_cycle/f1_fire_sweep.wav'), 'rb') as f:
        old = np.frombuffer(f.readframes(f.getnframes()), dtype='<i2').astype(float)/32768
    for path, digest in originals.items():
        assert hashlib.sha256((V2/path).read_bytes()).hexdigest() == digest
    report = dict(playback_performed=False, sample_rate=SR, channels=1,
                  leading_silence_s=2, repeat_period_s=4.5, repeats=2,
                  preview_duration_s=11.65, wav=v2.metrics(x), ogg=v2.metrics(decoded),
                  descent_window='2.05–3.40s; 40ms RMS windows, 25ms hop; excludes intentional tail release',
                  sweep_timing='rise 0.30–1.95s (1.65s), fall 1.95–3.60s (1.65s)',
                  tail_release='raised cosine, 3.46–3.62s; ends inside falling sweep, then digital silence',
                  original_descent_range_db=descent_range(old),
                  revised_descent_range_db=descent_range(x),
                  decoded_descent_range_db=descent_range(decoded),
                  v2_audio_unchanged=True, v2_sha256=originals)
    (HERE/'audio_check.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'v2_sha256'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
