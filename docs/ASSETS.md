# 3.1 runtime assets

| Asset | Origin and license | Integration |
|---|---|---|
| materials/asphalt_floor.jpg | [Poly Haven, Asphalt Floor](https://polyhaven.com/a/asphalt_floor), CC0 | Original 1K diffuse JPEG, overlaid with local lane markings |
| materials/cobblestone_floor_001.jpg | [Rob Tuytel, Poly Haven](https://polyhaven.com/a/cobblestone_floor_001), CC0 | Original 1K diffuse JPEG, used on sidewalks |
| audio/engine.pcm | [domasx2, Racing car engine sound loops](https://opengameart.org/content/racing-car-engine-sound-loops), CC0 | loop_0.wav converted to mono 22,050 Hz signed little-endian PCM, mixed with synthesized vehicle-family harmonics |
| audio/start.pcm | [looneybits, Car engine start 01](https://opengameart.org/content/car-engine-start-01), CC0 | generic_car_startengine.wav converted to mono 22,050 Hz signed little-endian PCM |
| fleet/*.mesh | Original shapes created for this project | 13 body profiles with 2 mesh detail levels. Editable parameters and generator in art/fleet. Run python3 art/fleet/build.py --obj DIR for OBJ export. Runtime applies the car dimensions in meters. |
| Wheel, cockpit, street, market and other scene parts | Supplied Kotlin geometry with local edits | See renderer, game/CityArchitectureCatalog.kt and docs/REFERENCES.md |
| App icon, bundled fonts and original artwork | Preserved from supplied 3.0.0 archive | Ownership and distribution records for these inherited assets require owner review |

[License for the CC0 assets](https://creativecommons.org/publicdomain/zero/1.0/).
The sedan, pickup and minibus retain procedural cabin assemblies. The engine recording is shared across the fleet.
