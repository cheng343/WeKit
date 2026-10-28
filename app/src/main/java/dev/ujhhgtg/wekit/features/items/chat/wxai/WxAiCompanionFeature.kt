package dev.ujhhgtg.wekit.features.items.chat.wxai

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WxAiCompanionFeature : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "WXAI数字生命伴侣"
    override val nameRes = R.string.feature_wxai_companion_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_wxai_companion_description

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        WxAiReplyEngine.onMessageInsertedValues(values)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val scope = rememberCoroutineScope()
            var apiKeyInput by remember { mutableStateOf(WxAiConfig.apiKey) }
            var baseUrlInput by remember { mutableStateOf(WxAiConfig.baseUrl) }
            var modelInput by remember { mutableStateOf(WxAiConfig.model) }
            var masterInput by remember { mutableStateOf(WxAiConfig.masterTalkers) }
            var presetState by remember { mutableStateOf(WxAiConfig.personaPreset) }
            var groupOnlyState by remember { mutableStateOf(WxAiConfig.groupOnlyWhenMentioned) }
            var multiBubbleState by remember { mutableStateOf(WxAiConfig.enableMultiBubble) }
            var agentToolsState by remember { mutableStateOf(WxAiConfig.enableAgentTools) }
            var visionState by remember { mutableStateOf(WxAiConfig.enableVision) }
            var patResponseState by remember { mutableStateOf(WxAiConfig.enablePatResponse) }
            var currentWhitelist by remember { mutableStateOf(WxAiConfig.whitelist) }
            var testingConnection by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_wxai_companion_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = "生效会话白名单",
                                    description = if (currentWhitelist.isEmpty()) {
                                        "未选择任何会话（不回复任何人）"
                                    } else {
                                        "已选择 ${currentWhitelist.size} 个好友/群聊"
                                    },
                                    onClick = {
                                        showComposeDialog(context) {
                                            val allContacts = remember {
                                                if (WeDatabaseApi.isReady) {
                                                    WeDatabaseApi.getContacts().filter { c ->
                                                        !c.wxId.startsWith("gh_") && !c.wxId.startsWith("fake_")
                                                    }
                                                } else emptyList()
                                            }
                                            ContactsSelector(
                                                title = "选择生效好友与群聊",
                                                contacts = allContacts,
                                                initialSelectedWxIds = currentWhitelist,
                                                onDismiss = onDismiss,
                                                onConfirm = { selected ->
                                                    currentWhitelist = selected
                                                    WxAiConfig.whitelist = selected
                                                    showToast("已保存 ${selected.size} 个生效会话")
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                )
                            }
                        }

                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseSupportingWidget(title = "API Key（密钥）") {
                                    OutlinedTextField(
                                        value = apiKeyInput,
                                        onValueChange = {
                                            apiKeyInput = it.trim()
                                            WxAiConfig.apiKey = apiKeyInput
                                        },
                                        placeholder = { Text("sk-...") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item {
                                BaseSupportingWidget(title = "Base URL（接口端点）") {
                                    OutlinedTextField(
                                        value = baseUrlInput,
                                        onValueChange = {
                                            baseUrlInput = it.trim()
                                            WxAiConfig.baseUrl = baseUrlInput
                                        },
                                        placeholder = { Text("https://api.deepseek.com/v1") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item {
                                BaseSupportingWidget(title = "模型名称（Model）") {
                                    OutlinedTextField(
                                        value = modelInput,
                                        onValueChange = {
                                            modelInput = it.trim()
                                            WxAiConfig.model = modelInput
                                        },
                                        placeholder = { Text("deepseek-chat") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item {
                                BaseSupportingWidget(title = "唯一主人微信标识（Master Talkers）") {
                                    OutlinedTextField(
                                        value = masterInput,
                                        onValueChange = {
                                            masterInput = it
                                            WxAiConfig.masterTalkers = masterInput
                                        },
                                        placeholder = { Text("wxid / 昵称（直接在微信私聊发 #认主 也可一键绑定）") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = !testingConnection,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    testingConnection = true
                                    scope.launch(Dispatchers.IO) {
                                        val cfg = WxAiConfig.snapshot()
                                        val res = WxAiClient.complete(cfg, "你好，请用一句话证明你已成功连通！")
                                        withContext(Dispatchers.Main) {
                                            testingConnection = false
                                            if (res.text != null) {
                                                showToast("✅ 连接成功: ${res.text}")
                                            } else {
                                                showToast("❌ 连接失败: ${res.error}")
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(if (testingConnection) "测试中..." else "测试 AI 连接")
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val self = WxAiSessionManager.getSelfState()
                                    val diaries = WxAiSessionManager.getRecentDiaries(limit = 2)
                                    val diarySummary = if (diaries.isEmpty()) "暂无日记" else diaries.first().entry
                                    showToast("心情: ${self.mood} | 精力: ${self.energy} | 日记: $diarySummary")
                                }
                            ) {
                                Text("查看心境与日记")
                            }
                        }

                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "群聊仅在 @我 时回复",
                                    description = "关闭则回复群内所有消息（需注意群聊频率）",
                                    checked = groupOnlyState,
                                    onCheckedChange = {
                                        groupOnlyState = it
                                        WxAiConfig.groupOnlyWhenMentioned = it
                                    }
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "拟人多气泡分段发送",
                                    description = "将多句话按真人打字节奏分气泡连续发出",
                                    checked = multiBubbleState,
                                    onCheckedChange = {
                                        multiBubbleState = it
                                        WxAiConfig.enableMultiBubble = it
                                    }
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "12 大微信 Agent 操控工具",
                                    description = "允许主人命令 AI 跨会话发消息、查聊天记录、拍一拍发朋友圈",
                                    checked = agentToolsState,
                                    onCheckedChange = {
                                        agentToolsState = it
                                        WxAiConfig.enableAgentTools = it
                                    }
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "多模态识图与表情包接梗",
                                    description = "自动解码微信图片与表情包，调用视觉理解画面接梗斗图",
                                    checked = visionState,
                                    onCheckedChange = {
                                        visionState = it
                                        WxAiConfig.enableVision = it
                                    }
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "微信拍一拍自动互动",
                                    description = "被拍头像后风趣吐槽并在 600ms 内拍回去",
                                    checked = patResponseState,
                                    onCheckedChange = {
                                        patResponseState = it
                                        WxAiConfig.enablePatResponse = it
                                    }
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }
}
