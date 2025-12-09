package com.clipboardsync

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivityV2 : ComponentActivity() {
    
    private var statusState = mutableStateOf("")
    private var statusColorState = mutableStateOf(Color.Gray)
    private var isConnectedState = mutableStateOf(false)
    private var isConnectingState = mutableStateOf(false)
    
    private val handler = Handler(Looper.getMainLooper())
    private val statusChecker = object : Runnable {
        override fun run() {
            // 读取 Service 的静态状态
            val status = ClipboardService.currentStatus
            val message = ClipboardService.statusMessage
            
            when (status) {
                0 -> { // 未连接/断开
                    if (message.isNotEmpty()) {
                        statusState.value = message
                        statusColorState.value = Color.Red
                    }
                    isConnectedState.value = false  // 按钮恢复成"连接"
                    isConnectingState.value = false
                }
                1 -> { // 连接中
                    statusState.value = message
                    statusColorState.value = Color.Gray
                    isConnectingState.value = true
                }
                2 -> { // 已连接
                    statusState.value = message
                    statusColorState.value = Color(0xFF4CAF50)
                    isConnectedState.value = true
                    isConnectingState.value = false
                }
                3 -> { // 错误
                    statusState.value = message
                    statusColorState.value = Color.Red
                    isConnectedState.value = false
                    isConnectingState.value = false
                }
            }
            
            // 每秒检查一次
            handler.postDelayed(this, 1000)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("clipboard_sync", MODE_PRIVATE)
        val savedPairCode = prefs.getString("pair_code", null) ?: ""
        
        setContent {
            MaterialTheme {
                var pairCode by remember { mutableStateOf(savedPairCode) }
                val status by statusState
                val statusColor by statusColorState
                val isConnecting by isConnectingState
                val isConnected by isConnectedState
                
                MainScreen(
                    pairCode = pairCode,
                    onPairCodeChange = { pairCode = it },
                    status = status,
                    statusColor = statusColor,
                    isConnecting = isConnecting,
                    isConnected = isConnected,
                    onConnect = {
                        if (pairCode.length >= 4) {
                            isConnectingState.value = true
                            statusState.value = "正在搜索设备..."
                            statusColorState.value = Color.Gray
                            
                            prefs.edit().putString("pair_code", pairCode).apply()
                            
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                val serverInfo = ServerDiscovery.discoverServer(this@MainActivityV2)
                                
                                withContext(Dispatchers.Main) {
                                    if (serverInfo != null) {
                                        val (host, _) = serverInfo
                                        statusState.value = "正在连接..."
                                        
                                        val intent = Intent(this@MainActivityV2, ClipboardService::class.java).apply {
                                            putExtra("serverHost", host)
                                            putExtra("pairCode", pairCode)
                                        }
                                        startForegroundService(intent)
                                        isConnectedState.value = true
                                    } else {
                                        statusState.value = "未找到设备，请确保在同一WiFi"
                                        statusColorState.value = Color.Red
                                        isConnectingState.value = false
                                    }
                                }
                            }
                        }
                    },
                    onDisconnect = {
                        stopService(Intent(this@MainActivityV2, ClipboardService::class.java))
                        ClipboardService.currentStatus = 0
                        ClipboardService.statusMessage = ""
                        prefs.edit().remove("pair_code").apply()
                        isConnectedState.value = false
                        isConnectingState.value = false
                        statusState.value = "已断开"
                        statusColorState.value = Color.Gray
                    }
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        handler.post(statusChecker)
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusChecker)
    }
}

@Composable
fun MainScreen(
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    status: String,
    statusColor: Color,
    isConnecting: Boolean,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "📋 ClipboardSync",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            OutlinedTextField(
                value = pairCode,
                onValueChange = onPairCodeChange,
                label = { Text("配对码") },
                placeholder = { Text("输入配对码") },
                singleLine = true,
                enabled = !isConnected && !isConnecting,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("断开连接", fontSize = 18.sp)
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = pairCode.length >= 4 && !isConnecting
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    } else {
                        Text("连接", fontSize = 18.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    fontSize = 16.sp,
                    color = statusColor
                )
            }
        }
    }
}
