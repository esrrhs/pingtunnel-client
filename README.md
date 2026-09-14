# pingtunnel-qt

[<img src="https://img.shields.io/github/license/esrrhs/pingtunnel-qt">](https://github.com/esrrhs/pingtunnel-qt)
[<img src="https://img.shields.io/github/languages/top/esrrhs/pingtunnel-qt">](https://github.com/esrrhs/pingtunnel-qt)
[<img src="https://img.shields.io/github/v/release/esrrhs/pingtunnel-qt">](https://github.com/esrrhs/pingtunnel-qt/releases)
[<img src="https://img.shields.io/github/downloads/esrrhs/pingtunnel-qt/total">](https://github.com/esrrhs/pingtunnel-qt/releases)
[<img src="https://img.shields.io/github/actions/workflow/status/esrrhs/pingtunnel-qt/go.yml?branch=master">](https://github.com/esrrhs/pingtunnel-qt/actions)

[pingtunnel](https://github.com/esrrhs/pingtunnel) 的图形界面客户端。填好参数后点击 GO，出现正常 ping 值即表示连接可用。关闭窗口会最小化到托盘，点击托盘图标可再打开。

界面用 Go + [Fyne](https://fyne.io/) 实现，支持 Windows、macOS、Linux。当前版本见 `version.go`。

## 用法

1. 打开程序
2. 填写 server、listen port、target 等参数
3. 按需开启 TCP mode / sock5
4. 点击 GO
5. 看到 ping 值后可关闭窗口，程序继续在托盘运行

「STOP」会停止隧道，「EXIT」或托盘 Quit 会退出程序。上次填写的参数会保存在可执行文件同目录的 `.pingtunnel-qt.json`。

ICMP 隧道在 Windows 上通常需要管理员权限。

## 构建

需要 Go 1.25+ 和 C 编译器。Windows 可用 MSYS2 gcc。

```bash
# Windows：必须加 -H windowsgui，否则会弹出控制台黑框
go build -ldflags="-H windowsgui" -o pingtunnel-qt.exe .

# macOS / Linux
go build -o pingtunnel-qt .
```

```bash
go test ./...
```

## 发布

版本号写在 `version.go` 的 `Version`。把它改成一个还没发过的号（如 `2.0.1`）并推到 `master` 后，GitHub Actions 会：

1. 对比该版本是否已有 GitHub Release
2. 没有则分别在 Windows / macOS / Linux 上编译打包
3. 自动创建 `v版本号` 的 Release 并上传三个平台的压缩包

同一版本号重复推送不会再发。也可在 Actions 里手动跑 `Release` 工作流。

## 下载

https://github.com/esrrhs/pingtunnel-qt/releases
