# Official F-Droid candidate

The official catalog candidate uses F-Droid's own app signing key. It intentionally
has no `Binaries`, per-build `binary`, or `AllowedAPKSigningKeys` fields.
GitHub release signing and the separate custom repository retain the developer key.
The two distributions cannot update each other in place because their signatures differ.

The GitHub source-check workflow builds and scans the app and saves the unsigned APK
and a recipe pinned to the checked source. It does not compare against GitHub APKs,
sign an official F-Droid APK, or publish to the official catalog.

Before submitting to fdroiddata, finish the production build prerequisites in the
recipe (currently provisioned by GitHub), review dependency licenses and inclusion
requirements, and pin the release source. F-Droid maintainers must review the recipe,
build it on their infrastructure, and sign/publish the accepted app.

Update request #4686 to state that developer-signature reproducibility is no longer
requested: https://gitlab.com/fdroid/rfp/-/work_items/4686
