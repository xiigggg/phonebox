package top.xingzhenxi.phonebox.ui.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.data.CallCategory
import top.xingzhenxi.phonebox.data.CallFilter
import top.xingzhenxi.phonebox.data.CallType
import top.xingzhenxi.phonebox.data.Records
import top.xingzhenxi.phonebox.net.AdminResult
import top.xingzhenxi.phonebox.net.BlockList
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.roles.ComposeSmsActivity
import top.xingzhenxi.phonebox.roles.SipDialer
import top.xingzhenxi.phonebox.sip.DialPolicy
import top.xingzhenxi.phonebox.sip.RegState
import top.xingzhenxi.phonebox.sip.TelephonyEmergencyChecker
import top.xingzhenxi.phonebox.telecom.CallCoordinator
import top.xingzhenxi.phonebox.ui.main.AppIcons
import top.xingzhenxi.phonebox.ui.theme.RiskTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** “通话”页：T9 拨号盘 + 实时匹配 + 通话记录。外屏记录为主、拨号盘按需展开，内屏左拨号右记录 */
@Composable
fun DialerScreen() {
    val ctx = LocalContext.current
    val graph = ctx.appGraph
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val reg by graph.sipEngine.registration.collectAsStateWithLifecycle()
    val pb by graph.phonebox.collectAsStateWithLifecycle()
    val snapshot by remember(pb) { pb?.feedSync?.state ?: flowOf(null) }.collectAsState(initial = null)
    val calls = remember(snapshot) { snapshot?.feed?.calls.orEmpty().sortedByDescending { it.ts } }

    var contactsGranted by remember { mutableStateOf(granted(ctx, Manifest.permission.READ_CONTACTS)) }
    var contacts by remember { mutableStateOf<List<T9Entry>>(emptyList()) }
    LaunchedEffect(contactsGranted) {
        if (contactsGranted) contacts = withContext(Dispatchers.IO) { runCatching { ContactsSource.load(ctx) }.getOrDefault(emptyList()) }
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }

    var pendingDial by remember { mutableStateOf<String?>(null) }
    fun placeCall(raw: String) {
        val n = DialPolicy.sanitize(raw)
        if (n.isEmpty()) return
        if (TelephonyEmergencyChecker(ctx).isEmergency(n)) {
            // 紧急号码绝不走 SIP：交系统蜂窝网络。不发不带包名的 DIAL——本应用是默认拨号器时它会绕回自己
            Toast.makeText(ctx, "紧急号码将使用本机网络", Toast.LENGTH_LONG).show()
            SipDialer.placeEmergency(ctx, n)
            return
        }
        scope.launch {
            val id = graph.callCoordinator.dial(n)
            if (id == null) Toast.makeText(ctx, "无法拨出：SIP 未注册", Toast.LENGTH_SHORT).show()
            else ctx.startActivity(CallCoordinator.inCallIntent(ctx, id))
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val n = pendingDial
        pendingDial = null
        if (ok && n != null) placeCall(n) else if (!ok) Toast.makeText(ctx, "需要麦克风权限才能通话", Toast.LENGTH_SHORT).show()
    }
    val dial: (String) -> Unit = { raw ->
        val emergency = TelephonyEmergencyChecker(ctx).isEmergency(DialPolicy.sanitize(raw))
        if (!emergency && !granted(ctx, Manifest.permission.RECORD_AUDIO)) {
            pendingDial = raw
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else placeCall(raw)
    }

    val recentEntries = remember(calls) {
        calls.distinctBy { it.number }.map { T9Entry("r${it.number}", it.tag, it.number, "", "") }
    }
    val matches = remember(input, contacts, recentEntries) {
        T9.match(input, contacts + recentEntries).distinctBy { it.entry.number }.take(30)
    }

    val names = remember(contacts) {
        contacts.filter { it.name.isNotEmpty() }.associate { CallFilter.normalize(it.number) to it.name }
    }
    var category by rememberSaveable { mutableStateOf(CallCategory.All) }
    val showBlockedInAll by graph.callLogPrefs.showBlockedInAll.collectAsStateWithLifecycle()
    val missedSeen by graph.callLogPrefs.missedSeenTs.collectAsStateWithLifecycle()
    // 查看"未接"即清零：记下当前最新一条未接的时间
    LaunchedEffect(category, calls) {
        if (category == CallCategory.Missed) graph.callLogPrefs.markMissedSeen(CallFilter.latestMissedTs(calls))
    }
    var listsSupported by remember { mutableStateOf(false) }
    LaunchedEffect(pb) { listsSupported = pb?.client?.lists() is AdminResult.Ok }
    val actions = CallActions(
        dial = dial,
        listsSupported = listsSupported,
        addToList = { which, n ->
            scope.launch {
                val r = pb?.client?.editList(which, n, add = true)
                val what = if (which == BlockList.Black) "黑名单" else "白名单"
                Toast.makeText(ctx, if (r is AdminResult.Ok) "已加入$what" else "加入${what}失败", Toast.LENGTH_SHORT).show()
            }
        },
        sms = { n -> ctx.startActivity(Intent(ctx, ComposeSmsActivity::class.java).setData(Uri.parse("smsto:$n"))) },
    )
    val callLog: @Composable () -> Unit = {
        CallLog(calls, names, category, { category = it }, showBlockedInAll, CallFilter.unreadMissed(calls, missedSeen), actions)
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 600.dp
        val permissionHint: @Composable () -> Unit = {
            if (!contactsGranted) {
                TextButton(onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }) { Text("允许读取联系人，用于 T9 搜索") }
            }
        }
        if (wide) {
            // 内屏：左拨号盘右记录，拨号盘常驻
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().widthIn(max = 480.dp).padding(horizontal = 16.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (input.isNotEmpty()) MatchList(matches) { dial(it) }
                        else Text("通话", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp, 8.dp))
                    }
                    permissionHint()
                    DialPad(input, reg, onInput = { input = it }, onDial = { if (input.isNotEmpty()) dial(input) })
                }
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) { callLog() }
            }
        } else {
            NarrowDialer(
                input = input,
                onInput = { input = it },
                matches = matches,
                regState = reg,
                dial = dial,
                permissionHint = permissionHint,
                callLog = callLog,
            )
        }
    }
}

