# سيارتي — My Car

Arabic-first, offline Android driving game set on a fictional Yemen-inspired route.
Kotlin runs the application and simulation, C and C++ implement the four-contact tire solver,
OpenGL ES 3.0 renders the world, and Python generates the editable fleet shell meshes.

## Play the Taiz delivery

Open القصة والمهمات and choose the second mission in chapter one. Take the pickup to
the parallel street by the awning. Stop inside the outlined bay, apply the handbrake or Park,
then use the loading action. Follow the green GPS route to the hillside water tank.
Deliver the coffee and return to the marked market bay. The active stage is saved on progress
and when pausing the game. The Control Studio contains the driver's seat and camera settings.
Use Simulation realism to enable manual clutch stalling.

## Build

Use JDK 21, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1 and the Gradle wrapper.
Host-side simulation tests also require a C/C++ compiler and CMake on PATH.
From the project root run:

    python3 art/fleet/build.py
    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:assemblePreview :app:lintPreview

For preview signing set MASALIK_KEYSTORE, MASALIK_STORE_PASSWORD, MASALIK_KEY_ALIAS
and MASALIK_KEY_PASSWORD to a local development key. The preview uses
com.masalik.yemen.preview and installs beside the original game. The release variant
uses com.masalik.yemen and requires the original signing credential for an in-place
update. Set MASALIK_RELEASE_KEYSTORE, MASALIK_RELEASE_STORE_PASSWORD,
MASALIK_RELEASE_KEY_ALIAS and MASALIK_RELEASE_KEY_PASSWORD before assembling release.
Keep private keys outside the project. Version 3.1.0 uses code 6 and supports API 26+.

See docs/ASSETS.md for editable asset sources and license details. The original baseline
is commit bd8915800d47f6c332756d8694efb2001e0681a5. docs/VERIFICATION.md and
docs/KEY-CUSTODY.md record the supplied 3.0.0 baseline.
