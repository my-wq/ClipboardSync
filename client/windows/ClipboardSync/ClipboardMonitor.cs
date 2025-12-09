using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace ClipboardSync
{
    public class ClipboardMonitor
    {
        private Window window;
        private HwndSource? hwndSource;
        private bool ignoreNextChange = false;
        private DateTime lastImageTime = DateTime.MinValue;
        private byte[]? lastImageHash = null;
        
        public event Action<string>? OnClipboardChanged;
        public event Action<byte[]>? OnImageChanged; // PNG data
        
        [DllImport("user32.dll")]
        private static extern bool AddClipboardFormatListener(IntPtr hwnd);
        
        [DllImport("user32.dll")]
        private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
        
        private const int WM_CLIPBOARDUPDATE = 0x031D;
        
        public ClipboardMonitor(Window window)
        {
            this.window = window;
        }
        
        public void Start()
        {
            hwndSource = PresentationSource.FromVisual(window) as HwndSource;
            if (hwndSource != null)
            {
                hwndSource.AddHook(WndProc);
                AddClipboardFormatListener(hwndSource.Handle);
            }
        }
        
        public void Stop()
        {
            if (hwndSource != null)
            {
                RemoveClipboardFormatListener(hwndSource.Handle);
                hwndSource.RemoveHook(WndProc);
            }
        }
        
        public void SetClipboard(string text)
        {
            try
            {
                ignoreNextChange = true;
                Clipboard.SetText(text);
            }
            catch { }
        }
        
        public void SetClipboardImage(byte[] pngData)
        {
            try
            {
                ignoreNextChange = true;
                using var ms = new MemoryStream(pngData);
                var bitmap = new BitmapImage();
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.StreamSource = ms;
                bitmap.EndInit();
                bitmap.Freeze();
                Clipboard.SetImage(bitmap);
            }
            catch { }
        }
        
        private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_CLIPBOARDUPDATE)
            {
                if (ignoreNextChange)
                {
                    ignoreNextChange = false;
                    return IntPtr.Zero;
                }
                
                try
                {
                    if (Clipboard.ContainsText())
                    {
                        string text = Clipboard.GetText();
                        OnClipboardChanged?.Invoke(text);
                    }
                    else if (Clipboard.ContainsImage())
                    {
                        // 防止短时间内重复发送同一图片
                        if ((DateTime.Now - lastImageTime).TotalMilliseconds < 1000)
                            return IntPtr.Zero;
                        
                        var image = Clipboard.GetImage();
                        if (image != null)
                        {
                            // 转换为 RGB 格式（去掉 Alpha 通道，避免透明变黑）
                            var convertedImage = new FormatConvertedBitmap(image, System.Windows.Media.PixelFormats.Bgr24, null, 0);
                            
                            var encoder = new PngBitmapEncoder();
                            encoder.Frames.Add(BitmapFrame.Create(convertedImage));
                            
                            using var ms = new MemoryStream();
                            encoder.Save(ms);
                            byte[] imageData = ms.ToArray();
                            
                            // 检查是否和上次相同
                            var hash = System.Security.Cryptography.MD5.HashData(imageData);
                            if (lastImageHash != null && hash.SequenceEqual(lastImageHash))
                                return IntPtr.Zero;
                            
                            lastImageHash = hash;
                            lastImageTime = DateTime.Now;
                            OnImageChanged?.Invoke(imageData);
                        }
                    }
                }
                catch { }
            }
            
            return IntPtr.Zero;
        }
    }
}