/**
 * 外屏：默认只显示记录，拨号盘由右下角按钮从底部展开（国产拨号器的习惯），
 * 否则常驻拨号盘占掉大半屏，记录只剩三四条。
 */
@Composable
private fun NarrowDialer(
    input: String,
    onInput: (String) -> Unit,
    matches: List<T9Match>,
    regState: RegState,
    dial: (String) -> Unit,
    permissionHint: @Composable () -> Unit,
    callLog: @Composable () -> Unit,
) {
    // 已输入号码时打开页面也展开拨号盘，免得号码被藏起来
    var open by rememberSaveable { mutableStateOf(input.isNotEmpty()) }
    // 用户开始拖动列表就收起；号码框有内容时不收，避免误触丢掉正在输的号码
    val collapseOnScroll = remember(input) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && input.isEmpty()) open = false
                return Offset.Zero
            }
        }
    }
    val density = LocalDensity.current
    var dragged by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(collapseOnScroll)) {
                if (open && input.isNotEmpty()) MatchList(matches) { dial(it) } else callLog()
            }
            AnimatedVisibility(visible = open, enter = expandVertically(expandFrom = Alignment.Top), exit = shrinkVertically(shrinkTowards = Alignment.Top)) {
                Column(
                    Modifier.fillMaxWidth().draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragged += it },
                        onDragStarted = { dragged = 0f },
                        // 下滑超过 48dp 收起
                        onDragStopped = { if (dragged > with(density) { 48.dp.toPx() }) open = false },
                    ),
                ) {
                    permissionHint()
                    DialPad(input, regState, onInput = onInput, onDial = { if (input.isNotEmpty()) dial(input) }, onCollapse = { open = false })
                }
            }
        }
        if (!open) {
            FloatingActionButton(
                onClick = { open = true },
                containerColor = Color(0xFF3E8E5A),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).semantics { contentDescription = "展开拨号盘" },
            ) { Icon(AppIcons.Dialpad, contentDescription = null) }
        }
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun MatchList(matches: List<T9Match>, onDial: (String) -> Unit) {
    if (matches.isEmpty()) {
        Text("无匹配的联系人或通话记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp, 16.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(matches, key = { it.entry.key }) { m ->
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onDial(m.entry.number) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(m.entry.name.ifEmpty { m.entry.number }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.entry.name.isNotEmpty()) Text(m.entry.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 记录区：分类筛选条 + 列表。筛选状态由调用方 rememberSaveable 持有，单栏和双栏切换时不丢 */
@Composable
private fun CallLog(
    calls: List<CallItem>,
    names: Map<String, String>,
    category: CallCategory,
    onCategory: (CallCategory) -> Unit,
    showBlockedInAll: Boolean,
    missedUnread: Int,
    actions: CallActions,
) {
    val isContact: (String) -> Boolean = { CallFilter.normalize(it) in names }
    val counts = remember(calls, names, showBlockedInAll) { CallFilter.counts(calls, isContact, showBlockedInAll) }
    val shown = remember(calls, names, category, showBlockedInAll) { CallFilter.filter(calls, category, isContact, showBlockedInAll) }
    Column {
        Text("通话记录", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
            items(CallCategory.entries.toList(), key = { it.name }) { c ->
                // 未接显示未读数，其余显示总数
                val n = if (c == CallCategory.Missed) missedUnread else counts[c] ?: 0
                FilterChip(
                    selected = c == category,
                    onClick = { onCategory(c) },
                    label = { Text(if (n > 0) "${c.label} $n" else c.label) },
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            if (shown.isEmpty()) item {
                Text(
                    if (calls.isEmpty()) "暂无通话记录（登录 phonebox 后显示）" else "这个分类下没有记录",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
                )
            }
            items(shown, key = { "${it.ts}-${it.number}-${it.dir}" }) { c ->
                CallRow(c, names[CallFilter.normalize(c.number)], actions)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 记录行上的操作；listsSupported=false（NAS 补丁未装）时不显示加黑白名单 */
private class CallActions(
    val dial: (String) -> Unit,
    val listsSupported: Boolean,
    val addToList: (BlockList, String) -> Unit,
    val sms: (String) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(c: CallItem, name: String?, actions: CallActions) {
    val ctx = LocalContext.current
    val type = if (CallFilter.isBlocked(c)) CallType.Blocked else Records.callType(c)
    val (glyph, color, desc) = when (type) {
        CallType.Incoming -> Triple("↙", MaterialTheme.colorScheme.tertiary, "来电")
        CallType.Outgoing -> Triple("↗", MaterialTheme.colorScheme.primary, "去电")
        CallType.Missed -> Triple("↙", MaterialTheme.colorScheme.error, "未接")
        CallType.Blocked -> Triple("⊘", MaterialTheme.colorScheme.error, "已拦截")
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { if (c.number.isNotEmpty()) actions.dial(c.number) }, onLongClick = { if (c.number.isNotEmpty()) menu = true })
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, color = color, fontSize = 20.sp, modifier = Modifier.width(28.dp).semantics { contentDescription = desc })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name ?: c.number.ifEmpty { "未知号码" }, style = MaterialTheme.typography.titleMedium,
                        color = if (type == CallType.Missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    // 只是号码类型的标签（如"手机"）已在副标题里，不再重复
                    if (c.tag.isNotBlank() && !CallFilter.isKindOnlyTag(c)) {
                        Spacer(Modifier.width(8.dp))
                        RiskTag(c.tag, Records.riskLevel(c))
                    }
                }
                if (name != null) Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sub = listOf(desc, c.loc, c.ntype).filter { it.isNotBlank() }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTs(c.ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.billsec > 0) Text(formatDuration(c.billsec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("复制号码") }, onClick = {
                menu = false
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("号码", c.number))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            })
            if (actions.listsSupported) {
                DropdownMenuItem(text = { Text("加入黑名单") }, onClick = { menu = false; actions.addToList(BlockList.Black, c.number) })
                DropdownMenuItem(text = { Text("加入白名单") }, onClick = { menu = false; actions.addToList(BlockList.White, c.number) })
            }
            DropdownMenuItem(text = { Text("发短信") }, onClick = { menu = false; actions.sms(c.number) })
        }
    }
}

private fun formatDuration(sec: Int): String = if (sec >= 60) "${sec / 60}分${sec % 60}秒" else "${sec}秒"

private fun formatTs(seconds: Long): String {
    val d = Date(seconds * 1000)
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { time = d }
    val sameDay = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "MM-dd", Locale.CHINA).format(d)
}

private val KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialPad(input: String, regState: RegState, onInput: (String) -> Unit, onDial: () -> Unit, onCollapse: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Text(
                input, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).semantics { contentDescription = "号码输入框" },
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .semantics { contentDescription = "退格" }
                    .combinedClickable(onClick = { onInput(input.dropLast(1)) }, onLongClick = { onInput("") }),
            ) { if (input.isNotEmpty()) Text("⌫", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                row.forEach { (digit, letters) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.size(width = 84.dp, height = 60.dp).clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .semantics { contentDescription = "按键 $digit" }
                            .combinedClickable(
                                onClick = { onInput(input + digit) },
                                // 长按 0 输入 +，国际号码用
                                onLongClick = if (digit == "0") ({ onInput(input + "+") }) else null,
                            ),
                    ) {
                        Text(digit, style = MaterialTheme.typography.headlineSmall)
                        if (letters.isNotEmpty()) Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // 左右各留同宽位置，让拨号键保持居中
            if (onCollapse != null) Spacer(Modifier.size(48.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF3E8E5A))
                    .semantics { contentDescription = "拨号" }
                    .clickable(onClick = onDial),
            ) { Icon(AppIcons.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            if (onCollapse != null) Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .semantics { contentDescription = "收起拨号盘" }
                    .clickable(onClick = onCollapse),
            ) { Icon(AppIcons.Dialpad, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val regText = when (regState) {
            RegState.Ok -> null
            RegState.Progress -> "SIP 正在注册…"
            is RegState.Failed -> "SIP 注册失败：${regState.reason}"
            else -> "SIP 未注册"
        }
        if (regText != null) Text(regText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
    }
}
