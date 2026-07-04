package icu.wuhui.voxlink.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.net.URI

// 项目链接
private val LINK_BILIBILI = "https://space.bilibili.com/526277131"
private val LINK_WEBSITE = "https://p2p.wuhui.icu/"
private val LINK_MCMOD = "https://www.mcmod.cn/class/28295.html"
private val MODRINTH = "https://modrinth.com/mod/voxlink"
private val LINK_GITHUB = "https://github.com/AUGUHDAR/VoxLink"
private val LINK_CURSEFORGE = "https://www.curseforge.com/minecraft/mc-mods/voxlink"

// 打开浏览器, 失败提示
private fun openUrl(url: String, onError: (String) -> Unit) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            onError(Strings["about_open_failed"])
        }
    } catch (e: Exception) {
        onError(Strings["about_open_failed"])
    }
}

// 关于页面: 项目介绍 + 链接按钮
@Composable
fun AboutScreen(onBack: () -> Unit) {
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = Strings["about_title"],
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = Strings["about_desc"],
            fontSize = 13.sp,
            color = MaterialTheme.colors.onBackground,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        // 6个链接按钮, 2列3行
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinkButton(Strings["about_author_bilibili"], LINK_BILIBILI) { errorMsg = it }
            LinkButton(Strings["about_website"], LINK_WEBSITE) { errorMsg = it }
            LinkButton(Strings["about_mcmod"], LINK_MCMOD) { errorMsg = it }
            LinkButton(Strings["about_modrinth"], MODRINTH) { errorMsg = it }
            LinkButton(Strings["about_github"], LINK_GITHUB) { errorMsg = it }
            LinkButton(Strings["about_curseforge"], LINK_CURSEFORGE) { errorMsg = it }
        }
        errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.width(180.dp).height(44.dp)
        ) {
            Text(Strings["back"], fontSize = 14.sp)
        }
    }
}

@Composable
private fun LinkButton(label: String, url: String, onError: (String) -> Unit) {
    Button(
        onClick = { openUrl(url, onError) },
        modifier = Modifier.width(260.dp).height(40.dp)
    ) {
        Text(label, fontSize = 13.sp)
    }
}
