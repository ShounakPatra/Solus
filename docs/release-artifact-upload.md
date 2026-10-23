# Attach CI APKs to a GitHub release

The manual **Attach APK to release** workflow transfers APKs on GitHub's runner.
It reuses an existing successful **Android CI** push run from `main`, with no APK
download to your PC and no additional Gradle build. CI artifacts expire after 14 days.

The workflow file must be merged into the default branch before the manual run
button appears in Actions. It never runs automatically on a push or tag.
Each dispatch requires an explicit source CI run ID. The APK kind defaults to
`signed-release`; transferring a debug APK requires selecting `debug` explicitly.

## Debug APK

1. Create the destination release in GitHub (a draft is also supported).
2. Open Actions → Attach APK to release → Run workflow.
3. Enter the existing release tag, CI run ID, and choose `debug`.

The workflow uploads the unchanged debug APK and its SHA-256 checksum. Its name
includes the release tag and source run ID. Existing assets are not overwritten.

## Signed release APK

Use your existing release keystore so installed copies of Solus can update.
In repository Settings → Secrets and variables → Actions, add these **secrets**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of your existing release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Release key alias |
| `ANDROID_KEY_PASSWORD` | Release key password |

Add the **variable** `ANDROID_SIGNING_CERT_SHA256` containing the expected release
certificate SHA-256 fingerprint, with or without colons. You can obtain it from
`keytool -list -v -keystore <your-keystore>`; do not commit the key or passwords.

Run the same workflow with `signed-release`. It downloads `solus-release-apk`,
aligns the unsigned release APK for 16 KiB native library pages, signs it, verifies
the signature and expected certificate, then uploads the APK and checksum.
Missing credentials, invalid signatures, or a certificate mismatch stop the upload.
The private key exists only in temporary runner storage and is removed after signing.
The workflow does not create releases or change a draft's publication status.

Select a source run and destination tag that describe the intended app version.
Attaching an APK does not change its embedded version name/code or the tag's commit.
Creating a new update may also require bumping the app version before the CI build.
