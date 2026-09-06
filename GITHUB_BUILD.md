# GitHub APK build

This repository includes `.github/workflows/android-apk.yml`.

## Build an APK

1. Upload/push the contents of this repository to the root of your GitHub repository.
2. Open the **Actions** tab.
3. Select **Build Android APK**.
4. Use **Run workflow**, or simply push to `main`.
5. After the job succeeds, open the workflow run and download the **lane-gps-debug-apk** artifact.

The APK inside the artifact is:

`app-debug.apk`

The workflow intentionally installs Gradle 9.6 because this repository did not originally include
a complete Gradle wrapper. It builds the Android project under `android/`.
