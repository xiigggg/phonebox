package top.xingzhenxi.phonebox.ui.messages

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.data.CodeCard
import top.xingzhenxi.phonebox.data.Conversation
import top.xingzhenxi.phonebox.data.FeedSnapshot
import top.xingzhenxi.phonebox.data.Records
import top.xingzhenxi.phonebox.data.SmsCategories
import top.xingzhenxi.phonebox.data.SmsCategory
import top.xingzhenxi.phonebox.data.parse.MessageParser
import top.xingzhenxi.phonebox.data.parse.ParsedCard
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.net.SmsRules
import top.xingzhenxi.phonebox.notify.NotificationActionReceiver
import top.xingzhenxi.phonebox.notify.SmsNotifyRules
import top.xingzhenxi.phonebox.ui.login.ReloginDialog
import top.xingzhenxi.phonebox.ui.main.AppIcons
import top.xingzhenxi.phonebox.ui.theme.RiskTag
import top.xingzhenxi.phonebox.ui.theme.pressScale

private const val NEW_KEY = "new"
private const val LOCAL_KEY = "local-sim"

/** 离线：只有缓存，或最近一次同步失败且事件流没连着 */
internal fun isOffline(s: FeedSnapshot): Boolean =
    s.fromCache || (s.lastError != null && s.mode != top.xingzhenxi.phonebox.data.SyncMode.Streaming)

/** 信息页：分类筛选 + 验证码卡片 + 会话列表，内屏双栏（列表 | 会话详情） */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen() {
    val ctx = LocalContext.current
    val graph = ctx.appGraph
    val pb by graph.phonebox.collectAsStateWithLifecycle()
    val snapshot by (pb?.feedSync?.state ?: flowOf(FeedSnapshot())).collectAsStateWithLifecycle(FeedSnapshot())
    val read by graph.readStore.read.collectAsStateWithLifecycle()
    val config by graph.configRepository.config.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    var refreshing by remember { mutableStateOf(false) }
    var relogin by remember { mutableStateOf(false) }
    var codesExpanded by rememberSaveable { mutableStateOf(false) }
    var categoryName by rememberSaveable { mutableStateOf(SmsCategory.All.name) }
    val category = SmsCategory.valueOf(categoryName)
    val showSpamInAll by graph.readStore.showSpamInAllFlow.collectAsStateWithLifecycle()

    val allSms = snapshot.feed.sms
    val blocked = remember(snapshot.feed.calls) { SmsCategories.blockedNumbers(snapshot.feed.calls) }
    val parser = remember { MessageParser.get(ctx) }
    // 解析只依赖短信本身，feed 不变就不重算
    val parsed = remember(allSms) { allSms.associate { SmsNotifyRules.key(it) to parser.parse(it.from, it.body, it.sig) } }
    val parsedOf: (SmsItem) -> ParsedCard = { parsed[SmsNotifyRules.key(it)] ?: ParsedCard.None }
    val sms = SmsCategories.filter(allSms, category, blocked, showSpamInAll)
    val conversations = Records.conversations(sms)
    // 详情按全部短信找，切换分类时已打开的会话不至于消失
    val allConversations = remember(allSms) { Records.conversations(allSms) }
    val now = System.currentTimeMillis() / 1000
    val codes = if (SmsCategories.showsCodeCards(category)) Records.codeCards(sms, now) else emptyList()
    val loggedIn = config?.phonebox?.hasSession == true
    val unread: (SmsItem) -> Boolean = { s -> s.ts >= graph.readStore.baselineTs && SmsNotifyRules.key(s) !in read }
    val unreadCounts = SmsCategories.unreadCounts(allSms, blocked, showSpamInAll, unread)

    if (relogin) ReloginDialog(onDismiss = { relogin = false })

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        listPane = {
            AnimatedPane {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            pb?.feedSync?.pollNow()
                            refreshing = false
                        }
                    },
                ) {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp, 8.dp, 0.dp, 8.dp)) {
                                Text("信息", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, NEW_KEY) } }) {
                                    Icon(MsgIcons.Edit, contentDescription = "新建短信")
                                }
                            }
                        }
                        item(key = "categories") {
                            CategoryBar(category, unreadCounts) { categoryName = it.name; codesExpanded = false }
                        }
                        if (snapshot.sessionExpired || (!loggedIn && config != null)) {
                            item {
                                Banner(if (snapshot.sessionExpired) "登录已失效，短信暂停同步" else "还没有登录 phonebox", "重新登录") { relogin = true }
                            }
                        } else if (isOffline(snapshot) && snapshot.feed.sms.isNotEmpty()) {
                            item { Banner("离线：显示的是本地缓存", null, null) }
                        }
                        // 只突出最新一条，其余收成一行，点开再展开成小卡片
                        codes.firstOrNull()?.let { latest -> item(key = "code:" + SmsNotifyRules.key(latest.sms)) { CodeCardView(latest, now) } }
                        if (codes.size > 1) {
                            item(key = "codes-more") {
                                TextButton(onClick = { codesExpanded = !codesExpanded }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (codesExpanded) "收起" else "还有 ${codes.size - 1} 条验证码")
                                }
                            }
                            if (codesExpanded) {
                                items(codes.drop(1), key = { "code:" + SmsNotifyRules.key(it.sms) }) { CodeCardView(it, now, compact = true) }
                            }
                        }
                        if (conversations.isEmpty()) {
                            item {
                                Text(if (category == SmsCategory.All) "暂无短信" else "「${category.label}」下暂无短信", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                            }
                        }
                        // 本机卡短信在详情栏里看：它自带列表，不能嵌进这里的 LazyColumn
                        if (category == SmsCategory.All) item(key = LOCAL_KEY) {
                            TextButton(onClick = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, LOCAL_KEY) } }, modifier = Modifier.fillMaxWidth()) {
                                Text("本机卡短信", modifier = Modifier.weight(1f))
                                Text("查看 ›")
                            }
                        }
                        items(conversations, key = { it.key }) { c ->
                            ConversationRow(
                                c, SmsCategories.summaryLine(c.latest, parsedOf(c.latest)), SmsCategories.classify(c.latest, blocked),
                                unreadCount = c.messages.count(unread), selected = navigator.currentDestination?.contentKey == c.key) {
                                scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, c.key) }
                            }
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val key = navigator.currentDestination?.contentKey
                val back: (() -> Unit)? = if (navigator.canNavigateBack()) ({ scope.launch { navigator.navigateBack() } }) else null
                when {
                    key == NEW_KEY -> NewMessage(back)
                    key == LOCAL_KEY -> Column(Modifier.fillMaxSize()) {
                        DetailHeader("本机卡短信", null, back)
                        top.xingzhenxi.phonebox.roles.LocalSimSection(Modifier.fillMaxSize().padding(horizontal = 16.dp))
                    }
                    key != null -> {
                        val c = allConversations.firstOrNull { it.key == key }
                        if (c == null) EmptyDetail() else ThreadView(c, parsedOf, back)
                    }
                    else -> EmptyDetail()
                }
            }
        },
    )
}

