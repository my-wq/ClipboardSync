# ClipboardSync V3

跨设备剪贴板同步工具，支持 Windows 和 Android 设备之间同步文本和图片。

## 功能特性

- 文本同步：复制的文本自动同步到其他设备
- 图片同步：支持任意格式图片，统一转换为 PNG 传输
- AES-256 加密：端到端加密，配对码作为密钥
- 自动发现：局域网内自动发现服务器
- 系统托盘：Windows 客户端支持最小化到托盘

## 架构

```
Windows 客户端（内置服务器）
    ↕ TCP 58090 / UDP 58091
Android 客户端
```

Windows 客户端同时包含服务器和客户端功能，启动后自动运行服务器并连接。

## 使用方法

### Windows

1. 编译：
```bash
cd client/windows/ClipboardSync
dotnet build -c Release
```

2. 运行 `bin/Release/net6.0-windows/ClipboardSync.exe`

3. 点击"启动服务器"，记住配对码

### Android

1. 用 Android Studio 打开 `client/android` 目录

2. 编译安装 APK

3. 输入 Windows 显示的配对码，点击连接

4. 需要 LSPosed + 爱玩机工具箱 授权后台剪贴板访问

## 文件结构

```
ClipboardSyncV3/
├── README.md
└── client/
    ├── android/                    # Android 客户端
    │   └── app/src/main/java/com/clipboardsync/
    │       ├── MainActivityV2.kt   # 主界面
    │       ├── ClipboardService.kt # 后台服务
    │       ├── ServerDiscovery.kt  # 服务器发现
    │       └── CryptoHelper.kt     # 加密
    └── windows/                    # Windows 客户端+服务器
        └── ClipboardSync/
            ├── MainWindow.xaml     # 主界面
            ├── ClipboardServer.cs  # 内置服务器
            ├── ClipboardClient.cs  # 客户端
            ├── ClipboardMonitor.cs # 剪贴板监控
            └── CryptoHelper.cs     # 加密
```

## 通信协议

- TCP 端口：58090（数据传输）
- UDP 端口：58091（服务器发现）
- 加密：AES-256-CBC，密钥由配对码 + "ClipboardSync" 经 SHA-256 派生


## 许可证

本项目采用 GPL v3 开源许可证。


## 项目借鉴

https://github.com/JustDoIt0910/CloudClipboard 

https://github.com/TopSecurityMem/ShareClipboard