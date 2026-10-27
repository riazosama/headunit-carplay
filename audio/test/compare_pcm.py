#!/usr/bin/env python3
"""Compare a decoded PCM raw file (s16le interleaved) against an ffmpeg reference.

Reports length, best alignment lag (samples), peak/RMS, and SDR both with unity
gain and with best-fit gain.  AAC decoding is not bit-exact; a correctly working
decoder should be highly correlated after alignment (SDR typically > 15-25 dB
with the codec delay removed).
"""
import sys
import numpy as np


def load(path, ch):
    raw = np.fromfile(path, dtype="<i2")
    if raw.size % ch:
        raw = raw[: raw.size - (raw.size % ch)]
    return raw.reshape(-1, ch).astype(np.float64)


def best_lag(ref, dec, max_lag=8192):
    n = min(len(ref), len(dec))
    a = ref[:n, 0].copy()
    b = dec[:n, 0].copy()
    a -= a.mean()
    b -= b.mean()
    nfft = 1
    while nfft < 2 * n:
        nfft *= 2
    fa = np.fft.rfft(a, nfft)
    fb = np.fft.rfft(b, nfft)
    corr = np.fft.irfft(fa * np.conj(fb), nfft)
    # lag > 0 means dec is delayed relative to ref: dec[n+lag] ~ ref[n]
    pos = corr[: max_lag + 1]
    neg = corr[-max_lag:]
    i = int(np.argmax(np.abs(pos)))
    j = int(np.argmax(np.abs(neg)))
    if abs(neg[j]) > abs(pos[i]):
        lag = -(j + 1)
        c = neg[j]
    else:
        lag = i
        c = pos[i]
    return lag, c


def sdr(ref, dec, gain):
    n = min(len(ref), len(dec))
    r = ref[:n].ravel()
    d = dec[:n].ravel() * gain
    err = r - d
    er = float(np.sum(err * err))
    sr = float(np.sum(r * r))
    if er <= 0:
        return 999.0
    return 10.0 * np.log10(sr / er)


def main():
    if len(sys.argv) < 3:
        print("usage: compare_pcm.py ref.raw decoded.raw [channels] [rate]")
        return 2
    refp, decp = sys.argv[1], sys.argv[2]
    ch = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    rate = int(sys.argv[4]) if len(sys.argv) > 4 else 44100

    ref = load(refp, ch)
    dec = load(decp, ch)
    print(f"ref: {len(ref)} frames ({len(ref)/rate:.3f} s) peak={np.max(np.abs(ref)):.0f} "
          f"rms={np.sqrt(np.mean(ref**2)):.1f}")
    print(f"dec: {len(dec)} frames ({len(dec)/rate:.3f} s) peak={np.max(np.abs(dec)):.0f} "
          f"rms={np.sqrt(np.mean(dec**2)):.1f}")
    if len(dec) == 0:
        print("RESULT status=FAIL reason=empty_decode")
        return 1

    lag, c = best_lag(ref, dec)
    print(f"best lag = {lag} samples ({lag/rate*1000:.2f} ms), corr={c:.3e}")
    # apply lag: dec[n+lag] ~ ref[n]  => compare ref[0:N-lag] with dec[lag:lag+N-lag]
    if lag >= 0:
        n = min(len(ref), len(dec) - lag)
        r = ref[:n]
        d = dec[lag : lag + n]
    else:
        n = min(len(ref) + lag, len(dec))
        r = ref[-lag : -lag + n]
        d = dec[:n]
    if n <= 0:
        print("RESULT status=FAIL reason=no_overlap")
        return 1
    energy_r = float(np.sum(r * r))
    energy_d = float(np.sum(d * d))
    gain = float(np.sum(r * d) / energy_d) if energy_d > 0 else 1.0
    corrcoef = float(np.corrcoef(r.ravel(), d.ravel())[0, 1])
    sdr1 = sdr(r, d, 1.0)
    sdg = sdr(r, d, gain)
    print(f"overlap {n} frames | corrcoef={corrcoef:.6f} | gain={gain:.4f} | "
          f"SDR(unity)={sdr1:.2f} dB | SDR(bestGain)={sdg:.2f} dB")
    # per-channel corr
    for c_ in range(ch):
        cc = np.corrcoef(r[:, c_], d[:, c_])[0, 1]
        e = np.sum(r[:, c_] ** 2) / max(1.0, np.sum((r[:, c_] - d[:, c_]) ** 2))
        print(f"  ch{c_}: corr={cc:.6f} SDR(unity)={10*np.log10(e):.2f} dB")
    ok = (corrcoef > 0.99 and sdr1 > 10.0 and np.max(np.abs(dec)) > 100)
    print(f"RESULT status={'PASS' if ok else 'FAIL'} corr={corrcoef:.6f} "
          f"sdr_unity={sdr1:.2f} sdr_gain={sdg:.2f} lag={lag} "
          f"peak={np.max(np.abs(dec)):.0f}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
