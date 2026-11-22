# Masalik Yemen 3.0.0

## محتويات المشروع

هذا مشروع أندرويد كامل قابل للتعديل والبناء، ويشمل الكود والموارد وGradle Wrapper والاختبارات. لا يحتوي على مفتاح توقيع أو كلمات مرور أو مجلدات البناء. لا يحتاج اللعب والحفظ ولوحة الرحلات المحلية إلى خادم.

الاسم التقني: `com.masalik.yemen`، رقم الإصدار: `3.0.0`، رقم البناء: `5`. الحد الأدنى أندرويد 8.0 (API 26).

## مهم: توقيع التحديثات

ملف APK المرفق منفصلاً موقّع بالشهادة المستخدمة في الإصدار السابق. المفتاح الخاص غير موجود داخل هذا الأرشيف. لإنشاء تحديث يقبله الهاتف فوق النسخة الحالية تحتاج إلى **المفتاح الأصلي نفسه**. إنشاء مفتاح جديد لا يستعيد هوية التوقيع القديمة. لا تحذف النسخة المثبتة لتجاوز خطأ التوقيع قبل تصدير نسخة احتياطية من حفظك.

راجع `docs/KEY-CUSTODY.md` قبل تسليم المشروع إلى مطور آخر. لم تُجهز نسخة احتياطية دائمة للمفتاح ضمن هذا التسليم.

## Build requirements

- JDK 21 was used for this build. Java source compatibility is 17.
- Android SDK Platform 36 and Build Tools 36.0.0, with accepted SDK licenses.
- Gradle 9.3.1 is pinned by the included wrapper. The first build needs internet for Gradle and Maven dependencies. This is not a dependency-offline bundle.
- Set `ANDROID_HOME` to your installed Android SDK directory, or create your own untracked `local.properties` containing `sdk.dir=/your/sdk/path`.
- Use a current Android Studio release that supports the Android Gradle Plugin version pinned in `gradle/libs.versions.toml`, or use the command line below.

## Build an update with the existing signing key

