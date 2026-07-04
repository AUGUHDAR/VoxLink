package icu.wuhui.voxlink.app.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.wuhui.voxlink.app.shared.session.AppSession

// 房间码展示页: 大字房间码可复制 + 等待状态 + 复制/关闭
@Composable
fun RoomCodeScreen(
    onConnected: (Int) -> Unit,
    onClose: () -> Unit
) {
    val roomCode by AppSession.roomCode.collectAsState()
    val connectionState by AppSession.connectionState.collectAsState()
    val localPort by AppSession.localPort.collectAsState()
    val error by AppSession.error.collectAsState()

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // P2P连接建立后跳成功页
    LaunchedEffect(connectionState) {
        if (connectionState == AppSession.ConnectionState.CONNECTED) {
            onConnected(localPort)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = Strings["room_created"],
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onBackground
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = Strings["room_code_label"],
            fontSize = 14.sp,
            color = MaterialTheme.colors.secondary
        )

        Spacer(Modifier.height(12.dp))

        // 房间码大字可点击复制
        val code = roomCode ?: "------"
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colors.surface,
            modifier = Modifier.fillMaxWidth().clickable {
                roomCode?.let {
                    clipboard.setText(AnnotatedString(it))
                    copied = true
                }
            }
        ) {
            Box(
                modifier = Modifier.padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = code,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = Strings["share_room_code"],
            fontSize = 13.sp,
            color = MaterialTheme.colors.secondary
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (copied) Strings["copied"] else "",
            fontSize = 13.sp,
            color = MaterialTheme.colors.secondary
        )

        Spacer(Modifier.height(32.dp))

        // 等待对方加入状态
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connectionState == AppSession.ConnectionState.WAITING_PEER ||
                connectionState == AppSession.ConnectionState.PUNCHING
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colors.secondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp).width(18.dp)
                )
                Text(
                    text = Strings["waiting_peer"],
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.secondary
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = Strings[it],
                fontSize = 13.sp,
                color = MaterialTheme.colors.error
            )
        }

        Spacer(Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    roomCode?.let {
                        clipboard.setText(AnnotatedString(it))
                        copied = true
                    }
                },
                modifier = Modifier.width(140.dp).height(48.dp)
            ) {
                Text(Strings["copy_room_code"])
            }
            Button(
                onClick = onClose,
                modifier = Modifier.width(140.dp).height(48.dp)
            ) {
                Text(Strings["close_room"])
            }
        }
    }
}
