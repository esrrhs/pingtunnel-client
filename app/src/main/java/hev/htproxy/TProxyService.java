package hev.htproxy;

/**
 * hev-socks5-tunnel 的 JNI 绑定类。
 *
 * hev-socks5-tunnel/src/hev-jni.c 在 JNI_OnLoad 中把 native 方法注册到
 * 固定的类名 hev/htproxy/TProxyService（PKGNAME/CLSNAME 为编译期默认值），
 * 因此本类必须保持该包名与类名不变；如需改名要重新编译 so 并传入
 * 不同的 PKGNAME/CLSNAME。函数实现见 third_party 克隆的 hev-socks5-tunnel。
 */
public final class TProxyService {
    private TProxyService() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    /** 启动隧道：config_path 为 YAML 配置文件路径，fd 为 TUN 文件描述符。阻塞直至 quit 或出错，内部已开线程。 */
    public static native boolean TProxyStartService(String config_path, int fd);

    /** 停止隧道并回收线程。 */
    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** [tx_packets, tx_bytes, rx_packets, rx_bytes]。 */
    public static native long[] TProxyGetStats();

    /**
     * 当前隧道会话清单，每行：
     * proto|srcIp|srcPort|dstIp|dstPort|upload|download|createdMs[|domain]；
     * 末字段 domain 为 mapped-DNS 反查域名，可能缺失或为空。
     * 隧道未运行或无会话时返回空串。
     */
    public static native String TProxyGetSessions();
}
