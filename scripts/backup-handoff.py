"""Cross-package transfer through Android's document picker. Only run on disposable CI devices."""
import json
import re
import shlex
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
    adb("shell","am","start","-W","-f","0x10008000","-n",pkg+"/com.masalik.yemen.MainActivity")
def settings():
    choose("lobby_settings_button","الإعدادات",scroll=True)
    choose("قاعدة بيانات الهاتف")
def export(name):
    choose("تصدير نسخة احتياطية (.masalik.json)",scroll=True)
    choose("android:id/button1","SAVE","Save","حفظ")
    for _ in range(15):
        files=adb("shell","find","/sdcard/Download","-type","f").decode().splitlines()
        backup=next((f for f in sorted(files, key=lambda v: ("(" in v),reverse=True) if "masalik_yemen_backup" in f),None)
        if backup:
            raw=adb("shell","cat",shlex.quote(backup))
            try: state=json.loads(raw)
            except (ValueError,UnicodeError):time.sleep(.5);continue
            if name=="cities-restored.json" and "pedalExponent" not in state["hudSettings"]:
                for n in tree():
                    if n.get("text","").lower() in ("replace","استبدال","yes","نعم"):
                        x,y,r,b=map(int,re.findall(r"\d+",n.attrib["bounds"]))
                        adb("shell","input","tap",(x+r)//2,(y+b)//2)
                time.sleep(.5)
                continue
            (OUT/name).write_bytes(raw)
            return state
        time.sleep(1)
    raise AssertionError("No completed game export in Downloads")

adb("shell","wm","size","720x1280")
adb("shell","wm","density","160")
adb("shell","settings","put","system","accelerometer_rotation","0")
adb("shell","settings","put","system","user_rotation","1")
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
adb("shell","run-as",NEW,"mkdir","-p","files/migration-test")
subprocess.run(["adb","shell",f"run-as {NEW} sh -c 'cat > files/migration-test/legacy.json'"],
               input=(OUT/"cities-legacy.json").read_bytes(),check=True,timeout=30)
# Drive the new Android share receiver with a granted FileProvider URI containing
# the exact old export. This exercises ContentResolver and the persisted Room state.
instrument=adb("shell","am","instrument","-w","-e","mode","backup",
    NEW+".test/com.masalik.yemen.TouchDriveProbe").decode()
(OUT/"share-test.txt").write_text(instrument)
assert "PASS legacy document shared" in instrument,instrument
new_raw=adb("exec-out","run-as",NEW,"cat","files/migration-test/restored.json")
(OUT/"cities-restored.json").write_bytes(new_raw)
new=json.loads(new_raw)
assert new["schemaVersion"] == 4 and "tripHistory" in new
for key in ("playerNameAr","selectedVehicleId","unlockedVehicles","masalikCoins","driverXp",
            "customizations","missionCompletions","missionBestTimesSec","playerDistanceM","audioMixer"):
    assert old[key]==new[key],(key,old[key],new[key])
assert all(new["missionRun"].get(k)==v for k,v in old["missionRun"].items()), (old["missionRun"],new["missionRun"])
for key,value in old["hudSettings"].items():
    if key=="smartWheel":
        assert all(new["hudSettings"][key][k]==v for k,v in value.items())
    else: assert value==new["hudSettings"][key],key
print("PASS actual 3.4 document export, Android share receiver, Room restoration and re-export compared")
