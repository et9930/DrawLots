package com.drawlots.app.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.drawlots.app.R
import com.drawlots.app.room.RoomCode
import com.drawlots.app.room.RoomInfo

/**
 * 房主侧的房间大厅：二维码 + 房间码 + 成员列表 + 结束房间。
 *
 * 加入方扫这个二维码（或手输房间码）就能进来。
 */
@Composable
fun RoomLobby(
    info: RoomInfo,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    onShareCode: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = info.roomName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${info.kind.label}房间 · 房主：${info.hostName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                val invite = info.invite
                if (invite != null) {
                    QrCodeImage(
                        content = RoomCode.encodeInvite(invite),
                        size = 216.dp,
                    )
                } else {
                    Text(
                        text = "二维码不可用，请让其他人手输房间码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "房间码",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = info.roomCode,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { onShareCode(info.roomCode) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("把房间码告诉别人")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text(
                    text = "已加入（${info.members.size}）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    info.members.forEachIndexed { index, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (index == 0) "👑" else "•",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onLeave) {
                        Text("结束房间", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) { Text("收起") }
                }
            }
        }
    }
}
