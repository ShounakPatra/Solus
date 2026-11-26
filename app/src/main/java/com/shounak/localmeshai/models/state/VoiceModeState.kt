package com.shounak.localmeshai.models.state

/**
 * Lifecycle phase for Milestone 2 Hands-Free Two-Way Offline Voice Mode.
 */
enum class VoiceModePhase(val displayLabel: String) {
    Idle("Idle"),
    MissingAssets("Voice models required"),
    Initializing("Initializing…"),
    Listening("Listening"),
    Transcribing("Transcribing"),
    Thinking("Thinking"),
    Speaking("Speaking"),
    Error("Voice error")
}

/**
 * Snapshot representing missing or incomplete offline voice models required for hands-free mode.
 */
data class VoiceAssetReadiness(
    val hasSileroVad: Boolean = false,
    val hasWhisperStt: Boolean = false,
    val hasTinyVoca: Boolean = false
) {
    val allReady: Boolean
        get() = hasSileroVad && hasWhisperStt && hasTinyVoca

    val missingNames: List<String>
        get() = buildList {
            if (!hasSileroVad) add("Silero VAD (~2.3 MB)")
            if (!hasWhisperStt) add("Whisper Tiny EN (~43 MB)")
            if (!hasTinyVoca) add("TinyVoca TTS (~21.4 MB)")
        }
}

/**
 * UI state holder for the Hands-Free Two-Way Offline Voice Overlay.
 */
data class VoiceModeUiState(
    val isOpen: Boolean = false,
    val phase: VoiceModePhase = VoiceModePhase.Idle,
    val readiness: VoiceAssetReadiness = VoiceAssetReadiness(),
    val isDownloadingAssets: Boolean = false,
    val downloadLabel: String = "",
    val downloadProgress: Float = 0f,
    val micLevel: Float = 0f,
    val isMuted: Boolean = false,
    val lastUserTranscript: String = "",
    val streamingAssistantText: String = "",
    val activeSpeechChunk: String = "",
    val errorMessage: String? = null,
    val turnCount: Int = 0
)
