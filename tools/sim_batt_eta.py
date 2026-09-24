#!/usr/bin/env python3
"""Host-side simulation of Carbon Ion 2.6.4 battery ETA logic."""
from __future__ import annotations

DEFAULT_SPP = 17280  # 20 days
MIN_SPP, MAX_SPP = 8640, 30240
MIN_SPAN, MIN_DROP = 2 * 3600, 3
SEED = 0x01
EWMA_NUM, EWMA_DEN = 1, 4

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
        if len(self.hist) >= 12:
            self.hist.pop(0)
        self.hist.append(Sample(now, pct, flags))
        return True

    def set_battery(self, now, percent, charging):
        was_c, was_p = self.charging, self.pct
        changed = (percent != was_p) or (charging != was_c)
        if charging:
            m = self.measured(now)
            if m:
                self.update_learned(m)
            self.hist.clear()
        elif was_c:
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
        if self.anchor_sec == 0:
            self.anchor_sec, self.anchor_pct = now, percent
        elif changed:
            self.anchor_sec, self.anchor_pct = now, percent

    def eta_to(self, target, now):
        ap = self.effective_ap()
        if ap <= target:
            return None
        spp = self.spp(now)
        eta_sec = self.anchor_sec + (ap - target) * spp
        return eta_sec, spp, ap


def days(sec):
    return sec / 86400.0


def run_scenario(name, step_pct, secs_per_true_pct, start_pct=100, unplug_mid=True):
    print(f"\n=== {name} (report step={step_pct}%, true {secs_per_true_pct}s/% = {days(secs_per_true_pct*100):.1f}d full) ===")
    m = Model()
    t = 1_700_000_000
    # charge then unplug mid-bucket
    m.set_battery(t, 100, True)
    t += 3600
    unplug_pct = start_pct
    if unplug_mid and step_pct > 1:
        # report floor of mid-bucket
        unplug_pct = (start_pct // step_pct) * step_pct
    m.set_battery(t, unplug_pct, False)  # SEED
    true_level = float(start_pct)  # continuous truth at unplug
    # drain
    samples = 0
    while true_level > 5:
        t += int(secs_per_true_pct)
        true_level -= 1.0
        reported = int(true_level // step_pct) * step_pct
        if reported != m.pct:
            m.set_battery(t, reported, False)
            samples += 1
            if samples in (3, 8) or reported in (50, 25, 20, 10):
                eta0, spp, ap = m.eta_to(0, t)
                true_eta = t + true_level * secs_per_true_pct
                err_h = (eta0 - true_eta) / 3600.0
                print(f"  t+{days(t-1_700_000_000):.2f}d report={reported}% true={true_level:.1f}% "
                      f"ap_eff={ap} spp={spp} ({days(spp*100):.1f}d full) "
                      f"ETA0={days(eta0-t):.2f}d true_ETA0={days(true_eta-t):.2f}d err={err_h:+.1f}h "
                      f"learned={m.learned}")
    # charge in the middle scenario handled separately


def run_with_charge():
    print("\n=== 1% steps, charge mid-cycle, learned survives ===")
    spp_true = 16920  # ~4.7h/%
    m = Model()
    t0 = 1_700_000_000
    t = t0
    m.set_battery(t, 100, True)
    t += 600
    m.set_battery(t, 100, False)  # SEED at 100
    true = 100.0
    for _ in range(40):  # down to 60%
        t += spp_true
        true -= 1
        m.set_battery(t, int(true), False)
    eta0, spp, ap = m.eta_to(0, t)
    print(f"  before charge @60%: spp={spp} learned={m.learned} ETA0={days(eta0-t):.2f}d true={days(true*spp_true/86400):.2f}d")
    # charge
    m.set_battery(t, 60, True)
    t += 7200
    m.set_battery(t, 100, True)
    t += 60
    m.set_battery(t, 100, False)  # new SEED; learned should remain
    print(f"  after unplug: hist={len(m.hist)} (seed only) learned={m.learned} spp_now={m.spp(t)} (should be learned/default)")
    true = 100.0
    for _ in range(10):
        t += spp_true
        true -= 1
        m.set_battery(t, int(true), False)
    eta0, spp, ap = m.eta_to(0, t)
    print(f"  after 10% drop: spp={spp} learned={m.learned} ETA0={days(eta0-t):.2f}d true={days(true*spp_true/86400):.2f}d")


if __name__ == "__main__":
    # Official app evidence: ~19.5d full → 16848 s/%; 25% → 4.8d empty ≈ 16588 s/%
    run_scenario("1% steps @4.7h/% (~19.6d full)", 1, 16920, start_pct=100)
    run_scenario("10% steps @4.7h/% (~19.6d full)", 10, 16920, start_pct=100)
    run_scenario("1% steps starting at 25% (app Empty-in check)", 1, 16920, start_pct=25)
    run_with_charge()
    print("\nLegacy clamp note: old MAX 7200 would reject 16920 and fall back to 2880 (80h) — markers ~6x too early.")
