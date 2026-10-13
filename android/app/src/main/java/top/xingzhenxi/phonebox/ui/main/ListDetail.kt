package top.xingzhenxi.phonebox.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.ui.theme.RiskLevel
import top.xingzhenxi.phonebox.ui.theme.RiskTag
import top.xingzhenxi.phonebox.ui.theme.pressScale

/** 占位条目；后续阶段替换为 phonebox feed 的真实数据结构 */
data class PlaceholderItem(val id: String, val title: String, val preview: String, val time: String, val risk: RiskLevel?, val riskLabel: String = "")

/**
 * 外屏单栏、内屏双栏的通用列表-详情骨架。
 * 导航器状态可保存，旋转或折叠/展开后选中项不丢。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PlaceholderListDetail(title: String, items: List<PlaceholderItem>) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        listPane = {
            AnimatedPane {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 12.dp))
                    }
                    items(items, key = { it.id }) { item ->
                        ItemCard(item, selected = navigator.currentDestination?.contentKey == item.id) {
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item.id) }
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val item = items.firstOrNull { it.id == navigator.currentDestination?.contentKey }
                DetailPlaceholder(item, showBack = navigator.canNavigateBack()) {
                    scope.launch { navigator.navigateBack() }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemCard(item: PlaceholderItem, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (item.risk != null) {
                        Spacer(Modifier.width(8.dp))
                        RiskTag(item.riskLabel, item.risk)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(item.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                item.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DetailPlaceholder(item: PlaceholderItem?, showBack: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (showBack) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "返回") }
        }
        if (item == null) {
            Text("选择左侧一项查看详情", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            Text(item.preview, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(4.dp))
        }
    }
}
