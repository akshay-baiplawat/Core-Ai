# Core AI

> **A headless, on-device LLM engine for Android — shared across apps via a secure AIDL IPC boundary.**

Core AI is a background Android service that runs large language model inference entirely on the local device. Any third-party app can bind to it, send prompts, and receive completions — without writing a single line of ML infrastructure. Models never leave the device. No cloud. No latency spikes. No per-token billing.

---

## Key Features

### Headless AI Platform

Core AI runs as a bound Android service (`CoreAiService`). It has no mandatory UI of its own; it is a shared inference backend. Multiple client apps can bind to it simultaneously, each isolated by API key. A built-in Material 3 host app ships for model management and developer onboarding.

### Local Inference — Privacy by Default

All inference is executed on-device using [LiteRT](https://ai.google.dev/edge/litert) (Google's edge LLM runtime), GGUF quantized models via llama.cpp/JNI, and MediaPipe GenAI. Prompts and completions are never transmitted to any server.

### Multi-Tenant Engine Lock

The engine is shared across all bound clients. A `ConcurrentHashMap`-backed model registry combined with an `AtomicBoolean` lock using CAS semantics prevents race conditions during concurrent `loadModel`, `unloadModel`, and `runInference` calls. Lock contention is surfaced immediately to the caller via the async callback — no silent queuing.

### Hardware Acceleration with Transparent CPU Fallback

On supported hardware Core AI attempts GPU acceleration (Qualcomm GpuArtisan). If the GPU backend fails to initialise, it falls back to CPU inference automatically. The client API is identical in both cases.

### API Key Security

Every client identity is a UUID v4 key stored in `EncryptedSharedPreferences` (AES-256 GCM). Every AIDL call validates the key before execution. Keys carry a last-used timestamp and can be revoked at runtime.

The service binding permission (`com.stridetech.coreai.permission.BIND_LLM_SERVICE`) uses `protectionLevel="normal"` — any installed app can declare it and bind to the service. The API key is the sole runtime access gate.

### In-App Model Hub

The host app includes a browsable model catalog, one-tap downloads with progress reporting (0–100 %), and a local file importer that accepts GGUF, LiteRT, and BIN formats via the Android Storage Access Framework. Downloads are written to a `.tmp` file and renamed atomically on success — a partial download never pollutes the model directory.

### GGUF Architecture Auto-Detection

For GGUF models, Core AI reads the `general.architecture` field directly from the file header (without loading the model into RAM) to select the correct chat template. This guarantees accurate prompt formatting even for models with opaque filenames. Supported architectures: `llama`, `gemma`, `gemma3`, `phi2`, `phi3`, `qwen2`, `qwen3`, `mistral`, `falcon`.

---

## Architecture

```text
┌─────────────────────────────────────────┐
│           Client Application            │
│  ServiceConnection → ICoreAiInterface   │
└───────────────────┬─────────────────────┘
                    │  Android IPC (Binder/AIDL)
┌───────────────────▼─────────────────────┐
│            CoreAiService                │
│   ┌─────────────────────────────────┐   │
│   │    ICoreAiInterface.Stub()      │   │
│   │  • API key validation           │   │
│   │  • Engine lock (AtomicBoolean)  │   │
│   │  • Callback broadcast           │   │
│   └──────────────┬──────────────────┘   │
│                  │                      │
│   ┌──────────────▼──────────────────┐   │
│   │  LlmEngine / ModelEngineFactory │   │
│   │  • LiteRT backend (GPU-first)   │   │
│   │  • GGUF backend (llama.cpp/JNI) │   │
│   │  • MediaPipe backend (.bin/.task)│   │
│   └─────────────────────────────────┘   │
└─────────────────────────────────────────┘

Host App (Material 3 / Compose)
  ├── Model Hub   — download & import
  ├── Playground  — interactive prompt testing
  ├── Settings    — key management
  └── Dev Docs    — integration guide (in-app)
```

**Layer overview:**

| Layer | Responsibility |
| --- | --- |
| AIDL Interface | IPC contract — `ICoreAiInterface` + `ICoreAiCallback` |
| Service | `CoreAiService` — orchestrates engine, security, callbacks |
| ML | `LlmEngine`, `ModelEngineFactory` — hardware strategy pattern |
| Security | `ApiKeyManager` — encrypted storage, validation, revocation |
| Hub | Catalog, downloader, importer — model lifecycle on disk |
| UI | Jetpack Compose — MVVM, `StateFlow`, Hilt DI |

---

## Getting Started (Client Integration)

### 1. Copy the AIDL files

Create the package directory in your project and copy both files:

```text
app/src/main/aidl/com/stridetech/coreai/
├── ICoreAiInterface.aidl
└── ICoreAiCallback.aidl
```

The package declaration in each file must remain `com.stridetech.coreai`.

### 2. Declare queries and permissions in your AndroidManifest.xml

```xml
<!-- Allow your app to discover and bind to the Core AI service -->
<queries>
    <package android:name="com.stridetech.coreai" />
</queries>

<!-- Required to bind to CoreAiService (normal protection level — any app can declare this) -->
<uses-permission android:name="com.stridetech.coreai.permission.BIND_LLM_SERVICE" />
```

If you intend to run inference in the foreground, also add:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

### 3. Bind to the service

```kotlin
private var coreAi: ICoreAiInterface? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        coreAi = ICoreAiInterface.Stub.asInterface(binder)
    }
    override fun onServiceDisconnected(name: ComponentName) {
        coreAi = null
    }
}

fun bindCoreAi(context: Context) {
    val intent = Intent().apply {
        component = ComponentName(
            "com.stridetech.coreai",
            "com.stridetech.coreai.service.CoreAiService"
        )
    }
    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
}
```

### 4. Register a callback and run inference

```kotlin
val callback = object : ICoreAiCallback.Stub() {
    override fun onInferenceResult(resultJson: String) {
        // {"completion":"...","latency_ms":312,"success":true,"error":null}
        Log.d("CoreAI", resultJson)
    }
    override fun onInferenceToken(token: String?) {}        // streaming token
    override fun onInferenceComplete(latencyMs: Long) {}    // fires after last token
    override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {}
    override fun onError(errorMessage: String?) {}
    override fun onModelTransferProgress(modelId: String?, percent: Int) {}
    override fun onModelTransferComplete(modelId: String?, filePath: String?) {}
    override fun onModelTransferError(modelId: String?, errorMessage: String?) {}
}

// Register once after binding
coreAi?.registerCallback(callback)

// Submit a prompt — result arrives asynchronously via onInferenceResult
coreAi?.runInference(YOUR_API_KEY, "Explain quantum entanglement in one sentence.")
```

> `runInference` returns immediately with a pending envelope:
> `{"completion":null,"latency_ms":0,"success":true,"pending":true,"error":null}`
> The real result is delivered via `ICoreAiCallback#onInferenceResult`.

### 5. Query engine state (synchronous)

```kotlin
// Active model
val activeJson     = coreAi?.getActiveModel(apiKey)
// {"modelId":"gemma-2b","isReady":true}

// All models on disk
val downloadedJson = coreAi?.getDownloadedModels(apiKey)
// {"models":[{"modelId":"gemma-2b","path":"/data/...","sizeBytes":1500000000}],"error":null}

// Models currently loaded into RAM
val loadedJson     = coreAi?.getLoadedModels(apiKey)
// {"models":["gemma-2b"],"error":null}
```

### 6. Manage models

```kotlin
// Download from catalog URL with progress callbacks.
// ⚠ SECURITY CONSTRAINTS: downloadUrl must start with "https://"; modelId must match
// [a-zA-Z0-9_-]+. Violations are rejected immediately.
// Pass "" for modelId to use the system default (gemma-3-1b-q4).
coreAi?.downloadCatalogModel(apiKey, "gemma-2b", "https://example.com/gemma-2b.bin", callback)

// Import from device storage (Storage Access Framework URI).
// Supported engineType values (case-insensitive): "gguf" | "litertlm" | "bin".
coreAi?.importLocalModel(apiKey, fileUri, "my-model", "gguf", callback)

// Load into RAM, then make active. Pass "" for modelId to load the default model.
coreAi?.loadModel(apiKey, "gemma-2b", callback)
coreAi?.setActiveModel(apiKey, "gemma-2b")

// Unload to free memory
coreAi?.unloadModel(apiKey, "gemma-2b", callback)

// Safe deletion — acquires engine lock, unloads from RAM, then deletes file from disk.
coreAi?.deleteModel(apiKey, "gemma-2b", callback)

// Browse the bundled model catalog
val catalogJson = coreAi?.getCatalog(apiKey)
// {"models":[{"id":"gemma-3-1b-q4","name":"...","download_url":"...","engine_type":"gguf"}],"error":null}
```

### 7. Context Isolation Mode

```kotlin
// FULL_PROMPT (default) — service is stateless; client sends full conversation history each call.
// PER_CLIENT — service tracks history per caller UID; send only the latest user message.
coreAi?.setContextMode(apiKey, "PER_CLIENT")

// Read the active mode ("FULL_PROMPT" or "PER_CLIENT")
val mode = coreAi?.getContextMode(apiKey)

// Flush conversation history for this session.
// PER_CLIENT: clears only this app's session. FULL_PROMPT: also flushes the native KV cache.
coreAi?.resetChatContext(apiKey, modelId)
```

### 8. Custom Chat Templates

By default Core AI auto-detects the correct prompt format using three tiers in priority order:

1. **Exact catalog ID match** — `gemma-3-1b-q4`, `gemma-3-4b-q4`, `llama-3.2-1b-instruct`, `phi-3.5-mini-q4`
2. **GGUF `general.architecture`** — for `.gguf` files the service reads the architecture field from the file header without loading the model (e.g. `llama` → Llama 3 tokens, `gemma3` → Gemma tokens, `phi3`/`qwen2`/`mistral`/`falcon` → ChatML)
3. **Name heuristics** — fallback when metadata is unavailable (contains `"llama"` → Llama 3 tokens, contains `"gemma"` → Gemma tokens, anything else → ChatML)

Use `setCustomChatTemplate` to override for a specific model ID — useful for fine-tuned variants or
models not yet in the built-in router.

```kotlin
// All eight fields are optional — missing ones default to "".
val templateJson = """
  {
    "bosToken":               "",
    "systemPromptPrefix":     "[INST] ",
    "systemPromptSuffix":     " [/INST]\n",
    "userMessagePrefix":      "[INST] ",
    "userMessageSuffix":      " [/INST]\n",
    "assistantMessagePrefix": "",
    "assistantMessageSuffix": "\n",
    "stopToken":              "</s>"
  }
""".trimIndent()

// Register — modelId must match the id used in loadModel() / setActiveModel().
// Takes effect on the next runInference() call.
coreAi?.setCustomChatTemplate(apiKey, "my-mistral-model", templateJson)

// Clear — pass "" to revert a model back to auto-detection.
coreAi?.setCustomChatTemplate(apiKey, "my-mistral-model", "")
```

---

### 9. Hugging Face Integration

Core AI supports downloading GGUF models directly from Hugging Face using the resolve endpoint.

```kotlin
// Public HF models — pass the resolve URL directly (no token needed).
// Same security constraints apply: HTTPS only, modelId must match [a-zA-Z0-9_-]+.
coreAi?.downloadCatalogModel(
    apiKey,
    "gemma-hf",   // modelId
    "https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
    callback
)

// Gated HF models — your app must download with an HF Bearer token, then import via GGUF.
// Core AI cannot attach your personal HF token to outbound requests.
val request = Request.Builder()
    .url("https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B.gguf")
    .addHeader("Authorization", "Bearer $userHfToken")
    .build()
okHttpClient.newCall(request).execute().use { response ->
    val file = File(cacheDir, "llama-3-2-1b.gguf")
    file.outputStream().use { response.body!!.byteStream().copyTo(it) }
    val uri = FileProvider.getUriForFile(context, "$packageName.provider", file)
    coreAi?.importLocalModel(apiKey, uri, "llama-3-2-1b", "GGUF", callback)
}
```

### 10. Clean up

```kotlin
// Always unregister before unbinding
coreAi?.unregisterCallback(callback)
context.unbindService(connection)
```

---

## Requirements

| | |
| --- | --- |
| **Min Android version** | Android 12 (API 31) |
| **Target SDK** | Android 16 (API 36) |
| **ABI** | arm64-v8a (primary), x86_64 (emulator) |
| **RAM** | 4 GB device minimum; 6 GB+ recommended for 2B+ parameter models |
| **Storage** | Varies by model (1–8 GB per model file) |

---

## Technology Stack

| Component | Library / Framework |
| --- | --- |
| On-device inference | Google LiteRT (`com.google.ai.edge:litertlm-android`) |
| GGUF inference | llama.cpp via JNI (`.gguf` quantized models) |
| Alternate model format | MediaPipe GenAI (`com.google.mediapipe:tasks-genai`) |
| IPC | Android AIDL / Binder |
| Encrypted storage | AndroidX Security Crypto (AES-256 GCM) |
| Networking | Retrofit + OkHttp |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt + KSP |
| Async | Kotlin Coroutines + StateFlow |
| Build | Gradle 8 + KTS |

---

## In-App Developer Docs

The host app ships a live integration guide under **Settings → Developer Docs**. It mirrors this README with interactive, copyable code snippets and covers all ten integration steps. Open it on any device running Core AI.

---

## License

Copyright © 2026 StrideTech. All rights reserved.
