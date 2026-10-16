#!/usr/bin/env python3
"""Host-side simulation of Carbon Ion 2.6.6 battery ETA + marker placement."""
from __future__ import annotations

DEFAULT_SPP = 17280  # 20 days
MIN_SPP, MAX_SPP = 8640, 30240
MIN_SPAN, MIN_DROP = 2 * 3600, 3
SEED = 0x01
EWMA_NUM, EWMA_DEN = 1, 4
HIST_MAX = 36


class Sample:
    def __init__(self, sec, pct, flags=0):
        self.sec, self.percent, self.flags = sec, pct, flags


class Model:
    def __init__(self):
        self.hist: list[Sample] = []
        self.learned = 0
        self.anchor_sec = 0
        self.anchor_pct = 100
        self.pct = 100
        self.charging = False
        self.plugged = False

    def measured(self, now):
        changes = [s for s in self.hist if not (s.flags & SEED)]
        if len(changes) < 2:
            return 0
        o, n = changes[0], changes[-1]
        if n.percent >= o.percent:
            return 0
        dsec = n.sec - o.sec
        dpct = o.percent - n.percent
        if dsec < MIN_SPAN or dpct < MIN_DROP:
            return 0
        spp = dsec // dpct
        if spp < MIN_SPP or spp > MAX_SPP:
            return 0
        return spp

    def update_learned(self, m):
        if not (MIN_SPP <= m <= MAX_SPP):
            return
        if self.learned == 0:
            self.learned = m
        else:
            self.learned = (self.learned * (EWMA_DEN - EWMA_NUM) + m * EWMA_NUM) // EWMA_DEN

    def spp(self, now):
        m = self.measured(now)
        if m > 0:
            if self.learned > 0:
                return (m * 2 + self.learned) // 3
            return m
        if MIN_SPP <= self.learned <= MAX_SPP:
            return self.learned
        return DEFAULT_SPP

    def est_step(self):
        best, prev, have = 1, 0, False
        for s in self.hist:
            if s.flags & SEED:
                continue
            if have:
                d = abs(prev - s.percent)
                if d > best:
                    best = d
            prev, have = s.percent, True
        return min(best, 20)

    def effective_ap(self):
        ap = self.anchor_pct
        step = self.est_step()
        if step > 1:
            ap = min(100, ap + step // 2)
        return ap

    def push(self, now, pct, flags):
        if self.hist and self.hist[-1].percent == pct:
            return False
        if len(self.hist) >= HIST_MAX:
            self.hist.pop(0)
        self.hist.append(Sample(now, pct, flags))
        return True

    def set_battery(self, now, percent, charging, plugged=False):
        was_c, was_p, was_pl = self.charging, self.pct, self.plugged
        was_on = was_c or was_pl
        on_power = charging or plugged
        changed = (percent != was_p) or (charging != was_c) or (plugged != was_pl)
        if on_power:
            m = self.measured(now)
            if m:
                self.update_learned(m)
            self.hist.clear()
        elif was_on:
            self.hist.clear()
            self.push(now, percent, SEED)
        elif self.anchor_sec != 0 and percent > was_p:
            m = self.measured(now)
            if m:
                self.update_learned(m)
            self.hist.clear()
            self.push(now, percent, SEED)
        elif percent != was_p or self.anchor_sec == 0:
            flags = SEED if (not self.hist and self.anchor_sec == 0) else 0
            if self.push(now, percent, flags) and flags == 0:
                m = self.measured(now)
                if m:
                    self.update_learned(m)
        self.pct = percent
        self.charging = charging
        self.plugged = plugged
        if self.anchor_sec == 0:
            self.anchor_sec, self.anchor_pct = now, percent
        elif changed:
            self.anchor_sec, self.anchor_pct = now, percent

    def secs_to_mark(self, M):
        raw = self.anchor_pct
        eff = self.effective_ap()
        spp = self.spp(self.anchor_sec)
        if raw < M:
            return (raw - M) * spp, spp, eff
        if raw == M:
            return 0, spp, eff
        return (eff - M) * spp, spp, eff

    def eta_to(self, target, now):
        secs, spp, ap = self.secs_to_mark(target)
        return self.anchor_sec + secs, spp, ap


def days(sec):
    return sec / 86400.0


def place_mark(raw, step, M, spp, age, forecast_h=24, clamp_right=False):
    past_h = forecast_h // 4
    window_start = -past_h * 3600
    window_end = forecast_h * 3600
    eff = raw + (step // 2 if step > 1 else 0)
    if eff > 100:
        eff = 100
    if raw < M:
        secs = (raw - M) * spp
    elif raw == M:
        secs = 0
    else:
        secs = (eff - M) * spp
    eta = -age + secs
    if eta < window_start:
        return None, eta, "culled_past"
    if eta > window_end:
        if not clamp_right:
            return None, eta, "culled_fut"
        return window_end, eta, "clamped_right"
    return eta, eta, ("past" if eta < 0 else "future")


def run_scenario(name, step_pct, secs_per_true_pct, start_pct=100, unplug_mid=True):
    print(f"\n=== {name} (report step={step_pct}%, true {secs_per_true_pct}s/% = {days(secs_per_true_pct*100):.1f}d full) ===")
    m = Model()
    t = 1_700_000_000
    m.set_battery(t, 100, True, True)
    t += 3600
    unplug_pct = start_pct
    if unplug_mid and step_pct > 1:
        unplug_pct = (start_pct // step_pct) * step_pct
    m.set_battery(t, unplug_pct, False, False)
    true_level = float(start_pct)
    samples = 0
    while true_level > 5:
        t += int(secs_per_true_pct)
        true_level -= 1.0
        reported = int(true_level // step_pct) * step_pct
        if reported != m.pct:
            m.set_battery(t, reported, False, False)
            samples += 1
            if samples in (3, 8) or reported in (50, 25, 20, 10):
                eta0, spp, ap = m.eta_to(0, t)
                true_eta = t + true_level * secs_per_true_pct
                err_h = (eta0 - true_eta) / 3600.0
                print(f"  t+{days(t-1_700_000_000):.2f}d report={reported}% true={true_level:.1f}% "
                      f"ap_eff={ap} spp={spp} ({days(spp*100):.1f}d full) "
                      f"ETA0={days(eta0-t):.2f}d true_ETA0={days(true_eta-t):.2f}d err={err_h:+.1f}h "
                      f"learned={m.learned} hist={len(m.hist)}")


def run_plugged_poison_guard():
    print("\n=== plugged-but-not-charging must not record drain ===")
    m = Model()
    t = 1_700_000_000
    m.set_battery(t, 100, True, True)
    t += 600
    m.set_battery(t, 100, False, True)
    assert len(m.hist) == 0 and m.plugged
    t += 86400
    m.set_battery(t, 100, False, True)
    assert len(m.hist) == 0
    m.set_battery(t + 10, 100, False, False)
    assert len(m.hist) == 1 and (m.hist[0].flags & SEED)
    print("  OK: no hist while plugged; SEED on unplug")


def run_with_charge():
    print("\n=== 1% steps, charge mid-cycle, learned survives ===")
    spp_true = 16920
    m = Model()
    t = 1_700_000_000
    m.set_battery(t, 100, True, True)
    t += 600
    m.set_battery(t, 100, False, False)
    true = 100.0
    for _ in range(40):
        t += spp_true
        true -= 1
        m.set_battery(t, int(true), False, False)
    eta0, spp, ap = m.eta_to(0, t)
    print(f"  before charge @60%: spp={spp} learned={m.learned} ETA0={days(eta0-t):.2f}d hist={len(m.hist)}")
    m.set_battery(t, 60, True, True)
    t += 7200
    m.set_battery(t, 100, True, True)
    t += 60
    m.set_battery(t, 100, False, False)
    print(f"  after unplug: hist={len(m.hist)} learned={m.learned} spp_now={m.spp(t)}")
    true = 100.0
    for _ in range(10):
        t += spp_true
        true -= 1
        m.set_battery(t, int(true), False, False)
    eta0, spp, ap = m.eta_to(0, t)
    print(f"  after 10% drop: spp={spp} learned={m.learned} ETA0={days(eta0-t):.2f}d hist={len(m.hist)}")


if __name__ == "__main__":
    run_scenario("1% steps @4.7h/% (~19.6d full)", 1, 16920, start_pct=100)
    run_scenario("10% steps @4.7h/% (~19.6d full)", 10, 16920, start_pct=100)
    run_scenario("1% steps starting at 25% (app Empty-in check)", 1, 16920, start_pct=25)
    run_with_charge()
    run_plugged_poison_guard()
    print("\n=== 2.6.6 placement @10% coarse / 30m old (no right clamp) ===")
    for M in (20, 10, 0):
        shown, eta, how = place_mark(10, 10, M, DEFAULT_SPP, 1800, clamp_right=False)
        sh = None if shown is None else shown / 3600.0
        print(f"  {M}%: {how} shown={sh} raw_eta_h={eta/3600:.2f}")
    print("\n=== 2.6.6 @3% / default spp — empty inside 24h window ===")
    for M in (20, 10, 0):
        shown, eta, how = place_mark(3, 1, M, DEFAULT_SPP, 0, clamp_right=False)
        sh = None if shown is None else shown / 3600.0
        print(f"  {M}%: {how} shown={sh} raw_eta_h={eta/3600:.2f}")
    print("\nLegacy note: old MAX 7200 rejected 16920 → fallback 2880 (80h) — markers ~6x too early.")
    print("2.6.5 clamp_right on 0% parked empty at forecast edge whenever ETA > forecast")
    print("(typical at ≥6% with default 17280 s/% on a 24h chart). 2.6.6 omits instead.")
