package app.sokhan.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Background = Color(0xFF0A1A1F)
private val Surface = Color(0xFF10262B)
private val Pink = Color(0xFFF28CBB)
private val Teal = Color(0xFF5EEAD4)
private val TextMain = Color(0xFFE6F4F1)
private val TextDim = Color(0xFF8FB3AE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.loadPrefs(this)
        setContent { SokhanScreen() }
    }

    override fun onResume() {
        super.onResume()
        // A foreground FGS start that was blocked in the background is retried here.
        SessionManager.retryStartFromForeground(this)
    }
}

@Composable
fun SokhanScreen() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(84.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("سخن", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "تبدیل گفتار به نوشتار — همه‌جا، با یک لمس",
                color = TextDim, fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))

            RequestPermissions()

            AccessibilityCard()
            Spacer(Modifier.height(14.dp))
            LanguageCard()
            Spacer(Modifier.height(14.dp))
            StatusCard()
            Spacer(Modifier.height(14.dp))
            GuideCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RequestPermissions() {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(wanted.toTypedArray())
    }
}

@Composable
private fun SokhanCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun AccessibilityCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Refresh the service status when returning from the settings screen.
        while (true) {
            enabled.value = SokhanAccessibilityService.instance != null
            kotlinx.coroutines.delay(1000)
        }
    }
    SokhanCard {
        Text("دسترسی‌پذیری", color = Pink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (enabled.value) "سرویس فعال است — دکمه لب روی صفحه است"
            else "برای نمایش دکمه لب، «سخن» را فعال کنید",
            color = if (enabled.value) Teal else TextMain, fontSize = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color(0xFF1A0E14))
        ) { Text("تنظیمات دسترسی‌پذیری") }
    }
}

@Composable
private fun LanguageCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var current by remember { mutableStateOf(SessionManager.language) }
    val options = listOf(
        "fa-IR" to "فارسی",
        "en-US" to "English",
        "ar-SA" to "العربية",
        "tr-TR" to "Türkçe",
    )
    SokhanCard {
        Text("زبان تشخیص گفتار", color = Pink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (code, label) ->
                FilterChip(
                    selected = current == code,
                    onClick = {
                        current = code
                        SessionManager.setLanguage(context, code)
                    },
                    label = { Text(label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Teal,
                        selectedLabelColor = Color(0xFF06251F),
                        labelColor = TextDim,
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusCard() {
    val state by SessionManager.state.collectAsStateWithLifecycle()
    val last by SessionManager.lastResult.collectAsStateWithLifecycle()
    SokhanCard {
        Text("وضعیت", color = Pink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            when (state) {
                SessionManager.State.Idle -> "آماده — روی دکمه لب بزنید"
                SessionManager.State.Recording -> "در حال ضبط… برای پایان دوباره بزنید"
                SessionManager.State.Transcribing -> "در حال تبدیل گفتار به نوشتار…"
            },
            color = if (state == SessionManager.State.Idle) TextMain else Teal,
            fontSize = 14.sp
        )
        if (last != null) {
            Spacer(Modifier.height(10.dp))
            Text("آخرین نتیجه", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            SelectionContainerText(last.orEmpty())
        }
    }
}

@Composable
private fun SelectionContainerText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, color = TextMain, fontSize = 14.sp)
    }
}

@Composable
private fun GuideCard() {
    SokhanCard {
        Text("راهنما", color = Pink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "۱. سرویس دسترسی‌پذیری را فعال کنید.\n" +
                "۲. دکمه لب روی صفحه ظاهر می‌شود؛ با کشیدن جابه‌جا کنید.\n" +
                "۳. هرجا می‌خواهید تایپ کنید، روی دکمه بزنید و صحبت کنید.\n" +
                "۴. دوباره بزنید تا ضبط پایان یابد؛ متن خودکار در محل تایپ درج می‌شود.",
            color = TextMain, fontSize = 14.sp
        )
    }
}
