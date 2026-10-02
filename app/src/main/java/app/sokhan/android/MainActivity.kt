package app.sokhan.android

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Background = Color(0xFF0A1A1F)
private val Surface = Color(0xFF10262B)
private val Pink = Color(0xFF4ADE80) // primary accent: green (v1 legacy palette)
private val Teal = Color(0xFF5EEAD4)
private val TextMain = Color(0xFFE6F4F1)
private val TextDim = Color(0xFF8FB3AE)
private val OnPink = Color(0xFF06251F) // text/icon on the green accent
private val OnTeal = Color(0xFF06251F) // text/thumb on Teal

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

            PermissionCard()

            ImeCard()
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
private fun PermissionCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(SessionManager.hasMicrophonePermission(context)) }
    val needed = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = SessionManager.hasMicrophonePermission(context) }

    // Ask on first open; RECORD_AUDIO is a runtime permission, so without it the
    // button appears to do nothing at all.
    LaunchedEffect(Unit) { if (!granted) launcher.launch(needed) }

    SokhanCard("اجازه‌ها") {
        Text(
            if (granted) "اجازه میکروفون داده شده است"
            else "برای ضبط، اجازه میکروفون لازم است",
            color = if (granted) Teal else Pink, fontSize = 14.sp
        )
        if (!granted) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { launcher.launch(needed) },
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = OnPink)
            ) { Text("دادن اجازه میکروفون") }
        }
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
private fun SokhanCard(title: String, content: @Composable () -> Unit) {
    SokhanCard {
        Text(title, color = Pink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ImeCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(isImeEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = isImeEnabled(context) }
    SokhanCard("صفحه‌کلید سخن (تایپ زنده)") {
        Text(
            if (enabled) "صفحه‌کلید افزوده شده — آن را انتخاب کنید تا متن همان لحظه تایپ شود"
            else "برای تایپ لحظه‌ای مثل Google Keyboard، «سخن» را از فهرست صفحه‌کلیدها فعال کنید",
            color = if (enabled) Teal else TextMain, fontSize = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = OnPink)
        ) { Text("تنظیمات صفحه‌کلید") }
    }
}

private fun isImeEnabled(context: Context): Boolean {
    // Reading Settings.Secure "enabled_input_methods" throws SecurityException on
    // targetSdk 34+; the proper API is the input-methods manager.
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.enabledInputMethodList.any {
            it.packageName == context.packageName
        }
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun LanguageCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var current by remember { mutableStateOf(SessionManager.language) }
    var halfspace by remember { mutableStateOf(SessionManager.faHalfspace) }
    val options = listOf(
        "auto" to "خودکار",
        "fa-IR" to "فارسی",
        "en-US" to "English",
        "ar-SA" to "العربية",
        "tr-TR" to "Türkçe",
    )
    SokhanCard("زبان تشخیص گفتار") {
        Spacer(Modifier.height(4.dp))
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
                        selectedLabelColor = OnTeal,
                        labelColor = TextDim,
                    )
                )
            }
        }
        // Half-space (ZWNJ) post-processing only affects Persian output.
        if (current.startsWith("fa")) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("نیم‌فاصله فارسی (می‌شود)", color = TextMain, fontSize = 14.sp)
                Switch(
                    checked = halfspace,
                    onCheckedChange = {
                        halfspace = it
                        SessionManager.setFaHalfspace(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Teal,
                        checkedThumbColor = OnTeal
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
    SokhanCard("وضعیت") {
        Text(
            when (state) {
                SessionManager.State.Idle -> "آماده — دکمه «گوش بده» را بزنید"
                SessionManager.State.Recording -> "در حال ضبط… برای پایان دوباره بزنید"
                SessionManager.State.Transcribing -> "در حال تبدیل گفتار به نوشتار…"
            },
            color = if (state == SessionManager.State.Idle) TextMain else Teal,
            fontSize = 14.sp
        )
        last?.let { text ->
            Spacer(Modifier.height(10.dp))
            Text("آخرین نتیجه", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            SelectionContainerText(text)
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
    SokhanCard("راهنما") {
        Text(
            "۱. «صفحه‌کلید سخن» را از فهرست صفحه‌کلیدها فعال و انتخاب کنید — " +
                "برای تایپ صوتی، هیچ دسترسی خاصی لازم نیست.\n" +
                "۲. هرجا فیلد متنی باز شد، دکمه «گوش بده» را بزنید و صحبت کنید؛ " +
                "متن همان لحظه در فیلد تایپ می‌شود.\n" +
                "۳. دوباره بزنید تا ضبط پایان یابد.\n" +
                "۴. اگه کیبورد را عوض کردید، آخرین متن روی کلیپ‌بورد باقی می‌ماند.",
            color = TextMain, fontSize = 14.sp
        )
    }
}
