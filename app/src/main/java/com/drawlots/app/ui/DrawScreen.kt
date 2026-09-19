package com.drawlots.app.ui

import android.util.Log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drawlots.app.AppViewModel
import com.drawlots.app.DrawOutcome
import com.drawlots.app.R
import com.drawlots.app.UiState
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.DrawRecord
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.ui.components.ActionButton
import com.drawlots.app.ui.components.LocalImage
import com.drawlots.app.ui.components.LotThumb
import com.drawlots.app.ui.components.ModeChip
import com.drawlots.app.ui.components.QuantityStepper
import com.drawlots.app.ui.room.RoomPermissions
import com.drawlots.app.room.RoomRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 抽签滚动动画：10 步 × 70ms ≈ 0.7 秒（按步数计时，不依赖系统时钟）。 */
private const val ROLL_STEPS = 10
private const val ROLL_STEP_MILLIS = 70L

/** 等待房主的兜底时长：超过就把按钮恢复，免得永久卡在「等待房主…」。 */
private const val WAITING_TIMEOUT_MILLIS = 12_000L

private const val UI_TAG = "DrawLotsUi"

/** 一次「滚动」的时长：抽签人的动画、以及别人抽时各设备的等待，都用它对齐。 */
private const val ROLL_DURATION_MILLIS = (ROLL_STEPS * ROLL_STEP_MILLIS).toLong()

/**
 * 抽签页：选择放回 / 不放回，抽一支或多支，结果按抽出顺序排列。 */
