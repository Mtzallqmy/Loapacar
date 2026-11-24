"""Run identical cockpit/road scenarios on an already-installed emulator companion."""
import json
import math
import os
import re
import statistics
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

PKG = "com.masalik.yemen.preview"
OUT = Path("journey-captures/benchmark")
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output(["adb", *map(str, args)], timeout=35)

def bounds(text):
    for _ in range(5):
        try:
            adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/drive-ui.xml")
            nodes = ET.fromstring(adb("shell", "cat", "/sdcard/drive-ui.xml")).iter("node")
            for n in nodes:
                if text in [n.get("text"), n.get("content-desc"), n.get("resource-id")]:
                    x,y,u,v = map(int,re.findall(r"\d+",n.attrib["bounds"]))
                    return (x+u)//2,(y+v)//2
        except (ET.ParseError,subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError("Missing driving control "+text)

def hold(pt, ms):
    adb("shell", "input", "swipe", *pt, *pt, ms)

def scenario(name, city, car, weather, night=False, cockpit=True, auto=False):
    adb("shell","pm","clear",PKG)
    adb("shell","am","start","-W","-n",PKG+"/com.masalik.yemen.MainActivity",
        "--ez","masalik_test_cockpit","true","--es","masalik_test_city",city,
        "--es","masalik_test_vehicle",car,"--es","masalik_test_weather",weather,
        "--es","masalik_test_camera","COCKPIT" if cockpit else "CHASE_NEAR",
        "--es","masalik_test_graphics","MEDIUM","--ez","masalik_test_night",str(night).lower())
    gas,brake = bounds("بنزين"),bounds("فرامل")
    switch = bounds("تشغيل السائق الآلي") if auto else None
    (OUT/(name+"-start.png")).write_bytes(adb("exec-out","screencap","-p"))
    layers = adb("shell","dumpsys","SurfaceFlinger","--list").decode().splitlines()
    layer = next((s for s in layers if "SurfaceView" in s and PKG in s and "Background" not in s), "")
    remote = "/sdcard/drive-"+name+".mp4"
    proc = subprocess.Popen(["adb","shell","screenrecord","--time-limit","8","--size","960x540",remote])
    adb("shell","dumpsys","SurfaceFlinger","--latency-clear")
    if switch:
        adb("shell","input","tap",*switch)
        time.sleep(2.5)
    hold(gas,3000)
    hold(brake,1500)
    memory = adb("shell","dumpsys","meminfo",PKG).decode()
    (OUT/(name+"-mem.txt")).write_text(memory)
    latency = adb("shell","dumpsys","SurfaceFlinger","--latency","'"+layer+"'").decode() if layer else ""
    (OUT/(name+"-latency.txt")).write_text(latency)
    stamps = [int(r.split()[1]) for r in latency.splitlines() if len(r.split()) == 3 and r.split()[1].isdigit() and 0 < int(r.split()[1]) < 2**62]
    intervals = [(b-a)/1e6 for a,b in zip(stamps,stamps[1:]) if b>a]
    (OUT/(name+"-end.png")).write_bytes(adb("exec-out","screencap","-p"))
    if proc.wait(timeout=20)==0:
        adb("pull",remote,str(OUT/(name+".mp4")))
    pss = re.search(r"TOTAL PSS:\s*(\d+)", memory) or re.search(r"^\s*TOTAL\s+(\d+)",memory,re.M)
    return dict(scenario=name, city=city, vehicle=car, weather=weather, night=night, quality="MEDIUM", input="ADB touch", viewport="1280x720", layer=layer,
                actual_frames=len(intervals), mean_fps=round(1000/statistics.mean(intervals),2) if intervals else None,
                p95_ms=round(sorted(intervals)[math.ceil(len(intervals)*.95)-1],2) if intervals else None,
                max_interval_ms=max(intervals,default=None), sample_pss_kib=int(pss[1]) if pss else None)

adb("shell","wm","size","720x1280")
adb("shell","wm","density","160")
adb("shell","svc","wifi","disable")
adb("shell","svc","data","disable")
runs=[]
for args in [("taiz-sedan","city_taiz_sabir","MADINA","CLEAR",False,False),
             ("taiz-pickup","city_taiz_sabir","SAQR","CLEAR",False,False),
             ("wet-cabin","city_ibb_green","SAQR","HEAVY_RAIN"),
             ("aden-night","city_aden_mokha_coast","SAHIL","CLEAR",True),
             ("takeover","city_taiz_sabir","SHAHIQ","CLEAR",False,False,True)]:
    runs.append(scenario(*args))
    (OUT/"results.json").write_text(json.dumps(dict(commit=os.getenv("GITHUB_SHA"),device="API 30 x86_64 SwiftShader emulator", runs=runs),indent=2))
