package hf.fontfix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hyperfontfix.module.BuildConfig
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(store: SettingsStore) {
    val context = LocalContext.current
    val bridge by store.bridge.collectAsState()
    val config = remember { mutableStateOf(store.read(context)) }
    var confirmReset by remember { mutableStateOf(false) }
    val online = bridge != null

    LaunchedEffect(bridge) {
        config.value = store.read(context)
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "HyperFontFix",
                subtitle = "状态栏与锁屏字重",
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Card(modifier = Modifier.padding(12.dp)) {
                    BridgeStatus(bridge)
                }
            }

            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "启用模块",
                        summary = if (config.value.enable) {
                            "字重修复生效中，关闭后立即恢复系统原生字重"
                        } else {
                            "已停用，系统使用原生字重"
                        },
                        checked = config.value.enable,
                        enabled = online,
                        onCheckedChange = {
                            config.value = config.value.copy(enable = it)
                            store.putFlag(HfKeys.ENABLE, it)
                        },
                    )
                }
            }

            item { SectionTitle("状态栏") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WeightSlider("时钟", "状态栏时间", config.value.clock, online,
                        onChange = { config.value = config.value.copy(clock = it) },
                        onCommit = { store.putInt(HfKeys.CLOCK, config.value.clock) })
                    WeightSlider("网络标识", "4G / 5G 标签", config.value.network, online,
                        onChange = { config.value = config.value.copy(network = it) },
                        onCommit = { store.putInt(HfKeys.NETWORK, config.value.network) })
                    WeightSlider("运营商", "状态栏运营商名称", config.value.carrier, online,
                        onChange = { config.value = config.value.copy(carrier = it) },
                        onCommit = { store.putInt(HfKeys.CARRIER, config.value.carrier) })
                    WeightSlider("网速", "网速数字与单位", config.value.speed, online,
                        onChange = { config.value = config.value.copy(speed = it) },
                        onCommit = { store.putInt(HfKeys.SPEED, config.value.speed) })
                    WeightSlider("外显电量数字", "电池百分比数字", config.value.battery, online,
                        onChange = { config.value = config.value.copy(battery = it) },
                        onCommit = { store.putInt(HfKeys.BATTERY, config.value.battery) })
                    WeightSlider("外显电量百分号", "电池 % 符号", config.value.mark, online,
                        onChange = { config.value = config.value.copy(mark = it) },
                        onCommit = { store.putInt(HfKeys.MARK, config.value.mark) })
                    WeightSlider("内显电量", "空心电池图标内的数字", config.value.hollow, online,
                        onChange = { config.value = config.value.copy(hollow = it) },
                        onCommit = { store.putInt(HfKeys.HOLLOW, config.value.hollow) })
                }
            }

            item { SectionTitle("锁屏") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WeightSlider("锁屏日期", "与状态栏日期独立设置", config.value.lockdate, online,
                        onChange = { config.value = config.value.copy(lockdate = it) },
                        onCommit = { store.putInt(HfKeys.LOCKDATE, config.value.lockdate) })
                    WeightSlider("闹钟", "锁屏闹钟", config.value.alarm, online,
                        onChange = { config.value = config.value.copy(alarm = it) },
                        onCommit = { store.putInt(HfKeys.ALARM, config.value.alarm) })
                    SwitchPreference(
                        title = "仅显示主卡运营商",
                        summary = "锁屏只保留主卡名称，避免双卡时整块被隐藏",
                        checked = config.value.singlecarrier,
                        enabled = online,
                        onCheckedChange = {
                            config.value = config.value.copy(singlecarrier = it)
                            store.putFlag(HfKeys.SINGLECARRIER, it)
                        },
                    )
                }
            }

            item { SectionTitle("其他文本") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WeightSlider("超级岛", "默认不修改，0 = 保持系统原样", config.value.island, online,
                        onChange = { config.value = config.value.copy(island = it) },
                        onCommit = { store.putInt(HfKeys.ISLAND, config.value.island) })
                    WeightSlider("控制中心日期", "默认不修改，0 = 保持系统原样", config.value.date, online,
                        onChange = { config.value = config.value.copy(date = it) },
                        onCommit = { store.putInt(HfKeys.DATE, config.value.date) })
                    WeightSlider("未分类文本", "其他状态栏文字，默认不修改", config.value.other, online,
                        onChange = { config.value = config.value.copy(other = it) },
                        onCommit = { store.putInt(HfKeys.OTHER, config.value.other) })
                }
            }

            item { SectionTitle("高级") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WeightSlider("全局通道", "无法识别的调用方使用该字重", config.value.global, online,
                        onChange = { config.value = config.value.copy(global = it) },
                        onCommit = { store.putInt(HfKeys.GLOBAL, config.value.global) })
                    WeightSlider("兼容模式", "大于 0 时所有未分类文本都加粗，0 = 关闭", config.value.all, online,
                        onChange = { config.value = config.value.copy(all = it) },
                        onCommit = { store.putInt(HfKeys.ALL, config.value.all) })
                    SwitchPreference(
                        title = "字体家族替换",
                        summary = "进程级替换 mipro-* 字体请求，可能影响其他界面",
                        checked = config.value.family,
                        enabled = online,
                        onCheckedChange = {
                            config.value = config.value.copy(family = it)
                            store.putFlag(HfKeys.FAMILY, it)
                        },
                    )
                    SwitchPreference(
                        title = "全局 460 通道",
                        summary = "替换系统共享字体 getter，实验功能",
                        checked = config.value.g1,
                        enabled = online,
                        onCheckedChange = {
                            config.value = config.value.copy(g1 = it)
                            store.putFlag(HfKeys.G1, it)
                        },
                    )
                    SwitchPreference(
                        title = "常亮显示进程",
                        summary = "默认不挂载，仅调试时开启",
                        checked = config.value.aod,
                        enabled = online,
                        onCheckedChange = {
                            config.value = config.value.copy(aod = it)
                            store.putFlag(HfKeys.AOD, it)
                        },
                    )
                    OverlayDropdownPreference(
                        title = "日志级别",
                        summary = "排查问题时使用",
                        items = listOf("关闭", "基础", "详细"),
                        selectedIndex = config.value.log,
                        enabled = online,
                        onSelectedIndexChange = {
                            config.value = config.value.copy(log = it)
                            store.putInt(HfKeys.LOG, it)
                        },
                    )
                }
            }

            item {
                Button(
                    onClick = { confirmReset = true },
                    enabled = online,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("恢复默认设置")
                }
            }

            item {
                Text(
                    text = "HyperFontFix ${BuildConfig.VERSION_NAME} · 修改后立即生效，无需重启系统界面",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    if (confirmReset) {
        OverlayDialog(
            show = true,
            title = "恢复默认设置",
            summary = "将清除应用内保存的全部配置，回到模块默认值。",
            onDismissRequest = { confirmReset = false },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { confirmReset = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        store.reset()
                        config.value = store.read(context)
                        confirmReset = false
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("恢复默认")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    SmallTitle(text)
}

@Composable
private fun BridgeStatus(bridge: HfBridge?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (bridge != null) "已连接系统服务" else "未连接系统服务",
            style = MiuixTheme.textStyles.body1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (bridge != null) {
                "${bridge.frameworkName} ${bridge.frameworkVersion} · API ${bridge.apiVersion}"
            } else {
                "请确认模块已在 LSPosed 中启用并勾选系统界面，然后重新打开本应用。"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WeightSlider(
    title: String,
    summary: String,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    SliderPreference(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(0, 1000)) },
        onValueChangeFinished = { onCommit() },
        title = title,
        summary = summary,
        valueText = if (value == 0) "关闭" else value.toString(),
        enabled = enabled,
        valueRange = 0f..1000f,
        steps = 99,
    )
}
