package top.xingzhenxi.phonebox.ui.wizard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import top.xingzhenxi.phonebox.PhoneboxApp
import top.xingzhenxi.phonebox.config.SipTransport
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_HOST
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_PASS
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_PB_FP
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_PORT
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_SIP_FP
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_URL
import top.xingzhenxi.phonebox.ui.wizard.WizardViewModel.Companion.F_USER

@Composable
fun WizardScreen(onFinished: () -> Unit, onCancel: (() -> Unit)?) {
    val app = LocalContext.current.applicationContext as PhoneboxApp
    val vm: WizardViewModel = viewModel(factory = viewModelFactory { initializer { WizardViewModel(app.configRepository) } })
    BackHandler(enabled = vm.step.ordinal > 0) { vm.back() }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { (vm.step.ordinal + 1f) / WizardStep.entries.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        )
        // 内屏上限制表单宽度，避免输入框拉得过长
        Column(
            Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedContent(vm.step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wizardStep") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (s) {
                        WizardStep.Welcome -> WelcomeStep()
                        WizardStep.Sip -> SipStep(vm)
                        WizardStep.Phonebox -> PhoneboxStep(vm)
                        WizardStep.Permissions -> PermissionsStep()
                        WizardStep.Done -> DoneStep()
                    }
                }
            }
        }
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                vm.step.ordinal > 0 -> TextButton(onClick = vm::back) { Text("上一步") }
                onCancel != null -> TextButton(onClick = onCancel) { Text("取消") }
            }
            Spacer(Modifier.weight(1f))
            if (vm.step == WizardStep.Done) {
                Button(onClick = { vm.finish(onFinished) }, enabled = !vm.saving) { Text("开始使用") }
            } else {
                Button(onClick = vm::next) { Text(if (vm.step == WizardStep.Welcome) "开始设置" else "下一步") }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 24.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep() {
    StepTitle("电话信箱", "用家里的电话卡打电话、收短信。\n接下来填写 SIP 账号和 phonebox 地址，大约一分钟。")
    InfoCard("所有口令只加密保存在本机，不会上传到任何第三方。")
}

@Composable
private fun InfoCard(text: String) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun Field(
    vm: WizardViewModel,
    key: String,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val err = vm.errors[key]
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it); vm.clearError(key) },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        isError = err != null,
        supportingText = (err ?: hint)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SipStep(vm: WizardViewModel) {
    StepTitle("SIP 账号", "用于注册到家里的电话服务器。")
    Field(vm, F_HOST, "服务器地址", vm.host, { vm.host = it }, placeholder = "sip.example.com", keyboard = KeyboardType.Uri)
    Field(vm, F_PORT, "端口", vm.port, { vm.port = it.filter(Char::isDigit) }, placeholder = "5061", keyboard = KeyboardType.Number)
    Text("传输方式", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SipTransport.entries.forEachIndexed { i, t ->
            SegmentedButton(
                selected = vm.transport == t,
                onClick = { vm.transport = t },
                shape = SegmentedButtonDefaults.itemShape(i, SipTransport.entries.size),
            ) { Text(t.name) }
        }
    }
    Field(vm, F_USER, "分机号", vm.username, { vm.username = it }, placeholder = "例如 1001", keyboard = KeyboardType.Number)
    Field(
        vm, F_PASS, "口令", vm.password, { vm.password = it }, secret = true, keyboard = KeyboardType.Password,
        hint = if (vm.hasSavedPassword) "留空则保持原口令" else null,
    )
    if (vm.transport == SipTransport.TLS) {
        Field(vm, F_SIP_FP, "证书 SHA-256 指纹（可选）", vm.sipFingerprint, { vm.sipFingerprint = it }, placeholder = "AB:CD:…", hint = "填写后只信任这张证书")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("SRTP 加密语音", style = MaterialTheme.typography.titleMedium)
            Text("服务器支持时建议打开", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = vm.srtp, onCheckedChange = { vm.srtp = it })
    }
}

@Composable
private fun PhoneboxStep(vm: WizardViewModel) {
    StepTitle("phonebox", "短信、通话记录和号码标签都来自这里。只支持 https。")
    Field(vm, F_URL, "地址", vm.baseUrl, { vm.baseUrl = it }, placeholder = "https://phonebox.example.com", keyboard = KeyboardType.Uri)
    Field(vm, F_PB_FP, "证书 SHA-256 指纹（可选）", vm.pbFingerprint, { vm.pbFingerprint = it }, placeholder = "AB:CD:…", hint = "自签证书时填写")
    // PIN 仅保存在内存；点“下一步”时登录，失败原因显示在这一栏
    Field(vm, WizardViewModel.F_PIN, "PIN", vm.pin, { vm.pin = it }, secret = true, keyboard = KeyboardType.NumberPassword, hint = if (vm.loggingIn) "正在登录…" else "用于登录换取令牌，PIN 本身不保存；留空可稍后在设置里登录")
}

@Composable
private fun PermissionsStep() {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    // 结果回来后递增以触发重组，重新读取授权状态
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    StepTitle("权限", "以下权限可以稍后在系统设置里再开，拒绝也能继续。")
    refresh.let { _ ->
        InfoCard("通知：来电提醒、短信和验证码一键复制。" + if (granted(Manifest.permission.POST_NOTIFICATIONS)) "（已允许）" else "")
        InfoCard("麦克风：通话时传输你的声音。" + if (granted(Manifest.permission.RECORD_AUDIO)) "（已允许）" else "")
    }
    Button(onClick = { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)) }) {
        Text("授予权限")
    }
}

@Composable
private fun DoneStep() {
    StepTitle("设置完成", "随时可以在「设置」里修改这些信息。")
    InfoCard("点“开始使用”后会连接服务器并开始同步短信和通话记录。")
}
