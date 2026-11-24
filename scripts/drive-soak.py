"""Fifteen minutes of offline Android driving, starting with a complete dock job via the lobby."""
import json
import math
import os
import re
import statistics
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

out=Path("journey-captures/soak")
out.mkdir(parents=True,exist_ok=True)
pkg=os.environ.get("SOAK_PACKAGE","com.masalik.yemen.preview")
def adb(*args):
    return subprocess.check_output(["adb",*map(str,args)],timeout=35)
def nodes():
    try:
        adb("shell","rm","-f","/sdcard/soak.xml")
        adb("shell","uiautomator","dump","--compressed","/sdcard/soak.xml")
        return list(ET.fromstring(adb("shell","cat","/sdcard/soak.xml")).iter("node"))
    except (ET.ParseError,subprocess.SubprocessError): return []
def tap(label):
    for _ in range(12):
        for n in nodes():
            if label in (n.get("text"),n.get("content-desc"),n.get("resource-id")):
                l,t,r,b=map(int,re.findall(r"\d+",n.attrib["bounds"]))
                adb("shell","input","tap",(l+r)//2,(t+b)//2)
                return
        time.sleep(.5)
    raise RuntimeError("Missing "+label)
def snap(name):
    (out/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))
def journey(city):
    tap(city); tap("انطلق في الرحلة"); tap("تشغيل السائق الآلي")

adb("shell","svc","wifi","disable"); adb("shell","svc","data","disable")
adb("shell","wm","size","720x1280"); adb("shell","wm","density","160")
adb("shell","pm","clear",pkg)
adb("logcat","-c")
adb("shell","am","start","-W","-n",pkg+"/com.masalik.yemen.MainActivity")
root=adb("shell","pm","path",pkg).decode().strip().split(":",1)[-1].rsplit("/",1)[0]
(out/"installation.txt").write_text(pkg+"\n"+adb("shell","du","-sk",root).decode())
journey("عدن")
start=time.monotonic(); changed=False; finished=False; samples=[]; measured=set(); timestamps=[]; active="Aden"; events=[]
next_time=start
while time.monotonic()-start < 900:
    now=time.monotonic(); sec=int(now-start)
    pid=adb("shell","pidof",pkg).decode().strip()
    if not pid: raise RuntimeError("App exited during mixed drive")
    mem=adb("shell","dumpsys","meminfo",pkg).decode()
    pss=re.search(r"TOTAL PSS:\s*(\d+)",mem) or re.search(r"^\s*TOTAL\s+(\d+)",mem,re.M)
    layer=next((s for s in adb("shell","dumpsys","SurfaceFlinger","--list").decode().splitlines()
                if pkg in s and "SurfaceView" in s and "Background" not in s),"")
    latency=adb("shell","dumpsys","SurfaceFlinger","--latency","'"+layer+"'").decode() if layer else ""
    stamps=[int(r.split()[1]) for r in latency.splitlines() if len(r.split())==3 and r.split()[1].isdigit() and 0<int(r.split()[1])<2**62]
    for a,b in zip(stamps,stamps[1:]):
        if b>a and (a,b) not in measured:
            timestamps.append((b-a)/1e6); measured.add((a,b))
    samples.append(dict(second=sec,section=active,pss_kib=int(pss[1]) if pss else None))
    labels=[n.get("text","") for n in nodes()]
    if not finished and any("أحسنت!" in v for v in labels):
        finished=True; snap("aden-delivered"); events.append([sec,"Aden mission completed"])
    if finished and sec>265 and not changed:
        adb("shell","input","keyevent","KEYCODE_BACK"); tap("الرئيسية")
        journey("جبلة"); active="Jibla"; changed=True; events.append([sec,"Night rain mission started"])
    elif changed and any("أحسنت!" in v for v in labels):
        events.append([sec,active+" mission completed"])
        adb("shell","input","keyevent","KEYCODE_BACK"); tap("الرئيسية")
        next_city="عدن" if active=="Jibla" else "جبلة"
        journey(next_city); active="Aden" if next_city=="عدن" else "Jibla"
    if sec>420 and not finished:
        snap("aden-stalled")
        (out/"hud.txt").write_text("\n".join(labels))
        raise RuntimeError("Aden mission did not finish within seven minutes")
    if len(samples)%10==1:
        snap("drive-"+str(sec))
        (out/("thermal-"+str(sec)+".txt")).write_bytes(adb("shell","dumpsys","thermalservice"))
    (out/"state.json").write_text(json.dumps(dict(events=events,samples=samples),indent=2))
    next_time+=12
    time.sleep(max(0,min(next_time,start+900)-time.monotonic()))
(out/"gpu.log").write_bytes(adb("logcat","-d","CitiesGPU:I","MyCarFrameTimes:I","*:S"))
summary=dict(package=pkg,device="Android API 30 x86_64 emulator, SwiftShader",duration_s=round(time.monotonic()-start,1),offline_aden_completed=finished,
    rain_journey_started=changed,observed_intervals=len(timestamps),mean_fps=1000/statistics.mean(timestamps) if timestamps else None,
    p95_ms=sorted(timestamps)[math.ceil(.95*len(timestamps))-1] if timestamps else None,max_interval_ms=max(timestamps,default=None),
    peak_sample_pss_kib=max((r["pss_kib"] for r in samples if r["pss_kib"]),default=None),events=events)
(out/"summary.json").write_text(json.dumps(summary,indent=2));print(summary,flush=True)
assert finished and changed, "Mixed drive did not complete Aden and start the Jibla section"
