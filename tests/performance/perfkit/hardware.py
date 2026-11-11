"""Erfassung der Messumgebung (Kopffeld "Hardware" jedes Messberichts)."""
import os
import platform


def collect():
    info = {"os": platform.platform(), "python": platform.python_version(), "logical_cpus": os.cpu_count()}
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.lower().startswith("model name"):
                    info["cpu_model"] = line.split(":", 1)[1].strip()
                    break
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal"):
                    info["memory_total_mb"] = int(line.split()[1]) // 1024
                    break
    except OSError:
        pass
    info.setdefault("cpu_model", "unbekannt")
    info["virtualized"] = "wahrscheinlich (Cloud-Sandbox, nicht geprüft)" if os.path.exists("/.dockerenv") or os.environ.get("CI") else "nicht geprüft"
    return info