@Composable
fun DrawScreen(
    ui: UiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val drawCount by viewModel.drawCount.collectAsState()
    val role by viewModel.roomRole.collectAsState()
    val roomInfo by viewModel.roomInfo.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()

    val canRecover = RoomPermissions.canRecover(role, roomInfo?.allowRecover ?: false)
    val canReset = RoomPermissions.canReset(role)
    val canChangeMode = RoomPermissions.canChangeMode(role)
    val recoverHint = RoomPermissions.recoverDisabledHint(role, roomInfo?.allowRecover ?: false)

    var rolling by remember { mutableStateOf(false) }
    var rollingLot by remember { mutableStateOf<Lot?>(null) }
    var highlightFrom by remember { mutableIntStateOf(-1) }
    var highlightCount by remember { mutableIntStateOf(0) }
    // 成员抽签：请求已发出，等房主广播回来（到了再高亮最新那一批）
    var pendingDrawCount by remember { mutableIntStateOf(0) }
    var waitingForHost by remember { mutableStateOf(false) }
    /**
     * 已经揭示给用户看的结果条数。
     *
     * 网格**只渲染到这个条数**：遮罩是默认状态，而不是「先渲染、再盖住」。
     * 之前用事后设置 `frozenResultCount` 的写法，新结果会在到达那一帧先渲染出来、
     * 下一帧才被盖上，真机表现就是抽签时画面「闪一下」。
     */
    var revealedCount by remember { mutableIntStateOf(ui.results.size) }
    val visibleResults = ui.results.take(revealedCount)
    /** 上一批已经揭示过的结果条数，用来判断「新到了一批结果」。 */
    var lastSeenResultCount by remember { mutableIntStateOf(ui.results.size) }

    /** 揭示 [before] 条之后的新结果：推进已揭示条数 + 高亮 + 震动 + 滚过去。 */
    suspend fun revealResults(before: Int) {
        revealedCount = ui.results.size
        if (before >= ui.results.size) return
        highlightFrom = before
        highlightCount = ui.results.size - before
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        runCatching { gridState.animateScrollToItem(before) }
    }
    // 记住正在放大查看的那条结果（用 seq，这样写备注时弹窗内容始终是最新的）
    var previewSeq by remember { mutableStateOf<Int?>(null) }

    val planned = if (ui.mode == DrawMode.WITH_REPLACEMENT) {
        drawCount
    } else {
        minOf(drawCount, ui.totalRemaining)
    }

    /**
     * 结果揭示。*唯一 owner**（每个设备各自计时，谁也不用等谁）。
     *
     * 键里带上 [rolling]：滚动结束时 effect 会重新评估一次，所以「结果在滚动途中到达。
     * 也能在滚完后立刻揭示。*必须先用 rolling 提前返回、再推进 lastSeenResultCount**—。
     * 反过来会把「条数变化」消费掉却什么都不做，而键不再变化 。永远不会再触发（真机上表现为
     * 滚完卡在「等待房主…」好几秒）。
     *
     * 也正因为揭示只在这里做，滚动协程不必。`ui`（它闭包里捕获的 ui 是旧的，读不到新结果）。
     */
    LaunchedEffect(ui.results.size, rolling) {
        // 结果被撤销/重置变少了：直接把已揭示条数收回来，不做揭示编排
        if (ui.results.size < revealedCount) {
            revealedCount = ui.results.size
            lastSeenResultCount = ui.results.size
            return@LaunchedEffect
        }
        if (rolling) return@LaunchedEffect

        val before = lastSeenResultCount
        if (ui.results.size <= before) return@LaunchedEffect
        lastSeenResultCount = ui.results.size

        if (pendingDrawCount > 0) {
            // 自己抽的：滚完了，立即揭示
            revealResults(before)
            pendingDrawCount = 0
            waitingForHost = false
            return@LaunchedEffect
        }

        // 别人抽的：自己也等同样长的一个滚动时长，再一起揭示
        delay(ROLL_DURATION_MILLIS)
        revealResults(before)
    }

    // 兜底：结果绝不允许被挡住超过一个滚动时长 + 余量（状态机万一有死角也不会卡住）
    LaunchedEffect(ui.results.size, revealedCount) {
        if (revealedCount >= ui.results.size) return@LaunchedEffect
        delay(ROLL_DURATION_MILLIS + 400)
        if (revealedCount < ui.results.size) {
            pendingDrawCount = 0
            waitingForHost = false
            revealResults(lastSeenResultCount)
            lastSeenResultCount = ui.results.size
        }
    }

    fun startDraw() {
        if (rolling || waitingForHost || !ui.canDraw) return
        scope.launch {
            val member = role == RoomRole.Member
            if (member) {
                // 成员。*先把请求发出。*（不。ack），立刻给出等待反馈。
        // 再用滚动动画盖住这段往返——BLE 上一个来回要几百毫秒到几秒，
                // 以前是等 ack 回来才变「等待房主」，看起来就像点了没反应。
                waitingForHost = true
                pendingDrawCount = planned.coerceAtLeast(1)
                val dispatch = viewModel.drawAsync()
                if (dispatch is DrawOutcome.Failed) {
                    waitingForHost = false
                    pendingDrawCount = 0
                    viewModel.showMessage(dispatch.reason)
                    return@launch
                }

                rolling = true
                val pool = ui.lots.map { it.lot }.filter { it.quantity > 0 }
                repeat(ROLL_STEPS) {
                    rollingLot = pool.randomOrNull()
                    delay(ROLL_STEP_MILLIS)
                }
                rolling = false
                rollingLot = null

                // 揭示统一交给上面。LaunchedEffect：它以 rolling 为键，滚完会重新评估。
        // 因此「结果早到了」和「滚完才到」两种情况都能正确处理。
                return@launch
            }

            // 本地抽取（房主/单机）：**先真正抽、并立刻广播**，滚动动画只用来盖住这段时间。
            // 原来是「滚完再抽再广播」，其他设备因此要多等一个滚动时长，看起来延迟很高。
            // 现在：房主滚完揭示（≈ 点击 + 700ms），其他设备收到后各等 700ms（≈ 点击 + 750ms），
            // 只差一个链路延迟。
            pendingDrawCount = planned.coerceAtLeast(1)
            when (val outcome = viewModel.drawAsync()) {
                is DrawOutcome.Failed -> {
                    pendingDrawCount = 0
                    viewModel.showMessage(outcome.reason)
                    return@launch
                }
                // 揭示由上面的 LaunchedEffect 统一做（本地抽取在滚完后立即揭示）
                else -> Unit
            }

            rolling = true
            val pool = ui.lots.map { it.lot }.filter { it.quantity > 0 }
            repeat(ROLL_STEPS) {
                rollingLot = pool.randomOrNull()
                delay(ROLL_STEP_MILLIS)
            }
            rolling = false
            rollingLot = null
        }
    }

    // 等待兜底：房主彻底没响应时别把按钮永久卡在「等待房主…」。
    LaunchedEffect(waitingForHost) {
        if (!waitingForHost) return@LaunchedEffect
        delay(WAITING_TIMEOUT_MILLIS)
        if (waitingForHost) {
            waitingForHost = false
            pendingDrawCount = 0
            // 注意：不在这里动 revealedCount——兜底只负责让按钮恢复，
            // 结果该不该露出交给上面那条兜底 effect（它会正常揭示）
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------- 放回 / 不放回（成员不能改，这是房主设定的房间规则） ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canChangeMode) {
                ModeChip(DrawMode.WITHOUT_REPLACEMENT, ui.mode, viewModel::setMode)
                Spacer(Modifier.width(8.dp))
                ModeChip(DrawMode.WITH_REPLACEMENT, ui.mode, viewModel::setMode)
            } else {
                Text(
                    text = "模式：${ui.mode.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "（由房主设定）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (ui.mode == DrawMode.WITHOUT_REPLACEMENT) {
                    "剩余 ${ui.totalRemaining}/${ui.totalQuantity}"
                } else {
                    "共 ${ui.totalQuantity} 支"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = ui.mode.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // ---------- 每次抽取数量 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("每次抽取", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            QuantityStepper(
                value = drawCount,
                onChange = viewModel::setDrawCount,
                max = 99,
                enabled = !rolling,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    planned <= 0 -> "没有可抽的签"
                    planned < drawCount -> "只剩 $planned 支，将全部抽完"
                    else -> "将抽出 $planned 支"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (planned < drawCount) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (ui.mode == DrawMode.WITHOUT_REPLACEMENT && ui.totalQuantity > 0) {
            RemainingBar(
                fraction = ui.totalRemaining.toFloat() / ui.totalQuantity.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // ---------- 滚动展示 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (rolling) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LotThumb(lot = rollingLot, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = rollingLot?.title ?: "？",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    text = if (ui.canDraw) "点下面的按钮开始抽签" else "签池里还没有可抽的签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 抽签按钮 ----------
        Button(
            onClick = { startDraw() },
            enabled = ui.canDraw && !rolling && !waitingForHost,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(56.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dice),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    rolling -> "抽取中…"
                    waitingForHost -> "等待房主…"
                    planned <= 0 -> "没有可抽的签"
                    else -> "抽 $planned 个签"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        // ---------- 结果 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("抽签结果（按顺序）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${visibleResults.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (recoverHint != null && visibleResults.isNotEmpty()) {
            Text(
                text = recoverHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }

        if (ui.results.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canRecover) {
                    ActionButton(
                        icon = painterResource(R.drawable.ic_undo),
                        text = "撤销",
                        onClick = { viewModel.undoLast() },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (canReset) {
                    ActionButton(
                        icon = painterResource(R.drawable.ic_restart),
                        text = "重置",
                        onClick = { viewModel.resetPool() },
                        modifier = Modifier.weight(1f),
                    )
                }
                ActionButton(
                    icon = painterResource(R.drawable.ic_share),
                    text = "分享",
                    onClick = { shareText(context, viewModel.resultsAsText()) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    icon = painterResource(R.drawable.ic_copy),
                    text = "复制",
                    onClick = {
                        copyText(context, viewModel.resultsAsText())
                        viewModel.showMessage("抽签结果已复制")
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (visibleResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.isEmpty) "先去「签池」添加签吧" else "还没有抽出任何签",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(visibleResults, key = { _, record -> record.seq }) { index, record ->
                    ResultCard(
                        record = record,
                        position = index + 1,
                        highlighted = highlightFrom >= 0 &&
                            index >= highlightFrom &&
                            index < highlightFrom + highlightCount,
                        onClick = { previewSeq = record.seq },
                    )
                }
            }
        }
    }

    val previewIndex = ui.results.indexOfFirst { it.seq == previewSeq }
    if (previewIndex >= 0) {
        val record = ui.results[previewIndex]
        ResultPreviewDialog(
            record = record,
            position = previewIndex + 1,
            canRecover = canRecover,
            onNoteChange = { viewModel.setNote(record, it) },
            onPutBack = {
                viewModel.putBack(record)
                previewSeq = null
            },
            onDismiss = { previewSeq = null },
        )
    }
}

@Composable
private fun RemainingBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun ResultCard(
    record: DrawRecord,
    position: Int,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (highlighted) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imagePath = record.imagePath
            if (record.kind == LotKind.IMAGE && !imagePath.isNullOrBlank()) {
                LocalImage(
                    path = imagePath,
                    modifier = Modifier.fillMaxSize(),
                    targetSizePx = 480,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 底部信息条：图片签的备注文字 + 手写备注（例如谁抽到的）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
            ) {
                if (record.kind == LotKind.IMAGE && record.text.isNotBlank()) {
                    Text(
                        text = record.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                if (record.note.isNotBlank()) {
                    Text(
                        text = record.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            // 联机时显示是谁抽到的
            if (record.drawnBy.isNotBlank()) {
                Text(
                    text = "@${record.drawnBy}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$position",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "抽签结果")
    }
    context.startActivity(Intent.createChooser(intent, "分享抽签结果"))
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("抽签结果", text))
}

