package com.shounak.localmeshai.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.shounak.localmeshai.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Represents an indexed searchable item in the Settings hierarchy.
 *
 * @param title The primary title displayed to the user.
 * @param subtitle A descriptive explanation of what the setting toggles or configures.
 * @param keywords A rich space-separated list of synonyms, search terms, and abbreviations.
 * @param category The breadcrumb location string (e.g., "APP > Appearance").
 * @param targetSubMenu The destination [SettingsSubMenu] to navigate to when tapped.
 * @param id Unique identifier for programmatic lookup and dynamic registration.
 * @param minVersionCode The minimum application version code where this setting was introduced.
 */
data class SearchableSetting(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val category: String,
    val targetSubMenu: SettingsSubMenu,
    val id: String = "",
    val minVersionCode: Int = 1
)

/**
 * Result data returned when the Settings Search Index performs automatic update synchronization.
 */
data class IndexSyncResult(
    val isNewUpdate: Boolean,
    val previousVersionCode: Int,
    val currentVersionCode: Int,
    val totalIndexedCount: Int,
    val timestamp: Long
)

/**
 * Centralized, automatically synchronized search index for all application settings.
 *
 * Automatically detects new application releases, synchronizes the index registry,
 * provides multi-token ranked search, and supports dynamic setting registrations.
 */
object SettingsSearchIndex {

    private const val TAG = "SettingsSearchIndex"
    private const val PREFS_NAME = "settings_search_index_sync"
    private const val KEY_LAST_INDEXED_VERSION_CODE = "last_indexed_version_code"
    private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
    private const val KEY_INDEXED_ITEM_COUNT = "last_indexed_count"
    private const val KEY_LAST_VERSION_NAME = "last_version_name"

    private val dynamicSettings = CopyOnWriteArrayList<SearchableSetting>()

