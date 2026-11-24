"""Exercise the lobby routes and driving controls through Android accessibility."""
import re
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree

package = "com.masalik.yemen.preview"
out = Path("journey-captures")
out.mkdir(exist_ok=True)

def adb(*args, timeout=25):
    return subprocess.check_output(["adb", *args], timeout=timeout)

def find_label(label, click=False):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        try:
            adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/journey-ui.xml", timeout=15)
            data = adb("shell", "cat", "/sdcard/journey-ui.xml")
            nodes = ElementTree.fromstring(data).iter("node")
            for node in nodes:
                if not any(value == label or value.startswith(label+" · ")
                           for value in (node.get("text",""),node.get("content-desc",""))):
                    continue
                if click:
                    x1,y1,x2,y2=map(int,re.findall(r"\d+",node.attrib["bounds"]))
                    adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
                return
        except (subprocess.SubprocessError,ElementTree.ParseError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Missing Android UI label: {label}")

def capture(name):
    (out / f"{name}.png").write_bytes(adb("exec-out","screencap","-p"))

def cockpit(slug, vehicle, weather, city, night=False):
    adb("shell","pm","clear",package)
    adb("shell","am","start","-W","-n",package+"/com.masalik.yemen.MainActivity",
        "--ez","masalik_test_cockpit","true","--es","masalik_test_vehicle",vehicle,
        "--es","masalik_test_city",city,"--es","masalik_test_weather",weather,
        "--ez","masalik_test_night",str(night).lower())
    find_label("أدوات المركبة")
    if weather == "HEAVY_RAIN":
        find_label("أدوات المركبة",click=True)
        find_label("المسّاحات · تلقائية",click=True)
        find_label("المسّاحات · تشغيل",click=True)
        find_label("المسّاحات · إيقاف")
        find_label("أدوات المركبة",click=True)
        capture(slug+"-parked")
        find_label("أدوات المركبة",click=True)
        find_label("المسّاحات · إيقاف",click=True)
        find_label("المسّاحات · تلقائية")
        find_label("أدوات المركبة",click=True)
    capture(slug)
    print(f"Rendered {vehicle} cockpit in {weather}",flush=True)

try:
    adb("shell","wm","size","720x1280")
    adb("shell","wm","density","160")
    adb("shell","pm","clear",package)
    adb("shell","am","start","-W","-n",package+"/com.masalik.yemen.MainActivity")
    find_label("قيادة حرة",click=True)
    find_label("تشغيل السائق الآلي",click=True)
    try:
        find_label("إيقاف السائق الآلي")
    except RuntimeError:
        capture("auto-driver-failed")
        print(adb("shell","cat","/sdcard/journey-ui.xml").decode(),flush=True)
        raise
    capture("auto-driver")
    find_label("إيقاف السائق الآلي",click=True)
    find_label("تشغيل السائق الآلي")
    print("Activated and released the automatic driver from the driving HUD",flush=True)
    cockpit("cockpit-tourer-night","SHAHIQ","CLEAR","city_taiz_sabir",night=True)
    cockpit("cockpit-pickup-rain","SAQR","HEAVY_RAIN","city_ibb_green")
    cockpit("cockpit-sedan","MADINA","CLEAR","city_sanaa_old")
    for slug, place, objective in [
        ("taiz-market","تعز","مخزن البن · ادخل الشارع الشرقي الموازي وحمّل الأكياس"),
        ("taiz-main","تعز","مخزن البن · ادخل الشارع الشرقي الموازي وحمّل الأكياس"),
        ("ibb","إب","عند المدرجات · ثبّت السيارة وحمّل أكياس البن"),
        ("sanaa","صنعاء","توقّف في الشارع الشرقي الموازي لصعود الركاب"),
        ("aden","عدن","تابع السير بمحاذاة البحر")
    ]:
        # Reset only the disposable test install on the CI emulator.
        adb("shell","pm","clear",package)
        adb("shell","am","start","-W","-n",package+"/com.masalik.yemen.MainActivity")
        find_label(place,click=True)
        if slug == "taiz-main":
            find_label("الشارع الرئيسي",click=True)
            find_label("حمولة صبر · الطريق الرئيسي")
        find_label("انطلق في الرحلة")
        capture("menu-"+slug)
        find_label("انطلق في الرحلة",click=True)
        find_label(objective)
        capture(slug)
        print(f"Opened {slug} from lobby and observed its first objective",flush=True)
finally:
    logs=adb("logcat","-d","MasalikShader:E","AndroidRuntime:E","*:S")
    (out/"android.log").write_bytes(logs)
    if b"Shader compile error" in logs or b"Program link failed" in logs or b"FATAL EXCEPTION" in logs:
        sys.exit("Android runtime or shader failure; inspect android.log")
