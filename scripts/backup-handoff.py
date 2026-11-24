"""Cross-package transfer through Android's document picker. Only run on disposable CI devices."""
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

OUT=Path("journey-captures/migration")
OUT.mkdir(parents=True,exist_ok=True)
OLD="com.masalik.yemen.legacy34"
NEW="com.masalik.yemen.preview"

def adb(*args):
    return subprocess.check_output(["adb",*map(str,args)],timeout=30)
def tree():
    adb("shell","rm","-f","/sdcard/backup-ui.xml")
    adb("shell","uiautomator","dump","--compressed","/sdcard/backup-ui.xml")
    return list(ET.fromstring(adb("shell","cat","/sdcard/backup-ui.xml")).iter("node"))
def choose(*matches,scroll=False):
    for attempt in range(12):
        try:
            nodes=tree()
            for match in matches:
                for n in nodes:
                    if match in (n.get("text"),n.get("content-desc"),n.get("resource-id")):
                        x,y,r,b=map(int,re.findall(r"\d+",n.get("bounds","")))
                        if b<=y: continue
                        adb("shell","input","tap",(x+r)//2,(y+b)//2)
                        return
            if scroll and attempt in (1,3,5,7):
                # Scroll both the RTL setting pane and the lobby action list.
                for x in (910,350): adb("shell","input","swipe",x,605,x,320,350)
        except (ET.ParseError,subprocess.SubprocessError):
            pass
        time.sleep(.7)
    (OUT/"picker-failure.png").write_bytes(adb("exec-out","screencap","-p"))
    raise AssertionError(f"Missing {matches} on "+str([(n.get('text'),n.get('resource-id')) for n in tree() if n.get('text')]))

def open_app(pkg):
    adb("shell","am","force-stop",pkg)
    adb("shell","am","start","-W","-n",pkg+"/com.masalik.yemen.MainActivity")
def settings():
    choose("lobby_settings_button","الإعدادات",scroll=True)
    choose("قاعدة بيانات الهاتف")
def export(name):
    choose("تصدير نسخة احتياطية (.masalik.json)",scroll=True)
    choose("android:id/button1","SAVE","Save","حفظ")
    for _ in range(15):
        files=adb("shell","find","/sdcard/Download","-type","f").decode().splitlines()
        backup=next((f for f in files if "masalik_yemen_backup" in f),None)
        if backup:
            raw=adb("shell","cat",backup)
            try: state=json.loads(raw)
            except (ValueError,UnicodeError):time.sleep(.5);continue
            adb("shell","mv",backup,"/sdcard/Download/"+name)
            (OUT/name).write_bytes(raw)
            return state
        time.sleep(1)
    raise AssertionError("No completed game export in Downloads")

adb("shell","wm","size","720x1280")
adb("shell","wm","density","160")
adb("shell","pm","clear",OLD)
open_app(OLD)
choose("تعز")
choose("انطلق في الرحلة")
time.sleep(3)
adb("shell","input","keyevent","KEYCODE_HOME")
time.sleep(2)
open_app(OLD)
settings()
old=export("cities-legacy.json")
assert old["missionRun"]["id"]=="journey_taiz_market",old
adb("shell","pm","clear",NEW)
open_app(NEW)
settings()
choose("استيراد نسخة محفوظة من الهاتف",scroll=True)
# Show Downloads if the document provider starts in Recent.
try:
    choose("cities-legacy.json")
except AssertionError:
    choose("Show roots","Open navigation drawer","إظهار الجذور","إظهار المواقع")
    choose("Downloads","التنزيلات")
    choose("cities-legacy.json")
time.sleep(2)
adb("shell","input","keyevent","KEYCODE_HOME")
time.sleep(2)
open_app(NEW)
settings()
new=export("cities-restored.json")
for key in ("playerNameAr","selectedVehicleId","unlockedVehicles","masalikCoins","driverXp",
            "customizations","missionCompletions","missionBestTimesSec","playerDistanceM","audioMixer"):
    assert old[key]==new[key],(key,old[key],new[key])
assert all(new["missionRun"].get(k)==v for k,v in old["missionRun"].items()), (old["missionRun"],new["missionRun"])
for key,value in old["hudSettings"].items():
    if key=="smartWheel":
        assert all(new["hudSettings"][key][k]==v for k,v in value.items())
    else: assert value==new["hudSettings"][key],key
print("PASS exported an active 3.4 Taiz journey via SAF, imported it into Cities, restarted, re-exported and compared values")
