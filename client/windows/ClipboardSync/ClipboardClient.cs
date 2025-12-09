using System;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace ClipboardSync
{
    public class ClipboardClient
    {
        private TcpClient? tcpClient;
        private NetworkStream? stream;
        private Thread? receiveThread;
        private bool isRunning;
        
        private string host;
        private int port;
        private string? pairCode;
        private bool isLoggedIn = false;
        
        public event Action? OnConnected;
        public event Action<string>? OnTextReceived;
        public event Action<byte[]>? OnImageReceived; // PNG data
        public event Action<string>? OnError;
        public event Action<string>? OnPairCodeReceived;
        public event Action<int>? OnClientCountChanged;
        
        private const byte OpLogin = 0x01;
        private const byte OpText = 0x03;
        private const byte OpHeartbeat = 0x04;
        private const byte OpResponse = 0x05;
        private const byte OpGetPairCode = 0x06;
        private const byte OpPairCode = 0x07;
        private const byte OpClientCount = 0x08;
        private const byte OpRefreshCode = 0x09;
        private const byte OpImage = 0x0A;
        
        // 图片格式 (统一使用PNG)
        public const byte ImageFormatPng = 0x01;
        
        public ClipboardClient(string host, int port)
        {
            this.host = host;
            this.port = port;
        }
        
        public void ConnectAndGetPairCode()
        {
            try
            {
                tcpClient = new TcpClient();
                tcpClient.Connect(host, port);
                stream = tcpClient.GetStream();
                
                // 请求配对码
                stream.WriteByte(OpGetPairCode);
                
                // Start receive thread
                isRunning = true;
                receiveThread = new Thread(ReceiveLoop);
                receiveThread.IsBackground = true;
                receiveThread.Start();
            }
            catch (Exception ex)
            {
                OnError?.Invoke($"连接失败: {ex.Message}");
            }
        }
        
        public void Connect(string pairCode)
        {
            try
            {
                tcpClient = new TcpClient();
                tcpClient.Connect(host, port);
                stream = tcpClient.GetStream();
                
                // Send pair code auth
                byte[] pairCodeBytes = Encoding.UTF8.GetBytes(pairCode);
                
                byte[] packet = new byte[2 + pairCodeBytes.Length];
                packet[0] = OpLogin;
                packet[1] = (byte)pairCodeBytes.Length;
                Array.Copy(pairCodeBytes, 0, packet, 2, pairCodeBytes.Length);
                
                stream.Write(packet, 0, packet.Length);
                
                // Start receive thread
                isRunning = true;
                receiveThread = new Thread(ReceiveLoop);
                receiveThread.IsBackground = true;
                receiveThread.Start();
            }
            catch (Exception ex)
            {
                OnError?.Invoke($"连接失败: {ex.Message}");
            }
        }
        
        private void ReceiveLoop()
        {
            try
            {
                while (isRunning && tcpClient != null && tcpClient.Connected)
                {
                    if (stream != null && stream.DataAvailable)
                    {
                        int op = stream.ReadByte();
                        
                        if (op == OpResponse)
                        {
                            int success = stream.ReadByte();
                            int msgLen = stream.ReadByte();
                            byte[] msgBytes = new byte[msgLen];
                            stream.Read(msgBytes, 0, msgLen);
                            
                            if (success == 1)
                            {
                                isLoggedIn = true;
                                OnConnected?.Invoke();
                            }
                            else
                            {
                                string msg = Encoding.UTF8.GetString(msgBytes);
                                OnError?.Invoke(msg);
                            }
                        }
                        else if (op == OpText)
                        {
                            byte[] lenBytes = new byte[2];
                            stream.Read(lenBytes, 0, 2);
                            int length = (lenBytes[0] << 8) | lenBytes[1];
                            
                            byte[] encryptedBytes = new byte[length];
                            stream.Read(encryptedBytes, 0, length);
                            
                            // 解密
                            byte[] textBytes = CryptoHelper.Decrypt(encryptedBytes);
                            string text = Encoding.UTF8.GetString(textBytes);
                            
                            OnTextReceived?.Invoke(text);
                        }
                        else if (op == OpHeartbeat)
                        {
                            // Heartbeat ack
                        }
                        else if (op == OpPairCode)
                        {
                            int len = stream.ReadByte();
                            byte[] codeBytes = new byte[len];
                            stream.Read(codeBytes, 0, len);
                            pairCode = Encoding.UTF8.GetString(codeBytes);
                            
                            OnPairCodeReceived?.Invoke(pairCode);
                            
                            // 设置加密密钥
                            CryptoHelper.SetKey(pairCode);
                            
                            // 只有首次连接时才自动登录，刷新配对码时不需要
                            if (!isLoggedIn)
                            {
                                byte[] pairCodeBytes = Encoding.UTF8.GetBytes(pairCode);
                                byte[] packet = new byte[2 + pairCodeBytes.Length];
                                packet[0] = OpLogin;
                                packet[1] = (byte)pairCodeBytes.Length;
                                Array.Copy(pairCodeBytes, 0, packet, 2, pairCodeBytes.Length);
                                stream.Write(packet, 0, packet.Length);
                            }
                        }
                        else if (op == OpClientCount)
                        {
                            int count = stream.ReadByte();
                            OnClientCountChanged?.Invoke(count);
                        }
                        else if (op == OpImage)
                        {
                            // 读取格式 (1字节, 统一PNG)
                            stream.ReadByte();
                            
                            // 读取长度 (4字节, big-endian)
                            byte[] lenBytes = new byte[4];
                            stream.Read(lenBytes, 0, 4);
                            int length = (lenBytes[0] << 24) | (lenBytes[1] << 16) | (lenBytes[2] << 8) | lenBytes[3];
                            
                            // 读取加密图片数据
                            byte[] encryptedData = new byte[length];
                            int totalRead = 0;
                            while (totalRead < length)
                            {
                                int read = stream.Read(encryptedData, totalRead, length - totalRead);
                                if (read <= 0) break;
                                totalRead += read;
                            }
                            
                            // 解密
                            byte[] imageData = CryptoHelper.Decrypt(encryptedData);
                            OnImageReceived?.Invoke(imageData);
                        }
                    }
                    
                    Thread.Sleep(100);
                }
            }
            catch (Exception ex)
            {
                OnError?.Invoke($"接收错误: {ex.Message}");
            }
        }
        
        public void SendText(string text)
        {
            if (tcpClient == null || !tcpClient.Connected || stream == null) return;
            
            try
            {
                // 加密文本
                byte[] textBytes = Encoding.UTF8.GetBytes(text);
                byte[] encryptedBytes = CryptoHelper.Encrypt(textBytes);
                
                byte[] packet = new byte[3 + encryptedBytes.Length];
                packet[0] = OpText;
                packet[1] = (byte)(encryptedBytes.Length >> 8);
                packet[2] = (byte)(encryptedBytes.Length & 0xFF);
                Array.Copy(encryptedBytes, 0, packet, 3, encryptedBytes.Length);
                
                stream.Write(packet, 0, packet.Length);
            }
            catch (Exception ex)
            {
                OnError?.Invoke($"发送失败: {ex.Message}");
            }
        }
        
        public void RefreshPairCode()
        {
            if (tcpClient == null || !tcpClient.Connected || stream == null) return;
            
            try
            {
                stream.WriteByte(OpRefreshCode);
            }
            catch (Exception ex)
            {
                OnError?.Invoke($"刷新失败: {ex.Message}");
            }
        }
        
        public void SendImage(byte[] pngData)
        {
            if (tcpClient == null || !tcpClient.Connected || stream == null) return;
            
            try
            {
                // 加密图片
                byte[] encryptedData = CryptoHelper.Encrypt(pngData);
                
                // [OpImage][格式1字节][长度4字节][加密数据]
                byte[] packet = new byte[6 + encryptedData.Length];
                packet[0] = OpImage;
                packet[1] = ImageFormatPng;
                packet[2] = (byte)(encryptedData.Length >> 24);
                packet[3] = (byte)(encryptedData.Length >> 16);
                packet[4] = (byte)(encryptedData.Length >> 8);
                packet[5] = (byte)(encryptedData.Length & 0xFF);
                Array.Copy(encryptedData, 0, packet, 6, encryptedData.Length);
                
                stream.Write(packet, 0, packet.Length);
            }
            catch (Exception ex)
            {
                OnError?.Invoke($"发送图片失败: {ex.Message}");
            }
        }
        
        public void Disconnect()
        {
            isRunning = false;
            stream?.Close();
            tcpClient?.Close();
        }
    }
}
