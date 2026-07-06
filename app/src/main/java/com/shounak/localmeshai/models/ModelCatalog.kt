package com.shounak.localmeshai.models

object ModelCatalog {
    val defaultModels = listOf(
        // Recommended all-rounder (always at top)
        ModelInfo(
            id = "qwen25_15b_q8_1280",
            name = "Qwen 2.5 1.5B Instruct",
            size = "1.6 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "Balanced everyday text model for chat, summarizing, rewriting, explanations, and light coding. It uses a public Android MediaPipe .task build, so it downloads without a Hugging Face license and avoids the riskier LiteRT-LM GPU init path.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB RAM, Dimensity 7000+ / Snapdragon 6 Gen+ / Exynos 1380+",
            url = hf("litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
            isRecommended = true
        ),
        ModelInfo(
            id = "gemma3_1b_recommended",
            name = "Gemma 3 1B IT - Starter Model",
            size = "555 MB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "gemma3-1b-it-int4.task",
            description = "Small Gemma starter model for quick questions, short summaries, and checking that local chat works. The repository is gated, so accept the Google Gemma license on Hugging Face and paste a read token before downloading.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB RAM, all supported chipsets",
            url = hf("litert-community/Gemma3-1B-IT", "gemma3-1b-it-int4.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            requiresHuggingFaceToken = true,
            isRecommended = true,
            contextWindowTokens = 2048
        ),
        // ── Coding specialists ────────────────────────────────────────
        ModelInfo(
            id = "qwen25_coder_15b_litertlm",
            name = "Qwen 2.5 Coder 1.5B ⌨️",
            size = "1.46 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "qwen2.5-coder-1.5b.litertlm",
            description = "Compact coding specialist for Kotlin snippets, shell commands, bug explanations, and small edits. It is faster and lighter than the 3B coder model, but still uses LiteRT-LM GPU and may need a stronger chipset.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "8 GB nominal RAM",
            url = hf("4ntoine/Qwen2.5-Coder-1.5B-Instruct-LiteRTLM", "model.litertlm"),
            modelPageUrl = "https://huggingface.co/4ntoine/Qwen2.5-Coder-1.5B-Instruct-LiteRTLM",
            isRecommended = true,
            contextWindowTokens = 32768
        ),
        ModelInfo(
            id = "qwen25_coder_3b_litertlm",
            name = "Qwen 2.5 Coder 3B ⌨️",
            size = "2.91 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "qwen2.5-coder-3b.litertlm",
            description = "Higher-quality coding model for Android, Python, project reasoning, and longer technical explanations. Choose it over the 1.5B coder model when accuracy matters more than speed or memory use.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "12 GB RAM recommended",
            url = hf("4ntoine/Qwen2.5-Coder-3B-Instruct-LiteRTLM", "model.litertlm"),
            modelPageUrl = "https://huggingface.co/4ntoine/Qwen2.5-Coder-3B-Instruct-LiteRTLM"
        ),

        // ── Qwen family ──────────────────────────────────────────────
        ModelInfo(
            id = "qwen25_05b_q8",
            name = "Qwen 2.5 0.5B Instruct",
            size = "547 MB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "Very small Qwen model for quick replies, app smoke tests, and low-stakes offline chat. It is not meant for deep reasoning, but the MediaPipe .task build is one of the safer options for phones.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB nominal RAM",
            url = hf("litert-community/Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct",
            isRecommended = true
        ),
        ModelInfo(
            id = "qwen25_3b_q8",
            name = "Qwen 2.5 3B Instruct",
            size = "3.3 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Qwen2.5-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "Mid-size Qwen chat model for better general answers, multilingual prompts, summaries, and writing help. It is heavier than the 1.5B model and may require a Hugging Face token for this hosted artifact.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Qwen2.5-3B-Instruct", "Qwen2.5-3B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen2.5-3B-Instruct",
            requiresHuggingFaceToken = true
        ),
        ModelInfo(
            id = "qwen25_7b_candidate",
            name = "Qwen 2.5 7B Instruct",
            size = "~7 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "qwen2.5-7b-instruct.litertlm",
            description = "Large Qwen 2.5 general chat model intended for stronger reasoning, richer writing, and more reliable long answers. This entry is a future Android build placeholder until a compatible on-device GPU artifact is available.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct"
        ),
        ModelInfo(
            id = "qwen3_06b_litertlm",
            name = "Qwen 3 0.6B",
            size = "586 MB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Qwen3-0.6B.litertlm",
            description = "Tiny Qwen 3 model with basic reasoning support, useful for fast local tests and short step-by-step answers. This is a LiteRT-LM GPU file, so prefer a MediaPipe .task model on mid-range devices if initialization is unstable.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "8 GB nominal RAM (flagship chipset recommended)",
            url = hf("litert-community/Qwen3-0.6B", "Qwen3-0.6B.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen3-0.6B",
            isRecommended = true,
            contextWindowTokens = 4096
        ),
        ModelInfo(
            id = "qwen3_4b_litertlm",
            name = "Qwen 3 4B Reasoning",
            size = "5.3 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "qwen3_4b_channelwise_int8_float32kv.litertlm",
            description = "Qwen 3 reasoning model for math, planning, structured answers, and step-by-step problem solving. It needs much more memory and a high-end LiteRT-LM GPU profile than the smaller Qwen options.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Qwen3-4B", "qwen3_4b_channelwise_int8_float32kv.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen3-4B"
        ),
        ModelInfo(
            id = "qwen3_8b_litertlm",
            name = "Qwen 3 8B Reasoning",
            size = "7.7 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "qwen3_8b_channelwise_int8_float32kv.litertlm",
            description = "Large Qwen 3 reasoning model for the strongest local answers in the current Qwen 3 LiteRT-LM set. Use it only on phones with enough RAM and a verified high-end chipset, because initialization and generation are demanding.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "16 GB RAM recommended",
            url = hf("litert-community/Qwen3-8B", "qwen3_8b_channelwise_int8_float32kv.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen3-8B"
        ),

        // ── Llama family ─────────────────────────────────────────────
        ModelInfo(
            id = "llama32_1b_q8",
            name = "Llama 3.2 1B Instruct",
            size = "~1.3 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "Small Llama 3.2 instruction model for familiar Llama-style chat, summaries, and simple reasoning. It uses a MediaPipe .task build, but the Meta license must be accepted and a Hugging Face read token is required.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB nominal RAM",
            url = hf("litert-community/Llama-3.2-1B-Instruct", "Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct",
            requiresHuggingFaceToken = true,
            isRecommended = true
        ),
        ModelInfo(
            id = "llama32_3b_q8",
            name = "Llama 3.2 3B Instruct",
            size = "~3.4 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "Larger Llama 3.2 instruction model with stronger general chat quality than the 1B build. It is still a CPU-safe MediaPipe package, but needs more memory and an accepted Meta license before download.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Llama-3.2-3B-Instruct", "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct",
            requiresHuggingFaceToken = true
        ),
        ModelInfo(
            id = "llama31_8b_candidate",
            name = "Llama 3.1 8B Instruct",
            size = "~8 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "llama-3.1-8b-instruct.litertlm",
            description = "Strong Llama 3.1 8B model for higher-quality general chat, reasoning, and writing. This is a future-build placeholder until a compatible Android GPU artifact is available, and the Meta license still applies.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct",
            requiresHuggingFaceToken = true
        ),

        // ── Mistral family ───────────────────────────────────────────
        ModelInfo(
            id = "mistral_7b_candidate",
            name = "Mistral 7B Instruct v0.3",
            size = "~7.2 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "mistral-7b-instruct-v03.litertlm",
            description = "Mistral 7B Instruct is a capable general chat model known for concise, practical answers and good instruction following. It cannot run from the original checkpoint here until an Android-ready GPU artifact is available.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3"
        ),
        ModelInfo(
            id = "mistral_ministral_8b_candidate",
            name = "Mistral Ministral 8B Instruct",
            size = "~8 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "ministral-8b-instruct.litertlm",
            description = "Ministral 8B is a stronger Mistral-family chat model for richer answers, summaries, and reasoning. This card is a future-build placeholder until a compatible Android artifact exists.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/mistralai/Ministral-8B-Instruct-2410"
        ),

        // ── Phi family ───────────────────────────────────────────────
        ModelInfo(
            id = "phi4_mini_litertlm",
            name = "Phi 4 Mini Instruct",
            size = "3.64 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "Compact Phi 4 text model for polished writing, chat, explanations, and code walkthroughs. This LiteRT-LM build can be faster on supported GPUs, but is less safe than the MediaPipe variant on unverified phones.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Phi-4-mini-instruct", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct"
        ),
        ModelInfo(
            id = "phi4_mini_mediapipe",
            name = "Phi 4 Mini Instruct - MediaPipe",
            size = "3.9 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "Android-ready Phi 4 Mini text model using the CPU-safe MediaPipe LLM runtime. It is good for chat, writing, and explanations, but this bundle is text-only and does not include audio encoders.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Phi-4-mini-instruct", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct",
            isRecommended = true
        ),
        ModelInfo(
            id = "phi4_multimodal_instruct_candidate",
            name = "Phi 4 Multimodal",
            size = "~24 GB checkpoint",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Vision,
            fileName = "phi-4-multimodal-instruct.litertlm",
            description = "Microsoft Phi 4 Multimodal is intended for text, image, and audio understanding in one model. The public checkpoint is not directly runnable by this app yet, so it needs an Android LiteRT-LM, MediaPipe, or ONNX Runtime GenAI package first.",
            backend = "Conversion needed",
            deviceTarget = "High-end phone, Android multimodal artifact needed",
            modelPageUrl = "https://huggingface.co/microsoft/Phi-4-multimodal-instruct",
            contextWindowTokens = 128000,
            supportsAudioInput = true
        ),
        ModelInfo(
            id = "whisper_large_v3_tflite_candidate",
            name = "Whisper Large-v3",
            size = "1.56 GB",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Vision,
            fileName = "whisper-large-v3.tflite",
            description = "Whisper Large-v3 is a speech-to-text model for transcribing recorded audio before another model answers. A TFLite file alone is not enough here; the app still needs the Whisper decoder, 128-bin mel frontend, and vocabulary pipeline.",
            backend = "Whisper TFLite decoder needed",
            deviceTarget = "12 GB RAM recommended for large-v3",
            modelPageUrl = "https://huggingface.co/cik009/whisper",
            supportsAudioInput = true
        ),
        ModelInfo(
            id = "phi4_mini_whisper_pipeline_candidate",
            name = "Phi 4 Mini + Whisper",
            size = "~5.5 GB",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Vision,
            fileName = "phi4-mini-whisper-audio-pipeline.zip",
            packageType = ModelPackage.ZipDirectory,
            description = "Planned audio-chat pipeline where Whisper transcribes speech and Phi 4 Mini reads the transcript to answer. It will need a bundled Android package with both models plus the Whisper audio frontend and decoder before local audio chat works.",
            backend = "Pipeline runtime needed",
            deviceTarget = "12 GB RAM recommended",
            modelPageUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct",
            contextWindowTokens = 4096,
            supportsAudioInput = true
        ),
        ModelInfo(
            id = "phi35_mini_candidate",
            name = "Phi 3.5 Mini Instruct",
            size = "~3.6 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "phi-3.5-mini-instruct.litertlm",
            description = "Phi 3.5 Mini is a compact Microsoft text model for reasoning, summarization, and helpful chat. This entry needs an Android GPU artifact before it can be downloaded and initialized in the app.",
            backend = "Conversion needed",
            deviceTarget = "12 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/microsoft/Phi-3.5-mini-instruct"
        ),

        // ── DeepSeek family ──────────────────────────────────────────
        ModelInfo(
            id = "deepseek_r1_qwen15b_q8",
            name = "DeepSeek R1 Distill Qwen 1.5B",
            size = "1.86 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
            description = "Small DeepSeek R1 distill focused on step-by-step reasoning and problem solving. It is packaged as an Android MediaPipe .task model, making it a safer reasoning option than larger future GPU-only builds.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB nominal RAM",
            url = hf("litert-community/DeepSeek-R1-Distill-Qwen-1.5B", "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            isRecommended = true
        ),
        ModelInfo(
            id = "deepseek_r1_qwen7b_candidate",
            name = "DeepSeek R1 Distill Qwen 7B",
            size = "~7 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "deepseek-r1-distill-qwen-7b.litertlm",
            description = "Larger DeepSeek R1 distill for stronger math, logic, and structured reasoning than the 1.5B build. It is a future-build placeholder until a compatible Android GPU artifact is available.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-7B"
        ),

        // ── GLM family ───────────────────────────────────────────────
        ModelInfo(
            id = "glm_edge_15b_candidate",
            name = "GLM Edge 1.5B Chat",
            size = "~1.5 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "glm-edge-1.5b-chat.litertlm",
            description = "Small GLM Edge chat model aimed at efficient general conversation and assistant tasks. It needs a converted Android GPU artifact before it can run locally in this app.",
            backend = "Conversion needed",
            deviceTarget = "8 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/THUDM/glm-edge-1.5b-chat"
        ),
        ModelInfo(
            id = "glm4_9b_candidate",
            name = "GLM 4 9B Chat",
            size = "~9 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "glm-4-9b-chat.litertlm",
            description = "GLM 4 9B is a larger GLM-family chat model for stronger instruction following, reasoning, and multilingual answers. It requires an Android-ready GPU artifact before on-device use.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/THUDM/glm-4-9b-chat"
        ),

        // ── Kimi family ──────────────────────────────────────────────
        ModelInfo(
            id = "kimi_vl_a3b_candidate",
            name = "Kimi VL A3B Instruct",
            size = "~3 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Vision,
            fileName = "kimi-vl-a3b.litertlm",
            description = "Kimi VL is an image-language model for visual question answering, image description, and reasoning over pictures. It needs a converted Android multimodal package before it can run on-device.",
            backend = "Conversion needed",
            deviceTarget = "12 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/moonshotai/Kimi-VL-A3B-Instruct",
            contextWindowTokens = 128000
        ),

        // ── MiMo family ──────────────────────────────────────────────
        ModelInfo(
            id = "mimo_7b_candidate",
            name = "Xiaomi MiMo 7B",
            size = "~7 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "mimo-7b.litertlm",
            description = "Xiaomi MiMo 7B is a reasoning-oriented text model for structured thinking, math, and multi-step prompts. This entry needs a compatible Android GPU build before download and initialization.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/XiaomiMiMo/MiMo-7B-SFT"
        ),

        // ── Other small text models ──────────────────────────────────
        ModelInfo(
            id = "tinyllama_11b_q8",
            name = "TinyLlama 1.1B Chat",
            size = "1.15 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
            description = "TinyLlama is a lightweight public fallback for quick checks, short chat, and lower-memory testing. It is not as capable as Qwen or Phi models, but the MediaPipe .task package is easy to download and safe to try.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB nominal RAM",
            url = hf("litert-community/TinyLlama-1.1B-Chat-v1.0", "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task"),
            modelPageUrl = "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0"
        ),
        ModelInfo(
            id = "gemma4_e2b_litertlm",
            name = "Gemma 4 E2B IT",
            size = "2.14 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Vision,
            fileName = "gemma-4-E2B-it.litertlm",
            description = "Gemma 4 E2B is a compact LiteRT-LM multimodal package for text, image, and audio-capable chats when the bundle includes the needed encoders. It should be used only on verified high-end LiteRT-LM device profiles; use MediaPipe vision models on mid-range phones.",
            backend = "LiteRT-LM multimodal CPU/GPU",
            deviceTarget = "Flagship LiteRT-LM GPU chipset recommended",
            url = hf("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            supportsAudioInput = true,
            supportsThinkingMode = true,
            contextWindowTokens = 32768
        ),
        ModelInfo(
            id = "gemma4_e4b_litertlm",
            name = "Gemma 4 E4B IT",
            size = "3.41 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Vision,
            fileName = "gemma-4-E4B-it.litertlm",
            description = "Gemma 4 E4B is a stronger LiteRT-LM multimodal model for richer image and text answers, with audio support only when the bundle includes the required encoders. It is heavier than E2B and should be treated as high-end-phone-only unless the crash guard proves it works.",
            backend = "LiteRT-LM multimodal CPU/GPU",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            supportsAudioInput = true,
            supportsThinkingMode = true,
            contextWindowTokens = 32768
        ),

        // ── Vision / Image Q&A models ────────────────────────────────
        ModelInfo(
            id = "gemma3n_e2b_vision",
            name = "Gemma 3n E2B Vision",
            size = "2.92 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Vision,
            fileName = "gemma-3n-E2B-it-int4.task",
            description = "Gemma 3n E2B Vision is an Android-ready image Q&A model for describing photos, reading visual details, and answering questions about selected images. It requires accepting the Google Gemma license and using a Hugging Face read token.",
            backend = "MediaPipe LLM Vision CPU-safe",
            deviceTarget = "8 GB RAM recommended",
            url = hf("google/gemma-3n-E2B-it-litert-preview", "gemma-3n-E2B-it-int4.task"),
            modelPageUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview",
            requiresHuggingFaceToken = true,
            contextWindowTokens = 32768
        ),
        ModelInfo(
            id = "gemma3n_e4b_vision",
            name = "Gemma 3n E4B Vision",
            size = "4.10 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Vision,
            fileName = "gemma-3n-E4B-it-int4.task",
            description = "Gemma 3n E4B Vision is the stronger Gemma 3n image Q&A option for more detailed visual reasoning and richer image descriptions. It needs more memory than E2B and requires the Google Gemma license plus a Hugging Face read token.",
            backend = "MediaPipe LLM Vision CPU-safe",
            deviceTarget = "12 GB RAM recommended",
            url = hf("google/gemma-3n-E4B-it-litert-preview", "gemma-3n-E4B-it-int4.task"),
            modelPageUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview",
            requiresHuggingFaceToken = true,
            contextWindowTokens = 32768
        ),
        ModelInfo(
            id = "fastvlm_05b_litertlm",
            name = "FastVLM 0.5B",
            size = "1.08 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Vision,
            fileName = "FastVLM-0.5B.litertlm",
            description = "FastVLM 0.5B is a lightweight vision-language model for quick image descriptions and simple visual questions. It is smaller than Gemma vision models, but the LiteRT-LM vision runtime still depends on device GPU compatibility.",
            backend = "LiteRT-LM Vision GPU",
            deviceTarget = "8 GB nominal RAM",
            url = hf("litert-community/FastVLM-0.5B", "FastVLM-0.5B.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/FastVLM-0.5B",
            contextWindowTokens = 1280
        ),

        // ── Qwen 3.5 family (not yet released — no Android artifacts) ──
        ModelInfo(
            id = "qwen35_08b_litertlm",
            name = "Qwen 3.5 0.8B",
            size = "~0.9 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.5-0.8B.litertlm",
            description = "Tiny Qwen 3.5 placeholder for very fast future chat and basic assistant tasks. It stays in Coming Soon until a real model page and Android GPU artifact are available.",
            backend = "Conversion needed",
            deviceTarget = "8 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),
        ModelInfo(
            id = "qwen35_2b_litertlm",
            name = "Qwen 3.5 2B",
            size = "~2.1 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.5-2B.litertlm",
            description = "Small Qwen 3.5 placeholder intended to sit above the tiny build for better chat quality while staying phone-friendly. It needs an Android GPU artifact before it can become downloadable.",
            backend = "Conversion needed",
            deviceTarget = "8 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),
        ModelInfo(
            id = "qwen35_4b_litertlm",
            name = "Qwen 3.5 4B",
            size = "~4.3 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.5-4B.litertlm",
            description = "Mid-size Qwen 3.5 placeholder for stronger writing, summaries, and general reasoning than the smaller future builds. It remains unavailable until an Android GPU artifact exists.",
            backend = "Conversion needed",
            deviceTarget = "12 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),
        ModelInfo(
            id = "qwen35_9b_litertlm",
            name = "Qwen 3.5 9B",
            size = "~8.5 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.5-9B.litertlm",
            description = "Large Qwen 3.5 placeholder for higher-quality future local chat on flagship phones. It needs a compatible Android artifact and enough memory before the app can initialize it.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),
        ModelInfo(
            id = "qwen35_27b_litertlm",
            name = "Qwen 3.5 27B",
            size = "~25 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.5-27B.litertlm",
            description = "Very large Qwen 3.5 placeholder for desktop-class chat quality on future high-memory Android devices. It is listed for tracking only until an Android artifact becomes available.",
            backend = "Conversion needed",
            deviceTarget = "24 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),
        ModelInfo(
            id = "qwen35_35b_a3b",
            name = "Qwen 3.5 35B-A3B",
            size = "~35 GB (est., MoE)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.5-35B-A3B.litertlm",
            description = "Qwen 3.5 MoE placeholder with many total parameters but fewer active parameters per token. It is meant for future flagship devices and needs a real Android GPU package before use.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),
        ModelInfo(
            id = "qwen36_35b_a3b",
            name = "Qwen 3.6 35B-A3B",
            size = "~35 GB (est., MoE)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Text,
            fileName = "Qwen3.6-35B-A3B.litertlm",
            description = "Qwen 3.6 MoE placeholder for a future flagship sparse model. It remains a catalog entry only until both the model and an Android-ready artifact are available.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),


        // ── Qwen 3 family additions ──────────────────────────────
        ModelInfo(
            id = "qwen3_17b_litertlm",
            name = "Qwen 3 1.7B",
            size = "~1.8 GB (est.)",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Qwen3-1.7B.litertlm",
            description = "Small Qwen 3 reasoning model between 0.6B and 4B, useful for general chat with better step-by-step ability than the tiny build. This Hub repository currently requires Hugging Face authentication before download.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "8 GB nominal RAM",
            url = hf("litert-community/Qwen3-1.7B", "Qwen3-1.7B.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen3-1.7B",
            requiresHuggingFaceToken = true
        ),
        ModelInfo(
            id = "qwen3_14b_litertlm",
            name = "Qwen 3 14B",
            size = "8.66 GB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "qwen3_14b_mixed_int4.litertlm",
            description = "Large dense Qwen 3 reasoning model using a mixed INT4 LiteRT-LM artifact for high-quality local answers. It is a strongest-phones-only option because download size, RAM pressure, and initialization risk are high.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "16 GB RAM, strongest phones only",
            url = hf("litert-community/Qwen3-14B", "qwen3_14b_mixed_int4.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Qwen3-14B"
        ),
        ModelInfo(
            id = "qwen3_30b_a3b",
            name = "Qwen 3 30B-A3B",
            size = "~30 GB (est., MoE)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "Qwen3-30B-A3B.litertlm",
            description = "Qwen 3 MoE model with 30B total and about 3B active parameters, designed to improve quality without activating the whole model each token. It still needs a compatible Android GPU artifact before use.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/Qwen/Qwen3-30B-A3B"
        ),
        ModelInfo(
            id = "qwen3_32b_litertlm",
            name = "Qwen 3 32B",
            size = "~28 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "Qwen3-32B.litertlm",
            description = "Largest dense Qwen 3 reasoning placeholder for very strong answers, math, and long-form reasoning. It needs a future Android GPU artifact and very high memory before it can run locally.",
            backend = "Conversion needed",
            deviceTarget = "24 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/Qwen/Qwen3-32B"
        ),

        // ── Gemma 3 / Gemma 4 family additions ───────────────────
        ModelInfo(
            id = "gemma3_270m_q8",
            name = "Gemma 3 270M",
            size = "304 MB",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "gemma3-270m-it-q8.task",
            description = "Tiny Gemma 3 instruction model for quick tests, short answers, and low-memory local chat. It uses an Android-ready MediaPipe task bundle, but the Google Gemma license and a Hugging Face read token are required.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "8 GB RAM, all supported chipsets",
            url = hf("litert-community/gemma-3-270m-it", "gemma3-270m-it-q8.task"),
            modelPageUrl = "https://huggingface.co/litert-community/gemma-3-270m-it",
            requiresHuggingFaceToken = true
        ),
        ModelInfo(
            id = "gemma3_4b_q8",
            name = "Gemma 3 4B IT",
            size = "~3.4 GB (est.)",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "gemma3-4b-it-int4.task",
            description = "Mid-size Gemma 3 instruction-tuned model for stronger chat, writing, and reasoning than the 270M or 1B variants. It uses a MediaPipe-style Android package and requires the Google Gemma license plus a Hugging Face read token.",
            backend = "MediaPipe LLM CPU-safe",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Gemma3-4B-IT", "gemma3-4b-it-int4.task"),
            modelPageUrl = "https://huggingface.co/litert-community/Gemma3-4B-IT",
            requiresHuggingFaceToken = true
        ),
        ModelInfo(
            id = "gemma3_12b_q8",
            name = "Gemma 3 12B IT",
            size = "~8.5 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "gemma3-12b-it-int4.task",
            description = "Large Gemma 3 instruction-tuned placeholder for higher-quality general chat and reasoning. It needs an Android-ready artifact before use, and the Google Gemma license plus a Hugging Face read token still apply.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/google/gemma-3-12b-it",
            requiresHuggingFaceToken = true
        ),
        ModelInfo(
            id = "gemma3_27b_q8",
            name = "Gemma 3 27B IT",
            size = "~18 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "gemma3-27b-it-int4.task",
            description = "Largest dense Gemma 3 placeholder for the strongest Gemma 3 text quality in this catalog. It needs a compatible Android artifact and a very high-memory device, plus the Google Gemma license and Hugging Face token.",
            backend = "Conversion needed",
            deviceTarget = "24 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/google/gemma-3-27b-it",
            requiresHuggingFaceToken = true
        ),
        // ── Gemma 4 larger Assistant variants (no public HF artifact yet) ───
        ModelInfo(
            id = "gemma4_12b_it_assistant",
            name = "Gemma 4 12B IT Assistant",
            size = "~12 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Vision,
            fileName = "gemma-4-12B-it-assistant.litertlm",
            description = "Gemma 4 12B Assistant placeholder for future text, image, and audio-capable local chat. It is listed for planning only until a public Android multimodal artifact is available; Gemma license and token requirements will still apply.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = null,
            requiresHuggingFaceToken = true,
            supportsAudioInput = true,
            supportsThinkingMode = true
        ),
        ModelInfo(
            id = "gemma4_26b_a4b_it_assistant",
            name = "Gemma 4 26B-A4B IT Assistant",
            size = "~26 GB (est., MoE)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Vision,
            fileName = "gemma-4-26B-A4B-it-assistant.litertlm",
            description = "Gemma 4 MoE multimodal Assistant placeholder with 26B total and about 4B active parameters. It is intended for future high-end phones when an Android artifact is available, with Gemma license and token requirements.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = null,
            requiresHuggingFaceToken = true,
            supportsAudioInput = true,
            supportsThinkingMode = true
        ),
        ModelInfo(
            id = "gemma4_31b_it_assistant",
            name = "Gemma 4 31B IT Assistant",
            size = "~28 GB (est.)",
            status = ModelStatus.ComingSoon,
            type = ModelType.Vision,
            fileName = "gemma-4-31B-it-assistant.litertlm",
            description = "Largest Gemma 4 multimodal Assistant placeholder for future text, image, and audio reasoning. It will need a very high-memory Android device and a compatible artifact before download and initialization.",
            backend = "Conversion needed",
            deviceTarget = "24 GB RAM, needs Android GPU artifact",
            modelPageUrl = null,
            requiresHuggingFaceToken = true,
            supportsAudioInput = true,
            supportsThinkingMode = true
        ),


        // ── Phi 4 family additions ───────────────────────────────
        ModelInfo(
            id = "phi4_mini_reasoning_litertlm",
            name = "Phi 4 Mini Reasoning",
            size = "~3.8 GB (est.)",
            status = ModelStatus.NotDownloaded,
            type = ModelType.Text,
            fileName = "Phi-4-mini-reasoning.litertlm",
            description = "Reasoning-tuned Phi 4 Mini variant for math, logic, planning, and step-by-step explanations. It is heavier than ordinary small chat models and uses LiteRT-LM GPU, so device compatibility matters.",
            backend = "LiteRT-LM GPU",
            deviceTarget = "12 GB RAM recommended",
            url = hf("litert-community/Phi-4-mini-reasoning", "Phi-4-mini-reasoning.litertlm"),
            modelPageUrl = "https://huggingface.co/litert-community/Phi-4-mini-reasoning"
        ),
        ModelInfo(
            id = "phi4_14b_litertlm",
            name = "Phi 4 14B",
            size = "~12 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "Phi-4-14B.litertlm",
            description = "Large Phi 4 text model for stronger general chat, coding help, writing, and reasoning than Phi 4 Mini. It needs a future Android GPU artifact and a high-end phone before local use.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),

        // ── SmolLM family ────────────────────────────────────────
        ModelInfo(
            id = "smollm2_17b_litertlm",
            name = "SmolLM2 1.7B",
            size = "~1.9 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "SmolLM2-1.7B-Instruct.litertlm",
            description = "SmolLM2 1.7B Instruct is a lightweight Hugging Face model for compact on-device chat and simple assistant tasks. It needs an Android GPU artifact before it can become downloadable here.",
            backend = "Conversion needed",
            deviceTarget = "8 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct"
        ),
        ModelInfo(
            id = "smollm3_3b_litertlm",
            name = "SmolLM3 3B",
            size = "~3.2 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "SmolLM3-3B.litertlm",
            description = "SmolLM3 3B is a small Hugging Face model aimed at better reasoning and instruction following than SmolLM2. This app needs an Android-ready artifact before it can run.",
            backend = "Conversion needed",
            deviceTarget = "12 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/HuggingFaceTB/SmolLM3-3B"
        ),

        // ── Granite family ───────────────────────────────────────
        ModelInfo(
            id = "granite41_3b_litertlm",
            name = "Granite 4.1 3B",
            size = "~3.1 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "granite-4.1-3b.litertlm",
            description = "IBM Granite 4.1 3B placeholder for business-style chat, summaries, and structured enterprise prompts. It needs a compatible Android GPU artifact before download and initialization.",
            backend = "Conversion needed",
            deviceTarget = "12 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/ibm-granite/granite-4.1-3b"
        ),
        ModelInfo(
            id = "granite41_8b_litertlm",
            name = "Granite 4.1 8B",
            size = "~8.0 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "granite-4.1-8b.litertlm",
            description = "IBM Granite 4.1 8B placeholder for stronger enterprise chat, document-style reasoning, and structured answers. It requires a future Android GPU artifact and more memory than the 3B build.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/ibm-granite/granite-4.1-8b"
        ),
        ModelInfo(
            id = "granite41_30b_litertlm",
            name = "Granite 4.1 30B",
            size = "~28 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "granite-4.1-30b.litertlm",
            description = "IBM Granite 4.1 30B placeholder for flagship enterprise-grade local reasoning and long-form answers. It needs a future Android artifact and very high device memory before it can run.",
            backend = "Conversion needed",
            deviceTarget = "24 GB RAM, needs Android GPU artifact",
            modelPageUrl = null
        ),

        // ── EXAONE Deep family ───────────────────────────────────
        ModelInfo(
            id = "exaone_deep_24b_litertlm",
            name = "EXAONE Deep 2.4B",
            size = "~2.5 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "EXAONE-Deep-2.4B.litertlm",
            description = "LG EXAONE Deep 2.4B placeholder for compact reasoning, math, and structured problem solving. It needs a compatible Android GPU artifact before local use.",
            backend = "Conversion needed",
            deviceTarget = "8 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-Deep-2.4B"
        ),
        ModelInfo(
            id = "exaone_deep_78b_litertlm",
            name = "EXAONE Deep 7.8B",
            size = "~7.8 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "EXAONE-Deep-7.8B.litertlm",
            description = "LG EXAONE Deep 7.8B placeholder for stronger reasoning and deeper multi-step answers than the 2.4B build. It requires an Android GPU artifact and a high-memory phone.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-Deep-7.8B"
        ),
        ModelInfo(
            id = "exaone_deep_32b_litertlm",
            name = "EXAONE Deep 32B",
            size = "~30 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "EXAONE-Deep-32B.litertlm",
            description = "LG EXAONE Deep 32B placeholder for flagship-class reasoning quality on future high-memory Android devices. It needs a compatible Android artifact before download or initialization.",
            backend = "Conversion needed",
            deviceTarget = "24 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-Deep-32B"
        ),

        // ── MiniCPM 4 family ─────────────────────────────────────
        ModelInfo(
            id = "minicpm4_05b_litertlm",
            name = "MiniCPM4-0.5B",
            size = "~0.6 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "MiniCPM4-0.5B.litertlm",
            description = "MiniCPM4-0.5B placeholder for very small on-device chat where speed and memory matter most. It needs an Android GPU artifact before the app can offer it as a runnable model.",
            backend = "Conversion needed",
            deviceTarget = "8 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/openbmb/MiniCPM4-0.5B"
        ),
        ModelInfo(
            id = "minicpm4_8b_litertlm",
            name = "MiniCPM4-8B",
            size = "~7.8 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "MiniCPM4-8B.litertlm",
            description = "MiniCPM4-8B placeholder for stronger compact chat and reasoning from the OpenBMB family. It needs a future Android GPU artifact and a high-memory device.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/openbmb/MiniCPM4-8B"
        ),

        // ── LFM 2.5 family ───────────────────────────────────────
        ModelInfo(
            id = "lfm25_8b_a1b",
            name = "LFM2.5-8B-A1B",
            size = "~8 GB (est., MoE)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "LFM2.5-8B-A1B.litertlm",
            description = "Liquid LFM2.5 8B-A1B is a sparse MoE-style placeholder with about 1B active parameters for efficient high-quality chat. It still needs a compatible Android GPU artifact before use.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/LiquidAI/LFM2.5-8B-A1B"
        ),

        // ── Mistral / Ministral-3 ────────────────────────────────
        ModelInfo(
            id = "ministral3_8b_litertlm",
            name = "Ministral-3 8B",
            size = "~7.5 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "Ministral-3-8B.litertlm",
            description = "Ministral-3 8B placeholder for a newer Mistral-family local chat model with strong instruction following. It needs an Android GPU artifact, and the Mistral license plus Hugging Face token must be handled before download.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/mistralai/Ministral-3-8B",
            requiresHuggingFaceToken = true
        ),

        // ── Zaya1 family ─────────────────────────────────────────
        ModelInfo(
            id = "zaya1_8b_litertlm",
            name = "Zaya1-8B",
            size = "~8.0 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "Zaya1-8B.litertlm",
            description = "Zaya1-8B placeholder for a larger general-purpose chat model with multilingual and reasoning potential. It cannot run here until an Android GPU artifact is available.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/zai-org/Zaya1-8B"
        ),

        // ── InternLM 3 family ────────────────────────────────────
        ModelInfo(
            id = "internlm3_8b_litertlm",
            name = "InternLM3-8B",
            size = "~7.8 GB (est.)",
            status = ModelStatus.NeedsConversion,
            type = ModelType.Text,
            fileName = "InternLM3-8B.litertlm",
            description = "InternLM3-8B placeholder for multilingual chat, summaries, and general assistant use. It needs a compatible Android GPU artifact before it can be downloaded and initialized locally.",
            backend = "Conversion needed",
            deviceTarget = "16 GB RAM, needs Android GPU artifact",
            modelPageUrl = "https://huggingface.co/internlm/internlm3-8b"
        )
    )

    private fun hf(repo: String, file: String): String {
        return "https://huggingface.co/$repo/resolve/main/$file?download=true"
    }
}
