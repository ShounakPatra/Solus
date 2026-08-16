# 🛡️ Security Policy — Solus

Solus is an open-source, 100% offline, on-device AI assistant for Android. Security and user privacy are foundational pillars of Solus. Because Solus operates entirely locally on your device without sending prompts or personal data to external cloud servers, user data security is inherently protected by local hardware boundaries. 

This document outlines our security model, supported versions, and procedures for responsibly reporting security vulnerabilities.

---

## 📋 Supported Versions

We actively maintain and provide security updates for the following versions of Solus:

| Version | Supported | Security Maintenance Status |
| :--- | :---: | :--- |
| `v1.5.x` | ✅ | **Current Stable Release** — Active security patches & bug fixes |
| `v1.0.x` – `v1.4.x` | ⚠️ | Critical security fixes backported when applicable |
| `< 1.0.0` | ❌ | End of Life (EOL) — Please upgrade to `v1.5.0` or newer |

---

## 🔒 Security Architecture & Guarantees

Solus is designed around a **Zero-Trust, Zero-Telemetry** local execution model:

1. **100% Offline Execution:** Solus requires zero network connectivity for AI inference, text generation, image vision analysis, or chat persistence.
2. **Zero Telemetry & Analytics:** Solus contains no analytics SDKs, user tracking, crash reporting telemetry, or third-party ad networks.
3. **Isolated Token Storage:** Hugging Face API tokens (used optionally for downloading gated open-weights models) are stored securely in Android `SharedPreferences` with `MODE_PRIVATE` and are never logged, exported, or transmitted to any third-party server.
4. **Native Memory Safety:** Native C++ JNI bindings (`llama.cpp`, LiteRT) implement strict RAII memory management, bounds checking, and automatic heap cleanup to prevent buffer overflows or memory leak vulnerabilities.
5. **Scoped Storage:** Model files, chat histories, and temporary app data are stored strictly within Android App-Specific Internal Storage (`/data/user/0/com.shounak.localmeshai/`).

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
4. **Coordinated Release:** A patch release (e.g., `v1.5.1`) will be published to GitHub Releases alongside an advisory acknowledging the reporter (unless anonymity is requested).

---

## 📜 Credit & Recognition

We deeply appreciate security researchers and open-source contributors who help keep Solus secure. Reporters of verified security vulnerabilities will be recognized in our release notes and Security Hall of Fame (with explicit permission).

---

*For general non-security issues or feature requests, please use our [GitHub Issue Tracker](https://github.com/ShounakPatra/Solus/issues).*
