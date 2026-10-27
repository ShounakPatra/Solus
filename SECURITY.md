# 🛡️ Security Policy — Solus

Solus is an open-source, on-device AI assistant for Android. Inference and document retrieval run locally. Downloads, update checks, selected tools, and explicitly shared reports or backups have separate network and data-handling considerations.

This document outlines our security model, supported versions, and procedures for responsibly reporting security vulnerabilities.

---

## 📋 Supported Versions

The current version is **v6.0.5** (version code **11**, Android 11/API 30 or newer). Obtain signed APKs from [GitHub Releases](https://github.com/ShounakPatra/Solus/releases/latest). Report the exact installed version and whether you used a published APK or a source build. Historical release notes are not a guarantee of continued backports to older versions.

---

## 🔒 Security Architecture

Solus uses local inference and app-private workspace storage:

1. **Local inference:** Text generation, vision/audio inference, chats, and document retrieval can run without a network connection once required models are available.
2. **Network use:** Model downloads, Hugging Face authentication, update checks, Wikipedia, external services and user-initiated sharing can use the network. Solus does not automatically upload conversations or crash reports; review a report before sharing it.
3. **Access tokens:** Solus v6.0.5 encrypts Hugging Face tokens with AES-GCM and a device-local Android Keystore key, migrating legacy preference keys after encrypted persistence succeeds. App settings are excluded from Android backup/device transfer, and tokens are excluded from workspace backups. The published v6.0.0 APK used app-private preferences without this encryption; hardware-backed key storage is not guaranteed on every device.
4. **Storage boundaries:** Chats, memories, indexes, and durable attachments use app-private storage. Models can use app-specific external storage or user-granted document URIs. Keep selected model files accessible after import.
5. **Portable backups:** Backups contain private chats, memories, settings, and optionally available attachments. Model weights, credentials, and document indexes are excluded. Choose a trusted export location and share backups deliberately.
6. **Recovery:** Malformed persisted chat and knowledge data requires recovery before writes are accepted. Supported backups can be used for recovery; malformed records are not silently discarded.
7. **Native runtimes:** Native inference uses third-party libraries and can fail or crash for unsupported models or devices. Successful builds and unit tests do not establish that all native execution is safe on every device.
8. **Untrusted models and tools:** Imported model files, templates, retrieved passages and tool results are untrusted input. Enable only needed skills and review calendar, email, sharing and external-app actions. Tool-call bounds and permission checks do not establish universal prompt-injection protection.
9. **Release identity:** Production signing uses the approval-protected `release` environment. Update acceptance depends on package/version and approved signing identity; do not distribute a debug or smoke-test APK as the production artifact.

---

## 🚨 Reporting a Vulnerability

We take all security reports seriously. If you discover a security vulnerability or privacy concern in Solus, please report it privately.

> [!IMPORTANT]
> **Do NOT create public GitHub issues or discussions for security vulnerabilities.**

### How to Report Privately

1. **Email Disclosure:** Send an email directly to **`shounakpatra@gmail.com`** with the subject line:
   `[SECURITY VULNERABILITY] Solus - <Brief Description>`
2. **GitHub Private Vulnerability Reporting:** Alternatively, submit a private report via the [Solus Security Advisory](https://github.com/ShounakPatra/Solus/security/advisories/new) tab on GitHub.

### What to Include in Your Report

To help us investigate and resolve the issue quickly, please include:
- A detailed description of the vulnerability and potential security impact.
- Step-by-step instructions or proof-of-concept (PoC) code to reproduce the issue.
- The Solus app version (`versionName` and `versionCode`).
- Android OS version, device model, and hardware specs (e.g., RAM, SoC).
- Any relevant stack traces, logs, or memory dumps (ensure no personal sensitive data is included in logs).

---

## ⏱️ Response Timeline & Disclosure Protocol

When a security vulnerability is reported:

1. **Initial Acknowledgment:** You will receive an email response acknowledging receipt of your report within **24 to 48 hours**.
2. **Assessment & Triage:** The Solus maintainer will investigate the issue and determine severity within **3 to 5 business days**.
3. **Patch Development:** A security fix will be engineered, tested, and validated against supported Android API levels.
4. **Coordinated Release:** A patch release will be published to GitHub Releases alongside an advisory acknowledging the reporter (unless anonymity is requested).

---

## 📜 Credit & Recognition

We deeply appreciate security researchers and open-source contributors who help keep Solus secure. Reporters of verified security vulnerabilities will be recognized in our release notes and Security Hall of Fame (with explicit permission).

---

*For general non-security issues or feature requests, please use our [GitHub Issue Tracker](https://github.com/ShounakPatra/Solus/issues).*
