# Prepare and upload a production release

The manual **Attach APK to release** workflow reuses an existing successful
**Android CI** push run from `main`. It transfers and signs artifacts on the GitHub
runner without downloading an APK to your PC or starting another Gradle build.
CI artifacts expire after 14 days. Preserve the matching R8 mapping and native
symbols privately when needed for crash analysis.

The workflow must be on the default branch. It runs only through manual dispatch,
not automatically when code or a tag is pushed, and requires an explicit CI run ID.
It does not create a release or publish a draft.

## 1. Prepare the source and release body

1. Include only the intended code/documentation in `main`; exclude APKs, model
   weights, credentials, scratch files and unrelated local edits.
2. Use a successful **Android CI** push run for the exact intended commit. A green
   PR check on an earlier revision does not validate later local changes.
3. Create the release tag at that same commit. The workflow checks the tag SHA
   against the source CI run; it must not be used to attach an older APK to a newer tag.
4. Create the GitHub release as a **draft** and review the release body. The prepared
   v6.0.5 body is a local `build/release/v6.0.5/release-notes.md` artifact: paste its
   contents into the v6.0.5 release page, rather than adding another committed
   `docs/releases/v6.0.5.md` or uploading the notes as an APK asset.

For v6.0.5, the app identity is `com.shounak.localmeshai`, versionName
`6.0.5`, versionCode `11`, minimum Android `11` / API `30`, and `arm64-v8a`.
LiteRT-LM remains `0.18.0`. Verify these values in the **final APK**, not just source.
Do not copy APK sizes, hashes or old validation counts from earlier releases.

## 2. Require approval before signing

In **Settings → Environments → release**, configure a required maintainer reviewer
and a deployment branch rule allowing only `main`. Signing credentials belong in
that environment, so they are unavailable until the run is approved.

Use the existing release keystore so installed copies can update. Add these
**environment secrets**, never plaintext files or committed Gradle properties:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Release key alias |
| `ANDROID_KEY_PASSWORD` | Release key password |

Add the **environment variable** `ANDROID_SIGNING_CERT_SHA256` with the expected
release-certificate SHA-256 fingerprint. Obtain it from the existing key using
`keytool -list -v -keystore <your-keystore>`; do not disclose the key or passwords.

## 3. Attach the signed APK

1. Open **Actions → Attach APK to release → Run workflow** on `main`.
2. Enter `v6.0.5`, the matching successful main CI run ID, and **signed-release**.
3. Review and approve the deployment to the `release` environment.
4. The runner downloads `solus-release-apk`, aligns its native library pages for
   16 KiB, signs it, verifies the expected certificate, and uploads the APK/checksum.

The resulting pair is:

- `Solus-v6.0.5-signed-release-<run_id>.apk`
- `Solus-v6.0.5-signed-release-<run_id>.apk.sha256`

Existing assets are not overwritten. Missing secrets, a certificate mismatch,
invalid signatures or a tag/source mismatch stop the upload. The private key is
held only in temporary runner storage and removed afterward.

**Debug and release-smoke APKs are for testing.** A workflow dispatch can explicitly
choose `debug`, but that artifact must not be presented as the production update.

## 4. Optional document-search mirror assets

The BGE search model is fetched from Hugging Face first. Solus v6.0.5 also names
these fallback assets on the **v6.0.5** release:

- `bge-small-en-v1.5-int8.onnx`
- `bge-small-en-v1.5-vocab.txt`

To provide that fallback, attach the pinned, hash-verified model and vocabulary
alongside their source/license notice and checksum file. Verify byte counts and
SHA-256 values against [EmbeddingModels.kt](../app/src/main/java/com/shounak/localmeshai/rag/embedding/EmbeddingModels.kt).
Do not rename another export to these filenames. The optional mirror files are
release assets, not files to commit to the code repository.

## 5. Verify before publishing the draft

- Download the exact prepared signed APK and verify its checksum and signing
  certificate; inspect package/version, minimum SDK, ABI, DEX and native packaging.
- Confirm the APK is **not debuggable** and can update an existing installation
  with the approved signing identity.
- Complete the relevant phone smoke tests: two-message inference, supported
  thinking/tools, documents/media, Stop/model switching, download resume, history
  deletion/restart and backup restoration. State any untested model combinations.
- Keep release notes accurate about supported export contracts and known limits.
- Publish only after the artifact and release body have been reviewed. Attaching
  assets does not change the embedded app version or publish the draft.
