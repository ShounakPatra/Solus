# Changelog

All notable changes to Solus are documented here. This project follows [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-07-11

### Added

- Resumable model downloads with persistent progress and recovery after app restarts.
- Expanded unit coverage for model metadata, prompt policy, runtime policy, thinking mode, and download restoration.
- Device-aware model guidance and clearer availability states in the model manager.
- Emoji compatibility support and refined light/dark glass styling.

### Changed

- Updated the local inference stack, including LiteRT-LM and Kotlin.
- Refined chat, image, model-management, and navigation experiences.
- Improved conversation resets when switching thinking mode or model runtime.

### Fixed

- Prevented hidden reasoning when thinking mode is disabled on compatible models.
- Removed malformed control tokens and thinking tags from final responses.
- Improved low-quality and empty-response handling for compact language models.
- Stabilized model download state and runtime initialization behavior.

## [1.0.0] - 2026-06-09

- Initial public release.
- Added offline local AI chat.
- Added model selection and download flow.
- Added thinking mode support for compatible models.
- Added output sanitization for tokenizer artifacts.
- Added small-model response quality handling.

[1.1.0]: https://github.com/ShounakPatra/Solus/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/ShounakPatra/Solus/releases/tag/v1.0.0
