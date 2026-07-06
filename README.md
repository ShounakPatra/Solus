# Solus

Solus is an offline Android AI chat app for running local language models on-device.

## Features

- Local model chat without a server dependency
- Multiple model support
- Thinking mode toggle for compatible models
- LiteRT and MediaPipe runtime paths
- Output cleanup for tokenizer artifacts
- Document text extraction support
- Jetpack Compose interface with a glass-style theme

## Tech Stack

- Kotlin
- Jetpack Compose
- Android Gradle Plugin
- MediaPipe GenAI
- LiteRT LM

## Build

Open the project in Android Studio, let Gradle sync finish, then run:

```powershell
.\gradlew.bat assembleDebug
```

For a release APK:

```powershell
.\gradlew.bat assembleRelease
```

The generated APK is written under:

```text
app/build/outputs/apk/
```

## Notes

Model files are not committed to this repository. Downloaded models and local Android Studio configuration stay on the developer machine.
