# 🛡️ Security Policy — Solus

Solus is an open-source, 100% offline, on-device AI assistant for Android. Security and user privacy are foundational pillars of Solus. Inference runs locally. Downloads, update checks, and explicitly shared reports or backups have separate network and data-handling considerations.

This document outlines our security model, supported versions, and procedures for responsibly reporting security vulnerabilities.

---

## 📋 Supported Versions

The current release is **v6.0.0** (version code **9**); **v5.0.0** is the previous release. Report the exact version and whether you used a published APK or a source build. Reproduce issues on the latest published release when possible. Historical release notes are not a guarantee of continued backports to older versions.

---

## 🔒 Security Architecture

Solus is designed around a **Zero-Trust, Zero-Telemetry** local execution model:

1. **Local inference:** Text generation, vision/audio inference, chats, and document retrieval can run without a network connection once required models are available.
2. **Network use:** Model downloads, Hugging Face authentication, update checks, and user-initiated sharing can use the network. There is no automatic analytics or crash-report upload; review a report before sharing it.
3. **Access tokens:** Optional Hugging Face tokens use app-private preferences and authenticated model requests. They are excluded from workspace backups. App-private preferences are not a claim of hardware-backed encryption.
4. **Storage boundaries:** Chats, memories, indexes, and durable attachments use app-private storage. Models can use app-specific external storage or user-granted document URIs. Keep selected model files accessible after import.
5. **Portable backups:** Backups contain private chats, memories, settings, and optionally available attachments. Model weights, credentials, and document indexes are excluded. Choose a trusted export location and share backups deliberately.
6. **Recovery:** Malformed persisted chat and knowledge data requires recovery before writes are accepted. Supported backups can be used for recovery; malformed records are not silently discarded.
7. **Native runtimes:** Native inference uses third-party libraries and can fail or crash for unsupported models or devices. Successful builds and unit tests do not establish that all native execution is safe on every device.

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
4. **Coordinated Release:** A patch release (e.g., `v6.0.1`) will be published to GitHub Releases alongside an advisory acknowledging the reporter (unless anonymity is requested).

---

## 📜 Credit & Recognition

We deeply appreciate security researchers and open-source contributors who help keep Solus secure. Reporters of verified security vulnerabilities will be recognized in our release notes and Security Hall of Fame (with explicit permission).

---

*For general non-security issues or feature requests, please use our [GitHub Issue Tracker](https://github.com/ShounakPatra/Solus/issues).*
