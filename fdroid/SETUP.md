# Solus F-Droid repository setup

This manual workflow publishes a custom repository, not the official F-Droid catalog.
It downloads a signed `app-release.apk` and `app-release.apk.sha256` from the
specified GitHub release, checks the checksum and the Solus APK certificate, and
generates a separate signed F-Droid index. It never re-signs the app APK.

Before running it:

1. Publish and phone-test the intended APK and its matching checksum. The current
   release is a prerelease; publishing it to this repository makes it available to
   anyone who adds the repository.
2. In repository Settings > Pages, choose GitHub Actions as the source. Check that
   this will not replace an existing Pages site: deployment replaces its contents.
3. Generate a dedicated, backed-up repository-index signing keystore on a secure
   machine. Keep it separate from the Solus APK signing key and never commit it.
4. Add Actions secrets `FDROID_REPO_KEYSTORE_BASE64` (base64 of the complete
   keystore), `FDROID_REPO_KEYSTORE_PASSWORD`, `FDROID_REPO_KEY_PASSWORD`, and
   `FDROID_REPO_KEY_ALIAS`. Protect these secrets and retain a secure backup.
5. Run **Publish Solus F-Droid repository** manually with the release tag. Check the
   build and deployment results before sharing the URL.

The intended repository URL is `https://shounakpatra.github.io/Solus/fdroid/repo`.
Verify the Pages deployment and add the repository in an F-Droid client before
sharing it; the actual URL may differ if the Pages configuration changes. Users
must add this custom repository themselves.

Each run publishes the APK from one specified release only. It does not retain
older versions from previous deployments. Future versions must have a version code
Android accepts for updates. The APK and index-signing secrets are never committed.
