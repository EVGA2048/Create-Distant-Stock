"""Synthesize wall-sounder candidates and inspect numerically. NEVER plays audio."""
from pathlib import Path
import hashlib
import json
import math
import shutil
import subprocess
import wave
import numpy as np

HERE=Path(__file__).resolve().parent
RUNTIME=HERE/'single_pulse'
PREVIEW=HERE/'manual_listen'
for p in (RUNTIME,PREVIEW):p.mkdir(parents=True,exist_ok=True)
SR=48000
PEAK_DB=-20
PEAK=10**(PEAK_DB/20)
LEAD_SECONDS=2.0
PERIOD_SECONDS=1.5
REPEATS=4


def tone(frequency,duration,attack,decay,partials=(1,.18,.035)):
    t=np.arange(round(duration*SR))/SR
    env=np.exp(-np.maximum(t-attack,0)/decay)
    n=min(len(t),round(attack*SR))
    env[:n]*=.5-.5*np.cos(np.linspace(0,np.pi,n))
    # Smooth the final 110ms down to an exact zero endpoint.
    tail=min(len(t),round(.11*SR))
    env[-tail:]*=.5+.5*np.cos(np.linspace(0,np.pi,tail))
    signal=np.zeros_like(t)
    for i,weight in enumerate(partials,1):
        # Higher harmonics decay more quickly to avoid a sharp metallic tail.
        signal+=weight*np.sin(2*np.pi*frequency*i*t)*np.exp(-t*(i-1)*5)
    result=signal*env
    result[0]=result[-1]=0
    return result


def normalize(a):
    return a*(PEAK/max(np.max(np.abs(a)),1e-12))


def wav(path,a):
    data=np.rint(np.clip(a,-1,1)*32767).astype('<i2')
    with wave.open(str(path),'wb') as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(SR)
        out.writeframes(data.tobytes())


def metrics(a):
    peak=float(np.max(np.abs(a)))
    rms=float(np.sqrt(np.mean(a*a)))
    return {'duration_s':round(len(a)/SR,4),'peak_dbfs':round(20*np.log10(max(peak,1e-12)),2),
            'rms_dbfs':round(20*np.log10(max(rms,1e-12)),2),
            'max_sample_step':round(float(np.max(np.abs(np.diff(a)))),7)}


def main():
    ffmpeg=shutil.which('ffmpeg')
    if not ffmpeg:raise RuntimeError('ffmpeg is required for Vorbis delivery')
    a=tone(660,.34,.055,.090,(1,.20,.04))
    b=np.zeros(round(.46*SR))
    first=tone(784,.27,.055,.078,(1,.12,.02))
    second=tone(587,.30,.055,.095,(1,.12,.02))
    b[:len(first)]+=first
    offset=round(.16*SR);b[offset:offset+len(second)]+=second*.88
    c=tone(440,.32,.065,.095,(1,.32,.075))
    candidates={
        'a_soft_chime':('柔和短铃',a),
        'b_descending_pair':('圆润双音',b),
        'c_low_signal':('低音机械提示',c),
    }
    report={'sample_rate':SR,'channels':1,'target_peak_dbfs':PEAK_DB,
            'preview_leading_silence_s':LEAD_SECONDS,'pulse_period_s':PERIOD_SECONDS,
            'preview_repeats':REPEATS,'playback_performed':False,'candidates':{}}
    for name,(label,raw) in candidates.items():
        pulse=normalize(raw)
        assert pulse[0]==pulse[-1]==0
        assert np.max(np.abs(pulse))<=PEAK+1e-9
        path=RUNTIME/(name+'.wav');wav(path,pulse)
        ogg=RUNTIME/(name+'.ogg')
        subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-i',str(path),
                        '-c:a','libvorbis','-q:a','5',str(ogg)],check=True)
        # Decode only into memory: validates the shipped OGG without audio output.
        decoded=subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-i',str(ogg),
                                '-f','f32le','-ac','1','-ar',str(SR),'pipe:1'],
                               capture_output=True,check=True)
        out=np.frombuffer(decoded.stdout,dtype='<f4')
        assert np.max(np.abs(out))<.12
        length=LEAD_SECONDS+(REPEATS-1)*PERIOD_SECONDS+len(pulse)/SR+1.0
        preview=np.zeros(math.ceil(length*SR))
        for i in range(REPEATS):
            start=round((LEAD_SECONDS+i*PERIOD_SECONDS)*SR)
            preview[start:start+len(pulse)]+=pulse
        assert np.all(preview[:round(LEAD_SECONDS*SR)]==0)
        wav(PREVIEW/(name+'_preview.wav'),preview)
        report['candidates'][name]={'label':label,'wav':metrics(pulse),'ogg_decoded':metrics(out),
                                    'preview_duration_s':round(len(preview)/SR,3),
                                    'ogg_sha256':hashlib.sha256(ogg.read_bytes()).hexdigest()}
    (HERE/'audio_check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
