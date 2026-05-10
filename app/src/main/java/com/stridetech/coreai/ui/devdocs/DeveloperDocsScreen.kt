package com.stridetech.coreai.ui.devdocs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Premium IDE color palette — Catppuccin Mocha inspired
private val IDE_BG = Color(0xFF1E1E2E)
private val IDE_HEADER_BG = Color(0xFF181825)
private val IDE_BORDER = Color(0xFF313244)
private val IDE_CODE_TEXT = Color(0xFFCDD6F4)
private val IDE_TITLE_TEXT = Color(0xFF89B4FA)
private val IDE_COPY_ICON = Color(0xFF6C7086)

// !! SYNC WARNING !!
// FULL_DOC_TEXT is the plain-text copy used by the top-level "Copy full doc" button.
// Every time you add, remove, or reword a step in the Compose UI below (DocStep / CodeSnippetCard),
// you MUST update this string to match. It is NOT auto-generated from the UI — drift will
// silently produce a stale clipboard payload for developers who copy the full guide.
private val FULL_DOC_TEXT = """
# Core AI — Developer Integration Guide

Follow these steps to bind your app to the Core AI service and run LLM inference locally.

---

## Step 1 — Copy the AIDL Files

Copy the two AIDL interface files into your project, preserving the exact package path
com/stridetech/coreai/ under your src/main/aidl/ directory.

Files required:
  src/main/aidl/com/stridetech/coreai/ICoreAiInterface.aidl
  src/main/aidl/com/stridetech/coreai/ICoreAiCallback.aidl

The package declaration inside each file must match exactly: package com.stridetech.coreai;

---

## Step 2 — Declare Manifest Requirements

Add a <queries> block so Android allows your app to discover the Core AI service.
Declare the BIND_LLM_SERVICE permission to bind to the service (normal protection
level — any app can declare this), and optionally FOREGROUND_SERVICE for foreground use.

  <manifest ...>
      <queries>
          <package android:name="com.stridetech.coreai" />
      </queries>
      <uses-permission android:name="com.stridetech.coreai.permission.BIND_LLM_SERVICE" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
      <application ...>
          ...
      </application>
  </manifest>

---

## Step 3 — Bind to the Service

Create a ServiceConnection, build the explicit Intent, and call bindService in your
Activity or ViewModel. Remember to unbind in onDestroy / onCleared.

  import android.content.ComponentName
  import android.content.Context
  import android.content.Intent
  import android.content.ServiceConnection
  import android.os.IBinder
  import com.stridetech.coreai.ICoreAiInterface

  private const val BIND_ACTION   = "com.stridetech.coreai.BIND_LLM_SERVICE"
  private const val SERVICE_PKG   = "com.stridetech.coreai"
  private const val SERVICE_CLASS = "com.stridetech.coreai.service.CoreAiService"

  class MyViewModel(private val app: Application) : AndroidViewModel(app) {
      private var coreAi: ICoreAiInterface? = null

      private val connection = object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, binder: IBinder) {
              coreAi = ICoreAiInterface.Stub.asInterface(binder)
              coreAi?.registerCallback(myCallback)
          }
          override fun onServiceDisconnected(name: ComponentName) { coreAi = null }
      }

      fun bind() {
          val intent = Intent(BIND_ACTION).apply { setClassName(SERVICE_PKG, SERVICE_CLASS) }
          app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      }

      override fun onCleared() {
          coreAi?.unregisterCallback(myCallback)
          app.unbindService(connection)
      }
  }

---

## Step 4 — Register Callback & Run Inference

Implement ICoreAiCallback.Stub to receive model state changes and inference results,
then call runInference(apiKey, prompt). Obtain an API key from the Core AI Settings screen.

  import com.stridetech.coreai.ICoreAiCallback
  import org.json.JSONObject

  private val myCallback = object : ICoreAiCallback.Stub() {
      override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {}
      override fun onError(errorMessage: String?) {}
      override fun onInferenceResult(resultJson: String?) {
          resultJson ?: return
          val json       = JSONObject(resultJson)
          val success    = json.optBoolean("success", false)
          val completion = json.optString("completion")
          val latencyMs  = json.optLong("latency_ms")
      }
      override fun onInferenceToken(token: String?) {}       // streaming token
      override fun onInferenceComplete(latencyMs: Long) {}   // fires after last token
      override fun onModelTransferProgress(modelId: String?, percent: Int) {}
      override fun onModelTransferComplete(modelId: String?, filePath: String?) {}
      override fun onModelTransferError(modelId: String?, errorMessage: String?) {}
  }

  fun runPrompt(apiKey: String, prompt: String) {
      val service = coreAi ?: return
      if (!service.isReady()) return
      viewModelScope.launch(Dispatchers.IO) {
          service.runInference(apiKey, prompt)
      }
  }

---

## Step 5 — Querying Engine State

Use the JSON state query methods to inspect what is active and what is available on the device.

  val activeJson     = coreAi.getActiveModel(apiKey)
  // { "modelId": "gemma-2b", "isReady": true }

  val downloadedJson = coreAi.getDownloadedModels(apiKey)
  // { "models": [{ "modelId": "gemma-2b", "path": "/data/.../gemma-2b.bin", "sizeBytes": 1500000000 }], "error": null }

  val loadedJson     = coreAi.getLoadedModels(apiKey)
  // { "models": ["gemma-2b", "phi-2"], "error": null }

  val isValid: Boolean = coreAi.validateApiKey(apiKey)

---

## Step 6 — Managing Models

Download official catalog models or import your own GGUF / LiteRT files from local storage.

  // Browse the bundled model catalog
  val catalogJson = coreAi.getCatalog(apiKey)
  // { "models": [{ "id": "gemma-3-1b-q4", "name": "...", "download_url": "...", "engine_type": "gguf" }], "error": null }

  // Download from URL
  //
  // SECURITY CONSTRAINTS — requests violating these are rejected immediately:
  //   • downloadUrl MUST start with "https://" (plain HTTP is blocked to prevent SSRF)
  //   • modelId     MUST match regex [a-zA-Z0-9_-]+ (alphanumeric, dashes, underscores only)
  //                 Any other characters trigger an instant rejection to prevent
  //                 path traversal attacks on the engine's internal storage sandbox.
  //
  // DEFAULT MODEL — pass "" or null for modelId to automatically use the
  //   system default model (gemma-3-1b-q4).
  coreAi.downloadCatalogModel(apiKey, "gemma-2b", "https://example.com/models/gemma-2b.bin", myCallback)
  // Callbacks: onModelTransferProgress("gemma-2b", 0..100) → onModelTransferComplete(...)

  // Import from device storage (Storage Access Framework URI)
  // Supported engineType values (case-insensitive):
  //   "gguf"     — GGUF-format models (llama.cpp-compatible, e.g. .gguf files)
  //   "litertlm" — LiteRT / TensorFlow Lite flat-buffer models
  //   "bin"      — Generic binary format
  coreAi.importLocalModel(apiKey, uri, "my-custom-model", "gguf", myCallback)

---

## Step 7 — Engine Lifecycle (RAM Management)

After a model file is on disk, use the lifecycle methods to control what lives in RAM.

  // Pass "" or null for modelId to load/delete the system default model
  // (gemma-3-1b-q4) without specifying an id explicitly.
  coreAi.loadModel(apiKey, "gemma-2b", myCallback)   // load into RAM
  coreAi.setActiveModel(apiKey, "gemma-2b")           // route runInference() here
  coreAi.runInference(apiKey, prompt)                 // produces onInferenceResult
  coreAi.unloadModel(apiKey, "gemma-2b", myCallback)  // free RAM when done

  // Safe deletion — acquires engine lock, unloads from RAM, then deletes file from disk.
  // Prevents TOCTOU file-in-use races. Result fires onModelStateChanged or onError.
  coreAi.deleteModel(apiKey, "gemma-2b", myCallback)

---

## Step 8 — Context Isolation Mode

Two modes control how conversation history is managed across calls:

  FULL_PROMPT  (default) — service is stateless; the client sends the full conversation
               history as the prompt on every runInference() call.
               Safest for multi-app scenarios — no cross-client bleed.

  PER_CLIENT   — the service stores conversation history keyed by your app's UID.
               Send only the latest user message; the service prepends history automatically.
               resetChatContext() clears only your app's session, not others'.

  // Switch mode — takes effect immediately for all subsequent runInference() calls.
  // "FULL_PROMPT" or "PER_CLIENT"
  coreAi.setContextMode(apiKey, "PER_CLIENT")

  // Read the active mode
  val mode: String = coreAi.getContextMode(apiKey)

  // Reset the current session (clears your UID's history in PER_CLIENT mode;
  // in FULL_PROMPT mode this also flushes the native llama.cpp KV cache).
  coreAi.resetChatContext(apiKey, modelId)

---

## Step 9 — Hugging Face Integration

Core AI supports downloading GGUF models directly from Hugging Face, including
gated (access-restricted) models, using the HF Resolve endpoint.

### URL pattern

  https://huggingface.co/[USER]/[REPO]/resolve/[REVISION]/[FILENAME]

  Examples:
    https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf
    https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B.gguf

### Download a public HF model

  // Public models need no token — pass the resolve URL directly.
  //
  // ⚠ SECURITY CONSTRAINTS (same as all downloadCatalogModel calls):
  //   • downloadUrl MUST start with "https://" — plain HTTP is rejected (SSRF prevention)
  //   • modelId     MUST be strictly alphanumeric plus dashes/underscores [a-zA-Z0-9_-]+
  //                 Any other characters trigger an instant path-traversal rejection.
  coreAi.downloadCatalogModel(
      apiKey,
      "gemma-hf",                 // modelId — alphanumeric + dashes/underscores only
      "https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
      myCallback
  )

### Download a gated HF model (client-side OkHttp + GGUF import)

  Gated (access-restricted) HF models cannot be downloaded via downloadCatalogModel —
  Core AI cannot attach your personal HF token to outbound requests. Your client app
  must perform the authenticated download itself using OkHttp with an
  "Authorization: Bearer <YOUR_HF_TOKEN>" header, then import the saved file via
  importLocalModel() using engineType "GGUF".

  // Step 1 — download the .gguf file with OkHttp + Bearer token (read from user input):
  val request = Request.Builder()
      .url("https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B.gguf")
      .addHeader("Authorization", "Bearer ${'$'}{userHfToken}")
      .build()

  okHttpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) { /* handle 401 / 403 — invalid or missing token */ return }
      val file = File(cacheDir, "Llama-3.2-1B.gguf")
      file.outputStream().use { response.body!!.byteStream().copyTo(it) }

      // Step 2 — import the saved file into Core AI via FileProvider URI.
      // "GGUF" is a fully supported engineType alongside "litertlm" and "bin".
      val uri = FileProvider.getUriForFile(context, "${'$'}{packageName}.provider", file)
      coreAi.importLocalModel(
          apiKey,
          uri,
          "llama-3-2-1b",   // targetModelId — alphanumeric + dashes/underscores only
          "GGUF",            // engineType: "GGUF" for any llama.cpp-compatible .gguf file
          myCallback
      )
  }
  // onModelTransferProgress → onModelTransferComplete → call loadModel() to bring into RAM

---

## Step 10 — Custom Chat Templates

Override the automatic prompt template for any model ID. By default Core AI
auto-detects the correct chat template using three tiers in priority order:
  1. Exact catalog ID match (gemma-3-1b-q4, gemma-3-4b-q4, llama-3.2-1b-instruct, phi-3.5-mini-q4)
  2. GGUF general.architecture field read from the file header without loading the model
     (e.g. llama → Llama 3 tokens; gemma/gemma3 → Gemma tokens;
      phi3/qwen2/qwen3/mistral/falcon → ChatML)
  3. Name heuristics — fallback when metadata is unavailable (contains "llama" → Llama 3,
     contains "gemma" → Gemma, anything else → ChatML)
Use setCustomChatTemplate to inject a fully custom template — useful for models not yet in the
router or for fine-tuned variants that use a non-standard format.
All eight fields are optional and default to an empty string when omitted.

  // Template JSON — all fields optional, default to ""
  {
    "bosToken":               "<|begin_of_text|>",
    "systemPromptPrefix":     "<|start_header_id|>system<|end_header_id|>\n\n",
    "systemPromptSuffix":     "<|eot_id|>",
    "userMessagePrefix":      "<|start_header_id|>user<|end_header_id|>\n\n",
    "userMessageSuffix":      "<|eot_id|>",
    "assistantMessagePrefix": "<|start_header_id|>assistant<|end_header_id|>\n\n",
    "assistantMessageSuffix": "<|eot_id|>",
    "stopToken":              "<|eot_id|>"
  }

  // Register — takes effect on the next runInference() call.
  // modelId must match the id used in loadModel() / setActiveModel().
  coreAi.setCustomChatTemplate(apiKey, "my-custom-model", templateJson)

  // Revert to auto-detection — pass null or a blank string.
  coreAi.setCustomChatTemplate(apiKey, "my-custom-model", null)

---

## API Response Format

  { "completion": "...", "latency_ms": 1234, "success": true, "error": null }
""".trimIndent()

