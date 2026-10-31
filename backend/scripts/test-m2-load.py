#!/usr/bin/env python3
"""M2.10 small, reproducible, disposable-CI HTTP performance smoke.
Not a production load certification or a replacement for k6 staging tests.
Uses only an MFA-verified synthetic employee on the real PostgreSQL backend.
"""
import concurrent.futures
import json
import math
import os
import pathlib
import statistics
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8080").rstrip("/")
TOKEN = os.environ["EMP_TOKEN"]
EXPECTED_EMPLOYEE = os.environ["EMP_ID"]
WORKERS = int(os.environ.get("M210_WORKERS", "10"))
COUNT = int(os.environ.get("M210_REQUESTS", "100"))
MAX_P95_MS = float(os.environ.get("M210_PROVISIONAL_P95_MS", "5000"))
if not 1 <= WORKERS <= 40 or not 1 <= COUNT <= 1000:
    raise ValueError("Bound test size to 1..40 workers and 1..1000 requests")
URL = BASE + "/api/v1/employee-self-service/me"

def request(_):
    started = time.perf_counter()
    req = urllib.request.Request(URL, headers={"Authorization": "Bearer " + TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            body = json.load(response)
            status = response.status
        correct = (status == 200 and body.get("employee", {}).get("employeeId") == EXPECTED_EMPLOYEE
                   and body.get("clockInEnabled") is False
                   and body.get("locationCaptureEnabled") is False
                   and body.get("biometricCaptureEnabled") is False)
        return (time.perf_counter() - started) * 1000, bool(correct), status
    except urllib.error.HTTPError as error:
        return (time.perf_counter() - started) * 1000, False, error.code
    except Exception as error:
        return (time.perf_counter() - started) * 1000, False, type(error).__name__

with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
    results = list(pool.map(request, range(COUNT)))

latencies = sorted(float(row[0]) for row in results)
def percentile(p):
    rank = max(0, math.ceil(p / 100 * len(latencies)) - 1)
    return round(latencies[rank], 2)

report = {
    "scope": "M2.10_SYNTHETIC_EMPLOYEE_HTTP_SMOKE_NOT_PRODUCTION_SLA",
    "source": "live HTTP, disposable PostgreSQL 17, GitHub Actions ephemeral runner",
    "requests": COUNT, "workers": WORKERS,
    "errors": sum(not row[1] for row in results),
    "statuses": {str(code): sum(row[2] == code for row in results) for code in set(row[2] for row in results)},
    "latency_ms": {
        "min": round(latencies[0], 2),
        "mean": round(statistics.mean(latencies), 2),
        "p50": percentile(50),
        "p95": percentile(95),
        "p99": percentile(99),
        "max": round(latencies[-1], 2),
    },
    "provisional_test_threshold_p95_ms": MAX_P95_MS,
    "result": "PASS" if all(row[1] for row in results) and percentile(95) <= MAX_P95_MS else "FAIL",
}
output = pathlib.Path("/tmp/hrm-m210-http-load.json")
output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2, sort_keys=True))
if report["result"] != "PASS":
    raise SystemExit("M2.10 concurrent synthetic ESS HTTP smoke failed")