@Composable
private fun Banner(text: String, action: String?, onAction: (() -> Unit)?) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CodeCardView(card: CodeCard, now: Long, compact: Boolean = false) {
    val ctx = LocalContext.current
    Card(
        shape = if (compact) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge,
        colors = if (compact) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(if (compact) 14.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("验证码 · ${card.source}", style = MaterialTheme.typography.labelLarge)
                Text(card.code, fontSize = if (compact) 22.sp else 36.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = if (compact) 2.sp else 4.sp)
                Text("${(now - card.ts).coerceAtLeast(0) / 60} 分钟前 · ${MessageFormat.short(card.ts)}", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(onClick = {
                NotificationActionReceiver.copy(ctx, card.code)
                val key = SmsNotifyRules.key(card.sms)
                ctx.appGraph.readStore.markRead(listOf(key))
                ctx.appGraph.smsNotifier.cancelForKeys(listOf(key))
            }) {
                Icon(MsgIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制")
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, summary: String, category: SmsCategory?, unreadCount: Int, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val latest = c.latest
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().pressScale(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.key, MessageFormat.avatarText(latest.sig, latest.from))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 标题和标签放进占满剩余宽度的子 Row：两个 weight 并列时会平分空间，时间就靠不到右边
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (unreadCount > 0) FontWeight.Bold else null, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        CategoryTag(category)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(MessageFormat.short(latest.ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("选择一个会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailHeader(title: String, subtitle: String?, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "返回") }
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** 时间线里的一条：收到的来自 feed，发出的来自本进程 [Outbox] */
private sealed interface Bubble {
    val ts: Long
    data class In(val sms: SmsItem) : Bubble { override val ts get() = sms.ts }
    data class Out(val out: OutgoingSms) : Bubble { override val ts get() = out.ts }
}

@Composable
private fun ThreadView(c: Conversation, parsedOf: (SmsItem) -> ParsedCard, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    val graph = ctx.appGraph
    // 签名会话可能来自多个号码，回复给最近一条的发件号码
    val replyTo = c.latest.from
    val canReply = SmsNotifyRules.canReply(c.latest)
    val numbers = c.messages.map { it.from }.toSet()
    LaunchedEffect(c.key, c.latest.ts) {
        val keys = c.messages.map(SmsNotifyRules::key)
        graph.readStore.markRead(keys)
        graph.smsNotifier.cancelForKeys(keys)
    }
    val bubbles = (c.messages.map { Bubble.In(it) } + Outbox.items.filter { it.to in numbers }.map { Bubble.Out(it) })
        .sortedBy { it.ts }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(c.title, if (c.bySignature) "回复至 $replyTo" else null, onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true,
        ) {
            items(bubbles.asReversed(), key = { b -> if (b is Bubble.In) "in:" + SmsNotifyRules.key(b.sms) else "out:" + (b as Bubble.Out).out.id }) { b ->
                when (b) {
                    is Bubble.In -> IncomingSms(b.sms, parsedOf(b.sms), MessageFormat.full(b.sms.ts))
                    is Bubble.Out -> BubbleView(
                        b.out.text, MessageFormat.full(b.out.ts), outgoing = true,
                        status = when (b.out.status) {
                            SendStatus.Sending -> "发送中…"
                            SendStatus.Sent -> "已发送"
                            SendStatus.Failed -> "发送失败：${b.out.error ?: ""}"
                        },
                    )
                }
            }
        }
        if (canReply) Composer(replyTo) else {
            Text("该号码不支持回复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun BubbleView(text: String, time: String, outgoing: Boolean, status: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp)
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text(
            if (status != null) "$time · $status" else time,
            style = MaterialTheme.typography.labelSmall,
            color = if (status?.startsWith("发送失败") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Composer(to: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(to) { mutableStateOf("") }
    // 打开会话时系统会把焦点交给界面上第一个可聚焦的控件，也就是这个输入框，键盘随之弹出。
    // 只有用户真的点了输入框（或在用实体键盘导航）才允许它获得焦点；失焦后恢复原状。
    val inputMode = LocalInputModeManager.current
    var userTapped by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(SmsRules.MAX_TEXT) },
            placeholder = { Text("短信") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            modifier = Modifier.weight(1f)
                .focusProperties { canFocus = userTapped || inputMode.inputMode == InputMode.Keyboard }
                .onFocusChanged { if (!it.isFocused) userTapped = false }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        userTapped = true
                    }
                },
        )
        IconButton(enabled = text.isNotBlank(), onClick = {
            val t = text
            text = ""
            scope.launch { Outbox.send(ctx.appGraph.phonebox.value?.client, to, t) }
        }) { Icon(MsgIcons.Send, contentDescription = "发送") }
    }
}

@Composable
private fun NewMessage(onBack: (() -> Unit)?) {
    var to by rememberSaveable { mutableStateOf("") }
    val valid = SmsRules.check(to, "x") == null
    val sent = Outbox.items.filter { it.to == to.trim() }
    Column(Modifier.fillMaxSize()) {
        DetailHeader("新建短信", null, onBack)
        OutlinedTextField(
            value = to,
            onValueChange = { to = it.filter { ch -> ch.isDigit() || ch == '+' }.take(20) },
            label = { Text("收件号码") },
            singleLine = true,
            isError = to.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sent, key = { it.id }) { o ->
                BubbleView(o.text, MessageFormat.full(o.ts), outgoing = true, status = when (o.status) {
                    SendStatus.Sending -> "发送中…"
                    SendStatus.Sent -> "已发送"
                    SendStatus.Failed -> "发送失败：${o.error ?: ""}"
                })
            }
        }
        if (valid) Composer(to.trim())
    }
}
