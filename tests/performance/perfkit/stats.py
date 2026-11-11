"""Perzentile nach der Nearest-Rank-Methode (keine Interpolation): p-tes Perzentil = sortiert[ceil(p/100*n)-1]."""
import math


def percentile(sorted_values, p):
    if not sorted_values:
        raise ValueError("leere Stichprobe")
    if not 0 < p <= 100:
        raise ValueError("p muss in (0,100] liegen")
    return sorted_values[max(0, math.ceil(p / 100 * len(sorted_values)) - 1)]


def summarize(values_ms):
    """Kennzahlen einer Latenzstichprobe in Millisekunden (aus den echten Messwerten, ohne Schätzung)."""
    v = sorted(values_ms)
    if not v:
        return {"n": 0}
    return {"n": len(v), "min_ms": round(v[0], 3), "mean_ms": round(sum(v) / len(v), 3),
            "p50_ms": round(percentile(v, 50), 3), "p90_ms": round(percentile(v, 90), 3),
            "p95_ms": round(percentile(v, 95), 3), "p99_ms": round(percentile(v, 99), 3), "max_ms": round(v[-1], 3)}
