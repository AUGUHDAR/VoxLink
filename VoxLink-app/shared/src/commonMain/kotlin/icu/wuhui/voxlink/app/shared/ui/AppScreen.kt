package icu.wuhui.voxlink.app.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import icu.wuhui.voxlink.app.shared.port.PortScanner
import icu.wuhui.voxlink.app.shared.session.AppSession

// 屏幕路由, sealed class 表示所有页面
sealed class AppScreen {
    object Home : AppScreen()
    object Create : AppScreen()
    object RoomCode : AppScreen()
    object Join : AppScreen()
    object Success : AppScreen()
    object About : AppScreen()
}

// 黑白灰深色主题, 无紫色
private val VoxLinkColors = darkColors(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF111111),
    secondary = Color(0xFF9E9E9E),
    onSecondary = Color(0xFF111111),
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF262626),
    onSurface = Color(0xFFE8E8E8),
    error = Color(0xFFE57373),
    onError = Color(0xFF111111)
)

// 主入口, 持有当前屏幕状态, 注入平台相关 PortScanner
@Composable
fun AppScreen(portScanner: PortScanner?) {
    MaterialTheme(colors = VoxLinkColors) {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)
        ) {
            var current by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
            var successPort by remember { mutableStateOf(0) }

            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部语言选择器, 左上角, 所有页面可见
                LanguageSelector()

                when (current) {
                    AppScreen.Home -> HomeScreen(
                        onCreate = { current = AppScreen.Create },
                        onJoin = { current = AppScreen.Join },
                        onAbout = { current = AppScreen.About }
                    )

                    AppScreen.Create -> CreateRoomScreen(
                        portScanner = portScanner,
                        onCreated = { current = AppScreen.RoomCode },
                        onBack = { current = AppScreen.Home }
                    )

                    AppScreen.RoomCode -> RoomCodeScreen(
                        onConnected = { port ->
                            successPort = port
                            current = AppScreen.Success
                        },
                        onClose = {
                            AppSession.leave()
                            current = AppScreen.Home
                        }
                    )

                    AppScreen.Join -> JoinRoomScreen(
                        onConnected = { port ->
                            successPort = port
                            current = AppScreen.Success
                        },
                        onBack = { current = AppScreen.Home }
                    )

                    AppScreen.Success -> SuccessScreen(
                        localPort = successPort,
                        onClose = {
                            AppSession.leave()
                            current = AppScreen.Home
                        }
                    )

                    AppScreen.About -> AboutScreen(
                        onBack = { current = AppScreen.Home }
                    )
                }
            }
        }
    }
}

// 语言选择器, 下拉切换中英文, 切换后整个 UI 重组
@Composable
private fun LanguageSelector() {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(8.dp)) {
        TextButton(onClick = { expanded = true }) {
            // 显示当前语言名, 文本走 Strings key 不硬编码
            Text(Strings["language_" + Strings.currentLanguage])
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(onClick = {
                Strings.setLanguage("zh")
                expanded = false
            }) {
                Text(Strings["language_zh"])
            }
            DropdownMenuItem(onClick = {
                Strings.setLanguage("en")
                expanded = false
            }) {
                Text(Strings["language_en"])
            }
        }
    }
}