@Composable
fun DeveloperDocsScreen() {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Developer Integration Guide",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(FULL_DOC_TEXT))
                    Toast.makeText(context, "Full doc copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy full document",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = "Follow these steps to bind your app to the Core AI service and run LLM inference locally.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        DocStep(number = 1, title = "Copy the AIDL Files") {
            Text(
                text = "Copy the two AIDL interface files into your project, preserving the exact package " +
                    "path com/stridetech/coreai/ under your src/main/aidl/ directory.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Files required:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            CodeSnippetCard(
                title = "AIDL file paths",
                code = """src/main/aidl/com/stridetech/coreai/ICoreAiInterface.aidl
src/main/aidl/com/stridetech/coreai/ICoreAiCallback.aidl"""
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The package declaration inside each file must match exactly: package com.stridetech.coreai;",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DocStep(number = 2, title = "Declare Manifest Requirements") {
            Text(
                text = "Add a <queries> block so Android allows your app to discover the Core AI " +
                    "service. Declare the BIND_LLM_SERVICE permission to bind to the service — " +
                    "it uses normal protection level so any app can declare it. FOREGROUND_SERVICE " +
                    "is optional and only needed if you run inference from a foreground service.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "AndroidManifest.xml",
                code = """<manifest ...>

    <!-- Allow querying the Core AI service package -->
    <queries>
        <package android:name="com.stridetech.coreai" />
    </queries>

    <!-- Required to bind to CoreAiService -->
    <!-- protectionLevel="normal" — any app can declare this permission -->
    <uses-permission
        android:name="com.stridetech.coreai.permission.BIND_LLM_SERVICE" />

    <!-- Optional: only needed for foreground service inference -->
    <uses-permission
        android:name="android.permission.FOREGROUND_SERVICE" />

    <application ...>
        ...
    </application>

</manifest>"""
            )
        }

        DocStep(number = 3, title = "Bind to the Service") {
            Text(
                text = "Create a ServiceConnection, build the explicit Intent, and call bindService in your " +
                    "Activity or ViewModel. Remember to unbind in onDestroy / onCleared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — ServiceConnection & bindService",
                code = """import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.stridetech.coreai.ICoreAiInterface

private const val BIND_ACTION   = "com.stridetech.coreai.BIND_LLM_SERVICE"
private const val SERVICE_PKG   = "com.stridetech.coreai"
private const val SERVICE_CLASS =
    "com.stridetech.coreai.service.CoreAiService"

class MyViewModel(private val app: Application) : AndroidViewModel(app) {

    private var coreAi: ICoreAiInterface? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            coreAi = ICoreAiInterface.Stub.asInterface(binder)
            coreAi?.registerCallback(myCallback)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            coreAi = null
        }
    }

    fun bind() {
        val intent = Intent(BIND_ACTION).apply {
            setClassName(SERVICE_PKG, SERVICE_CLASS)
        }
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        coreAi?.unregisterCallback(myCallback)
        app.unbindService(connection)
    }
}"""
            )
        }

        DocStep(number = 4, title = "Register Callback & Run Inference") {
            Text(
                text = "Implement ICoreAiCallback.Stub to receive model state changes and inference results, " +
                    "then call runInference(apiKey, prompt). Obtain an API key from the Core AI Settings screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — Callback & runInference",
                code = """import com.stridetech.coreai.ICoreAiCallback
import org.json.JSONObject

// 1. Implement the one-way callback
private val myCallback = object : ICoreAiCallback.Stub() {

    override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {
        // Update UI: model ready / not ready
    }

    override fun onError(errorMessage: String?) {
        // Show error to user
    }

    override fun onInferenceResult(resultJson: String?) {
        resultJson ?: return
        val json       = JSONObject(resultJson)
        val success    = json.optBoolean("success", false)
        val completion = json.optString("completion")
        val latencyMs  = json.optLong("latency_ms")
        // Update UI with completion text
    }

    override fun onInferenceToken(token: String?) {
        // Streaming token — append to a buffer for live-streaming UI
    }

    override fun onInferenceComplete(latencyMs: Long) {
        // Fires after the last token; latencyMs is total inference time
    }

    // ── Transfer progress callbacks ────────────────────────────────────

    override fun onModelTransferProgress(modelId: String?, percent: Int) {
        // Update a progress bar: 0–100
    }

    override fun onModelTransferComplete(modelId: String?, filePath: String?) {
        // File is written to disk and ready to load into RAM via loadModel()
    }

    override fun onModelTransferError(modelId: String?, errorMessage: String?) {
        // Show download/import error to user
    }
}

// 2. Run inference (call from a coroutine / background thread)
fun runPrompt(apiKey: String, prompt: String) {
    val service = coreAi ?: return
    if (!service.isReady()) return

    viewModelScope.launch(Dispatchers.IO) {
        val resultJson = service.runInference(apiKey, prompt)
        // Parse resultJson or wait for onInferenceResult callback
    }
}"""
            )
        }

        DocStep(number = 5, title = "Querying Engine State") {
            Text(
                text = "Use the JSON state query methods to inspect what is active and what is available " +
                    "on the device. All three methods return a JSON string synchronously.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — State queries",
                code = """// Active model — returns immediately
val activeJson = coreAi.getActiveModel(apiKey)
// { "modelId": "gemma-2b", "isReady": true }
// { "modelId": null,       "isReady": false }  ← nothing loaded

// Models stored on disk
val downloadedJson = coreAi.getDownloadedModels(apiKey)
// {
//   "models": [
//     { "modelId": "gemma-2b", "path": "/data/.../gemma-2b.bin", "sizeBytes": 1500000000 }
//   ],
//   "error": null
// }

// Models currently held in RAM (loaded but not necessarily active)
val loadedJson = coreAi.getLoadedModels(apiKey)
// { "models": ["gemma-2b", "phi-2"], "error": null }"""
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — Validate API key",
                code = """// Returns true if the key is recognised by the engine, false otherwise.
// Call this on startup or before the first inference to give the user
// early feedback rather than a silent failure later.
val isValid: Boolean = coreAi.validateApiKey(apiKey)
if (!isValid) {
    // Prompt user to check / re-enter their key in Core AI Settings
}"""
            )
        }

        DocStep(number = 6, title = "Managing Models") {
            Text(
                text = "Download official catalog models or import your own GGUF / LiteRT files from " +
                    "local storage. Both operations report progress via ICoreAiCallback.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — Browse the bundled catalog",
                code = """// Returns the list of models bundled in the Core AI catalog.
// Use the returned download_url with downloadCatalogModel() to fetch a specific model.
val catalogJson = coreAi.getCatalog(apiKey)
// {
//   "models": [
//     { "id": "gemma-3-1b-q4", "name": "Gemma 3 1B Instruct (Q4_K_M)",
//       "download_url": "https://huggingface.co/.../gemma-3-1b-it-Q4_K_M.gguf",
//       "file_size": 806058496, "engine_type": "gguf" }
//   ],
//   "error": null
// }"""
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — Download a catalog model",
                code = """// Triggers a background download; progress arrives on the callback.
// Completes immediately (cache hit) if the file already exists on disk.
// downloadUrl is required — the service does not maintain a built-in URL registry.
//
// ⚠ SECURITY CONSTRAINTS — requests violating these are rejected immediately:
//   • downloadUrl MUST start with "https://" (plain HTTP is blocked to prevent SSRF)
//   • modelId     MUST match regex [a-zA-Z0-9_-]+ (alphanumeric, dashes, underscores only)
//                 Any other characters trigger an instant rejection to prevent
//                 path traversal attacks on the engine's internal storage sandbox.
//
// ℹ DEFAULT MODEL — pass "" or null for modelId to automatically use the
//   system default model (gemma-3-1b-q4).
coreAi.downloadCatalogModel(
    apiKey,
    "gemma-2b",                                // modelId — used in all subsequent calls
    "https://example.com/models/gemma-2b.bin", // downloadUrl — HTTPS only
    myCallback                                 // receives progress + completion events
)

// Callback sequence:
//   onModelTransferProgress("gemma-2b", 0..100)
//   onModelTransferComplete("gemma-2b", "/data/.../gemma-2b.bin")
//     → file is on disk; call loadModel() to bring it into RAM"""
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — Import a custom local model",
                code = """// 1. Obtain a content Uri via the system file picker or FileProvider
val pickModelLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    uri ?: return@registerForActivityResult
    importModel(uri)
}

// Launch the picker scoped to common model file types
pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))

// 2. Call importLocalModel — the service streams the file into its sandbox
//    Supported engineType values (case-insensitive):
//      "gguf"     — GGUF-format models (llama.cpp-compatible, e.g. .gguf files)
//      "litertlm" — LiteRT / TensorFlow Lite flat-buffer models
//      "bin"      — Generic binary format
fun importModel(uri: Uri) {
    coreAi.importLocalModel(
        apiKey,
        uri,
        "my-custom-model", // targetModelId used in subsequent calls
        "gguf",            // engineType: "gguf", "litertlm", or "bin" (case-insensitive)
        myCallback
    )
    // onModelTransferProgress → onModelTransferComplete → loadModel(...)
}"""
            )
        }

        DocStep(number = 7, title = "Engine Lifecycle (RAM Management)") {
            Text(
                text = "After a model file is on disk, use the lifecycle methods to control what lives in RAM " +
                    "and which model handles inference requests.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — loadModel, setActiveModel, unloadModel",
                code = """// 1. Load a model into RAM (required before inference)
//    Result delivered via onModelStateChanged or onError on the callback.
//    Pass "" or null for modelId to load the system default model
//    (gemma-3-1b-q4) without specifying an id explicitly.
coreAi.loadModel(apiKey, "gemma-2b", myCallback)

// 2. Promote a loaded model to the active inference slot
//    Use this when multiple models are in RAM and you want to switch
//    which one handles runInference() calls — no reload needed.
coreAi.setActiveModel(apiKey, "gemma-2b")

// 3. Unload a model to free RAM
//    Result delivered via onModelStateChanged or onError on the callback.
//    The model file remains on disk; call loadModel() again to restore it.
coreAi.unloadModel(apiKey, "gemma-2b", myCallback)

// Typical flow:
//   downloadCatalogModel / importLocalModel   ← file lands on disk
//     → onModelTransferComplete
//   loadModel                                 ← file enters RAM
//     → onModelStateChanged(isReady = true)
//   setActiveModel                            ← routes runInference() here
//   runInference                              ← produces onInferenceResult
//   unloadModel                               ← frees RAM when done"""
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — deleteModel (safe deletion)",
                code = """// Permanently removes a model file from disk.
// The service acquires the engine lock before deletion, ensuring the model
// is fully unloaded from RAM first — preventing file-in-use races (TOCTOU).
// Result is delivered via onModelStateChanged or onError on the callback.
coreAi.deleteModel(apiKey, "gemma-2b", myCallback)

// Safe deletion order enforced internally:
//   1. Acquire engine lock
//   2. Unload model from RAM (if loaded)
//   3. Delete file from disk
//   4. Release engine lock → fires onModelStateChanged"""
            )
        }

        DocStep(number = 8, title = "Context Isolation Mode") {
            Text(
                text = "Choose how conversation history is managed. FULL_PROMPT (default) is stateless — " +
                    "the client sends full history every call. PER_CLIENT lets the service track " +
                    "history per app UID so you only send the latest user message.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — Set and read context mode",
                code = """// Switch to PER_CLIENT — service tracks history per caller UID.
// Send only the latest user message; history is prepended automatically.
coreAi.setContextMode(apiKey, "PER_CLIENT")

// Switch back to FULL_PROMPT — service is stateless.
// Client is responsible for sending full conversation history each call.
coreAi.setContextMode(apiKey, "FULL_PROMPT")

// Read the active mode ("FULL_PROMPT" or "PER_CLIENT")
val mode: String = coreAi.getContextMode(apiKey)"""
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — Reset session context",
                code = """// Clears this app's conversation history.
//   PER_CLIENT mode: removes only your UID's session — other apps are unaffected.
//   FULL_PROMPT mode: flushes the native llama.cpp KV cache for a clean slate.
// Call this after the user clears the chat to keep the service in sync.
coreAi.resetChatContext(apiKey, modelId)"""
            )
        }

        DocStep(number = 9, title = "Hugging Face Integration") {
            Text(
                text = "Download GGUF models directly from Hugging Face — including gated " +
                    "(access-restricted) repositories — using the HF Resolve endpoint.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "HF Resolve URL pattern",
                code = """https://huggingface.co/[USER]/[REPO]/resolve/[REVISION]/[FILENAME]

// Examples:
https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf
https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B.gguf"""
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — Download a public HF model",
                code = """// Public models need no token — pass the resolve URL directly.
//
// ⚠ SECURITY CONSTRAINTS (same as all downloadCatalogModel calls):
//   • downloadUrl MUST start with "https://" — plain HTTP is rejected (SSRF prevention)
//   • modelId     MUST be strictly alphanumeric plus dashes/underscores [a-zA-Z0-9_-]+
//                 Any other characters trigger an instant path-traversal rejection.
coreAi.downloadCatalogModel(
    apiKey,
    "gemma-hf",                 // modelId — alphanumeric + dashes/underscores only
    "https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
    myCallback
)"""
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Gated (access-restricted) HF models cannot be downloaded via downloadCatalogModel — " +
                    "Core AI cannot attach your personal HF token to outbound requests. Your client app " +
                    "must perform the authenticated download itself using OkHttp with an " +
                    "\"Authorization: Bearer <YOUR_HF_TOKEN>\" header, then import the saved file via " +
                    "importLocalModel() using engineType \"GGUF\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "Kotlin — Download a gated HF model (client-side OkHttp + GGUF import)",
                code = """// Gated models: your app must perform the authenticated download itself.
// Core AI cannot attach HF tokens to outbound requests on your behalf.
//
// Step 1 — download the .gguf file with OkHttp + Bearer token (read from user input):
val request = Request.Builder()
    .url("https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B.gguf")
    .addHeader("Authorization", "Bearer ${'$'}{userHfToken}")
    .build()

okHttpClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) { /* handle 401 / 403 — invalid or missing token */ return }
    val file = File(cacheDir, "Llama-3.2-1B.gguf")
    file.outputStream().use { response.body!!.byteStream().copyTo(it) }

    // Step 2 — import the saved file into Core AI via FileProvider URI.
    // "GGUF" is a fully supported engineType alongside "litertlm" and "bin".
    val uri = FileProvider.getUriForFile(context, "${'$'}{packageName}.provider", file)
    coreAi.importLocalModel(
        apiKey,
        uri,
        "llama-3-2-1b",   // targetModelId — alphanumeric + dashes/underscores only
        "GGUF",            // engineType: "GGUF" for any llama.cpp-compatible .gguf file
        myCallback
    )
}
// onModelTransferProgress → onModelTransferComplete → call loadModel() to bring into RAM"""
            )
        }

        DocStep(number = 10, title = "Custom Chat Templates") {
            Text(
                text = "Override the automatic prompt template for any model ID. Core AI resolves " +
                    "templates using three tiers: (1) exact catalog ID match (gemma-3-1b-q4, " +
                    "gemma-3-4b-q4, llama-3.2-1b-instruct, phi-3.5-mini-q4); (2) GGUF " +
                    "general.architecture field read from the file header without loading the model " +
                    "(llama, gemma3, phi3, qwen2, mistral, falcon → appropriate template); " +
                    "(3) name heuristics as a final fallback (contains \"llama\" → Llama 3, " +
                    "contains \"gemma\" → Gemma, anything else → ChatML). " +
                    "Use setCustomChatTemplate to inject a fully custom template — useful for models " +
                    "not yet in the router or for fine-tuned variants that use a non-standard format. " +
                    "All eight fields are optional and default to an empty string when omitted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "JSON — ChatTemplate structure (all fields optional)",
                code = """
{
  "bosToken":               "<|begin_of_text|>",
  "systemPromptPrefix":     "<|start_header_id|>system<|end_header_id|>\n\n",
  "systemPromptSuffix":     "<|eot_id|>",
  "userMessagePrefix":      "<|start_header_id|>user<|end_header_id|>\n\n",
  "userMessageSuffix":      "<|eot_id|>",
  "assistantMessagePrefix": "<|start_header_id|>assistant<|end_header_id|>\n\n",
  "assistantMessageSuffix": "<|eot_id|>",
  "stopToken":              "<|eot_id|>"
}""".trimIndent()
            )
            Spacer(Modifier.height(12.dp))
            CodeSnippetCard(
                title = "Kotlin — Register and clear a custom template",
                code = "// Build the JSON string for your template (all fields optional).\n" +
                    "val templateJson = \"\"\"\n" +
                    "  {\n" +
                    "    \"bosToken\": \"<|begin_of_text|>\",\n" +
                    "    \"userMessagePrefix\": \"<|start_header_id|>user<|end_header_id|>\\n\\n\",\n" +
                    "    \"userMessageSuffix\": \"<|eot_id|>\",\n" +
                    "    \"assistantMessagePrefix\": \"<|start_header_id|>assistant<|end_header_id|>\\n\\n\",\n" +
                    "    \"assistantMessageSuffix\": \"<|eot_id|>\",\n" +
                    "    \"stopToken\": \"<|eot_id|>\"\n" +
                    "  }\n" +
                    "\"\"\".trimIndent()\n" +
                    "\n" +
                    "// Register — takes effect on the next runInference() call.\n" +
                    "// modelId must match the id used in loadModel() / setActiveModel().\n" +
                    "coreAi.setCustomChatTemplate(apiKey, \"my-custom-model\", templateJson)\n" +
                    "\n" +
                    "// Revert to auto-detection — pass null or a blank string.\n" +
                    "coreAi.setCustomChatTemplate(apiKey, \"my-custom-model\", null)"
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "API Response Format",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = """{ "completion": "...", "latency_ms": 1234, "success": true, "error": null }""",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DocStep(
    number: Int,
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        content()
    }
}

@Composable
fun CodeSnippetCard(title: String, code: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = IDE_BG, shape = RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = IDE_BORDER, shape = RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = IDE_HEADER_BG,
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                )
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = IDE_TITLE_TEXT
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = IDE_COPY_ICON,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 20.sp
                ),
                color = IDE_CODE_TEXT
            )
        }
    }
}
