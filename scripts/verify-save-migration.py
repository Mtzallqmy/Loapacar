"""Replay the app's migration on the exact SQLite DDL shipped in the owner's 3.0 APK."""
import argparse
import hashlib
import json
import re
import sqlite3
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--fixture", type=Path, help="Write the populated version 2 database here")
args = parser.parse_args()
digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
if digest != "9e26db94b1928db4a9fc410093de04cb73b66ce6e42d5b0e988d9ab8b6c5c3cd":
    raise ValueError("Expected the supplied 3.0.0-full APK")
rows = {
    "player_profile": (1,"سائق تعز","SAQR",8920,410,"SPORT","MEDIUM","MORNING",1,9.5,"CLEAR",230,
                       "TAIZ_HIGHLANDS",'["city_taiz_sabir"]','["market"]',3120,"2026-11-20T09:00:00Z"),
    "garage_fleet": ("SAQR",1,47.5,'{"engineHealthPct":91.0}','{"brakeUpgradeLevel":2}'),
    "mission_records": ("story_1",51.5,1),
    "settings_hud": (1,'{"targetFps":30}','{"musicVolume":0.6}'),
    "radio_preferences": (1,'{"isOpen":true}'),
    "driving_logs": (1,"2026-11-20T09:00:00Z","SAQR","TAIZ_HIGHLANDS","مرتفعات تعز",3.12,52.5,313)
}
schema = json.loads(next((ROOT/"app/schemas").rglob("3.json")).read_text())["database"]
db = sqlite3.connect(":memory:")
with zipfile.ZipFile(args.apk) as apk:
    for dex in (p for p in apk.namelist() if p.endswith(".dex")):
        for statement, name in re.findall(rb"(CREATE TABLE IF NOT EXISTS `([a-z_]+)`[^\x00]*)", apk.read(dex)):
            if name.decode() in rows:
                db.execute(statement.decode())
for table, row in rows.items():
    db.execute(f"INSERT INTO {table} VALUES ({','.join('?' for _ in row)})", row)
db.execute("PRAGMA user_version=2")
db.commit()
if args.fixture:
    if args.fixture.exists():
        raise FileExistsError(args.fixture)
    with sqlite3.connect(args.fixture) as legacy:
        db.backup(legacy)
source = (ROOT/"app/src/main/java/com/masalik/yemen/data/MasalikDatabase.kt").read_text()
[statement] = re.findall(r'db\.execSQL\("(ALTER TABLE [^"]+)"\)',source)
with db:
    db.execute(statement)
    db.execute("PRAGMA user_version=3")
for entity in schema["entities"]:
    table = entity["tableName"]
    columns = list(db.execute(f"PRAGMA table_info({table})"))
    actual = {c[1]:(c[2],bool(c[3]),bool(c[5])) for c in columns}
    primary = entity["primaryKey"]["columnNames"]
    expected = {f["columnName"]:(f["affinity"],f.get("notNull",False),f["columnName"] in primary)
                for f in entity["fields"]}
    assert actual == expected, table
    persisted = db.execute(f"SELECT * FROM {table}").fetchone()
    assert persisted == rows[table] + ((None,) if table == "player_profile" else ()), table
print("PASS: 6 legacy tables retained all fixture values and match exported Room v3 columns and keys")
