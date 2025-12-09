package com.clipboardsync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ServerDiscovery {
    
    companion object {
        fun discoverServer(context: Context): Pair<String, Int>? {
            try {
                Log.d("ServerDiscovery", "Starting parallel server discovery...")
                
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val dhcpInfo = wifiManager.dhcpInfo
                val localIp = dhcpInfo.ipAddress
                
                val localIpStr = intToIp(localIp)
                Log.d("ServerDiscovery", "Local IP: $localIpStr")
                
                val ipParts = localIpStr.split(".")
                if (ipParts.size != 4) return null
                
                val networkPrefix = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
                Log.d("ServerDiscovery", "Scanning network: $networkPrefix.x (parallel)")
                
                val result = AtomicReference<String?>(null)
                val latch = CountDownLatch(254)
                
                // 并行扫描所有IP
                for (host in 1..254) {
                    val testIp = "$networkPrefix.$host"
                    if (testIp == localIpStr) {
                        latch.countDown()
                        continue
                    }
                    
                    Thread {
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(testIp, 58090), 1000)
                            socket.close()
                            result.compareAndSet(null, testIp)
                            Log.d("ServerDiscovery", "Server found at $testIp:58090")
                        } catch (e: Exception) {
                            // 连接失败
                        } finally {
                            latch.countDown()
                        }
                    }.start()
                }
                
                // 等待最多5秒或找到服务器
                latch.await(5, TimeUnit.SECONDS)
                
                val foundIp = result.get()
                if (foundIp != null) {
                    return Pair(foundIp, 58090)
                }
                
                Log.d("ServerDiscovery", "No server found")
                
            } catch (e: Exception) {
                Log.e("ServerDiscovery", "Discovery failed", e)
            }
            
            return null
        }
        
        private fun intToIp(ip: Int): String {
            return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
        }
        
        // 获取配对码
        fun getPairCode(host: String): String? {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, 58090), 5000)
                socket.soTimeout = 5000
                
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                
                // 发送 OpGetPairCode
                output.write(0x06)
                output.flush()
                
                // 读取响应
                val op = input.read()
                if (op == 0x07) { // OpPairCode
                    val len = input.read()
                    val codeBytes = ByteArray(len)
                    input.read(codeBytes)
                    socket.close()
                    return String(codeBytes)
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e("ServerDiscovery", "Get pair code failed", e)
            }
            return null
        }
        
        // 刷新配对码
        fun refreshPairCode(host: String): String? {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, 58090), 5000)
                socket.soTimeout = 5000
                
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                
                // 发送 OpRefreshCode
                output.write(0x09)
                output.flush()
                
                // 读取响应
                val op = input.read()
                if (op == 0x07) { // OpPairCode
                    val len = input.read()
                    val codeBytes = ByteArray(len)
                    input.read(codeBytes)
                    socket.close()
                    return String(codeBytes)
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e("ServerDiscovery", "Refresh pair code failed", e)
            }
            return null
        }
    }
}
