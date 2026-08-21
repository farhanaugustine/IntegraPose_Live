# Build IntegraPose Live

IntegraPose Live is an Android Studio project. The public source contains no
model weights or test videos. Import a compatible ONNX file or NCNN model
package after installing the app.

## Requirements

- JDK 17, selected explicitly as Android Studio's Gradle JDK
- Android SDK Platform 35
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- An internet connection for the first Gradle dependency sync

Android Studio normally offers to install missing SDK, NDK, and CMake
components during project sync. `local.properties` is intentionally excluded
because it contains the Android SDK path for one computer; Android Studio
creates it locally.

Do not assume the JBR bundled with the currently installed Android Studio is
JDK 17. Newer Android Studio releases may bundle a newer JBR that this
Gradle/Kotlin combination cannot run. In Android Studio, set **Settings > Build
Tools > Gradle > Gradle JDK** to a JDK 17 installation. For command-line builds,
set `JAVA_HOME` to the same JDK and confirm `./gradlew --version` reports JVM 17.

## Android Studio

1. Extract or clone the project.
2. Open the folder containing `settings.gradle.kts` in Android Studio.
3. Allow Gradle sync to finish and install any requested SDK components.
4. Select the `debug` build variant.
5. Connect an Android device or start an emulator, then press **Run**.

The public debug build is debuggable for Android Studio but otherwise uses the
same startup and product workflow as the release build. It does not expose or
package the private validation kit.

## Command line

On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

On macOS or Linux:

```bash
chmod +x gradlew
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

With an emulator or test device connected, run
`./gradlew :app:connectedDebugAndroidTest`. The GitHub Actions workflow runs the
unit, lint, build, and connected-device gates for pushes and pull requests.

Debug APKs are written under `app/build/outputs/apk/debug/`. Release APKs are
unsigned unless you configure your own signing key. Use Android Studio's
**Build > Generate Signed Bundle / APK** workflow for a distributable release.
Do not commit keystores, passwords, or machine-local signing files.

## Native dependency

The required NCNN 20260526 Android Vulkan prebuilt libraries are vendored under
`third_party/ncnn/` for the four configured Android ABIs. Their license and
official release source are documented in that directory.

## Models

The app accepts supported ONNX files and NCNN packages supplied by the user.
File format alone does not guarantee tensor-layout compatibility. Review the
model contract described in [README.md](README.md) before importing a model.