    /**
     * Comprehensive built-in catalog of all settings across all 19 [SettingsSubMenu] categories.
     */
    val BUILT_IN_SETTINGS: List<SearchableSetting> = listOf(
        // ==========================================
        // 1. APPEARANCE (APP > Appearance)
        // ==========================================
        SearchableSetting(
            id = "app_dark_mode",
            title = "Dark Mode",
            subtitle = "Enable dark visual appearance throughout the app (default)",
            keywords = "appearance theme color style dark light mode oled black display visual daytime nighttime",
            category = "APP > Appearance",
            targetSubMenu = SettingsSubMenu.APPEARANCE
        ),
        SearchableSetting(
            id = "app_dynamic_themes",
            title = "Dynamic Model Accent Themes",
            subtitle = "Adapt UI accents for DeepSeek (Cyan), Gemma (Amber), Qwen (Violet), Llama (Green)",
            keywords = "accent colors dynamic theme deepseek gemma qwen llama styling tint palette branding custom model",
            category = "APP > Appearance",
            targetSubMenu = SettingsSubMenu.APPEARANCE
        ),
        SearchableSetting(
            id = "app_pure_black_oled",
            title = "Pure Black Background (OLED)",
            subtitle = "True pitch black background for AMOLED/OLED displays to conserve battery",
            keywords = "pure black amoled oled true black high contrast display battery saver dark theme",
            category = "APP > Appearance",
            targetSubMenu = SettingsSubMenu.APPEARANCE
        ),

        // ==========================================
        // 2. CHAT DEFAULTS (APP > Chat Defaults)
        // ==========================================
        SearchableSetting(
            id = "chat_temperature",
            title = "Temperature",
            subtitle = "Lower is deterministic and focused; higher produces more diverse text",
            keywords = "temperature sampling randomness creativity deterministic default chat generation slider focus variance",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_top_p",
            title = "Top P",
            subtitle = "Nucleus sampling threshold (0.05 to 1.00)",
            keywords = "top p nucleus sampling probability threshold candidate filter distribution default",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_top_k",
            title = "Top K",
            subtitle = "Limits token candidates to the K most likely tokens (1 to 100)",
            keywords = "top k sampling vocabulary limit tokens filter candidates diversity rank cut",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_max_length",
            title = "Max Output Length",
            subtitle = "Longest answer in tokens. 0 lets the model decide naturally",
            keywords = "max length tokens limit context budget longest response answer size cutoff auto",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_compact_automatically",
            title = "Compact Automatically At",
            subtitle = "Automatically summarize older turns when context fills this percentage",
            keywords = "compact automatically summarize context memory turns compression older history clean truncate prune",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_warmup_after_loading",
            title = "Warm up after loading",
            subtitle = "Prepare prefill and decode token before model is ready for user input",
            keywords = "warmup speed prefill decode loading startup prompt latency first token cold start",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_suggestion_pills",
            title = "Quick Suggestion Pills",
            subtitle = "Show empty chat prompt suggestion chips and conversation starters",
            keywords = "starter prompts suggestions pills chips chat empty ideas quick actions recommendations",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),
        SearchableSetting(
            id = "chat_auto_unload_timer",
            title = "Auto-Unload Timer",
            subtitle = "Unload inactive models from RAM to save memory and battery (1m, 5m, 15m, 30m, never)",
            keywords = "auto unload timer battery ram sleep timeout minutes idle background memory free power",
            category = "APP > Chat Defaults",
            targetSubMenu = SettingsSubMenu.CHAT_DEFAULTS
        ),

        // ==========================================
        // 3. VOICE & SPEECH (APP > Voice & Speech)
        // ==========================================
        SearchableSetting(
            id = "voice_switcher",
            title = "Voice Switcher",
            subtitle = "Toggle between F2 (female) and M2 (male) voice",
            keywords = "voice switcher female male f2 m2 audio tinyvoca sound speaker narrator gender speech tts",
            category = "APP > Voice & Speech",
            targetSubMenu = SettingsSubMenu.VOICE
        ),
        SearchableSetting(
            id = "voice_speech_speed",
            title = "Speech Speed Multiplier",
            subtitle = "1.0x / 1.25x / 1.5x speech playback speed multiplier",
            keywords = "speech speed rate multiplier voice tts 1.0x 1.25x 1.5x fast slow audio tempo pacing",
            category = "APP > Voice & Speech",
            targetSubMenu = SettingsSubMenu.VOICE
        ),
        SearchableSetting(
            id = "voice_tinyvoca_neural_tts",
            title = "TinyVoca Neural TTS",
            subtitle = "On-device 8.1 MB bundled neural text-to-speech voice model",
            keywords = "tinyvoca voca tts neural model download onnx speech voice local offline narrator synthesis",
            category = "APP > Voice & Speech",
            targetSubMenu = SettingsSubMenu.VOICE
        ),
        SearchableSetting(
            id = "voice_live_text_highlight",
            title = "Live Text Highlight (Beta)",
            subtitle = "Turn on real-time text word highlighting when voice model speaks",
            keywords = "live text highlight voice speech word karaoke reading read aloud sync tts beta follow along",
            category = "APP > Voice & Speech",
            targetSubMenu = SettingsSubMenu.VOICE
        ),
        SearchableSetting(
            id = "voice_auto_speak_responses",
            title = "Auto-Speak Responses",
            subtitle = "Automatically read aloud model responses when generation finishes",
            keywords = "auto speak aloud readout voice talk automatic audio output listen accessibility narration",
            category = "APP > Voice & Speech",
            targetSubMenu = SettingsSubMenu.VOICE
        ),

        // ==========================================
        // 4. PERSONAS & PRESETS (APP > Personas & Presets)
        // ==========================================
        SearchableSetting(
            id = "persona_active_selection",
            title = "Active Persona",
            subtitle = "Code Auditor, ELI5, Proofreader, Translator system prompt presets",
            keywords = "personas persona presets active coder eli5 proofreader translator instructions prompt system role assistant personality",
            category = "APP > Personas",
            targetSubMenu = SettingsSubMenu.PERSONAS
        ),
        SearchableSetting(
            id = "persona_custom_editor",
            title = "Custom Persona Editor",
            subtitle = "Create, edit, and configure custom assistant instructions and prompt templates",
            keywords = "custom persona editor create add edit prompt templates instructions roles guidelines authoring",
            category = "APP > Personas",
            targetSubMenu = SettingsSubMenu.PERSONAS
        ),

        // ==========================================
        // 5. PERSISTENT MEMORY (APP > Persistent Memory)
        // ==========================================
        SearchableSetting(
            id = "memory_persistent_toggle",
            title = "Persistent Long-Term Memory",
            subtitle = "Remember user identity, preferences, facts across chats",
            keywords = "persistent memory remember recall facts long term identity across chats preferences profile continuity",
            category = "APP > Memory",
            targetSubMenu = SettingsSubMenu.MEMORY
        ),
        SearchableSetting(
            id = "memory_auto_extract",
            title = "Auto-Extract Memories",
            subtitle = "Automatically detect and save facts when you say 'Remember that...'",
            keywords = "auto extract memories detect remember facts automatically save preferences conversation learning",
            category = "APP > Memory",
            targetSubMenu = SettingsSubMenu.MEMORY
        ),
        SearchableSetting(
            id = "memory_manage_saved_facts",
            title = "Manage Saved Facts",
            subtitle = "Browse, search, edit, or delete stored memories and learned facts",
            keywords = "manage saved memories facts list edit delete clear stored memory items database inspect",
            category = "APP > Memory",
            targetSubMenu = SettingsSubMenu.MEMORY
        ),
        SearchableSetting(
            id = "memory_clear_all",
            title = "Clear All Memories",
            subtitle = "Permanently wipe all stored long-term memories and facts",
            keywords = "clear wipe reset delete all memories memory database privacy reset erase",
            category = "APP > Memory",
            targetSubMenu = SettingsSubMenu.MEMORY
        ),

        // ==========================================
        // 6. ACCESSIBILITY (APP > Accessibility)
        // ==========================================
        SearchableSetting(
            id = "access_reduce_motion",
            title = "Reduce Decorative Motion",
            subtitle = "Limit movement and animated transitions for vestibular comfort",
            keywords = "reduce decorative motion animation transitions limit accessibility movement vestibular disable effects",
            category = "APP > Accessibility",
            targetSubMenu = SettingsSubMenu.ACCESSIBILITY
        ),
        SearchableSetting(
            id = "access_touch_targets",
            title = "Larger Touch Targets",
            subtitle = "Make buttons and interactive cards larger and easier to tap",
            keywords = "larger touch targets buttons tap accessibility size comfortable spacing reach padding motor",
            category = "APP > Accessibility",
            targetSubMenu = SettingsSubMenu.ACCESSIBILITY
        ),
        SearchableSetting(
            id = "access_high_contrast",
            title = "High Contrast Text & Borders",
            subtitle = "Increase readability and border emphasis on glassmorphism cards",
            keywords = "high contrast text readability borders colors visibility legibility accessibility vision",
            category = "APP > Accessibility",
            targetSubMenu = SettingsSubMenu.ACCESSIBILITY
        ),

        // ==========================================
        // 7. AGENT & TOOLS (AGENT > Agent & Tools)
        // ==========================================
        SearchableSetting(
            id = "agent_tool_calling",
            title = "Tool Calling (Experimental)",
            subtitle = "Calculator, local date/time, and document search tools for compatible LiteRT and GGUF models",
            keywords = "tools tool calling experimental calculator time function calling agent capabilities skills execution functions",
            category = "AGENT > Agent & Tools",
            targetSubMenu = SettingsSubMenu.AGENT_TOOLS
        ),
        SearchableSetting(
            id = "agent_manage_skills",
            title = "Manage Tool Skills",
            subtitle = "Enable, configure, and inspect individual agent tools, plugins, and custom skills",
            keywords = "manage tool skills plugins functions extensions capabilities activate configure custom tools",
            category = "AGENT > Agent & Tools",
            targetSubMenu = SettingsSubMenu.AGENT_TOOLS
        ),
        SearchableSetting(
            id = "agent_calculator_tool",
            title = "Calculator Tool",
            subtitle = "Perform exact arithmetic evaluations, trigonometry, and unit math",
            keywords = "calculator math evaluate arithmetic precision formulas numbers algebra calculation",
            category = "AGENT > Agent & Tools",
            targetSubMenu = SettingsSubMenu.AGENT_TOOLS
        ),
        SearchableSetting(
            id = "agent_datetime_tool",
            title = "Local Date & Time Tool",
            subtitle = "Allow models to check current system date, time, and timezone",
            keywords = "date time local clock timezone calendar current day month year hours minutes",
            category = "AGENT > Agent & Tools",
            targetSubMenu = SettingsSubMenu.AGENT_TOOLS
        ),

        // ==========================================
        // 8. WORKFLOWS (AGENT > Workflows)
        // ==========================================
        SearchableSetting(
            id = "workflow_autonomous_pipelines",
            title = "Workflows & Automations",
            subtitle = "Automated multi-step pipelines and autonomous agent tasks",
            keywords = "workflows automation pipelines multi-step agent autonomous chain execution recipe upcoming orchestrate",
            category = "AGENT > Workflows",
            targetSubMenu = SettingsSubMenu.WORKFLOWS
        ),
        SearchableSetting(
            id = "workflow_chained_execution",
            title = "Chained Model Execution",
            subtitle = "Pipe inputs and outputs across multiple on-device models",
            keywords = "chained model execution pipe multi-model extract elaborate pipeline workflow sequential",
            category = "AGENT > Workflows",
            targetSubMenu = SettingsSubMenu.WORKFLOWS
        ),
        SearchableSetting(
            id = "workflow_batch_documents",
            title = "Batch Document Automations",
            subtitle = "Ingest entire directories of PDFs or notes to summarize, classify, index",
            keywords = "batch document automations pdf notes summarize classify index directory bulk folders ingest",
            category = "AGENT > Workflows",
            targetSubMenu = SettingsSubMenu.WORKFLOWS
        ),
        SearchableSetting(
            id = "workflow_multi_tool_pipelines",
            title = "Multi-Tool Agent Pipelines",
            subtitle = "Orchestrate multi-step tasks with Python/JSON recipes, web validation, and memory updates",
            keywords = "multi-tool agent pipelines python json recipes web verification routines tasks complex",
            category = "AGENT > Workflows",
            targetSubMenu = SettingsSubMenu.WORKFLOWS
        ),
        SearchableSetting(
            id = "workflow_triggers",
            title = "Scheduled & Conditional Triggers",
            subtitle = "Run maintenance, memory consolidation, or news aggregation workflows on schedule or while charging",
            keywords = "scheduled conditional triggers automation background maintenance timer battery charging cron recurring",
            category = "AGENT > Workflows",
            targetSubMenu = SettingsSubMenu.WORKFLOWS
        ),
        SearchableSetting(
            id = "workflow_suggest_feature",
            title = "Suggest a Workflow / Automation",
            subtitle = "Submit workflow or automation feature proposal directly to the repository",
            keywords = "suggest workflow proposal feature request automation idea feedback github submit create",
            category = "AGENT > Workflows",
            targetSubMenu = SettingsSubMenu.WORKFLOWS
        ),

        // ==========================================
        // 9. WEB SEARCH (AGENT > Web Search)
        // ==========================================
        SearchableSetting(
            id = "web_search_enable",
            title = "Enable Web Search",
            subtitle = "Allow supported models to look up current live web data and verify facts",
            keywords = "enable web search live internet online browsing lookup google duckduckgo real-time fact check",
            category = "AGENT > Web Search",
            targetSubMenu = SettingsSubMenu.WEB_SEARCH
        ),
        SearchableSetting(
            id = "web_search_provider",
            title = "Web Search Provider",
            subtitle = "Choose DuckDuckGo, Brave Search, or Tavily search engine",
            keywords = "search provider engine duckduckgo brave tavily choose select switch api endpoint",
            category = "AGENT > Web Search",
            targetSubMenu = SettingsSubMenu.WEB_SEARCH
        ),
        SearchableSetting(
            id = "web_search_brave_key",
            title = "Brave Search API Key",
            subtitle = "Enter personal API key for high-quality Brave Search results",
            keywords = "brave api key search brave.com token secret authentication key header private",
            category = "AGENT > Web Search",
            targetSubMenu = SettingsSubMenu.WEB_SEARCH
        ),
        SearchableSetting(
            id = "web_search_tavily_key",
            title = "Tavily Search API Key",
            subtitle = "Enter personal API key for Tavily AI research and structured search",
            keywords = "tavily api key search tavily.com token ai search research structured key",
            category = "AGENT > Web Search",
            targetSubMenu = SettingsSubMenu.WEB_SEARCH
        ),
        SearchableSetting(
            id = "web_search_test_connection",
            title = "Test Search Connection",
            subtitle = "Test query with active provider to verify internet connectivity and formatting",
            keywords = "test search connection verify query check diagnose network test test search ping",
            category = "AGENT > Web Search",
            targetSubMenu = SettingsSubMenu.WEB_SEARCH
        ),

        // ==========================================
        // 10. RAG KNOWLEDGE BASE (AGENT > Knowledge Base (RAG))
        // ==========================================
        SearchableSetting(
            id = "rag_enable_kb",
            title = "RAG Knowledge Base",
            subtitle = "Semantically search indexed documents and inject relevant context into chats",
            keywords = "rag knowledge base documents pdf search semantic index context offline retrieval reference files",
            category = "AGENT > Knowledge Base (RAG)",
            targetSubMenu = SettingsSubMenu.RAG
        ),
        SearchableSetting(
            id = "rag_neural_search",
            title = "Neural Semantic Search",
            subtitle = "potion-base-2M neural embedding search offline for meaning-based retrieval",
            keywords = "neural semantic search potion base 2m model2vec embeddings vector rag meaning offline model ai retrieval bge",
            category = "AGENT > Knowledge Base (RAG)",
            targetSubMenu = SettingsSubMenu.RAG
        ),
        SearchableSetting(
            id = "rag_manage_documents",
            title = "Manage Indexed Documents",
            subtitle = "Inspect indexed PDFs, text notes, total chunks count, or remove documents",
            keywords = "manage indexed documents pdf files notes chunks count status rebuild remove delete chunks",
            category = "AGENT > Knowledge Base (RAG)",
            targetSubMenu = SettingsSubMenu.RAG
        ),
        SearchableSetting(
            id = "rag_document_ingestion",
            title = "Document Ingestion & File Parser",
            subtitle = "Import and parse local documents into the on-device knowledge base",
            keywords = "document ingestion import parse pdf txt markdown attach knowledge base extract text",
            category = "AGENT > Knowledge Base (RAG)",
            targetSubMenu = SettingsSubMenu.RAG
        ),

        // ==========================================
        // 11. TELEMETRY & THERMAL GUARD (AGENT > Telemetry & Thermal Guard)
        // ==========================================
        SearchableSetting(
            id = "telemetry_performance_bar",
            title = "Performance Telemetry Bar",
            subtitle = "Show tokens per second (t/s) and latency (ms) in the header pill",
            keywords = "performance telemetry bar speed tokens per second latency ms header pill stats fps benchmark timing",
            category = "AGENT > Telemetry & Thermal Guard",
            targetSubMenu = SettingsSubMenu.TELEMETRY
        ),
        SearchableSetting(
            id = "telemetry_thermal_guard",
            title = "Battery Temperature (°C)",
            subtitle = "Display live battery thermal reading in telemetry bar to monitor heat",
            keywords = "battery temperature guard thermal heat celsius fahrenheit monitor hot protection degrees celsius",
            category = "AGENT > Telemetry & Thermal Guard",
            targetSubMenu = SettingsSubMenu.TELEMETRY
        ),
        SearchableSetting(
            id = "telemetry_ram_guard",
            title = "System Memory (RAM) Guard",
            subtitle = "Display available system RAM (GB free) indicator in telemetry bar",
            keywords = "system memory ram guard free gigabytes indicator hardware status monitor memory usage",
            category = "AGENT > Telemetry & Thermal Guard",
            targetSubMenu = SettingsSubMenu.TELEMETRY
        ),
        SearchableSetting(
            id = "telemetry_solus_bench",
            title = "Solus Bench Rating Button",
            subtitle = "Show device performance benchmark rating button on model cards",
            keywords = "solus bench rating device benchmark button test score rating performance speed score",
            category = "AGENT > Telemetry & Thermal Guard",
            targetSubMenu = SettingsSubMenu.TELEMETRY
        ),

        // ==========================================
        // 12. ON-DEVICE ENGINE (MODELS > On-Device Engine)
        // ==========================================
        SearchableSetting(
            id = "engine_backend_preference",
            title = "Inference Backend (GPU/CPU)",
            subtitle = "Choose Auto, Vulkan GPU accelerator, or CPU safe mode",
            keywords = "inference backend vulkan gpu cpu accelerator hardware safe mode litert gguf llama chip driver",
            category = "MODELS > On-Device Engine",
            targetSubMenu = SettingsSubMenu.ENGINE
        ),
        SearchableSetting(
            id = "engine_detected_hardware",
            title = "Detected Hardware Accelerator",
            subtitle = "View detected Vulkan GPU adapter, OpenCL support, and chip specifications",
            keywords = "detected hardware accelerator gpu name vulkan device adapter chip adreno mali qualcomm mediatek",
            category = "MODELS > On-Device Engine",
            targetSubMenu = SettingsSubMenu.ENGINE
        ),
        SearchableSetting(
            id = "engine_speed_tuner",
            title = "Runtime Speed Tuner",
            subtitle = "Measure and tune CPU threads and GPU probe speeds for optimal latency",
            keywords = "runtime speed tuner threads probe measure optimize latency benchmark cpu threads test tuning",
            category = "MODELS > On-Device Engine",
            targetSubMenu = SettingsSubMenu.ENGINE
        ),
        SearchableSetting(
            id = "engine_context_window",
            title = "Context Window Capacity",
            subtitle = "Default token context window capacity (2048, 4096, 8192 tokens)",
            keywords = "context window capacity size tokens 2048 4096 8192 buffer limit length memory budget",
            category = "MODELS > On-Device Engine",
            targetSubMenu = SettingsSubMenu.ENGINE
        ),

        // ==========================================
        // 13. PROVIDERS & API (MODELS > Providers & API)
        // ==========================================
        SearchableSetting(
            id = "provider_hf_token",
            title = "Hugging Face Access Token",
            subtitle = "Enter personal access read token for gated models (Gemma, Llama)",
            keywords = "hugging face access token hf api key gated download login auth secret bearer read token permission",
            category = "MODELS > Providers & API",
            targetSubMenu = SettingsSubMenu.PROVIDERS
        ),
        SearchableSetting(
            id = "provider_hf_tutorial",
            title = "Token Creation Tutorial",
            subtitle = "Open step-by-step tutorial on creating a free Hugging Face token",
            keywords = "token creation tutorial guide instructions how to hugging face help link video walkthrough",
            category = "MODELS > Providers & API",
            targetSubMenu = SettingsSubMenu.PROVIDERS
        ),

        // ==========================================
        // 14. MODEL PROFILES (MODELS > Model Profiles)
        // ==========================================
        SearchableSetting(
            id = "profiles_per_model",
            title = "Per-Model Profiles",
            subtitle = "Save custom instructions, context limits, and sampling parameters per model",
            keywords = "per model profiles custom context limit temperature top p sampling instructions per-model config",
            category = "MODELS > Model Profiles",
            targetSubMenu = SettingsSubMenu.MODEL_PROFILES
        ),
        SearchableSetting(
            id = "profiles_editor",
            title = "Model Profile Configuration",
            subtitle = "Edit GGUF/LiteRT specific sampling, template, and system instructions",
            keywords = "model profile configuration editor gguf litert parameters configure save override prompt system",
            category = "MODELS > Model Profiles",
            targetSubMenu = SettingsSubMenu.MODEL_PROFILES
        ),

        // ==========================================
        // 15. STORAGE & DOWNLOADS (MODELS > Storage & Downloads)
        // ==========================================
        SearchableSetting(
            id = "storage_usage_breakdown",
            title = "Storage Usage & Breakdown",
            subtitle = "Inspect model files, attachments, and knowledge base vector storage",
            keywords = "storage usage breakdown disk space size gigabytes models attachments cache internal bytes",
            category = "MODELS > Storage & Downloads",
            targetSubMenu = SettingsSubMenu.STORAGE
        ),
        SearchableSetting(
            id = "storage_clear_cache",
            title = "Clear Unused Cache",
            subtitle = "Delete temporary attachments and cache files to recover disk space",
            keywords = "clear unused cache clean cleanup delete free disk space temporary files recover memory",
            category = "MODELS > Storage & Downloads",
            targetSubMenu = SettingsSubMenu.STORAGE
        ),
        SearchableSetting(
            id = "storage_model_file_manager",
            title = "Model File Manager",
            subtitle = "View installed weights, file paths, and delete unused GGUF/LiteRT models",
            keywords = "model file manager installed weights delete uninstall gguf litert manage files free space",
            category = "MODELS > Storage & Downloads",
            targetSubMenu = SettingsSubMenu.STORAGE
        ),

        // ==========================================
        // 16. BACKUP & IMPORT (DATA > Backup & Import)
        // ==========================================
        SearchableSetting(
            id = "backup_export",
            title = "Export Backup",
            subtitle = "Export chat history, memories, and portable settings to a portable ZIP archive",
            keywords = "export backup save zip transfer archive portable download chats history memories export",
            category = "DATA > Backup & Import",
            targetSubMenu = SettingsSubMenu.BACKUP
        ),
        SearchableSetting(
            id = "backup_restore",
            title = "Restore Backup",
            subtitle = "Import saved chats, memories, and portable settings from a ZIP backup archive",
            keywords = "restore backup import zip load migrate transfer data chats archive restore",
            category = "DATA > Backup & Import",
            targetSubMenu = SettingsSubMenu.BACKUP
        ),

        // ==========================================
        // 17. PRIVACY & ARCHITECTURE (DATA > Privacy & Architecture)
        // ==========================================
        SearchableSetting(
            id = "privacy_zero_cloud",
            title = "100% On-Device AI Architecture",
            subtitle = "Zero cloud processing, zero analytics, zero external telemetry or tracking",
            keywords = "privacy architecture 100% offline local-only security safety zero cloud telemetry tracking private safe",
            category = "DATA > Privacy & Architecture",
            targetSubMenu = SettingsSubMenu.PRIVACY
        ),
        SearchableSetting(
            id = "privacy_local_storage",
            title = "Local Data Isolation",
            subtitle = "All chats, models, memories, and documents are stored 100% locally on device",
            keywords = "local data isolation encrypted storage device private confidential safe sandbox no cloud",
            category = "DATA > Privacy & Architecture",
            targetSubMenu = SettingsSubMenu.PRIVACY
        ),

        // ==========================================
        // 18. TROUBLESHOOTING & DIAGNOSTICS (HELP > Troubleshooting & Diagnostics)
        // ==========================================
        SearchableSetting(
            id = "troubleshoot_crash_reports",
            title = "Crash Reports & Logs",
            subtitle = "Inspect JVM and native engine crash logs with sanitized system information",
            keywords = "crash reports logs bugs error stacktrace debug exception jvm native diagnostics dump",
            category = "HELP > Troubleshooting & Diagnostics",
            targetSubMenu = SettingsSubMenu.TROUBLESHOOTING
        ),
        SearchableSetting(
            id = "troubleshoot_report_github",
            title = "Report Issue to GitHub",
            subtitle = "Open pre-filled issue report on repository with safe diagnostic report on clipboard",
            keywords = "report issue bug github feedback support repo submit crash report log issue ticket",
            category = "HELP > Troubleshooting & Diagnostics",
            targetSubMenu = SettingsSubMenu.TROUBLESHOOTING
        ),
        SearchableSetting(
            id = "troubleshoot_clear_logs",
            title = "Clear Crash Reports",
            subtitle = "Delete all stored crash logs and diagnostic crash summaries",
            keywords = "clear crash reports delete wipe remove error reports reset logs",
            category = "HELP > Troubleshooting & Diagnostics",
            targetSubMenu = SettingsSubMenu.TROUBLESHOOTING
        ),
        SearchableSetting(
            id = "troubleshoot_diagnostics",
            title = "System Diagnostics",
            subtitle = "Inspect Android version, device ABI, available memory, and runtime specs",
            keywords = "system diagnostics android version abi cpu ram device specs hardware info memory details",
            category = "HELP > Troubleshooting & Diagnostics",
            targetSubMenu = SettingsSubMenu.TROUBLESHOOTING
        ),

        // ==========================================
        // 19. ABOUT SOLUS (HELP > About Solus)
        // ==========================================
        SearchableSetting(
            id = "about_solus_updates",
            title = "About Solus & Updates",
            subtitle = "Version info, developer, GitHub repository and update checks",
            keywords = "about solus version build developer github author repo release notes information contact",
            category = "HELP > About Solus",
            targetSubMenu = SettingsSubMenu.ABOUT
        ),
        SearchableSetting(
            id = "about_check_updates",
            title = "Check for Updates",
            subtitle = "Check for newer Solus releases on GitHub and view release highlights",
            keywords = "check for updates release upgrade new version github releases download ota update",
            category = "HELP > About Solus",
            targetSubMenu = SettingsSubMenu.ABOUT
        ),
        SearchableSetting(
            id = "about_release_notes",
            title = "Release Notes & Changelog",
            subtitle = "Inspect current release version features, improvements, and highlights",
            keywords = "release notes changelog what's new updates history features changes v7.0.0 highlights",
            category = "HELP > About Solus",
            targetSubMenu = SettingsSubMenu.ABOUT
        ),
        SearchableSetting(
            id = "about_foss_licenses",
            title = "Open Source Licenses",
            subtitle = "Third-party open source licenses and software attribution",
            keywords = "open source licenses foss attribution notices terms apache mit candle llamacpp onnx litert",
            category = "HELP > About Solus",
            targetSubMenu = SettingsSubMenu.ABOUT
        ),
        SearchableSetting(
            id = "about_fdroid_info",
            title = "F-Droid Distribution",
            subtitle = "100% Free and Open Source Software (FOSS) compliant distribution",
            keywords = "fdroid f-droid foss reproducible free software distribution repo package",
            category = "HELP > About Solus",
            targetSubMenu = SettingsSubMenu.ABOUT
        )
    )

