"""Simulate Carbon Ion battery-marker accuracy: a Python port of the 3.3.0 logic
vs the real 3.3.1 C code (src/c/modules/batt_eta.c, driven through ctypes).
Watch: Pebble Time 2, nRF fuel gauge, reported pct = ceil(SoC) sampled each minute (PebbleOS).
Run from the project root:  TZ=UTC python3 tools/sim_batt_accuracy.py"""
import math, random, statistics as st, sys

DAY = 86400
FULL_DAYS = 19.5
DEF, SMIN, SMAX = 17280, 8640, 30240
HMAX, HWIN = 36, 7 * DAY

def make_trace(seed, diurnal=(1.35, 0.45), lowsoc_boost=1.0, restarts_per_day=6, days=19.5, noise=0.25):
    """Minute-by-minute true SoC from 100 down to 0, plus a face-running mask."""
    rnd = random.Random(seed)
    day_f, night_f = diurnal
    mean_f = (16 * day_f + 8 * night_f) / 24
    base = 100.0 / (days * DAY / 60) / mean_f       # %/min at f=1
    soc, t = 100.0, 0
    t0_hour = 18                                     # unplug at 18:00
    trace = []
    run = True; down_until = -1
    # day-to-day variation of usage
    dayscale = {}
    while soc > 0 and t < 40 * DAY:
        h = ((t // 3600) + t0_hour) % 24
        d = t // DAY
        if d not in dayscale: dayscale[d] = max(0.5, rnd.gauss(1.0, noise))
        f = day_f if 7 <= h < 23 else night_f
        g = lowsoc_boost if soc < 15 else 1.0
        soc -= base * f * g * dayscale[d]
        if t > down_until and rnd.random() < restarts_per_day / 1440 * (16 / 24 if 7 <= h < 23 else 0.05) * 24 / 16:
            down_until = t + rnd.randint(1, 15) * 60   # an app opened for 1-15 min
        trace.append((t, max(soc, 0.0), t <= down_until))
        t += 60
    return trace

class Current:
    """Port of daylight_layer.c (3.3.0) battery logic."""
    def __init__(s, learned=0, fix_step=False, fix_anchor=False, restart_anchor=False):
        s.hist = []; s.learned = learned; s.pct = None; s.anchor = 0; s.anchor_pct = 0
        s.fix_step = fix_step; s.fix_anchor = fix_anchor; s.restart_anchor = restart_anchor
    def prune(s, now):
        s.hist = [x for x in s.hist if now - x[0] <= HWIN]
    def push(s, now, p, seed):
        s.prune(now)
        if s.hist and s.hist[-1][1] == p: return
        if len(s.hist) == HMAX: s.hist.pop(0)
        s.hist.append((now, p, seed))
    def measured(s, now):
        s.prune(now)
        ch = [x for x in s.hist if not x[2]]
        if len(ch) < 2: return 0
        a, b = ch[0], ch[-1]
        if b[1] >= a[1]: return 0
        dsec, dp = b[0] - a[0], a[1] - b[1]
        if dsec < 7200 or dp < 3: return 0
        spp = dsec // dp
        return spp if SMIN <= spp <= SMAX else 0
    def step(s):
        if s.fix_step: return 1
        best = 1; prev = None
        for x in s.hist:
            if x[2]: continue
            if prev is not None: best = max(best, abs(prev - x[1]))
            prev = x[1]
        return min(best, 20)
    def spp(s, now):
        m = s.measured(now)
        if m: return (m * 2 + s.learned) // 3 if s.learned else m
        return s.learned if s.learned else DEF
    def cold_start(s, now, p):
        """Face re-created (app closed): anchor_sec == 0 path."""
        s.anchor = 0
        if s.hist:
            last = s.hist[-1]
            if p != last[1]:
                if s.restart_anchor and not last[2] and last[1] > p:
                    # proposal: expected crossing time instead of "now"
                    est = last[0] + (last[1] - p) * s.spp(now)
                    s.push(min(est, now), p, True)
                else:
                    s.push(now, p, True)
            last = s.hist[-1]
            if last[1] == p: s.anchor, s.anchor_pct = last[0], p
        if not s.anchor: s.anchor, s.anchor_pct = now, p
        s.pct = p
    def update(s, now, p):
        was = s.pct
        if was is None:
            s.push(now, p, True); s.pct = p; s.anchor, s.anchor_pct = now, p; return
        if p > was:
            if p < was + 3:
                s.pct = p; s.anchor, s.anchor_pct = now, p; return
        if p != was:
            s.push(now, p, False)
            s.pct = p
            if s.fix_anchor and s.hist and s.hist[-1][1] == p:
                s.anchor, s.anchor_pct = s.hist[-1][0], p
            else:
                s.anchor, s.anchor_pct = now, p
    def eta(s, now, m):
        ap = s.anchor_pct; st_ = s.step(); spp = s.spp(now)
        ae = min(ap + st_ // 2, 100) if st_ > 1 else ap
        if ap < m: d = (ap - m) * spp
        elif ap == m: d = 0
        else: d = (ae - m) * spp
        return s.anchor + d

class Profile(Current):
    """Proposal: least-squares slope over all CHANGE points in the window (time-weighted),
    plus an hour-of-day drain profile learned from the history (24 buckets)."""
    def __init__(s, *a, diurnal=True, **k):
        super().__init__(*a, fix_step=True, fix_anchor=True, restart_anchor=True, **k)
        s.diurnal = diurnal
        s.w = [1.0] * 24      # learned relative drain per hour (persisted, EWMA)
    def measured(s, now):
        s.prune(now)
        ch = [x for x in s.hist if not x[2]]
        if len(ch) < 3: return Current.measured(s, now)
        n = len(ch); mt = sum(x[0] for x in ch) / n; mp = sum(x[1] for x in ch) / n
        sxx = sum((x[0] - mt) ** 2 for x in ch); sxy = sum((x[0] - mt) * (x[1] - mp) for x in ch)
        if sxx <= 0 or sxy >= 0: return 0
        spp = int(-sxx / sxy)
        if ch[-1][0] - ch[0][0] < 7200 or ch[0][1] - ch[-1][1] < 3: return 0
        return spp if SMIN <= spp <= SMAX else 0
    def learn_profile(s, hour_of):
        # spread each 1% step's duration over the hours it spanned -> % per hour per bucket
        ch = [x for x in s.hist if not x[2]]
        acc = [0.0] * 24; tim = [0.0] * 24
        for a, b in zip(ch, ch[1:]):
            dur = b[0] - a[0]; dp = a[1] - b[1]
            if dur <= 0 or dp <= 0 or dur > 3 * DAY: continue
            t = a[0]
            while t < b[0]:
                h = hour_of(t); nxt = min(b[0], (t // 3600 + 1) * 3600)
                tim[h] += nxt - t; acc[h] += dp * (nxt - t) / dur
                t = nxt
        # rate in a bucket can't be measured from step durations alone: use inverse duration weighting
        rates = [acc[h] / tim[h] if tim[h] > 3 * 3600 else None for h in range(24)]
        if None in rates: return
        m = sum(rates) / 24
        s.w = [0.7 * s.w[h] + 0.3 * rates[h] / m for h in range(24)]
    def eta(s, now, m, hour_of=None):
        if not s.diurnal or hour_of is None: return Current.eta(s, now, m)
        ap = s.anchor_pct; spp = s.spp(now)
        if ap <= m: return s.anchor + (ap - m) * spp
        need = (ap - m) * spp          # "average seconds" of drain needed from anchor
        t = s.anchor
        while need > 0:
            h = hour_of(t); nxt = (t // 3600 + 1) * 3600
            cap = (nxt - t) * s.w[h]
            if cap >= need: return t + need / s.w[h]
            need -= cap; t = nxt
        return t


import ctypes, os, subprocess
_LIB = None
def _lib():
    global _LIB
    if _LIB is None:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        so = "/tmp/carbon_sim_batt.so"
        subprocess.check_call(["cc", "-std=c11", "-O2", "-shared", "-fPIC", "-I" + root + "/tools/host",
                               "-o", so, root + "/tools/sim_batt_lib.c", root + "/src/c/modules/batt_eta.c"])
        _LIB = ctypes.CDLL(so)
        _LIB.sim_eta.restype = ctypes.c_long
        for f in ("sim_reset", "sim_set", "sim_cold_start", "sim_eta"):
            getattr(_LIB, f).argtypes = {"sim_reset": [ctypes.c_long], "sim_set": [ctypes.c_long, ctypes.c_int, ctypes.c_int, ctypes.c_int],
                                         "sim_cold_start": [ctypes.c_long, ctypes.c_int], "sim_eta": [ctypes.c_long, ctypes.c_int]}[f]
    return _LIB

class CAlgo:
    """The real 3.3.1 C module. TZ must be UTC: the trace starts at 18:00 UTC."""
    def __init__(s, learned=16848, profile=True):
        s.l = _lib(); s.l.sim_reset(learned); s.profile = profile
        s.first = True
    def update(s, now, p):
        if s.first:   # unplugged just before the trace
            s.l.sim_set(now - 60, p, 0, 1); s.first = False
        s.l.sim_set(now, p, 0, 0)
    def cold_start(s, now, p):
        if s.first: return s.update(now, p)
        s.l.sim_cold_start(now, p)
    def eta(s, now, m):
        return s.l.sim_eta(now, m)

T_EPOCH = 1763488800  # 2025-11-18 18:00 UTC

def run(algo_factory, seeds=40, **trace_kw):
    errs = {20: [], 10: [], 4: []}
    for seed in range(seeds):
        tr = [(t + T_EPOCH, soc, d) for t, soc, d in make_trace(seed, **trace_kw)]
        hour_of = lambda t: ((t - T_EPOCH) // 3600 + 18) % 24
        # true crossing times (ceil semantics: reported M first appears when SoC <= M)
        cross = {}
        for t, soc, _ in tr:
            for m in errs:
                if m not in cross and soc <= m: cross[m] = t
        a = algo_factory()
        rep = None; was_down = False; last_hour = -1
        for t, soc, down in tr:
            p = max(0, math.ceil(soc - 1e-9))
            if down: was_down = True; continue
            if was_down:
                a.cold_start(t, p); was_down = False; rep = p
            elif p != rep:
                a.update(t, p); rep = p
            if isinstance(a, Profile) and t // 3600 != last_hour and t % (6 * 3600) == 0:
                a.learn_profile(hour_of)
            if t % 3600 == 0:
                for m, ct in cross.items():
                    if 0 < ct - t <= 24 * 3600 and p > m:     # visible on the 24 h forecast
                        e = (a.eta(t, m, hour_of) if isinstance(a, Profile) else a.eta(t, m)) - ct
                        errs[m].append(e / 3600)
    out = {}
    for m, v in errs.items():
        av = sorted(abs(x) for x in v)
        out[m] = (st.mean(v), av[len(av) // 2], av[int(len(av) * 0.9)])
    return out

if __name__ == "__main__":
    scen = {
        "realistic (day 1.35x / night 0.45x, ±25% day-to-day, 6 app opens/day)": dict(),
        "no app opens (face always running)": dict(restarts_per_day=0),
        "flat drain (no day/night)": dict(diurnal=(1, 1)),
        "gauge drains 20% faster below 15%": dict(lowsoc_boost=1.2),
    }
    algos = {
        "3.3.0 (Python port)": lambda: Current(learned=16848),
        "3.3.1 (real C: A+B+D+E)": lambda: CAlgo(learned=16848),
    }
    for sn, kw in scen.items():
        print("\n##", sn)
        print(f"{'algorithm':40s} | " + " | ".join(f"{m:>2d}%: bias / med|err| / p90|err| (h)" for m in (20, 10, 4)))
        for an, f in algos.items():
            r = run(f, **kw)
            print(f"{an:40s} | " + " | ".join(f"{r[m][0]:+5.1f} / {r[m][1]:4.1f} / {r[m][2]:4.1f}            " for m in (20, 10, 4)))
