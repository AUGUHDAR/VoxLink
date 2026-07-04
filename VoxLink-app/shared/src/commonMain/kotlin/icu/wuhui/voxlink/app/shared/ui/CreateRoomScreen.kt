package icu.wuhui.voxlink.app.shared.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.wuhui.voxlink.app.shared.port.PortScanResult
import icu.wuhui.voxlink.app.shared.port.PortScanner
import icu.wuhui.voxlink.app.shared.session.CreateRoomViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 创建房间页: 步骤引导 + 端口扫描 + 创建
@Composable
fun CreateRoomScreen(
    portScanner: PortScanner?,
    onCreated: () -> Unit,
    onBack: () -> Unit
) {
    val viewModel = remember { CreateRoomViewModel() }
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()

    var step by remember { mutableStateOf(1) }
    var baseline by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var scanResult by remember { mutableStateOf<PortScanResult?>(null) }
    var selectedPort by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 入场采集基线端口
    LaunchedEffect(Unit) {
        if (portScanner != null) {
            baseline = withContext(Dispatchers.Default) {
                portScanner.scanJavaListeningPorts()
            }
        }
    }

    // 创建成功跳房间码页
    LaunchedEffect(status) {
        if (status == CreateRoomViewModel.CreateStatus.WAITING) {
            onCreated()
        }
    }

    val creating = status == CreateRoomViewModel.CreateStatus.CREATING

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(Strings["back"]) }
            Spacer(Modifier.width(16.dp))
            Text(
                text = Strings["create_room"],
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
        }

        when (step) {
            1 -> Step1Guide(
                scanning = scanning,
                onScan = {
                    if (portScanner == null) {
                        scanFailed = true
                        step = 2
                        return@Step1Guide
                    }
                    scanning = true
                    scanFailed = false
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching { portScanner.diffNewPorts(baseline) }
                                .getOrNull()
                        }
                        scanResult = result
                        if (result == null) {
                            scanFailed = true
                        } else {
                            selectedPort = result.uniqueNew?.toString() ?: ""
                        }
                        scanning = false
                        step = 2
                    }
                }
            )

            2 -> Step2PortSelect(
                scanResult = scanResult,
                scanFailed = scanFailed,
                selectedPort = selectedPort,
                onPortSelected = { selectedPort = it },
                manualPort = manualPort,
                onManualPortChange = { manualPort = it.filter { c -> c.isDigit() } },
                creating = creating,
                status = status,
                error = error,
                onCreate = {
                    val port = (selectedPort.ifBlank { manualPort }).toIntOrNull()
                    if (port != null && port in 1..65535) {
                        viewModel.createRoom("VoxLink", port)
                    }
                },
                onRetry = {
                    step = 1
                    scanResult = null
                    scanFailed = false
                    selectedPort = ""
                    manualPort = ""
                }
            )
        }
    }
}

@Composable
private fun Step1Guide(
    scanning: Boolean,
    onScan: () -> Unit
) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = Strings["create_room_step1"],
        fontSize = 16.sp,
        color = MaterialTheme.colors.onBackground
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onScan,
        enabled = !scanning,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (scanning) {
            CircularProgressIndicator(
                color = MaterialTheme.colors.onPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.height(20.dp).width(20.dp)
            )
        } else {
            Text(Strings["scan_ports"], fontSize = 16.sp)
        }
    }
}

@Composable
private fun Step2PortSelect(
    scanResult: PortScanResult?,
    scanFailed: Boolean,
    selectedPort: String,
    onPortSelected: (String) -> Unit,
    manualPort: String,
    onManualPortChange: (String) -> Unit,
    creating: Boolean,
    status: CreateRoomViewModel.CreateStatus,
    error: String?,
    onCreate: () -> Unit,
    onRetry: () -> Unit
) {
    Text(
        text = Strings["create_room_step2"],
        fontSize = 16.sp,
        color = MaterialTheme.colors.onBackground
    )

    val result = scanResult
    if (result != null && result.hasNew) {
        if (result.uniqueNew != null) {
            // 唯一新增端口高亮
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colors.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${Strings["detected_port"]}: ${result.uniqueNew}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface
                    )
                }
            }
        } else {
            // 多个端口下拉选择
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(
                        if (selectedPort.isEmpty()) Strings["select_port"]
                        else "${Strings["port_label"]}: $selectedPort"
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    result.newPorts.forEach { port ->
                        DropdownMenuItem(onClick = {
                            onPortSelected(port.toString())
                            expanded = false
                        }) {
                            Text(port.toString())
                        }
                    }
                }
            }
        }
    } else {
        // 无新增或扫描失败 → 手动输入
        if (scanFailed) {
            Text(
                text = Strings["scan_failed"],
                fontSize = 14.sp,
                color = MaterialTheme.colors.error
            )
        }
        Text(
            text = Strings["manual_input_port"],
            fontSize = 16.sp,
            color = MaterialTheme.colors.onBackground
        )
        OutlinedTextField(
            value = manualPort,
            onValueChange = onManualPortChange,
            label = { Text(Strings["manual_input_hint"]) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(16.dp))

    val portReady = selectedPort.isNotBlank() || manualPort.isNotBlank()
    Button(
        onClick = onCreate,
        enabled = portReady && !creating,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(
            text = if (creating) Strings["creating"] else Strings["create"],
            fontSize = 16.sp
        )
    }

    if (status == CreateRoomViewModel.CreateStatus.FAILED) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = Strings["create_failed"],
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
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Text(Strings["retry"])
        }
    }
}
