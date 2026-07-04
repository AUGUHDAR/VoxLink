package icu.wuhui.voxlink.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.wuhui.voxlink.app.shared.session.JoinRoomViewModel

// 加入房间页: 房间码输入 + 连接 + 状态显示
@Composable
fun JoinRoomScreen(
    onConnected: (Int) -> Unit,
    onBack: () -> Unit
) {
    val viewModel = remember { JoinRoomViewModel() }
    val status by viewModel.status.collectAsState()
    val localPort by viewModel.localPort.collectAsState()
    val error by viewModel.error.collectAsState()

    var code by remember { mutableStateOf("") }

    // 连接成功跳成功页
    LaunchedEffect(status) {
        if (status == JoinRoomViewModel.JoinStatus.CONNECTED) {
            onConnected(localPort)
        }
    }

    val connecting = status == JoinRoomViewModel.JoinStatus.JOINING ||
            status == JoinRoomViewModel.JoinStatus.PUNCHING

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (status == JoinRoomViewModel.JoinStatus.IDLE ||
                    status == JoinRoomViewModel.JoinStatus.FAILED
                ) {
                    onBack()
                } else {
                    viewModel.disconnect()
                    onBack()
                }
            }) { Text(Strings["back"]) }
            Spacer(Modifier.width(16.dp))
            Text(
                text = Strings["join_room"],
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = Strings["enter_room_code"],
            fontSize = 16.sp,
            color = MaterialTheme.colors.onBackground
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.trim().take(32) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { viewModel.joinRoom(code) },
            enabled = code.isNotBlank() && !connecting,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colors.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(20.dp).width(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(Strings["connecting"], fontSize = 16.sp)
                }
            } else {
                Text(Strings["connect"], fontSize = 16.sp)
            }
        }

        if (status == JoinRoomViewModel.JoinStatus.FAILED) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = Strings["join_failed"],
                fontSize = 16.sp,
                color = MaterialTheme.colors.error
            )
            error?.let {
                Text(
                    text = Strings[it],
                    fontSize = 13.sp,
                    color = MaterialTheme.colors.error
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    code = ""
                    viewModel.disconnect()
                },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text(Strings["retry"])
            }
        }
    }
}