Obtain the original key through a separately agreed secure transfer, not a chat attachment. Keep it outside the source tree. In a local Bash terminal:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export MASALIK_KEYSTORE=/absolute/private/path/original.keystore
export MASALIK_KEY_ALIAS=androiddebugkey
read -r -s -p 'Store password: ' MASALIK_STORE_PASSWORD; echo
export MASALIK_STORE_PASSWORD
read -r -s -p 'Key password: ' MASALIK_KEY_PASSWORD; echo
export MASALIK_KEY_PASSWORD
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
unset MASALIK_STORE_PASSWORD MASALIK_KEY_PASSWORD
```

The original signing configuration is a debug-origin certificate, even for the release variant. Changing the build variant does not change the certificate's origin. Treat the key as sensitive; do not reuse it for another app.

Output: `app/build/outputs/apk/release/app-release.apk`.

Windows: use `gradlew.bat` from a terminal with the same environment variables set securely. Do not paste passwords into commands or commit them to files. The helper scripts below require Bash.

For a memory-limited build, use this instead of the final Gradle command:

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease \
  --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx900m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

## Build an independent copy with a NEW key

```bash
bash scripts/generate-new-keystore.sh /absolute/private/path/masalik-new.p12
export MASALIK_KEYSTORE=/absolute/private/path/masalik-new.p12
export MASALIK_KEY_ALIAS=masalik
# Read/export the two password variables as above, then run Gradle.
```

The script asks for the new password directly in your terminal through Java keytool. It does not print it or save it in the source. PKCS12 uses the same store and key password. This new identity cannot update the currently installed app. For side-by-side development, change `applicationId` to a distinct ID first; this creates a separate app and separate local data.

For unit tests alone, no signing key needs to be present:

```bash
./gradlew :app:testDebugUnitTest
```

## What changed in 3.0

This is a procedural simulation expansion, not a geographic or photorealistic reconstruction.

- **Cities and streets:** Regional building-height profiles, Sana'a gypsum details, Shibam earth-toned towers, lower-rise Zabid courtyard forms, Aden-style wooden screens. Urban hubs have fictional connected side-street grids with physical road boundaries, rather than decorative roads only. Existing missions still follow the main 22 km route. Added side streets are not real street maps.
- **Vehicles:** Separate upright utility and sloped car cabin shapes, pickup beds, minibus window divisions, painted pillars, rounded bodywork, traffic details and a more utility-heavy traffic mix. The 13 vehicle names are game archetypes, not licensed replicas. Do not interpret them as exact Hilux or Land Cruiser models.
- **People and markets:** Jointed leg motion, foot lift, garment hems, maawis stripes, residents who stop and stroll near market stalls, produce crates and awnings. Clothing remains simplified procedural geometry; there are no motion-captured people or full social AI.
- **Environment and traffic:** Existing rain, dust, day/night, headlights and wipers now work with weather/curvature traffic speed limits, visibility headlights and safer lane changes. Drivers check target-lane gaps ahead and behind. These are bounded rules, not a learned traffic model.
- **Driving and sound:** Traction-limited yaw outside arcade mode, grip-sensitive lateral inertia, handbrake response and five synthesized engine timbre families combined with each vehicle's base frequency. Audio is synthesized, not recordings of real engines. No claim of a calibrated tire or suspension solver is made.
- **UI and lower-end devices:** Initial loading waits for both profile and first scene frame, compact-by-default speed/RPM/fuel display, tap-to-expand full gauges, reduced cockpit dashboard overlap, a hidden-until-open camera drawer and smaller cockpit touch steering. New installations on low-RAM/under-3 GB devices start at LOW/30 FPS, without overwriting existing saved settings. LOW bounds traffic, people, weather particles and environment draw distance and disables the live mirror and emitted vehicle particles.
- **First-person cockpit:** Visible hands and forearms follow the steering wheel, with lap/knees, cabin sides, dashboard, windshield frame and animated wipers. The windshield opening stays clear; this is not a transparent-glass ray-tracing effect. Camera control supports the existing cockpit, hood, chase and orbit modes.

Architectural and vehicle context is documented in `docs/REFERENCES.md`. No new third-party 3D pack or real engine recording was added.

## Verification and limits

18 JVM regression tests cover expansion data, mesh finite values/normals, street boundary projection, side-street vehicle movement, low-grip response, traction-limited turning, engine-voice normalization, all-vehicle/weather finite states and a traffic/weather run. They are not end-to-end driving tests.

Release lint completed with 0 errors and 59 warnings. The final APK passed signature, package/version, ABI and 16 KB alignment checks. Both debug and final release builds were installed and opened on an Android 8/API 26 software emulator. Actual frames were inspected for the menu, cockpit with visible hands, Sana'a-inspired night/rain/wipers, and the Shibam-inspired street junction. The final release menu and normal driving HUD were also inspected after dismissing Android's first-run full-screen help. Those screenshots demonstrate rendering only. They do not prove physical-phone frame rate, continuous play reliability, every city, every vehicle, tilt steering, backup restoration or all quality presets. No physical Android phone was available for this pass. LOW intentionally looks more pixelated and sparse.

The source includes a debug-only emulator smoke-test entry point. Release builds ignore its intent extras. It is for repeatable scene inspection, not a hidden release feature.

## Device acceptance checklist

1. Export progress from the installed app. Install the update without uninstalling and confirm saved progress remains.
2. Check the menu at the phone's normal display and font size, including scrolling to every action.
3. Switch between cockpit, hood, near/far chase and orbit. Check hands, road visibility, wipers and HUD touch targets.
4. Visit city hubs, turn into cross-streets and drive the parallel side streets. Check boundaries and returning to the main route. The layout is fictional; mission finish points remain on the main route.
5. Compare city profiles, daytime/nighttime and rain/dust. Check market residents and traffic yielding. Check that clear weather removes rain effects.
6. Try LOW, MEDIUM and HIGH and 30/45/60 FPS targets. More geometry can cost GPU time; targets are not guarantees. Do not use software-emulator speed as a phone benchmark.
7. Try touch and tilt steering, reverse, parking, pause/resume and a mission completion. Compare loose-surface steering with dry asphalt.
8. Capture a screenshot at LOW and HIGH. Confirm saved images are complete.
9. Export/import a backup and check settings, money and mission records. Trip history is intentionally excluded from backup.

## Local data

The local leaderboard is the best five recent recorded trips by distance, not a global or all-time board. Save backup covers progress, settings and mission records, not recent-trip history. There is no multiplayer server, online ranking or cloud backup in this version.

## Assets and rights

The project retains the user's supplied resources. No new third-party 3D model pack was added in this pass. This package does not assert rights clearance for every original resource. Review original media before store distribution.

## Project map

- `app/src/main/java/com/masalik/yemen/game/`: simulation, vehicles, missions and local data.
- `app/src/main/java/com/masalik/yemen/renderer/`: OpenGL renderer, shaders and meshes.
- `app/src/main/java/com/masalik/yemen/ui/`: Compose screens and controls.
- `app/src/main/res/`: Android resources and launcher graphics.
- `app/src/test/`: JVM regression tests.
- `gradle/wrapper/`, `gradlew`, `gradlew.bat`: portable Gradle entry points.
- `scripts/`: new-key helper; never creates a compatible replacement for a lost original key.
