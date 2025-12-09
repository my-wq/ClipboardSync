package com.clipboardsync

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

// 图片格式常量
object ImageFormat {
    const val PNG: Byte = 0x01
    const val JPEG: Byte = 0x02
    const val BMP: Byte = 0x03
    const val GIF: Byte = 0x04
}

class ClipboardService : Service() {
    
    companion object {
        // 静态状态，Activity 可以直接读取
        @Volatile var currentStatus = 0  // 0=未连接, 1=连接中, 2=已连接, 3=错误
        @Volatile var statusMessage = ""
    }
    
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var clipboardManager: ClipboardManager? = null
    private var isRunning = false
    private var ignoreNextChange = false
    private var serverHost: String = ""
    private var pairCode: String = ""
    private var shouldReconnect = true
    private var heartbeatThread: Thread? = null
    private var lastImageTime: Long = 0
    private var lastImageSize: Int = 0
    
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (ignoreNextChange) {
            ignoreNextChange = false
            return@OnPrimaryClipChangedListener
        }
        
        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            
            // 检查是否是图片
            val uri = item.uri
            if (uri != null && isImageUri(uri)) {
                // 防止1秒内重复发送
                val now = System.currentTimeMillis()
                if (now - lastImageTime < 1000) {
                    return@OnPrimaryClipChangedListener
                }
                lastImageTime = now
                
                thread {
                    val pngData = loadImageAsPng(uri)
                    if (pngData != null) {
                        // 检查是否和上次相同大小（简单防重复）
                        if (pngData.size == lastImageSize) {
                            return@thread
                        }
                        lastImageSize = pngData.size
                        sendImage(pngData)
                    }
                }
                return@OnPrimaryClipChangedListener
            }
            
