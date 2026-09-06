#!/usr/bin/env python3
import argparse, json
from collections import defaultdict
from pathlib import Path

def evaluate(records):
    total = correct = hc_total = hc_correct = false_changes = 0
    bins = defaultdict(lambda: [0,0])
    prev_pred = prev_truth = None

    for r in records:
        truth = r.get("actual_lane")
        pred = r.get("predicted_lane")
        conf = float(r.get("confidence",0))
        if truth is None:
            continue

        total += 1
        hit = pred == truth
        correct += int(hit)
        if conf >= 0.85:
            hc_total += 1
            hc_correct += int(hit)

        bucket=min(9,int(conf*10))
        bins[bucket][0]+=1
        bins[bucket][1]+=int(hit)

        if prev_pred is not None and pred != prev_pred and truth == prev_truth:
            false_changes += 1
        prev_pred, prev_truth = pred, truth

    pct=lambda a,b: 0.0 if b==0 else round(100*a/b,2)
    return {
        "samples_with_truth": total,
        "overall_accuracy_pct": pct(correct,total),
        "high_confidence_samples": hc_total,
        "high_confidence_accuracy_pct": pct(hc_correct,hc_total),
        "false_lane_change_events": false_changes,
        "calibration": {
            f"{i*10}-{(i+1)*10}%": {
                "samples": n,
                "actual_accuracy_pct": pct(h,n)
            } for i,(n,h) in sorted(bins.items())
        }
    }

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("drive",type=Path)
    args=ap.parse_args()
    with args.drive.open() as f:
        rows=[json.loads(x) for x in f if x.strip()]
    print(json.dumps(evaluate(rows),indent=2))

if __name__=="__main__":
    main()
