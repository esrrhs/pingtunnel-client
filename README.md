# pingtunnel-client (Android)

pingtunnel 的 Android 客户端：把 TCP/UDP 流量封装进 ICMP echo（ping）包穿透传输，
**免 root** 即可使用——普通 App 权限直接收发自定义 ICMP 协议包。

```
App / 其它应用
   │
   ▼
VpnService (TUN 虚拟网卡, 全量抓包)
   │
   ▼
hev-socks5-tunnel (JNI, tun2socks)
   │
   ▼ (可选) RuleSocksServer 用户态分流器 —— 绕过局域网 / CN 直连 / 域名直连
   │
   ▼
pingtunnel 内核子进程 (libpingtunnel.so, 本地 SOCKS5: CONNECT + UDP ASSOCIATE)
   │
   ▼
ICMP echo（免 root ping socket）──▶ pingtunnel server ──▶ 互联网
```

## 免 root 原理

Android（Linux 3.11+）允许非特权进程创建 **ICMP datagram ping socket**
（`SOCK_DGRAM + IPPROTO_ICMP`）。所依赖的 pingtunnel 版本（上游 commit
`699904d`，`icmp_listen_android.go`）在 Android 目标上以
`icmp.ListenPacket("udp4", ...)` 替代需要 `CAP_NET_RAW` 的 raw socket，并适配了
内核对该类 socket 的两个行为：重写 echo ID（客户端跳过 ID 校验）与
`*net.UDPAddr` 地址类型。因此 App 只要持有 `INTERNET` 权限即可收发自定义
ICMP echo 包，无需 root。

## 功能

- 多配置管理（Shadowsocks 风格电源键、扫码/链接/文件导入导出、真实延迟测速、故障切换）
- VPN 全量接管（IPv4/IPv6）、分应用代理（全部/仅选中/排除选中）
- 智能分流：绕过局域网、CN 地址直连（chnroute 精确表）、大陆域名直连（可自定义规则）
- 断线自动重连（指数退避）、网络切换（WiFi↔蜂窝）无缝重建、可信 WiFi 自动暂停
- 快速设置磁贴、开机自启、始终开启 VPN 引导
- 实时速率通知栏、会话/累计流量统计、当前连接与单连接历史
- 内核日志查看、隧道自检、IP/DNS 泄漏检测、出口 IP 归属地查询
- 认证：旧式数字 key（`-key`）或 AEAD 加密（`-encrypt`：aes128 / aes256 / chacha20）

## 构建

环境要求：JDK 17、Android SDK + NDK、Go 1.26+。

```bash
# 1. 构建全部 native 产物（首次会克隆 pingtunnel / hev-socks5-tunnel 源码）
bash scripts/build_native.sh
#   产物：app/src/main/jniLibs/<abi>/libpingtunnel.so      —— pingtunnel 内核（Go 交叉编译）
#         app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so —— tun2socks（ndk-build）

# 2. 打 APK
./gradlew assembleDebug

# 3. 单元测试（含用户态分流器 JVM E2E）
./gradlew testDebugUnitTest
```

说明：

- 内核固定在已知稳定提交（含免 root ICMP 支持），可用环境变量覆盖：
  `PINGTUNNEL_DIR`（本地源码路径）、`PINGTUNNEL_COMMIT`、`PINGTUNNEL_ABIS`。
- `CGO_ENABLED=1` 必须开启：Android 没有 `/etc/resolv.conf`，纯 Go 解析器无法
  解析服务器域名；链接 bionic 用系统解析器。
- 链接参数带 `-Wl,-z,max-page-size=16384`，满足 Android 15+ 的 16KB 页对齐要求。
- `app/build.gradle.kts` 的 `useLegacyPackaging = true` 必须保留：让 so 落盘为
  真实文件，`libpingtunnel.so`（实为可执行文件）才能在 `nativeLibraryDir` 获得
  执行权限。

## 服务端

在服务器上运行 [pingtunnel](https://github.com/esrrhs/pingtunnel) server，
客户端配置里填该服务器地址与相同密码：

```bash
# 旧式数字 key（App 里「不加密」模式）
sudo ./pingtunnel -type server -key 123456

# 或 AEAD 加密（App 里选择对应加密模式 + 相同密钥）
sudo ./pingtunnel -type server -encrypt aes256 -encrypt-key your-passphrase
```

## pingtunnel 参数映射

App 配置项与 pingtunnel client 命令行参数一一对应：

| App 字段 | CLI 参数 | 默认 |
| --- | --- | --- |
| 服务器地址 | `-s` | 必填 |
| 认证 Key（不加密时） | `-key` | 0 |
| 加密模式 / 加密密钥 | `-encrypt` / `-encrypt-key` | 不加密 |
| 连接超时 | `-timeout` | 60 秒 |
| TCP 收发缓冲区 | `-tcp_bs` | 1MB |
| TCP 最大窗口 | `-tcp_mw` | 20000（上限 100000） |
| TCP 重传超时 | `-tcp_rst` | 400ms |
| TCP 压缩阈值 | `-tcp_gz` | 0（关闭） |
| 最大连接数 | `-maxconn` | 0（不限） |
| 拥塞控制 | `-congestion` | bb（可关） |

本地固定参数：`-type client -sock5 1 -l 127.0.0.1:<随机端口> -nolog 1`。

## 与桌面版的关系

本仓库原为 [esrrhs/pingtunnel-client](https://github.com/esrrhs/pingtunnel-client)
（Go + Fyne 桌面 GUI），现已重构为 Android 客户端，桌面版代码见上游仓库。

## 致谢

- [esrrhs/pingtunnel](https://github.com/esrrhs/pingtunnel) —— 隧道内核
- [itismoej](https://github.com/itismoej) —— Android 免 root ICMP ping socket 支持（pingtunnel PR #79）
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) —— tun2socks 数据面
- 本项目 UI/服务框架移植自 spp_client_android

## License

Apache-2.0（见 [LICENSE](LICENSE)）