    /**
     * Ensures that the Settings Search Index is automatically synchronized with the current app version.
     *
     * If a new app update is detected ([BuildConfig.VERSION_CODE] > last indexed version code),
     * the synchronization timestamp and version are updated in SharedPreferences, dynamic items
     * are refreshed, and an [IndexSyncResult] indicating a new update is returned.
     */
    fun ensureIndexSynchronized(context: Context? = null): IndexSyncResult {
        val currentVersionCode = BuildConfig.VERSION_CODE
        val currentVersionName = BuildConfig.VERSION_NAME
        val totalCount = getAllSettings().size
        val timestamp = System.currentTimeMillis()

        if (context == null) {
            return IndexSyncResult(
                isNewUpdate = false,
                previousVersionCode = currentVersionCode,
                currentVersionCode = currentVersionCode,
                totalIndexedCount = totalCount,
                timestamp = timestamp
            )
        }

        val prefs = getPreferences(context)
        val previousVersionCode = prefs.getInt(KEY_LAST_INDEXED_VERSION_CODE, 0)
        val isNewUpdate = previousVersionCode < currentVersionCode

        if (isNewUpdate || !prefs.contains(KEY_LAST_INDEXED_VERSION_CODE)) {
            Log.i(
                TAG,
                "Automatic index synchronization triggered: app updated from build $previousVersionCode to $currentVersionCode ($currentVersionName). Total items indexed: $totalCount"
            )
            prefs.edit()
                .putInt(KEY_LAST_INDEXED_VERSION_CODE, currentVersionCode)
                .putString(KEY_LAST_VERSION_NAME, currentVersionName)
                .putLong(KEY_LAST_SYNC_TIMESTAMP, timestamp)
                .putInt(KEY_INDEXED_ITEM_COUNT, totalCount)
                .apply()
        }

        return IndexSyncResult(
            isNewUpdate = isNewUpdate,
            previousVersionCode = previousVersionCode,
            currentVersionCode = currentVersionCode,
            totalIndexedCount = totalCount,
            timestamp = timestamp
        )
    }

