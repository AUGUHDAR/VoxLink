package icu.wuhui.voxlink.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 主页: 创建/加入/关于三个按钮, 简洁居中
@Composable
fun HomeScreen(
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onAbout: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = Strings["app_title"],
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCreate,
                modifier = Modifier.width(240.dp).height(56.dp)
            ) {
                Text(Strings["create_room"], fontSize = 18.sp)
            }
            OutlinedButton(
                onClick = onJoin,
                modifier = Modifier.width(240.dp).height(56.dp)
            ) {
                Text(Strings["join_room"], fontSize = 18.sp)
            }
            OutlinedButton(
                onClick = onAbout,
                modifier = Modifier.width(240.dp).height(40.dp)
            ) {
                Text(Strings["about"], fontSize = 13.sp)
            }
        }
    }
}
