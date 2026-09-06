# GitHub APK build

This repository includes `.github/workflows/android-apk.yml`.

## Build an APK

1. Push to `main`, or run **Build Android APK** from the Actions tab.
2. Wait for the build job to succeed.
3. Download the **lane-gps-debug-apk** artifact.
4. The APK inside is `app-debug.apk`.

The workflow installs Gradle 9.6.0 and builds the Android project under `android/`.

## Updateable development APKs

Main-branch builds cache one development-only Android debug signing key and reuse it on later builds. The workflow also sets `versionCode` from the GitHub Actions run number, so each later main-branch APK can install over the previous one.

Because APKs built before this change were signed with temporary runner keys, testers must uninstall the older build once before installing the first APK produced by this new workflow. After that one-time transition, later main-branch APKs should update in place.

This cached key is only for development/testing. A production or Play Store release must use a separate private release key stored as a GitHub secret or another secure signing service.
