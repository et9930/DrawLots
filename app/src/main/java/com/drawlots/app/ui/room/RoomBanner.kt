package com.drawlots.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.drawlots.app.R
import com.drawlots.app.room.RoomInfo
import com.drawlots.app.room.RoomRole
import com.drawlots.app.room.RoomStatus
import androidx.compose.material3.Icon

/**
 * 房间状态条：常驻在顶部，显示房间名、身份、人数与连接状态，右侧是「退出」。
 * 点整条可以重新打开房间详情（二维码 / 房间码 / 成员）。
 */
@Composable
fun RoomBanner(
    role: RoomRole,
    status: RoomStatus,
    info: RoomInfo?,
    onOpenDetails: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (status) {
        is RoomStatus.Hosting -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        is RoomStatus.Connected -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        is RoomStatus.Reconnecting -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        is RoomStatus.Closed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(onClick = onOpenDetails)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_group),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(info?.roomName?.takeIf { it.isNotBlank() } ?: "房间")
                    append(" · ")
                    append(if (role == RoomRole.Host) "房主" else "成员")
                    val count = info?.members?.size ?: 0
                    if (count > 0) append(" · $count 人")
                },
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
            Text(
                text = statusText(status),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.85f),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onLeave)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "退出",
                style = MaterialTheme.typography.labelLarge,
                color = if (container == MaterialTheme.colorScheme.errorContainer) Color.Unspecified else content,
            )
        }
    }
}

private fun statusText(status: RoomStatus): String = when (status) {
    is RoomStatus.Idle -> "未连接"
    is RoomStatus.Hosting -> "等待其他人加入"
    is RoomStatus.Connecting -> "正在连接…"
    is RoomStatus.Connected -> "已连接"
    is RoomStatus.Reconnecting -> "正在重连（第 ${status.attempt} 次）：${status.reason}"
    is RoomStatus.Closed -> status.reason
}
