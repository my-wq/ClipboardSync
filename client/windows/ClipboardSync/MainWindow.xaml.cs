using System;
using System.Windows;
using System.Windows.Forms;
using System.Drawing;
using Microsoft.Win32;

namespace ClipboardSync
{
    public partial class MainWindow : Window
    {
        private ClipboardServer? server;
        private ClipboardClient? client;
        private ClipboardMonitor? monitor;
        private NotifyIcon? trayIcon;
        
        private const string AppName = "ClipboardSync";
        private const string StartupKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
        
        public MainWindow()
        {
            InitializeComponent();
            SetupTrayIcon();
            CheckAutoStart();
        }
        
        private void CheckAutoStart()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(StartupKey, false);
                var value = key?.GetValue(AppName);
                AutoStartCheckBox.IsChecked = value != null;
            }
            catch { }
        }
        
        private void AutoStartCheckBox_Changed(object sender, RoutedEventArgs e)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(StartupKey, true);
                if (key == null) return;
                
                if (AutoStartCheckBox.IsChecked == true)
                {
                    var exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
                    if (exePath != null)
                    {
                        key.SetValue(AppName, $"\"{exePath}\"");
                    }
                }
                else
                {
                    key.DeleteValue(AppName, false);
                }
            }
            catch { }
        }
        
        private void SetupTrayIcon()
        {
            trayIcon = new NotifyIcon
            {
                Text = "ClipboardSync",
                Visible = false
            };
            
            // 使用系统图标
            trayIcon.Icon = SystemIcons.Application;
            
            trayIcon.DoubleClick += (s, e) =>
            {
                Show();
                WindowState = WindowState.Normal;
                trayIcon.Visible = false;
            };
            
            var menu = new ContextMenuStrip();
            menu.Items.Add("显示", null, (s, e) =>
            {
                Show();
                WindowState = WindowState.Normal;
                trayIcon.Visible = false;
            });
            menu.Items.Add("-");
            menu.Items.Add("退出", null, (s, e) =>
            {
                trayIcon.Visible = false;
                StopAll();
                System.Windows.Application.Current.Shutdown();
            });
            
            trayIcon.ContextMenuStrip = menu;
        }
        

        
        private void StartButton_Click(object sender, RoutedEventArgs e)
        {
            StartServer();
        }
        
        private void StartServer()
        {
            server = new ClipboardServer();
            
            server.OnLog += (msg) =>
            {
                Dispatcher.Invoke(() => AddLog(msg));
            };
            
            server.OnPairCodeChanged += (code) =>
            {
                Dispatcher.Invoke(() =>
                {
                    ServerPairCodeText.Text = code;
                    // 更新加密密钥
                    CryptoHelper.SetKey(code);
                });
            };
            
            server.OnClientCountChanged += (count) =>
            {
                Dispatcher.Invoke(() =>
                {
                    // 减1是因为不算本机客户端
                    var otherDevices = Math.Max(0, count - 1);
                    ClientCountText.Text = otherDevices.ToString();
                });
            };
            
            server.Start();
            
            // 切换到主界面
            StartPanel.Visibility = Visibility.Collapsed;
            MainPanel.Visibility = Visibility.Visible;
            
            // 连接本地客户端
            ConnectLocalClient();
        }

        private void ConnectLocalClient()
        {
            ClientStatusText.Text = "正在连接...";
            ClientStatusText.Foreground = System.Windows.Media.Brushes.Orange;
            
            client = new ClipboardClient("127.0.0.1", 58090);
            
            client.OnPairCodeReceived += (code) =>
            {
                Dispatcher.Invoke(() =>
                {
                    AddLog($"收到配对码: {code}");
                });
            };
            
            client.OnConnected += () =>
            {
                Dispatcher.Invoke(() =>
                {
                    ClientStatusText.Text = "已连接";
                    ClientStatusText.Foreground = System.Windows.Media.Brushes.Green;
                    
                    // 启动剪贴板监控
                    monitor = new ClipboardMonitor(this);
                    monitor.OnClipboardChanged += (text) =>
                    {
                        client?.SendText(text);
                    };
                    monitor.OnImageChanged += (pngData) =>
                    {
                        client?.SendImage(pngData);
                    };
                    monitor.Start();
                    
                    MonitorStatusText.Text = "监控中";
                    MonitorStatusText.Foreground = System.Windows.Media.Brushes.Green;
                    AddLog("剪贴板监控已启动");
                });
            };
            
            client.OnTextReceived += (text) =>
            {
                Dispatcher.Invoke(() =>
                {
                    monitor?.SetClipboard(text);
                });
            };
            
            client.OnImageReceived += (pngData) =>
            {
                Dispatcher.Invoke(() =>
                {
                    monitor?.SetClipboardImage(pngData);
                });
            };
            
            client.OnClientCountChanged += (count) =>
            {
                Dispatcher.Invoke(() =>
                {
                    // 减1是因为不算本机客户端
                    var otherDevices = Math.Max(0, count - 1);
                    ClientCountText.Text = otherDevices.ToString();
                });
            };
            
            client.OnError += (error) =>
            {
                Dispatcher.Invoke(() =>
                {
                    AddLog($"错误: {error}");
                });
            };
            
            client.ConnectAndGetPairCode();
        }
        
        private void RefreshCodeButton_Click(object sender, RoutedEventArgs e)
        {
            server?.RefreshPairCode();
            // 重新连接本地客户端
            monitor?.Stop();
            client?.Disconnect();
            ConnectLocalClient();
        }
        
        private void StopButton_Click(object sender, RoutedEventArgs e)
        {
            StopAll();
            
            // 返回启动界面
            MainPanel.Visibility = Visibility.Collapsed;
            StartPanel.Visibility = Visibility.Visible;
        }
        
        private void StopAll()
        {
            monitor?.Stop();
            client?.Disconnect();
            server?.Stop();
            
            monitor = null;
            client = null;
            server = null;
        }
        
        private void AddLog(string msg)
        {
            var time = DateTime.Now.ToString("HH:mm:ss");
            LogList.Items.Insert(0, $"[{time}] {msg}");
            
            // 限制日志数量
            while (LogList.Items.Count > 50)
            {
                LogList.Items.RemoveAt(LogList.Items.Count - 1);
            }
        }
        
        private void Window_StateChanged(object sender, EventArgs e)
        {
            if (WindowState == WindowState.Minimized && server != null)
            {
                Hide();
                trayIcon!.Visible = true;
                trayIcon.ShowBalloonTip(1000, "ClipboardSync", "已最小化到系统托盘", ToolTipIcon.Info);
            }
        }
        
        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            StopAll();
            trayIcon?.Dispose();
        }
    }
}
