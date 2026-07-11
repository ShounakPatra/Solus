# Contributing to Solus

Thanks for helping improve private, on-device AI on Android. Bug fixes, model compatibility reports, focused features, tests, and documentation improvements are welcome.

## Before You Start

- Search existing issues and pull requests before opening a duplicate.
- Open an issue before starting a large feature or architectural change.
- Keep changes focused; unrelated refactors should use a separate pull request.
- Never commit model files, APKs, keystores, access tokens, build output, or local IDE configuration.

## Development Setup

You will need Android Studio, Android SDK 36, and JDK 17.

```bash
git clone https://github.com/ShounakPatra/Solus.git
cd Solus
```

Open the project in Android Studio and allow Gradle sync to finish.

## Validation

Run the same checks used by continuous integration before submitting a pull request.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Add or update unit tests when changing prompt policy, model metadata, thinking behavior, download recovery, or response processing.

## Pull Requests

- Explain what changed, why it changed, and how it was tested.
- Include the affected model and runtime for inference-specific fixes.
- Add screenshots for visible UI changes when practical.
- Update `CHANGELOG.md` for notable user-facing changes.
- Confirm that no credentials or private data appear in logs or screenshots.

By contributing, you agree that your work will be licensed under the repository's [Apache License 2.0](LICENSE) and that you will follow the [Code of Conduct](CODE_OF_CONDUCT.md).
