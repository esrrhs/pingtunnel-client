# hev-socks5-tunnel：JNI_OnLoad 用硬编码类名 hev/htproxy/TProxyService
# 执行 FindClass + RegisterNatives，类名不可被 R8 重命名/移除。
-keep class hev.htproxy.TProxyService {
    *;
}
