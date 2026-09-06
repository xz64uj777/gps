# Lane-Level GPS MVP

Android-first, open-source/self-hosted prototype for early, live lane-level navigation.

## Implemented starter components

- Android/Jetpack Compose shell with forward-looking lane visualization.
- Runtime precise/approximate location permission flow.
- Live Android `LocationManager` GPS/GNSS fixes.
- Live `GnssStatus` satellite visibility and used-in-fix counts.
- Explicit diagnostic states for precise permission, provider availability, and first fix.
- Pure Kotlin `lane-engine` module.
- Probabilistic lane matcher with uncertainty handling.
- Weighted lane-graph planner.
- FastAPI lane-corridor backend.
- PostgreSQL/PostGIS schema.
- OSM lane-tag normalization helper.
- JSONL drive replay/evaluation tool.
- Docker Compose development stack.
- Architecture specification.

## Layout

```text
android/
  app/
  lane-engine/
backend/
database/
tools/drive-replay/
docs/
docker-compose.yml
```

## Backend

```bash
docker compose up --build
```

API docs: `http://localhost:8080/docs`

## Android

Open `android/` in Android Studio. Requirements:

- JDK 17
- Android SDK 37
- Gradle 9.6.0
- Android Gradle Plugin 9.4.0

The current Android screen is a live GNSS diagnostic build. It requests location
permission, reports whether precise location is enabled, shows GPS/network provider
state, and displays latitude, longitude, accuracy, speed, bearing, plus GNSS satellite
counts as fixes arrive.

The forward-looking lane graphic is intentionally a visualization placeholder.
Real lane geometry is not loaded yet, so `Lane data: NOT LOADED` is expected until
the OSM route/lane corridor is connected to Android.

## Live GNSS test

1. Grant **Location → Precise → While using the app**.
2. Make sure the phone's system Location setting is ON.
3. Test outdoors with a clear sky view.
4. Launch the app.
5. `GNSS fix: waiting…` should transition to `GNSS fix: OK` after a location fix.
6. Latitude, longitude, accuracy, speed, bearing, and satellite counts should update.

If `Precise: NO`, exact-lane mode should remain unavailable rather than pretending to know the lane.

## Replay evaluator

```bash
python tools/drive-replay/replay.py tools/drive-replay/sample-drive.jsonl
```

## Core next steps

1. Connect live GNSS observations to the lane matcher and active-route lane candidates.
2. Motion-sensor fusion and device-to-vehicle calibration.
3. OSM PBF importer for the selected launch region.
4. Lane-centerline generation and junction connectivity enrichment.
5. Self-hosted OSM road routing integration.
6. Active-route corridor download/cache.
7. Drive recorder with passenger-entered ground truth.
8. Field calibration of confidence thresholds.

## Gradle wrapper note

The package includes `gradle-wrapper.properties` but not the generated wrapper
JAR/scripts. If your IDE does not create them automatically, run:

```bash
cd android
./bootstrap-gradle.sh
```

using a locally installed Gradle once, then commit the generated wrapper files.