    /**
     * Returns all indexed settings (built-in + any dynamically registered settings).
     */
    fun getAllSettings(): List<SearchableSetting> {
        return BUILT_IN_SETTINGS + dynamicSettings
    }

    /**
     * Registers dynamic searchable settings (e.g. installed models, custom personas, custom tool skills).
     */
    fun registerDynamicSetting(setting: SearchableSetting) {
        dynamicSettings.removeAll { it.id == setting.id }
        dynamicSettings.add(setting)
    }

    /**
     * Clears all dynamically registered settings.
     */
    fun clearDynamicSettings() {
        dynamicSettings.clear()
    }

    /**
     * Synchronizes downloaded / available models into the search index so users can search for models by name.
     */
    fun syncAvailableModels(models: List<com.shounak.localmeshai.models.ModelInfo>) {
        dynamicSettings.removeAll { it.id.startsWith("model_profile_") }
        for (model in models) {
            registerDynamicSetting(
                SearchableSetting(
                    id = "model_profile_${model.id}",
                    title = "Model: ${model.name}",
                    subtitle = "Configure custom profile, sampling parameters and prompt for ${model.name}",
                    keywords = "model profile ${model.name} ${model.id} ${model.fileName} sampling context prompt weights",
                    category = "MODELS > Model Profiles",
                    targetSubMenu = SettingsSubMenu.MODEL_PROFILES
                )
            )
        }
    }

