using System;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace ClipboardSync
{
    public class ClipboardServer
    {
        private TcpListener? tcpListener;
        private UdpClient? udpClient;
        private bool isRunning;
        private string pairCode;
        private readonly ConcurrentDictionary<string, TcpClient> clients = new();
        
        private const int TCPPort = 58090;
        private const int UDPPort = 58091;
        
        private const byte OpLogin = 0x01;
        private const byte OpText = 0x03;
        private const byte OpHeartbeat = 0x04;
        private const byte OpResponse = 0x05;
        private const byte OpGetPairCode = 0x06;
        private const byte OpPairCode = 0x07;
        private const byte OpClientCount = 0x08;
        private const byte OpRefreshCode = 0x09;
        private const byte OpImage = 0x0A;
        
        public event Action<string>? OnLog;
        public event Action<int>? OnClientCountChanged;
        public event Action<string>? OnPairCodeChanged;
        
        public string PairCode => pairCode;
        public int ClientCount => clients.Count;
        public bool IsRunning => isRunning;
        
        public ClipboardServer()
        {
            pairCode = GeneratePairCode();
        }
        
        public void Start()
        {
            if (isRunning) return;
            isRunning = true;
            
            // 启动 TCP 服务器
            new Thread(TcpServerLoop) { IsBackground = true }.Start();
            
            // 启动 UDP 发现服务
            new Thread(UdpDiscoveryLoop) { IsBackground = true }.Start();
            
            OnLog?.Invoke($"服务器已启动，配对码: {pairCode}");
            OnPairCodeChanged?.Invoke(pairCode);
        }
        
        public void Stop()
        {
            isRunning = false;
            tcpListener?.Stop();
            udpClient?.Close();
            
            foreach (var client in clients.Values)
            {
                try { client.Close(); } catch { }
            }
            clients.Clear();
            
            OnLog?.Invoke("服务器已停止");
            OnClientCountChanged?.Invoke(0);
        }

        
        public void RefreshPairCode()
        {
            pairCode = GeneratePairCode();
            
            // 断开所有客户端
            foreach (var client in clients.Values)
            {
                try { client.Close(); } catch { }
            }
            clients.Clear();
            
            OnLog?.Invoke($"配对码已刷新: {pairCode}");
            OnPairCodeChanged?.Invoke(pairCode);
            OnClientCountChanged?.Invoke(0);
        }
        
        private void TcpServerLoop()
        {
            try
            {
                tcpListener = new TcpListener(IPAddress.Any, TCPPort);
                tcpListener.Start();
                OnLog?.Invoke($"TCP 监听端口 {TCPPort}");
                
                while (isRunning)
                {
                    if (tcpListener.Pending())
                    {
                        var client = tcpListener.AcceptTcpClient();
                        new Thread(() => HandleClient(client)) { IsBackground = true }.Start();
                    }
                    Thread.Sleep(100);
                }
            }
            catch (Exception ex)
            {
                if (isRunning) OnLog?.Invoke($"TCP 错误: {ex.Message}");
            }
        }
        
        private void UdpDiscoveryLoop()
        {
            try
            {
                udpClient = new UdpClient(UDPPort);
                OnLog?.Invoke($"UDP 发现服务端口 {UDPPort}");
                
                while (isRunning)
                {
                    try
                    {
                        udpClient.Client.ReceiveTimeout = 1000;
                        var remoteEP = new IPEndPoint(IPAddress.Any, 0);
                        var data = udpClient.Receive(ref remoteEP);
                        var msg = Encoding.UTF8.GetString(data);
                        
                        if (msg == "DISCOVER")
                        {
                            var response = Encoding.UTF8.GetBytes($"CLIPBOARD_SYNC:{TCPPort}");
                            udpClient.Send(response, response.Length, remoteEP);
                            OnLog?.Invoke($"响应发现请求: {remoteEP.Address}");
                        }
                    }
                    catch (SocketException) { }
                }
            }
            catch (Exception ex)
            {
                if (isRunning) OnLog?.Invoke($"UDP 错误: {ex.Message}");
            }
        }

        
        private void HandleClient(TcpClient tcpClient)
        {
            string? clientId = null;
            var stream = tcpClient.GetStream();
            var remoteEndPoint = tcpClient.Client.RemoteEndPoint?.ToString() ?? "unknown";
            
            try
            {
                // 设置超时，防止僵尸连接
                tcpClient.ReceiveTimeout = 60000; // 60秒
                
                while (isRunning && tcpClient.Connected)
                {
                    if (!stream.DataAvailable)
                    {
                        Thread.Sleep(50);
                        continue;
                    }
                    
                    int op = stream.ReadByte();
                    if (op < 0) break;
                    
                    switch (op)
                    {
                        case OpLogin:
                            int codeLen = stream.ReadByte();
                            var codeBytes = new byte[codeLen];
                            stream.Read(codeBytes, 0, codeLen);
                            var code = Encoding.UTF8.GetString(codeBytes);
                            
                            if (code == pairCode)
                            {
                                // 如果已经登录过，不重复添加
                                if (clientId == null)
                                {
                                    clientId = Guid.NewGuid().ToString();
                                    clients[clientId] = tcpClient;
                                    OnLog?.Invoke($"客户端已配对: {remoteEndPoint}");
                                    NotifyClientCount();
                                }
                                SendResponse(stream, true, "Connected");
                            }
                            else
                            {
                                SendResponse(stream, false, "Invalid pair code");
                                return;
                            }
                            break;
                            
                        case OpGetPairCode:
                            // 只发送配对码，不计入连接数（这是查询请求）
                            SendPairCode(stream);
                            SendClientCount(stream);
                            break;
                            
                        case OpRefreshCode:
                            // 只有已认证的客户端才能刷新
                            if (clientId != null)
                            {
                                RefreshPairCode();
                                SendPairCode(stream);
                                clients[clientId] = tcpClient;
                                NotifyClientCount();
                            }
                            break;
                            
                        case OpText:
                            if (clientId == null) break;
                            var textData = ReadTextData(stream);
                            if (textData != null)
                            {
                                BroadcastText(textData, clientId);
                            }
                            break;
                            
                        case OpImage:
                            if (clientId == null) break;
                            var imageData = ReadImageData(stream);
                            if (imageData != null)
                            {
                                BroadcastImage(imageData, clientId);
                            }
                            break;
                            
                        case OpHeartbeat:
                            stream.WriteByte(OpHeartbeat);
                            break;
                    }
                }
            }
            catch (Exception ex)
            {
                if (isRunning && clientId != null)
                {
                    OnLog?.Invoke($"客户端错误: {ex.Message}");
                }
            }
            finally
            {
                if (clientId != null)
                {
                    clients.TryRemove(clientId, out _);
                    NotifyClientCount();
                    OnLog?.Invoke($"客户端断开: {remoteEndPoint}");
                }
                try { tcpClient.Close(); } catch { }
            }
        }
        
        private string GeneratePairCode()
        {
            var random = new Random();
            return random.Next(0, 1000000).ToString("D6");
        }
        
        private void SendResponse(NetworkStream stream, bool success, string msg)
        {
            var msgBytes = Encoding.UTF8.GetBytes(msg);
            var buf = new byte[3 + msgBytes.Length];
            buf[0] = OpResponse;
            buf[1] = (byte)(success ? 1 : 0);
            buf[2] = (byte)msgBytes.Length;
            Array.Copy(msgBytes, 0, buf, 3, msgBytes.Length);
            stream.Write(buf, 0, buf.Length);
        }
        
        private void SendPairCode(NetworkStream stream)
        {
            var codeBytes = Encoding.UTF8.GetBytes(pairCode);
            var buf = new byte[2 + codeBytes.Length];
            buf[0] = OpPairCode;
            buf[1] = (byte)codeBytes.Length;
            Array.Copy(codeBytes, 0, buf, 2, codeBytes.Length);
            stream.Write(buf, 0, buf.Length);
        }
        
        private void SendClientCount(NetworkStream stream)
        {
            var buf = new byte[] { OpClientCount, (byte)clients.Count };
            stream.Write(buf, 0, buf.Length);
        }
        
        private void NotifyClientCount()
        {
            var count = clients.Count;
            var buf = new byte[] { OpClientCount, (byte)count };
            
            foreach (var client in clients.Values)
            {
                try
                {
                    var s = client.GetStream();
                    s.Write(buf, 0, buf.Length);
                }
                catch { }
            }
            
            OnClientCountChanged?.Invoke(count);
        }
        
        private byte[]? ReadTextData(NetworkStream stream)
        {
            var lenBytes = new byte[2];
            stream.Read(lenBytes, 0, 2);
            int length = (lenBytes[0] << 8) | lenBytes[1];
            
            var data = new byte[length];
            int totalRead = 0;
            while (totalRead < length)
            {
                int read = stream.Read(data, totalRead, length - totalRead);
                if (read <= 0) return null;
                totalRead += read;
            }
            return data;
        }
        
        private byte[]? ReadImageData(NetworkStream stream)
        {
            // 读取格式 (1字节)
            int format = stream.ReadByte();
            if (format < 0) return null;
            
            // 读取长度 (4字节, big-endian)
            var lenBytes = new byte[4];
            stream.Read(lenBytes, 0, 4);
            int length = (lenBytes[0] << 24) | (lenBytes[1] << 16) | (lenBytes[2] << 8) | lenBytes[3];
            
            // 限制50MB
            if (length > 50 * 1024 * 1024) return null;
            
            // 读取数据
            var data = new byte[length + 1]; // +1 for format
            data[0] = (byte)format;
            
            int totalRead = 0;
            while (totalRead < length)
            {
                int read = stream.Read(data, totalRead + 1, length - totalRead);
                if (read <= 0) return null;
                totalRead += read;
            }
            return data;
        }
        
        private void BroadcastText(byte[] data, string excludeClientId)
        {
            var packet = new byte[3 + data.Length];
            packet[0] = OpText;
            packet[1] = (byte)(data.Length >> 8);
            packet[2] = (byte)(data.Length & 0xFF);
            Array.Copy(data, 0, packet, 3, data.Length);
            
            foreach (var kvp in clients)
            {
                if (kvp.Key == excludeClientId) continue;
                try
                {
                    var s = kvp.Value.GetStream();
                    s.Write(packet, 0, packet.Length);
                }
                catch { }
            }
        }
        
        private void BroadcastImage(byte[] data, string excludeClientId)
        {
            // data[0] = format, data[1:] = image data
            var packet = new byte[6 + data.Length - 1];
            packet[0] = OpImage;
            packet[1] = data[0]; // format
            int len = data.Length - 1;
            packet[2] = (byte)(len >> 24);
            packet[3] = (byte)(len >> 16);
            packet[4] = (byte)(len >> 8);
            packet[5] = (byte)(len & 0xFF);
            Array.Copy(data, 1, packet, 6, len);
            
            foreach (var kvp in clients)
            {
                if (kvp.Key == excludeClientId) continue;
                try
                {
                    var s = kvp.Value.GetStream();
                    s.Write(packet, 0, packet.Length);
                }
                catch { }
            }
        }
    }
}