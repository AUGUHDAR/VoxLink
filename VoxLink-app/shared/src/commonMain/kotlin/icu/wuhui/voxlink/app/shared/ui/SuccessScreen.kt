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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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

// 成功页: 连接成功 + localhost:port 大字可复制 + 复制地址 + 关闭
@Composable
fun SuccessScreen(
    localPort: Int,
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val address = "localhost:$localPort"

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = Strings["connection_success"],
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onBackground
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = Strings["mc_direct_connect"],
            fontSize = 14.sp,
            color = MaterialTheme.colors.secondary
        )

        Spacer(Modifier.height(16.dp))

        // 关键地址, 大字体可点击复制
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colors.surface,
            modifier = Modifier.fillMaxWidth().clickable {
                clipboard.setText(AnnotatedString(address))
                copied = true
            }
        ) {
            Box(
                modifier = Modifier.padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = address,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (copied) Strings["copied"] else Strings["enter_address"],
            fontSize = 13.sp,
            color = MaterialTheme.colors.secondary
        )

        Spacer(Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                },
                modifier = Modifier.width(140.dp).height(48.dp)
            ) {
                Text(Strings["copy_address"])
            }
            Button(
                onClick = onClose,
                modifier = Modifier.width(140.dp).height(48.dp)
            ) {
                Text(Strings["close"])
            }
        }
    }
}