    /**
     * Performs a smart, multi-token ranked search across all indexed settings.
     *
     * Items are scored by:
     * - Exact title match (+1000)
     * - Title equals query case-insensitively (+500)
     * - Title starts with query (+300)
     * - Title contains query substring (+200)
     * - Title contains token (+100 per token)
     * - Keywords contain exact token (+80 per token)
     * - Keywords contain partial token (+40 per token)
     * - Subtitle contains query (+50)
     * - Subtitle contains token (+20 per token)
     * - Category contains token (+15 per token)
     *
     * @param query The raw user search string.
     * @return List of matching [SearchableSetting]s sorted by relevance (highest score first).
     */
    fun search(query: String): List<SearchableSetting> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val allItems = getAllSettings()
        val scoredResults = mutableListOf<Pair<SearchableSetting, Int>>()

        for (item in allItems) {
            val titleLower = item.title.lowercase()
            val subtitleLower = item.subtitle.lowercase()
            val keywordsLower = item.keywords.lowercase()
            val categoryLower = item.category.lowercase()

            // Verify that EVERY token matches somewhere in title, subtitle, keywords, or category
            val matchesAllTokens = tokens.all { token ->
                titleLower.contains(token) ||
                subtitleLower.contains(token) ||
                keywordsLower.contains(token) ||
                categoryLower.contains(token)
            }

            if (!matchesAllTokens) continue

            var score = 0

            // Title relevance
            if (titleLower == trimmed) {
                score += 1000
            } else if (titleLower.startsWith(trimmed)) {
                score += 500
            } else if (titleLower.contains(trimmed)) {
                score += 300
            }

            for (token in tokens) {
                if (titleLower.contains(token)) {
                    score += 100
                    if (titleLower.split("\\s+".toRegex()).any { it == token }) {
                        score += 50
                    }
                }

                // Keyword relevance
                if (keywordsLower.contains(token)) {
                    score += 40
                    if (keywordsLower.split("\\s+".toRegex()).any { it == token }) {
                        score += 40
                    }
                }

                // Subtitle relevance
                if (subtitleLower.contains(token)) {
                    score += 20
                }

                // Category relevance
                if (categoryLower.contains(token)) {
                    score += 15
                }
            }

            // Phrase boost in subtitle
            if (subtitleLower.contains(trimmed)) {
                score += 50
            }

            scoredResults.add(item to score)
        }

        // Sort descending by score
        return scoredResults.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Returns the version code when the index was last synchronized.
     */
    fun getLastIndexedVersionCode(context: Context): Int {
        return getPreferences(context).getInt(KEY_LAST_INDEXED_VERSION_CODE, 0)
    }

    /**
     * Returns the epoch millisecond timestamp of the last index sync.
     */
    fun getLastSyncTimestamp(context: Context): Long {
        return getPreferences(context).getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
