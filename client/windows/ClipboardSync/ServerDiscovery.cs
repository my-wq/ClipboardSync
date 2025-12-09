using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;

namespace ClipboardSync
{
    public class ServerDiscovery
    {
        private const int BroadcastPort = 58091;
        private const string DiscoverMessage = "DISCOVER";
        private const int Timeout = 5000; // 5 seconds

        public static async Task<(string host, int port)?> DiscoverServerAsync()
        {
            try
            {
                using (var client = new UdpClient())
                {
                    client.EnableBroadcast = true;
                    
                    // 发送广播
                    var sendData = Encoding.UTF8.GetBytes(DiscoverMessage);
                    var broadcastEndpoint = new IPEndPoint(IPAddress.Broadcast, BroadcastPort);
                    await client.SendAsync(sendData, sendData.Length, broadcastEndpoint);
                    
                    Console.WriteLine($"Broadcast sent to port {BroadcastPort}");
                    
                    // 等待响应
                    var receiveTask = client.ReceiveAsync();
                    var timeoutTask = Task.Delay(Timeout);
                    
                    var completedTask = await Task.WhenAny(receiveTask, timeoutTask);
                    
                    if (completedTask == receiveTask)
                    {
                        var result = await receiveTask;
                        var response = Encoding.UTF8.GetString(result.Buffer);
                        var serverIp = result.RemoteEndPoint.Address.ToString();
                        
                        Console.WriteLine($"Received response: {response} from {serverIp}");
                        
                        // 解析响应: CLIPBOARD_SYNC:58090
                        if (response.StartsWith("CLIPBOARD_SYNC:"))
                        {
                            var parts = response.Split(':');
                            if (parts.Length == 2 && int.TryParse(parts[1], out int port))
                            {
                                Console.WriteLine($"Server found at {serverIp}:{port}");
                                return (serverIp, port);
                            }
                        }
                    }
                    else
                    {
                        Console.WriteLine("Discovery timeout");
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Discovery failed: {ex.Message}");
            }
            
            return null;
        }
    }
}
