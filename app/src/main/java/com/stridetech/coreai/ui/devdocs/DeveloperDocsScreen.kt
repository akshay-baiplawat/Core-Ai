package com.stridetech.coreai.ui.devdocs

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
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

private val CODE_BG = Color(0xFF1E1E2E)
private val CODE_HEADER_BG = Color(0xFF2A2A3E)
private val CODE_TEXT = Color(0xFFCDD6F4)
private val CODE_TITLE_TEXT = Color(0xFF89B4FA)

@Composable
fun DeveloperDocsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Developer Integration Guide",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Follow these steps to bind your app to the Core AI service and run LLM inference locally.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        DocStep(number = 1, title = "Copy the AIDL Files") {
            Text(
                text = "Copy the two AIDL interface files into your project, preserving the exact package " +
                    "path com/stridetech/coreai/ under your src/main/aidl/ directory.",
                style = MaterialTheme.typography.bodyMedium
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
                text = "Add a <queries> block so Android allows your app to discover the Core AI service, " +
                    "and declare the FOREGROUND_SERVICE permission required for IPC binding.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            CodeSnippetCard(
                title = "AndroidManifest.xml",
                code = """<manifest ...>

    <!-- Allow querying the Core AI service package -->
    <queries>
        <package android:name="com.stridetech.coreai" />
    </queries>

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
                style = MaterialTheme.typography.bodyMedium
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
                style = MaterialTheme.typography.bodyMedium
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

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "API Response Format",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(4.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = number.toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Text(text = title, style = MaterialTheme.typography.titleMedium)
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
            .background(color = CODE_BG, shape = RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = CODE_HEADER_BG,
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = CODE_TITLE_TEXT
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = CODE_TITLE_TEXT
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                color = CODE_TEXT
            )
        }
    }
}
