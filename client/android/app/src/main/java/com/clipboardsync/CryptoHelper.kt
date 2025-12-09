package com.clipboardsync

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private var key: ByteArray? = null
    
    // 从配对码生成 AES-256 密钥
    fun setKey(pairCode: String) {
        val md = MessageDigest.getInstance("SHA-256")
        key = md.digest((pairCode + "ClipboardSync").toByteArray())
    }
    
    // 加密数据: 返回 [IV 16字节][加密数据]
    fun encrypt(data: ByteArray): ByteArray {
        val k = key ?: return data
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(k, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        
        // IV + 加密数据
        return iv + encrypted
    }
    
    // 解密数据: 输入 [IV 16字节][加密数据]
    fun decrypt(data: ByteArray): ByteArray {
        val k = key ?: return data
        if (data.size < 17) return data
        
        return try {
            val iv = data.copyOfRange(0, 16)
            val encrypted = data.copyOfRange(16, data.size)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(k, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            data // 解密失败返回原数据
        }
    }
}
