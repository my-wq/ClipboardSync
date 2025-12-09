using System;
using System.Security.Cryptography;
using System.Text;

namespace ClipboardSync
{
    public static class CryptoHelper
    {
        private static byte[]? _key;
        
        // 从配对码生成 AES-256 密钥
        public static void SetKey(string pairCode)
        {
            using var sha256 = SHA256.Create();
            _key = sha256.ComputeHash(Encoding.UTF8.GetBytes(pairCode + "ClipboardSync"));
        }
        
        // 加密数据: 返回 [IV 16字节][加密数据]
        public static byte[] Encrypt(byte[] data)
        {
            if (_key == null) return data;
            
            using var aes = Aes.Create();
            aes.Key = _key;
            aes.GenerateIV();
            
            using var encryptor = aes.CreateEncryptor();
            var encrypted = encryptor.TransformFinalBlock(data, 0, data.Length);
            
            // IV + 加密数据
            var result = new byte[16 + encrypted.Length];
            Array.Copy(aes.IV, 0, result, 0, 16);
            Array.Copy(encrypted, 0, result, 16, encrypted.Length);
            
            return result;
        }
        
        // 解密数据: 输入 [IV 16字节][加密数据]
        public static byte[] Decrypt(byte[] data)
        {
            if (_key == null || data.Length < 17) return data;
            
            try
            {
                using var aes = Aes.Create();
                aes.Key = _key;
                
                // 提取 IV
                var iv = new byte[16];
                Array.Copy(data, 0, iv, 0, 16);
                aes.IV = iv;
                
                // 解密
                using var decryptor = aes.CreateDecryptor();
                return decryptor.TransformFinalBlock(data, 16, data.Length - 16);
            }
            catch
            {
                return data; // 解密失败返回原数据
            }
        }
    }
}
