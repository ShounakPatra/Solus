# Official F-Droid candidate

The official catalog candidate uses F-Droid's own app signing key. It intentionally
has no `Binaries`, per-build `binary`, or `AllowedAPKSigningKeys` fields.
GitHub release signing and the separate custom repository retain the developer key.
The two distributions cannot update each other in place because their signatures differ.

The GitHub source-check workflow builds and scans the app and saves the unsigned APK
and a recipe pinned to the checked source. It does not compare against GitHub APKs,
sign an official F-Droid APK, or publish to the official catalog.

The recipe declares its Debian packages, OpenJDK 21, Android SDK components,
NDK 28.2.13676358 and Rust 1.90.0 through fdroiddata's rustup srclib. It removes
the upstream daemon auto-download file and uses the Debian JDK instead. Gradle
remains 9.8.2 with its checked distribution SHA-256.

`solusFdroid=true` disables GitHub update checks, downloads and installation, and
changes the settings button to open F-Droid. The recipe removes the APK-install
permission. This prevents offering an update signed with the incompatible GitHub key.

The cloud rehearsal executes the recipe's exact sudo block, since local `fdroid
build --test` skips it. Only NDK installation is bootstrapped separately because
fdroid validates its path before executing prebuild. The official `--server`
builder installs the declared NDK itself. This Ubuntu rehearsal does not replace
testing on F-Droid's Debian buildserver.

The workflow saves a resolved runtime inventory, POM license evidence, native library
inventory, Rust license metadata and findings alongside scanner/build logs. See
DEPENDENCY_REVIEW.md for manual scope and remaining review questions.

Before submitting to fdroiddata, pass the updated rehearsal and pin the approved
release source. F-Droid maintainers must review the recipe and dependency evidence,
build it on their infrastructure, and sign/publish the accepted app.

Update request #4686 to state that developer-signature reproducibility is no longer
requested: https://gitlab.com/fdroid/rfp/-/work_items/4686
