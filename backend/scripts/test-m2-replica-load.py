#!/usr/bin/env python3
"""M2.10.3: two distinct Spring Boot processes sharing one disposable PostgreSQL.
Sustained, bounded synthetic HTTP reads and concurrent identical nonce writes.
This is a CI topology exercise, not multi-host production load certification.
"""
import concurrent.futures
import hashlib
import json
import math
import os
import pathlib
import statistics
import time
import urllib.error
import urllib.request
import uuid

HOSTS = [os.environ.get("M210_REPLICA_A", "http://127.0.0.1:8081"),
         os.environ.get("M210_REPLICA_B", "http://127.0.0.1:8082")]
TOKEN = os.environ["M210_EMP_TOKEN"]
SECRET = os.environ["M210_DEVICE_SECRET"]
DEVICE = os.environ["M210_DEVICE_ID"]
EMPLOYEE = os.environ["M210_EMP_ID"]
COUNT = int(os.environ.get("M210_REPLICA_REQUESTS", "800"))
PACE_MS = int(os.environ.get("M210_REPLICA_PACE_MS", "500"))
WORKERS = int(os.environ.get("M210_REPLICA_WORKERS", "20"))
MAX_P95 = float(os.environ.get("M210_REPLICA_P95_GUARD_MS", "5000"))
if not (2 <= COUNT <= 2000 and 2 <= WORKERS <= 40 and 0 <= PACE_MS <= 2000):
    raise ValueError("Synthetic CI limits: 2..2000 requests, 2..40 workers")
if len(set(HOSTS)) != 2:
    raise ValueError("Replicas must have different URLs")

def call(url, method="GET", payload=None, device_secret=None):
    raw = json.dumps(payload).encode() if payload is not None else None
    headers = {"Authorization": "Bearer " + TOKEN}
    if raw is not None:
        headers["Content-Type"] = "application/json"
    if device_secret:
        headers["X-Device-Credential"] = device_secret
    req = urllib.request.Request(url, data=raw, headers=headers, method=method)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as reply:
            code = reply.status
            data = json.load(reply)
    except urllib.error.HTTPError as exc:
        code = exc.code
        data = None
    except Exception as exc:
        code = type(exc).__name__
        data = None
    return ((time.perf_counter()-start)*1000,code,data)

def read(index):
    # A bounded ~20-second CI traffic stream, not a production stress workload.
    time.sleep(PACE_MS / 1000)
    host = HOSTS[index % 2]
    elapsed,code,body = call(host + "/api/v1/employee-self-service/me")
    valid = (code == 200 and isinstance(body,dict) and
             body.get("employee",{}).get("employeeId") == EMPLOYEE and
             body.get("clockInEnabled") is False and
             body.get("locationCaptureEnabled") is False and
             body.get("biometricCaptureEnabled") is False)
    return {"replica":index%2,"ms":elapsed,"status":code,"valid":valid}

# Ensure each replica is serving the target identity before introducing concurrency.
for host in HOSTS:
    _,code,body = call(host + "/api/v1/employee-self-service/me")
    if code != 200 or body.get("employee",{}).get("employeeId") != EMPLOYEE:
        raise SystemExit("Replica identity preflight failed: " + host + " status=" + str(code) + " employee_matches=" + str(isinstance(body,dict) and body.get("employee",{}).get("employeeId")==EMPLOYEE))

begin = time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
    readings = list(pool.map(read,range(COUNT)))
duration = time.monotonic()-begin
lat = sorted(item["ms"] for item in readings)
def pct(n):
    return round(lat[max(0,math.ceil(len(lat)*n/100)-1)],2)

# Single shared nonce across *both* application processes. Database serializes
# the device row and uniqueness guards concurrent exact-replay acceptance.
nonce = str(uuid.uuid4())
stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime())
payload = {"nonce":nonce,"timestamp":stamp}
def attest(index):
    elapsed,code,body = call(
        HOSTS[index%2]+"/api/v1/employee-self-service/devices/"+DEVICE+"/attest",
        method="POST",payload=payload,device_secret=SECRET)
    return {"replica":index%2,"ms":round(elapsed,2),"status":code,
            "accepted":code==200 and isinstance(body,dict) and
            body.get("nonce")==nonce and "NO_ATTENDANCE" in body.get("classification","")}
with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
    attestations=list(pool.map(attest,range(20)))

per_replica=[{"replica":i,"requests":sum(x["replica"]==i for x in readings),
              "errors":sum(not x["valid"] for x in readings if x["replica"]==i)}
             for i in range(2)]
accepted=sum(x["accepted"] for x in attestations)
rejected=sum(x["status"] in (403,409) for x in attestations)
passed=(all(x["valid"] for x in readings) and pct(95)<=MAX_P95 and
        accepted==1 and rejected==19 and all(x["status"] in (200,403,409) for x in attestations))
report={
    "scope":"M2.10.3_TWO_LOCAL_PROCESSES_SHARED_POSTGRES_NOT_PRODUCTION_CAPACITY",
    "infrastructure":"GitHub-hosted runner; two Spring Boot JVM processes; shared PostgreSQL 17 container",
    "replicas":per_replica,"workers":WORKERS,"requests":COUNT,"per_worker_pace_ms":PACE_MS,"elapsed_seconds":round(duration,2),
    "throughput_rps_observed":round(COUNT/duration,2),
    "latency_ms":{"mean":round(statistics.mean(lat),2),"p50":pct(50),"p95":pct(95),"p99":pct(99),"max":round(lat[-1],2)},
    "provisional_ci_p95_guard_ms":MAX_P95,"errors":sum(not x["valid"] for x in readings),
    "cross_replica_identical_nonce":{"attempts":len(attestations),"accepted":accepted,
                                    "explicitly_denied":rejected,"results":attestations},
    "result":"PASS" if passed else "FAIL"
}
out=pathlib.Path("/tmp/hrm-m210-replicas.json")
out.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n")
print(json.dumps(report,indent=2,sort_keys=True))
if not passed:
    raise SystemExit("Two-process shared-database regression failed")
