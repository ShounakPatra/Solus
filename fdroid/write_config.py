"""Write private F-Droid configuration in the runner's temporary directory."""

import base64
import os
from pathlib import Path

import yaml


def main():
    names = ("FDROID_REPO_KEYSTORE_BASE64", "FDROID_REPO_KEYSTORE_PASSWORD",
             "FDROID_REPO_KEY_PASSWORD", "FDROID_REPO_KEY_ALIAS")
    if any(not os.environ.get(name) for name in names):
        raise SystemExit("Missing F-Droid repository signing secrets")
    keystore = Path("repo-signing.jks")
    keystore.write_bytes(base64.b64decode(os.environ[names[0]], validate=True))
    config = {
        "repo_url": "https://shounakpatra.github.io/Solus/fdroid/repo",
        "repo_name": "Solus",
        "repo_description": "Solus releases signed by the Solus maintainer.",
        "keystore": str(keystore.resolve()),
        "keystorepass": os.environ[names[1]],
        "keypass": os.environ[names[2]],
        "repo_keyalias": os.environ[names[3]],
    }
    if os.name == "nt":
        sdk = os.environ.get("ANDROID_HOME")
        if not sdk:
            raise SystemExit("ANDROID_HOME is required on Windows")
        apksigner = Path(sdk) / "build-tools" / "36.0.0" / "apksigner.bat"
        if not apksigner.is_file():
            raise SystemExit("Android build-tools 36.0.0 apksigner.bat was not found")
        config["apksigner"] = str(apksigner)
    Path("config.yml").write_text(yaml.safe_dump(config), encoding="utf-8")


if __name__ == "__main__":
    main()
