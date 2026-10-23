"""Prepare an existing CI APK for a manually selected GitHub release. Never builds an APK."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def prepare(artifact_dir, output_dir, kind, tag, run_id, build_tools=None):
    if not re.fullmatch(r"v?[0-9][A-Za-z0-9._-]*", tag) or not re.fullmatch(r"[0-9]+", run_id):
        raise ValueError("Invalid release tag or run ID")
    if kind not in ("debug", "signed-release"):
        raise ValueError("Unsupported APK kind")
    root = Path(artifact_dir).resolve()
    apks = list(root.rglob("*.apk"))
    if len(apks) != 1 or not apks[0].is_file() or apks[0].is_symlink() or not apks[0].resolve().is_relative_to(root):
        raise ValueError("Artifact must contain exactly one APK within its directory")
    source = apks[0]
    output_dir = Path(output_dir)
    output = output_dir / f"Solus-{tag}-{kind}-{run_id}.apk"
    checksum = output.with_suffix(".apk.sha256")
    if output.exists() or output.is_symlink() or checksum.exists() or checksum.is_symlink():
        raise ValueError("Output already exists; refusing to overwrite it")
    output_dir.mkdir(parents=True, exist_ok=True)
    try:
        if kind == "debug":
            shutil.copyfile(source, output)
        else:
            if not source.name.endswith("-unsigned.apk"):
                raise ValueError("Expected the unsigned release APK from solus-release-apk")
            required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD", "ANDROID_SIGNING_CERT_SHA256")
            missing = [key for key in required if not os.environ.get(key)]
            if missing:
                raise ValueError("Missing signing configuration: " + ", ".join(missing))
            expected = os.environ["ANDROID_SIGNING_CERT_SHA256"].replace(":", "").strip().lower()
            if not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise ValueError("ANDROID_SIGNING_CERT_SHA256 must be the release certificate SHA-256 fingerprint")
            key_bytes = base64.b64decode("".join(os.environ["ANDROID_KEYSTORE_BASE64"].split()), validate=True)
            if not 1 <= len(key_bytes) <= 1024 * 1024:
                raise ValueError("Invalid keystore size")
            tools = Path(build_tools or "")
            if build_tools is None or not all((tools / name).is_file() for name in ("zipalign", "apksigner")):
                raise ValueError("Android build tools containing zipalign and apksigner are required")
            # Only temporary runner storage holds the private key; no Gradle properties or repository secrets files.
            with tempfile.TemporaryDirectory(prefix="solus-sign-", dir=os.environ.get("RUNNER_TEMP")) as directory:
                key = Path(directory) / "release.jks"
                key.write_bytes(key_bytes)
                key.chmod(0o600)
                aligned = Path(directory) / "aligned.apk"
                subprocess.run([str(tools / "zipalign"), "-P", "16", "-f", "4", str(source), str(aligned)], check=True, capture_output=True)
                subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(key),
                                "--ks-key-alias", os.environ["ANDROID_KEY_ALIAS"],
                                "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                                "--out", str(output), str(aligned)], check=True, capture_output=True)
                verified = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(output)],
                                          check=True, capture_output=True, text=True)
                signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", verified.stdout)
                if len(signers) != 1 or signers[0].lower() != expected:
                    raise ValueError("Signed APK certificate does not match ANDROID_SIGNING_CERT_SHA256")
        with output.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        checksum.write_text(f"{digest}  {output.name}\n", encoding="ascii")
        return output
    except Exception:
        output.unlink(missing_ok=True)
        checksum.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--kind", choices=("debug", "signed-release"), required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--build-tools")
    try:
        print(prepare(**vars(parser.parse_args())))
    except subprocess.CalledProcessError:
        raise SystemExit("Android APK alignment/signing/verification failed; check the key configuration and input APK")
