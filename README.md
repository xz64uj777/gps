# Lane-Level GPS MVP

Android-first, open-source/self-hosted prototype for early, live lane-level navigation.

## Implemented starter components

- Android/Jetpack Compose shell with forward-looking lane visualization.
- Runtime precise/approximate location permission flow.
- Live Android GPS/GNSS location fixes and GNSS satellite-status collection.
- Live accelerometer, gyroscope, and rotation-vector heading telemetry.
- Real phone observations are passed into the Kotlin lane matcher (real lane candidates still await the route-corridor cache).
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

The Android UI no longer fabricates a current-lane estimate. It requests foreground
location in context, streams real phone GPS/GNSS and motion-sensor observations, and
passes them into `LaneMatcher`. Because the OSM route/lane corridor is not yet cached
on-device, the candidate lane list is intentionally empty and the UI reports the
current lane as uncertain.

The next Android seam is the active-route lane-corridor cache; the next backend seam
is the OSM PBF -> road/lane graph importer.

## Replay evaluator

```bash
python tools/drive-replay/replay.py tools/drive-replay/sample-drive.jsonl
```

## Core next steps

1. Device-to-vehicle sensor-frame calibration + sensor fusion.
2. OSM PBF importer for the selected launch region.
3. Lane centerlines and junction connectivity.
4. Lane-centerline generation and connectivity enrichment.
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