            // 检查文本
            val text = item.text?.toString()
            if (!text.isNullOrEmpty() && text != lastClipboardText) {
                lastClipboardText = text
                thread {
                    sendText(text)
                }
            }
        }
    }
    
    private fun isImageUri(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri)
        return mimeType?.startsWith("image/") == true
    }
    
    private fun loadImageAsPng(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.e("ClipboardService", "Load image error", e)
            null
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.serverHost = intent?.getStringExtra("serverHost") ?: "127.0.0.1"
        this.pairCode = intent?.getStringExtra("pairCode") ?: return START_NOT_STICKY
        
        shouldReconnect = true
        startForeground(1, createNotification("连接中..."))
        
        thread {
            connectAndAuth()
        }
        
        return START_STICKY
    }
    
    private var isAuthenticated = false
    
    private fun connectAndAuth() {
        while (shouldReconnect) {
            try {
                isAuthenticated = false
                updateNotification("连接中...")
                setStatus(1, "正在连接...")
                
                Log.d("ClipboardService", "Connecting to $serverHost:58090...")
                socket = Socket(serverHost, 58090)
                socket?.keepAlive = true
                socket?.soTimeout = 15000 // 15 second timeout for faster disconnect detection
                
                Log.d("ClipboardService", "TCP connected, sending auth...")
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()
                
                // Send pair code auth
                val pairCodeBytes = pairCode.toByteArray()
                val packet = ByteArray(2 + pairCodeBytes.size)
                packet[0] = 0x01.toByte() // OpLogin
                packet[1] = pairCodeBytes.size.toByte()
                System.arraycopy(pairCodeBytes, 0, packet, 2, pairCodeBytes.size)
                
                outputStream?.write(packet)
                outputStream?.flush()
                
                isRunning = true
                
                // Start receive loop (will handle auth response)
                receiveLoop()
                
            } catch (e: Exception) {
                Log.e("ClipboardService", "Connection error: ${e.message}", e)
                isAuthenticated = false
                updateNotification("连接失败，5秒后重连...")
                
                // Clean up
                stopHeartbeat()
                isRunning = false
                try {
                    socket?.close()
                } catch (ex: Exception) {}
                socket = null
                outputStream = null
                inputStream = null
                
                // Wait before reconnecting
                if (shouldReconnect) {
                    Thread.sleep(5000)
                }
            }
        }
    }
    
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatThread = thread {
            try {
                while (isRunning && shouldReconnect) {
                    Thread.sleep(5000) // Send heartbeat every 5 seconds
                    try {
                        outputStream?.write(byteArrayOf(0x04))
                        outputStream?.flush()
                        Log.d("ClipboardService", "Heartbeat sent")
                    } catch (e: Exception) {
                        Log.e("ClipboardService", "Heartbeat failed, disconnecting", e)
                        // 心跳失败，关闭 socket 触发 receiveLoop 退出
                        isRunning = false
                        try { socket?.close() } catch (ex: Exception) {}
                        break
                    }
                }
            } catch (e: InterruptedException) {
                Log.d("ClipboardService", "Heartbeat thread interrupted")
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }
    
    private var clipboardPollingThread: Thread? = null
    private var lastClipboardText: String = ""
    
    private fun startClipboardMonitoring() {
        // 注册剪贴板监听器（需要LSPosed模块才能在后台工作）
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        Log.d("ClipboardService", "Clipboard listener registered")
    }
    

    
    private fun receiveLoop() {
        val buffer = ByteArray(4096)
        
        try {
            while (isRunning) {
                val op = inputStream?.read() ?: break
                
                when (op) {
                    0x05 -> { // Response
                        val success = inputStream?.read() ?: 0
                        val msgLen = inputStream?.read() ?: 0
                        val msgBytes = ByteArray(msgLen)
                        inputStream?.read(msgBytes)
                        
                        if (success == 1) {
                            Log.d("ClipboardService", "Login successful!")
                            isAuthenticated = true
                            // 设置加密密钥
                            CryptoHelper.setKey(pairCode)
                            updateNotification("已连接 - 监控中")
                            setStatus(2, "已连接")
                            // 启动心跳
                            startHeartbeat()
                            // 启动剪贴板监控
                            startClipboardMonitoring()
                        } else {
                            val msg = String(msgBytes)
                            Log.e("ClipboardService", "Login failed: $msg")
                            isAuthenticated = false
                            updateNotification("配对码错误")
                            setStatus(3, "配对码错误")
                            // 配对码错误，停止重连
                            shouldReconnect = false
                            throw Exception("Auth failed: $msg")
                        }
                    }
                    
                    0x03 -> { // Text
                        val lenBytes = ByteArray(2)
                        inputStream?.read(lenBytes)
                        val length = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                        
                        val encryptedBytes = ByteArray(length)
                        inputStream?.read(encryptedBytes)
                        
                        // 解密
                        val textBytes = CryptoHelper.decrypt(encryptedBytes)
                        val text = String(textBytes)
                        
                        Log.d("ClipboardService", "Received text: $text")
                        
                        // 写入剪贴板
                        ignoreNextChange = true
                        lastClipboardText = text
                        val clip = ClipData.newPlainText("sync", text)
                        clipboardManager?.setPrimaryClip(clip)
                        Log.d("ClipboardService", "Text set to clipboard: $text")
                        
                        updateNotification("收到同步")
                    }
                    
                    0x0A -> { // Image
                        // 读取格式 (1字节)
                        inputStream?.read()
                        
                        // 读取长度 (4字节, big-endian)
                        val lenBytes = ByteArray(4)
                        inputStream?.read(lenBytes)
                        val length = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                                    ((lenBytes[1].toInt() and 0xFF) shl 16) or
                                    ((lenBytes[2].toInt() and 0xFF) shl 8) or
                                    (lenBytes[3].toInt() and 0xFF)
                        
                        // 读取加密图片数据
                        val encryptedData = ByteArray(length)
                        var totalRead = 0
                        while (totalRead < length) {
                            val read = inputStream?.read(encryptedData, totalRead, length - totalRead) ?: break
                            if (read <= 0) break
                            totalRead += read
                        }
                        
                        // 解密
                        val imageData = CryptoHelper.decrypt(encryptedData)
                        Log.d("ClipboardService", "Received image: ${imageData.size} bytes")
                        
                        // 保存图片到缓存并设置到剪贴板
                        saveImageToClipboard(imageData)
                        updateNotification("收到图片")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ClipboardService", "Receive error", e)
        } finally {
            // 连接断开时更新状态
            isAuthenticated = false
            isRunning = false
            stopHeartbeat()
            // 不自动重连，直接显示断开
            shouldReconnect = false
            updateNotification("连接中断")
            setStatus(0, "连接中断")
        }
    }
    
    private fun sendText(text: String) {
        try {
            Log.d("ClipboardService", "Sending text: $text")
            // 加密文本
            val textBytes = text.toByteArray()
            val encryptedBytes = CryptoHelper.encrypt(textBytes)
            
            val packet = ByteArray(3 + encryptedBytes.size)
            packet[0] = 0x03.toByte()
            packet[1] = (encryptedBytes.size shr 8).toByte()
            packet[2] = (encryptedBytes.size and 0xFF).toByte()
            System.arraycopy(encryptedBytes, 0, packet, 3, encryptedBytes.size)
            
            outputStream?.write(packet)
            outputStream?.flush()
            
            Log.d("ClipboardService", "Text sent successfully")
            updateNotification("已同步")
        } catch (e: Exception) {
            Log.e("ClipboardService", "Send error", e)
        }
    }
    
    private fun sendImage(pngData: ByteArray) {
        try {
            Log.d("ClipboardService", "Sending image: ${pngData.size} bytes")
            // 加密图片
            val encryptedData = CryptoHelper.encrypt(pngData)
            
            // [OpImage][格式1字节][长度4字节][加密数据]
            val packet = ByteArray(6 + encryptedData.size)
            packet[0] = 0x0A.toByte() // OpImage
            packet[1] = ImageFormat.PNG
            packet[2] = (encryptedData.size shr 24).toByte()
            packet[3] = (encryptedData.size shr 16).toByte()
            packet[4] = (encryptedData.size shr 8).toByte()
            packet[5] = (encryptedData.size and 0xFF).toByte()
            System.arraycopy(encryptedData, 0, packet, 6, encryptedData.size)
            
            outputStream?.write(packet)
            outputStream?.flush()
            
            Log.d("ClipboardService", "Image sent successfully")
            updateNotification("已同步图片")
        } catch (e: Exception) {
            Log.e("ClipboardService", "Send image error", e)
        }
    }
    
    private fun saveImageToClipboard(pngData: ByteArray) {
        try {
            // 保存到缓存目录
            val file = File(cacheDir, "clipboard_image.png")
            FileOutputStream(file).use { it.write(pngData) }
            
            // 设置 URI 到剪贴板
            ignoreNextChange = true
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val clip = ClipData.newUri(contentResolver, "image", uri)
            clipboardManager?.setPrimaryClip(clip)
            
            Log.d("ClipboardService", "Image set to clipboard via URI")
            updateNotification("收到图片")
        } catch (e: Exception) {
            Log.e("ClipboardService", "Save image error", e)
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "clipboard_sync",
            "剪贴板同步",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "clipboard_sync")
            .setContentTitle("ClipboardSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(text))
    }
    
    private fun setStatus(status: Int, message: String) {
        currentStatus = status
        statusMessage = message
        Log.d("ClipboardService", "Status: $status, $message")
    }
    
    override fun onDestroy() {
        shouldReconnect = false
        isRunning = false
        stopHeartbeat()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        try {
            socket?.close()
        } catch (e: Exception) {}
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
