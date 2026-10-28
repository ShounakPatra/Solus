# Solus security patch

Upstream: candle-core 0.9.1 from crates.io (huggingface/candle).
Original crate archive SHA-256:
`a9f51e2ecf6efe9737af8f993433c839f956d2b6ed4fd2dd4a7c6d8b0fa667ff`.
The upstream license is retained in LICENSE.

Local changes:

- Cargo.toml pins zip 9.1.3 with default features disabled, replacing zip 1.3.1.
- npy.rs and pickle.rs adapt to ZIP's fallible filename API.
- Every archive reader validates local/central header consistency before reading
  entries, including duplicate and overlapping-entry checks supplied by ZIP.
- The unused upstream Cargo.lock is omitted; Solus's root lockfile is authoritative.

This addresses the affected dependency in GHSA-m665-v7pm-7grj and
GHSA-94vh-gphv-8pm8 while retaining Candle's 0.9.1 model APIs. Solus regression
tests check valid tensor archives and rejection of mismatched headers.
