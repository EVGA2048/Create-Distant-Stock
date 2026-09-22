"""Small-speaker distortion and periodic fire-tone studies. NO PLAYBACK."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import wave
import numpy as np

HERE=Path(__file__).resolve().parent
SINGLE=HERE/'single_cycle';PREVIEW=HERE/'manual_listen'
for p in (SINGLE,PREVIEW):p.mkdir(parents=True,exist_ok=True)
SR=48000;PEAK_LIMIT=10**(-20/20);RMS_TARGET=10**(-30/20)
LEAD=2.0;PERIOD=1.5;REPEATS=4


def biquad(x,kind,hz,q=.707,gain=0):
    w=2*np.pi*hz/SR;c=np.cos(w);s=np.sin(w);alpha=s/(2*q)
    if kind=='highpass':b=[(1+c)/2,-(1+c),(1+c)/2];a=[1+alpha,-2*c,1-alpha]
    elif kind=='lowpass':b=[(1-c)/2,1-c,(1-c)/2];a=[1+alpha,-2*c,1-alpha]
    elif kind=='peak':
        amp=10**(gain/40);b=[1+alpha*amp,-2*c,1-alpha*amp];a=[1+alpha/amp,-2*c,1-alpha/amp]
    else:raise ValueError(kind)
    b=np.asarray(b)/a[0];a=np.asarray(a)/a[0]
    result=np.zeros_like(x);x1=x2=y1=y2=0.
    for i,v in enumerate(x):
        y=b[0]*v+b[1]*x1+b[2]*x2-a[1]*y1-a[2]*y2
        result[i]=y;x2=x1;x1=v;y2=y1;y1=y
    return result


def edges(x,attack=.055,release=.09):
    x=x.copy();na=round(attack*SR);nr=round(release*SR)
    x[:na]*=.5-.5*np.cos(np.linspace(0,np.pi,na))
    x[-nr:]*=.5+.5*np.cos(np.linspace(0,np.pi,nr))
    x[0]=x[-1]=0
    return x


def speaker(x,strength=.5,seed=100):
    x=x/max(np.max(np.abs(x)),1e-9)
    x=biquad(x,'highpass',350+strength*100)
    x=biquad(x,'peak',1160,1.5,4+strength*3)
    # Asymmetric overload makes a small loudspeaker's rough edge, without clicks.
    drive=2.2+strength*3.0;bias=.07+strength*.07
    x=.88*(np.tanh(drive*x+bias)-np.tanh(bias))+.12*x
    # Short resonant path colours the enclosure rather than adding room reverb.
    delay=round(.0013*SR)
    x[delay:]+=x[:-delay].copy()*(.08+strength*.09)
    x=biquad(x,'peak',760,2.0,2.0)
    # Coarse drive circuitry and a very small signal-gated paper-cone rasp.
    steps=2**(9-round(strength*2))
    x=np.round(x*steps)/steps
    rng=np.random.default_rng(seed)
    noise=rng.normal(size=len(x))
    noise=biquad(biquad(noise,'highpass',850),'lowpass',2400)
    noise/=max(np.std(noise),1e-9)
    gate=np.sqrt(np.convolve(x*x,np.ones(480)/480,mode='same'))
    x+=noise*gate*(.008+strength*.018)
    # AC-couple after asymmetric saturation, removing the DC it introduces.
    x=biquad(x,'highpass',240)
    x=biquad(x,'lowpass',3000-strength*600)
    return edges(x,attack=.06,release=.10)


def sweep():
    t=np.arange(round(4.15*SR))/SR
    # Slow climb and quicker fall, phase continuous throughout the cycle.
    freq=np.interp(t,[0,.30,3.0,3.85,4.15],[760,800,1540,760,760])
    phase=2*np.pi*np.cumsum(freq)/SR
    tone=np.sin(phase)+.21*np.sin(2*phase)+.045*np.sin(3*phase)
    return edges(tone,.075,.14)


def alternating():
    t=np.arange(round(1.12*SR))/SR
    # Two high/low pairs with rounded pitch transitions, no phase reset.
    switch=.5+.5*np.tanh(4*np.sin(2*np.pi*t/.56))
    freq=800+450*switch
    phase=2*np.pi*np.cumsum(freq)/SR
    tone=np.sin(phase)+.20*np.sin(2*phase)+.04*np.sin(3*phase)
    return edges(tone,.075,.14)


def level(x):
    peak=np.max(np.abs(x));rms=np.sqrt(np.mean(x*x))
    return x*min(PEAK_LIMIT/max(peak,1e-9),RMS_TARGET/max(rms,1e-9))


def write_wav(path,x):
    with wave.open(str(path),'wb') as f:
        f.setnchannels(1);f.setsampwidth(2);f.setframerate(SR)
        f.writeframes(np.rint(np.clip(x,-1,1)*32767).astype('<i2').tobytes())


def metrics(x):
    return {'duration_s':round(len(x)/SR,4),
            'peak_dbfs':round(float(20*np.log10(max(np.max(np.abs(x)),1e-12))),2),
            'rms_dbfs':round(float(20*np.log10(max(np.sqrt(np.mean(x*x)),1e-12))),2),
            'mean':round(float(np.mean(x)),8)}


def main():
    ffmpeg=shutil.which('ffmpeg')
    if not ffmpeg:raise RuntimeError('ffmpeg required for OGG encoding')
    with wave.open(str(HERE/'reference/b_original.wav'),'rb') as f:
        assert (f.getframerate(),f.getnchannels(),f.getsampwidth())==(SR,1,2)
        b=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2').astype(float)/32768
    candidates={
        'b1_small_speaker':('B1 双音 / 轻破音',speaker(b,.38,11),PERIOD,REPEATS),
        'b2_worn_speaker':('B2 双音 / 较重破音',speaker(b,.85,12),PERIOD,REPEATS),
        'f1_fire_sweep':('F1 消防感 / 4.5秒升降扫频',speaker(sweep(),.48,13),4.5,2),
        'f2_fire_alternating':('F2 消防感 / 高低交替',speaker(alternating(),.48,14),PERIOD,REPEATS),
    }
    report={'playback_performed':False,'sample_rate':SR,'channels':1,
            'peak_limit_dbfs':-20,'target_rms_dbfs':-30,'leading_silence_s':LEAD,
            'brand_recording_used':False,'candidates':{}}
    for name,(label,raw,period,repeats) in candidates.items():
        x=level(raw)
        assert x[0]==x[-1]==0
        assert np.max(np.abs(x))<=PEAK_LIMIT+1e-9
        assert np.sqrt(np.mean(x*x))<=RMS_TARGET+1e-9
        assert abs(np.mean(x))<.0001
        assert len(x)<period*SR
        path=SINGLE/(name+'.wav');write_wav(path,x)
        ogg=SINGLE/(name+'.ogg')
        subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-i',str(path),'-c:a','libvorbis','-q:a','5',str(ogg)],check=True)
        result=subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-i',str(ogg),'-f','f32le','-ac','1','-ar',str(SR),'pipe:1'],capture_output=True,check=True)
        decoded=np.frombuffer(result.stdout,dtype='<f4')
        assert np.max(np.abs(decoded))<.115
        length=round((LEAD+(repeats-1)*period+len(x)/SR+1)*SR)
        preview=np.zeros(length)
        for i in range(repeats):
            start=round((LEAD+i*period)*SR);preview[start:start+len(x)]+=x
        assert not np.any(preview[:round(LEAD*SR)])
        write_wav(PREVIEW/(name+'_preview.wav'),preview)
        report['candidates'][name]={'label':label,'wav':metrics(x),'ogg':metrics(decoded),
                                    'repeat_period_s':period,'repeats':repeats,
                                    'preview_duration_s':round(len(preview)/SR,3),
                                    'ogg_sha256':hashlib.sha256(ogg.read_bytes()).hexdigest()}
    (HERE/'audio_check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
