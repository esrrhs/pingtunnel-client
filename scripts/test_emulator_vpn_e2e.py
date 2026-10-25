#!/usr/bin/env python3
"""
端到端 Android 真实集成测试脚本 (E2E Android Emulator VPN Test)

测试全矩阵：
1. 代理模式：
   - 真实 pingtunnel ICMP 协议与 Native 进程模式 (libpingtunnel.so 子进程 + 免 root ping socket)
   - 服务端跑在模拟器内部 (adb root 提权，raw ICMP)，验证完整 ICMP 隧道链路
2. 网络与传输协议：
   - TCP 报文穿透与数据完整性回显
   - UDP 数据报穿透与中继回显
   - HTTP/1.1 标准 GET 请求与 200 OK 响应
   - HTTP/2 原生连接前奏 (Connection Preface) 与 SETTINGS 帧双向握手
3. 高并发与可靠性：
   - 批量并发请求压测 (0 失败)
4. 生命周期管理：
   - 系统虚拟网卡 tun0 干净建立与销毁
   - Native 进程生命周期正常回收
5. 物理网络切换：
   - 飞行模式丢掉当前网络后，VPN 松开已消失的底层网络
   - 网络回来后重建隧道，TCP 仍然穿透，且底层网络不是已拆掉的 netId
"""

import argparse
import http.server
import json
import os
import re
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time
import zipfile

# CI 日志按行刷出，避免进程退出前才一次性吐出全部输出。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(line_buffering=True)

def find_adb():
    adb_env = os.environ.get("ADB")
    if adb_env and os.path.exists(adb_env):
        return adb_env
    default_path = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    if os.path.exists(default_path):
        return default_path
    which_adb = subprocess.run(["which", "adb"], capture_output=True, text=True)
    if which_adb.returncode == 0:
        return which_adb.stdout.strip()
    return "adb"

def run_adb(adb_cmd, serial, args, check=True, timeout=30):
    """执行 adb。

    设备端 shell 会按空格切分 `adb shell` 拼起来的命令行。
    简单命令拆成独立参数；含管道、引号的脚本保持为一个参数，让设备 shell 自己解析。
    不要再套一层 `sh -c`，否则脚本会被拆开，`appops` 只会打出帮助文本。
    """
    cmd = [adb_cmd]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    res = subprocess.run(cmd, capture_output=True, text=True, errors='ignore', timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"ADB command failed: {' '.join(cmd)}\nStderr: {res.stderr}\nStdout: {res.stdout}")
    return res

def grant_vpn_consent(adb, serial):
    """预授权 VPN。必须在应用安装之后调用，且命令必须真正在设备上执行。"""
    pkg = "com.esrrhs.pingtunnel.client"
    run_adb(adb, serial, [
        "shell", "pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS",
    ], check=False)
    for op in ("ACTIVATE_VPN", "ACTIVATE_PLATFORM_VPN"):
        run_adb(adb, serial, ["shell", "appops", "set", pkg, op, "allow"], check=False)
        run_adb(adb, serial, ["shell", "appops", "set", "--uid", pkg, op, "allow"], check=False)
        got = run_adb(adb, serial, ["shell", "appops", "get", pkg, op], check=False)
        text = ((got.stdout or "") + (got.stderr or "")).strip()
        print(f"==> appops {op}: {text or '(无输出)'}")
        if "allow" not in text:
            raise RuntimeError(f"VPN 授权失败，appops {op} 未变为 allow: {text}")

def dump_vpn_log(adb, serial, reason="tun 未就绪"):
    print(f"==> {reason}，抓取 logcat ...")
    # 模拟器 logcat 是 *:V，不能只看最后几百行再 grep，否则服务日志会被冲掉。
    # NetworkWatchdog 必须带上：物理网络切换/丢失是"看起来像协议回归"的头号嫌疑，
    # 少了它就只能靠猜。
    res = run_adb(
        adb, serial,
        ["shell", "logcat", "-d", "-s", "PingtunnelVpnService:V", "PingtunnelProcess:V", "HevTunnel:V",
         "NetworkWatchdog:V", "AndroidRuntime:E"],
        check=False,
    )
    text = ((res.stdout or "") + "\n" + (res.stderr or "")).strip()
    print(text or "(logcat 为空)")

def assert_native_libs(apk_path):
    needed = (
        "lib/x86_64/libhev-socks5-tunnel.so",
        "lib/x86_64/libpingtunnel.so",
    )
    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
    missing = [name for name in needed if name not in names]
    if missing:
        raise RuntimeError(
            "APK 缺少 native 库: "
            + ", ".join(missing)
            + "。请先运行 scripts/build_native.sh 再打包"
        )

def start_vpn_service(adb, serial, action, profile_json=None):
    args = ["shell", "am", "start-foreground-service", "-a", action]
    if profile_json is not None:
        args.extend([
            "--es", "com.esrrhs.pingtunnel.client.extra.PROFILE_JSON", f"'{profile_json}'",
        ])
    args.extend(["-n", "com.esrrhs.pingtunnel.client/.vpn.PingtunnelVpnService"])
    res = run_adb(adb, serial, args, check=False)
    out = ((res.stdout or "") + (res.stderr or "")).strip()
    print(f"==> am exit={res.returncode} {out}")
    if res.returncode != 0 or "Error" in out or "Exception" in out or "Permission Denial" in out:
        dump_vpn_log(adb, serial)
        raise RuntimeError(f"启动 VPN 服务失败: {out}")
    return res

def get_host_lan_ip():
    ips = []
    try:
        res = subprocess.run(['hostname', '-I'], capture_output=True, text=True)
        if res.returncode == 0:
            ips.extend(res.stdout.split())
    except Exception:
        pass

    try:
        res = subprocess.run(['ifconfig'], capture_output=True, text=True)
        for line in res.stdout.splitlines():
            m = re.search(r'inet\s+(\d+\.\d+\.\d+\.\d+)', line)
            if m:
                ips.append(m.group(1))
    except Exception:
        pass

    valid_ips = [
        ip for ip in ips
        if not ip.startswith('127.')
        and not ip.startswith('198.18.')
        and not ip.startswith('169.254.')
    ]
    if valid_ips:
        return valid_ips[0]

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        if not ip.startswith('198.18.'):
            return ip
    except Exception:
        pass
    finally:
        s.close()
    return '127.0.0.1'

class TcpEchoServer:
    def __init__(self, port=19001):
        self.port = port
        self.sock = None
        self.running = False
        self.thread = None

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(64)
        self.running = True
        self.thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                threading.Thread(target=self._client_loop, args=(c,), daemon=True).start()
            except Exception:
                break

    def _client_loop(self, c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            while self.running:
                data = c.recv(16384)
                if not data:
                    break
                c.sendall(data)
        except Exception:
            pass
        finally:
            try:
                c.close()
            except Exception:
                pass

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

class UdpEchoServer:
    def __init__(self, port=19001):
        self.port = port
        self.sock = None
        self.running = False
        self.thread = None

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.running = True
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(65535)
                self.sock.sendto(data, addr)
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class Http1EchoServer:
    def __init__(self, port=19004):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        req = conn.recv(1024)
                        if req:
                            resp = (
                                b"HTTP/1.1 200 OK\r\n"
                                b"Content-Type: text/plain\r\n"
                                b"Content-Length: 11\r\n"
                                b"Connection: close\r\n\r\n"
                                b"HTTP1_OK\n"
                            )
                            conn.sendall(resp)
                            time.sleep(0.5)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class Http2MockServer:
    def __init__(self, port=19005):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        buf = bytearray()
                        while len(buf) < 24:
                            chunk = conn.recv(24 - len(buf))
                            if not chunk:
                                break
                            buf.extend(chunk)
                        if bytes(buf).startswith(b'PRI * HTTP/2.0'):
                            # 尝试读取客户端随后的 SETTINGS 帧（9 字节），若未到也可直接回复服务端 SETTINGS
                            conn.settimeout(0.5)
                            try:
                                conn.recv(9)
                            except Exception:
                                pass
                            # 回复服务端的 SETTINGS 帧 (长度0, 类型4) 及 SETTINGS ACK 帧
                            conn.sendall(b'\x00\x00\x00\x04\x00\x00\x00\x00\x00\x00\x00\x00\x04\x01\x00\x00\x00\x00')
                            # 等待对端读取并断开或等待 1 秒缓冲，避免提前 RST 导致数据未被代理收取
                            time.sleep(1.0)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class DnsMockServer:
    def __init__(self, port=19006):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(4096)
                if len(data) >= 12:
                    tx_id = data[:2]
                    # Header: ID(2), Flags 0x8180 (standard response), QD=1, AN=1, NS=0, AR=0
                    resp_hdr = tx_id + b'\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00'
                    # Query section
                    query = data[12:]
                    # Answer: Pointer 0xc00c, Type A (0x0001), Class IN (0x0001), TTL 60s, Len 4, IP 1.2.3.4
                    ans = b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\x01\x02\x03\x04'
                    self.sock.sendto(resp_hdr + query + ans, addr)
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class WebSocketMockServer:
    def __init__(self, port=19007):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        buf = bytearray()
                        # 读取完整的 HTTP Upgrade 请求
                        while b'\r\n\r\n' not in buf:
                            chunk = conn.recv(1024)
                            if not chunk: break
                            buf.extend(chunk)
                        
                        if b'Upgrade: websocket' in bytes(buf):
                            # 握手响应
                            handshake = (
                                "HTTP/1.1 101 Switching Protocols\r\n"
                                "Upgrade: websocket\r\n"
                                "Connection: Upgrade\r\n"
                                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
                            )
                            conn.sendall(handshake.encode('utf-8'))
                            
                            # 检查 buf 中是否有后续的 WebSocket 帧数据
                            idx = bytes(buf).find(b'\r\n\r\n') + 4
                            frame_data = buf[idx:]
                            while len(frame_data) < 6:
                                chunk = conn.recv(1024)
                                if not chunk: break
                                frame_data.extend(chunk)
                            
                            if len(frame_data) >= 6:
                                l = frame_data[1] & 0x7F
                                mask = frame_data[2:6]
                                payload = frame_data[6:6+l]
                                while len(payload) < l:
                                    chunk = conn.recv(l - len(payload))
                                    if not chunk: break
                                    payload.extend(chunk)
                                
                                unmasked = bytearray(l)
                                for i in range(l):
                                    unmasked[i] = payload[i] ^ mask[i % 4]
                                
                                # 回送未掩码文本帧 (0x81)
                                reply = bytes([0x81, l]) + unmasked
                                conn.sendall(reply)
                                time.sleep(0.5)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class GrpcMockServer:
    def __init__(self, port=19008):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        buf = bytearray()
                        while len(buf) < 24:
                            chunk = conn.recv(24 - len(buf))
                            if not chunk: break
                            buf.extend(chunk)
                        if bytes(buf).startswith(b'PRI * HTTP/2.0'):
                            # 读取客户端 SETTINGS 帧
                            conn.settimeout(0.5)
                            try: conn.recv(9)
                            except Exception: pass
                            # 回复服务端 SETTINGS 帧
                            conn.sendall(b'\x00\x00\x00\x04\x00\x00\x00\x00\x00')
                            # 读取客户端 gRPC 帧
                            try: conn.recv(9)
                            except Exception: pass
                            # 回传 gRPC HEADERS 帧 (Flags 0x05 END_STREAM, Stream 1)
                            conn.sendall(b'\x00\x00\x04\x01\x05\x00\x00\x00\x01GRPC')
                            time.sleep(1.0)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class QuicMockServer:
    def __init__(self, port=19009):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(65535)
                # QUIC Long Header 0xC0 开头
                if len(data) >= 5 and (data[0] & 0xC0) == 0xC0:
                    resp = b'\xc0\x00\x00\x00\x01' + b'QUIC_SERVER_HANDSHAKE_ACK'
                    self.sock.sendto(resp, addr)
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class MockSocks5ServerWithUdp:
    def __init__(self, port=19002, user="testuser", password="testpass", remap_ports=None):
        self.port = port
        self.user = user
        self.password = password
        self.remap_ports = remap_ports or {}
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(64)
        self.running = True
        threading.Thread(target=self._accept_loop, daemon=True).start()

    def _accept_loop(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                threading.Thread(target=self._client_loop, args=(c,), daemon=True).start()
            except Exception:
                break

    def _client_loop(self, c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # 1. 认证协商
            ver, n = struct.unpack('!BB', c.recv(2))
            c.recv(n)
            c.sendall(b'\x05\x02') # 用户名密码认证

            subver, ulen = struct.unpack('!BB', c.recv(2))
            user = c.recv(ulen).decode('utf-8', errors='ignore')
            plen = struct.unpack('!B', c.recv(1))[0]
            passwd = c.recv(plen).decode('utf-8', errors='ignore')
            if user != self.user or passwd != self.password:
                c.sendall(b'\x01\x01')
                c.close()
                return
            c.sendall(b'\x01\x00')

            # 2. 请求处理
            ver, cmd, _, atyp = struct.unpack('!BBBB', c.recv(4))
            if cmd == 1:  # CONNECT (TCP)
                if atyp == 1:
                    dest = socket.inet_ntoa(c.recv(4))
                elif atyp == 3:
                    dlen = struct.unpack('!B', c.recv(1))[0]
                    dest = c.recv(dlen).decode('utf-8', errors='ignore')
                elif atyp == 4:
                    dest = socket.inet_ntop(socket.AF_INET6, c.recv(16))
                else:
                    c.close()
                    return
                port = struct.unpack('!H', c.recv(2))[0]

                dest_ip = '127.0.0.1' if dest in ('10.0.2.2', '127.0.0.1') else dest
                target_port = self.remap_ports.get(port, port)

                target = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                target.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                target.connect((dest_ip, target_port))
                c.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')

                def forward(src, dst):
                    try:
                        while self.running:
                            d = src.recv(16384)
                            if not d: break
                            dst.sendall(d)
                    except Exception:
                        pass
                    finally:
                        try: dst.shutdown(socket.SHUT_WR)
                        except Exception: pass

                t1 = threading.Thread(target=forward, args=(c, target), daemon=True)
                t2 = threading.Thread(target=forward, args=(target, c), daemon=True)
                t1.start(); t2.start(); t1.join(); t2.join()
                c.close(); target.close()

            elif cmd == 3:  # UDP ASSOCIATE
                if atyp == 1: c.recv(4)
                elif atyp == 3: c.recv(struct.unpack('!B', c.recv(1))[0])
                elif atyp == 4: c.recv(16)
                c.recv(2)

                udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                udp_sock.bind(('0.0.0.0', 0))
                bnd_port = udp_sock.getsockname()[1]
                c.sendall(struct.pack('!BBBBIH', 5, 0, 0, 1, 0x7f000001, bnd_port))

                # 每对 (client_addr, target_port) 使用独立的 backend socket，彻底避免串线和覆盖
                backend_sockets = {}

                def udp_relay():
                    while self.running:
                        try:
                            data, client_addr = udp_sock.recvfrom(65535)
                            if len(data) < 10 or data[0] != 0 or data[1] != 0:
                                continue
                            u_atyp = data[3]
                            idx = 4
                            if u_atyp == 1:
                                dest_host = socket.inet_ntoa(data[idx:idx+4])
                                idx += 4
                            elif u_atyp == 3:
                                dlen = data[idx]
                                idx += 1
                                dest_host = data[idx:idx+dlen].decode('utf-8', errors='ignore')
                                idx += dlen
                            elif u_atyp == 4:
                                dest_host = socket.inet_ntop(socket.AF_INET6, data[idx:idx+16])
                                idx += 16
                            else:
                                continue
                            t_port = struct.unpack('!H', data[idx:idx+2])[0]
                            idx += 2
                            payload = data[idx:]

                            target_port = self.remap_ports.get(t_port, t_port)
                            target_host = '127.0.0.1' if dest_host in ('10.0.2.2', '127.0.0.1') else dest_host

                            key = (client_addr, target_host, target_port)
                            if key not in backend_sockets:
                                b_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                                backend_sockets[key] = b_sock

                                def listen_backend(sock, c_addr, orig_host, orig_port):
                                    while self.running:
                                        try:
                                            resp, _ = sock.recvfrom(65535)
                                            # SOCKS5 UDP 头部封装回传：保留客户端请求的目标原始 IP
                                            try:
                                                orig_ip_bytes = socket.inet_aton(orig_host)
                                                hdr = struct.pack('!HBB', 0, 0, 1) + orig_ip_bytes + struct.pack('!H', orig_port)
                                            except Exception:
                                                hdr = struct.pack('!HBB', 0, 0, 1) + socket.inet_aton('127.0.0.1') + struct.pack('!H', orig_port)
                                            udp_sock.sendto(hdr + resp, c_addr)
                                        except Exception:
                                            break
                                threading.Thread(target=listen_backend, args=(b_sock, client_addr, dest_host, t_port), daemon=True).start()

                            backend_sockets[key].sendto(payload, (target_host, target_port))
                        except Exception:
                            break

                threading.Thread(target=udp_relay, daemon=True).start()
                while self.running:
                    b = c.recv(1)
                    if not b: break
                for s in backend_sockets.values():
                    try: s.close()
                    except Exception: pass
                udp_sock.close(); c.close()
        except Exception:
            pass

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

def set_airplane_mode(adb, serial, enabled):
    """开关飞行模式，让系统拆掉再重建物理网络。"""
    action = "enable" if enabled else "disable"
    res = run_adb(
        adb, serial,
        ["shell", "cmd", "connectivity", "airplane-mode", action],
        check=False,
    )
    out = ((res.stdout or "") + (res.stderr or "")).strip()
    if res.returncode != 0 or "Unknown command" in out or "Error" in out:
        # 旧镜像没有 connectivity airplane-mode 子命令时走 settings + 广播。
        run_adb(adb, serial, [
            "shell", "settings", "put", "global", "airplane_mode_on",
            "1" if enabled else "0",
        ])
        run_adb(adb, serial, [
            "shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
            "--ez", "state", "true" if enabled else "false",
        ], check=False)
        return
    if out:
        print(f"    airplane-mode {action}: {out}")


def read_vpn_logs(adb, serial):
    res = run_adb(
        adb, serial,
        ["shell", "logcat", "-d", "-s", "NetworkWatchdog:I", "PingtunnelVpnService:I"],
        check=False,
        timeout=20,
    )
    return ((res.stdout or "") + "\n" + (res.stderr or "")).strip()


def wait_for_log(adb, serial, needle, timeout):
    start = time.time()
    last = ""
    while time.time() - start < timeout:
        last = read_vpn_logs(adb, serial)
        if needle in last:
            return last
        time.sleep(0.5)
    raise RuntimeError(f"超时未看到日志「{needle}」\n{last[-2500:]}")


def wait_for_any_log(adb, serial, needles, timeout):
    """等任意一个日志出现，返回 (命中的 needle, 日志全文)。

    needles 的顺序即优先级：同一段日志里多个都命中时返回靠前的那个。
    """
    start = time.time()
    last = ""
    while time.time() - start < timeout:
        last = read_vpn_logs(adb, serial)
        for needle in needles:
            if needle in last:
                return needle, last
        time.sleep(0.5)
    raise RuntimeError(f"超时未看到任一日志「{' | '.join(needles)}」\n{last[-2500:]}")


NETWORK_EVENT_LOGS = ("physical network changed", "physical network lost")


def settle_network_events(adb, serial, quiet=5.0, timeout=20):
    """等到物理网络事件平息再放行。

    场景 1 的飞行模式刚把网络拉回来，NetworkWatchdog 的事件可能晚到；带着这条
    尾巴进下一个场景，会在压测中途触发"拆数据面 → SIGTERM 掉 pingtunnel 子进程 →
    重建"，探测失败看起来像协议回归。这里清掉日志后反复观察，直到连续 quiet 秒
    不再有新事件（超时不失败，因为后续探测还有重建保护）。
    """
    run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)
    deadline = time.time() + timeout
    stable_since = None
    while time.time() < deadline:
        log = read_vpn_logs(adb, serial)
        if any(e in log for e in NETWORK_EVENT_LOGS):
            # 还有新事件在冒：清掉重来，重新计静默时长
            print("    [DEBUG] 观察到物理网络事件余波，继续等待其平息...")
            run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)
            stable_since = None
        elif stable_since is None:
            stable_since = time.time()
        elif time.time() - stable_since >= quiet:
            return
        time.sleep(1.0)
    print(f"    [WARN] {timeout}s 内物理网络事件未平息，继续（后续探测有重建保护）")


def read_connectivity(adb, serial):
    res = run_adb(adb, serial, ["shell", "dumpsys", "connectivity"], check=False, timeout=60)
    return res.stdout or ""


def assert_underlying_network_alive(text):
    """VPN 声明的底层网络必须还活着；没钉死则表示交给系统默认网络。"""
    if "VPN:com.esrrhs.pingtunnel.client" not in text:
        # 切网重建后 ConnectivityService 重新注册 VPN 网络存在短暂窗口；
        # 由调用方在行为验证（TCP 穿透）成功后重试，避免快照时序误判。
        raise VpnNetworkNotVisible()
    live = set(re.findall(r"NetworkAgentInfo\{network\{(\d+)\}", text))
    match = re.search(
        r"VPN:com\.esrrhs\.pingtunnel\.client.*?underlying\{\[([^\]]*)\]\}",
        text,
    )
    if not match:
        print("    underlying 未钉死，走系统默认网络")
        return
    inner = match.group(1).strip()
    if inner in ("", "null"):
        print("    underlying 为空，走系统默认网络")
        return
    ids = re.findall(r"\d+", inner)
    missing = [net_id for net_id in ids if net_id not in live]
    if missing:
        raise RuntimeError(
            f"VPN 底层网络已失效: {missing}，当前存活网络: {sorted(live)}"
        )
    print(f"    underlying {ids} 仍在存活网络中")


class VpnNetworkNotVisible(RuntimeError):
    """dumpsys 快照里暂时看不到 VPN 网络（切网重注册窗口）。"""


def wait_and_assert_underlying_alive(adb, serial):
    """切网后 TCP 已穿透（行为证明隧道工作），再等待 VPN 网络块回归并校验底层未钉死。

    ConnectivityService 重建 VPN 网络有数秒窗口；轮询取最新 dumpsys。
    超时仍不可见时降级为 WARN——此刻 TCP 经隧道回显成功，比快照字符串更可信。
    """
    deadline = time.time() + 10
    last_text = ""
    while time.time() < deadline:
        last_text = read_connectivity(adb, serial)
        try:
            assert_underlying_network_alive(last_text)
            print("    [PASS] 底层网络没有钉在已消失的网络上")
            return
        except VpnNetworkNotVisible:
            time.sleep(1)
    print("    [WARN] dumpsys 暂未重新列出 VPN 网络；切网后 TCP 穿透已成功，按行为判为通过")


def verify_tcp_echo(adb, serial, host_ip, token):
    tcp_cmd = f"(printf '{token}\\n'; sleep 1) | timeout 4 toybox nc {host_ip} 19001"
    last = ""
    for _ in range(5):
        last = run_adb(adb, serial, ["shell", tcp_cmd], check=False).stdout or ""
        if token in last:
            return
        time.sleep(1)
    raise RuntimeError(f"TCP 未收到预期回显: {last}")


def verify_airplane_switch(adb, serial, host_ip):
    """飞行模式丢掉物理网络再恢复（断网重连路径，非交接）。"""
    print("\n[SOCKS5] 11. 模拟断网重连（飞行模式）后隧道恢复...")
    run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)
    print("==> 打开飞行模式，丢掉当前物理网络")
    set_airplane_mode(adb, serial, True)
    try:
        wait_for_log(adb, serial, CLEAR_LOG, timeout=30)
        print("    [PASS] 旧网络断开后已松开底层绑定")
        print("==> 关闭飞行模式，等待新网络并重建隧道")
        set_airplane_mode(adb, serial, False)
        wait_for_log(adb, serial, "physical network changed", timeout=40)
        # 改绑日志先于重建完成；等 hev 再次起来，避免打到正在拆掉的旧隧道。
        wait_for_log(adb, serial, "hev tunnel started", timeout=40)
        if not wait_for_tun(adb, serial, should_exist=True, timeout=15):
            dump_vpn_log(adb, serial)
            raise RuntimeError("切网后 tun0 未能恢复")
        verify_tcp_echo(adb, serial, host_ip, "NETSWITCH_TCP")
        print("    [PASS] 切网后 TCP 穿透成功")
        wait_and_assert_underlying_alive(adb, serial)
    finally:
        set_airplane_mode(adb, serial, False)


def svc_set(adb, serial, kind, enabled):
    """开关某类无线（wifi / data）。"""
    value = "enable" if enabled else "disable"
    res = run_adb(adb, serial, ["shell", "svc", kind, value], check=False, timeout=30)
    out = ((res.stdout or "") + (res.stderr or "")).strip()
    print(f"    svc {kind} {value}: exit={res.returncode} {out or '(无输出)'}")
    return res.returncode == 0


# dumpsys 对"传输类型"和"网络状态"的写法随版本差异极大，逐 token 兼容：
#   A10 : ni{[type: WIFI[], state: CONNECTED/CONNECTED ...]} / Transport types: WIFI
#   A12+: NI{WIFI CONNECTED EXTRA: } / Transports: WIFI
# 早期版本按 `STATE: CONNECTED` 判活，在 API 34 上一个块都匹配不到（状态写在
# NI{} 里且没有 STATE: 前缀）。
#
# 注意：这份解析只用于打印诊断，**不作判定依据**。NetworkAgentInfo 块的后续行里
# 会带上 NetworkRequest 的 capabilities，扫全块会让同一个 Wi-Fi 网络同时报出
# WIFI 和 CELLULAR——据此判定"有双通道"会把单通道镜像误判成可交接。所以只看
# 网络自己的首行摘要。能否交接由 verify_network_handover 的行为探针说了算。
TRANSPORT_ALIASES = {
    "WIFI": "WIFI",
    "CELLULAR": "CELLULAR",
    "MOBILE": "CELLULAR",
    "ETHERNET": "ETHERNET",
    "VPN": "VPN",
}

_TRANSPORT_TOKEN_RE = re.compile(r"TRANSPORT_(WIFI|CELLULAR|ETHERNET|VPN|BLUETOOTH|WIFI_AWARE)")
_TRANSPORT_FIELD_RE = re.compile(r"TRANSPORTS?\s*(?:TYPES)?:\s*([A-Z|_]+)")
_TYPE_FIELD_RE = re.compile(r"TYPE:\s*([A-Z|_\[]+)")
_NI_HEAD_RE = re.compile(r"NI\{")


def parse_agent_blocks(text):
    """把 dumpsys connectivity 里的 NetworkAgentInfo 解析成 (传输集合, 状态)。

    只看每个网络自己的首行摘要：后续行是 NetworkRequest / LinkProperties 等，
    里面会带上别的传输的 capabilities。
    """
    blocks = []
    for block in text.split("NETWORKAGENTINFO{")[1:]:
        line = block.split("\n", 1)[0]
        m = _NI_HEAD_RE.search(line)
        head = line[m.end():m.end() + 200] if m else ""
        # DISCONNECTED 含 CONNECTED 子串，断开必须先判，否则会把已断的网络算成活的。
        if "DISCONNECTED" in head or "DISCONNECTING" in head:
            state = "DISCONNECTED"
        elif "CONNECTED" in head or "CONNECTED" in line:
            state = "CONNECTED"
        else:
            m2 = re.search(r"STATE:\s*([A-Z/]+)", line)
            state = m2.group(1) if m2 else "?"

        found = set()
        for tok in _TRANSPORT_TOKEN_RE.findall(line):
            alias = TRANSPORT_ALIASES.get(tok)
            if alias:
                found.add(alias)
        for pat in (_TRANSPORT_FIELD_RE, _TYPE_FIELD_RE):
            for raw in pat.findall(line):
                for tok in re.split(r"[|_\[]", raw):
                    alias = TRANSPORT_ALIASES.get(tok.strip())
                    if alias:
                        found.add(alias)
        m3 = re.match(r"\s*([A-Z_]+)", head)  # A12+ 的 NI{WIFI CONNECTED EXTRA: }
        if m3:
            alias = TRANSPORT_ALIASES.get(m3.group(1))
            if alias:
                found.add(alias)
        blocks.append((found, state))
    return blocks


def verify_network_switch(adb, serial, host_ip):
    """切网验证：能跑真实交接就跑交接，否则降级为飞行模式断网重连。

    真实切网（Wi-Fi↔蜂窝）才是线上场景；飞行模式是"网络整体消失再恢复"，
    覆盖的是断线重连。二者走的日志分支不同（SWITCH vs CLEAR），分开断言。
    """
    if not verify_network_handover(adb, serial, host_ip):
        # 交接探测会 flap 一次 Wi-Fi；没兜底通道时网络是真断过的，必须等它回到
        # 基线再跑飞行模式用例——否则"开飞行模式"时网络本来就不在，等不到
        # physical network lost，会把后续用例误判成失败。
        wait_network_restored(adb, serial)
        verify_airplane_switch(adb, serial, host_ip)


def wait_network_restored(adb, serial, timeout=60):
    """等物理网络与隧道回到可用基线（best effort），让后续用例从干净状态开始。"""
    print("    ==> 等待网络与隧道回到基线")
    run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)
    try:
        wait_for_log(adb, serial, "physical network changed", timeout=timeout)
        wait_for_log(adb, serial, "hev tunnel started", timeout=timeout)
    except Exception as exc:
        print(f"    [WARN] 等待基线恢复超时（{exc}），继续后续用例")
    if not wait_for_tun(adb, serial, should_exist=True, timeout=20):
        print("    [WARN] tun0 未恢复，继续后续用例（由用例自身断言把关）")


def report_network_topology(adb, serial):
    """打印设备上报的传输类型与网络接口（纯诊断，不参与判定）。"""
    text = read_connectivity(adb, serial).upper()
    blocks = parse_agent_blocks(text)
    seen = [f"{'+'.join(sorted(t)) or '?'}/{s}" for t, s in blocks]
    print(f"    [DEBUG] connectivity 网络块: len={len(text)} agents={len(seen)} {seen[:8]}")
    print(f"    [DEBUG] 接口: {transports_from_interfaces(adb, serial)}")
    return {tr for trs, st in blocks if st == "CONNECTED" for tr in trs}


# 蜂窝/ Wi-Fi 在 sysfs 里的常见接口名前缀（模拟器 netsim、qemu 与各厂商 modem 均覆盖）。
CELLULAR_IFACE_PREFIXES = ("rmnet", "radio", "ccmni", "wwan", "ccinet", "v4-rmnet")
WIFI_IFACE_PREFIXES = ("wlan", "wifi", "swlan")


def transports_from_interfaces(adb, serial):
    """dumpsys 不可信时的兜底探测：按网络接口名判断有没有 Wi-Fi / 蜂窝。

    接口存在 ≠ 网络已连接，但这里只是决定"要不要试交接"——真跑了以后仍由
    SWITCH/CLEAR 断言把关，误报会走降级分支而不会误判为通过。
    """
    res = run_adb(adb, serial, ["shell", "ls /sys/class/net"], check=False, timeout=20)
    names = (res.stdout or "").split()
    found = set()
    if any(n.startswith(CELLULAR_IFACE_PREFIXES) for n in names):
        found.add("CELLULAR")
    if any(n.startswith(WIFI_IFACE_PREFIXES) for n in names):
        found.add("WIFI")
    print(f"    [DEBUG] 接口兜底探测: ifaces={names} -> {sorted(found)}")
    return found


def assert_switch_not_clear(text):
    """交接与断网的本质区别：切换全程至少有一个物理网络在。

    NetworkWatchdog 走 CLEAR（"physical network lost"）说明网络整体消失，
    那是"断网重连"而不是"交接"——飞行模式用例已经覆盖，这里必须区分开。
    """
    if "physical network lost" in text:
        raise RuntimeError("物理网络整体消失（无兜底通道），属断网重连而非交接")


# 判定顺序即优先级：同一段日志里两者都出现时按 CLEAR 算（保守，宁可降级）。
SWITCH_LOG = "physical network changed"
CLEAR_LOG = "physical network lost, release underlying"


HANDOVER_PROBE_TIMEOUT = 25


def try_handover_step(adb, serial, kind, timeout=HANDOVER_PROBE_TIMEOUT):
    """关掉某类无线，返回 watchdog 的判定：SWITCH / CLEAR / None（无可观测变化）。"""
    run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)
    svc_set(adb, serial, kind, False)
    try:
        hit, _ = wait_for_any_log(adb, serial, (CLEAR_LOG, SWITCH_LOG), timeout=timeout)
        return hit
    except RuntimeError:
        return None


def verify_network_handover(adb, serial, host_ip):
    """真实交接：关掉一条通道，物理网络若没整体消失即为交接（SWITCH）。

    判据不用 dumpsys 上报的传输类型——模拟器同一个 NetworkAgentInfo 块里能同时
    扫出 WIFI 和 CELLULAR（块内带着 NetworkRequest 的 capabilities），据此判
    "有双通道"会把单通道镜像误判成可交接。改成行为探针：真的关掉一条通道，看
    NetworkWatchdog 报 SWITCH（网络只是换了一条）还是 CLEAR（网络整体消失）。

    先关 Wi-Fi（真机场景），没反应再关蜂窝——模拟器 VPN 的底层网络常常本就钉在
    蜂窝上（dumpsys 里是 CELLULAR+VPN），关 Wi-Fi 对它不可见，反过来才观察得到。
    报 CLEAR 说明本机没有可交接的通道，交给飞行模式用例去覆盖。
    """
    print("\n[SOCKS5] 11. 探测设备能否做真实交接（关一条通道，看网络是否只是换了一条）...")
    report_network_topology(adb, serial)
    svc_set(adb, serial, "data", True)
    svc_set(adb, serial, "wifi", True)
    time.sleep(3)
    try:
        for kind, label in (("wifi", "Wi-Fi"), ("data", "蜂窝")):
            print(f"==> 关闭 {label}，看物理网络是否只是换了一条而非消失")
            hit = try_handover_step(adb, serial, kind)
            if hit is None:
                print(f"    [DEBUG] svc {kind} disable 未造成可观测的网络变化，换下一种")
                svc_set(adb, serial, kind, True)
                time.sleep(3)
                continue
            if hit == CLEAR_LOG:
                print(f"    [SKIP] 关掉 {label} 后物理网络整体消失：本机没有兜底通道（属断网重连）")
                return False
            print(f"    [PASS] 关掉 {label} 走 SWITCH 分支（物理网络未整体消失）")
            wait_for_log(adb, serial, "hev tunnel started", timeout=45)
            if not wait_for_tun(adb, serial, should_exist=True, timeout=15):
                dump_vpn_log(adb, serial)
                raise RuntimeError(f"关掉 {label} 后 tun0 未恢复")
            verify_tcp_echo(adb, serial, host_ip, f"HANDOVER_OFF_{kind}")
            print(f"    [PASS] 交接后 TCP 穿透成功")
            wait_and_assert_underlying_alive(adb, serial)

            print(f"==> 恢复 {label}，切回原通道")
            run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)
            svc_set(adb, serial, kind, True)
            back, text = wait_for_any_log(adb, serial, (CLEAR_LOG, SWITCH_LOG), timeout=45)
            assert_switch_not_clear(text)
            if back == CLEAR_LOG:
                raise RuntimeError(f"恢复 {label} 途中物理网络整体消失，非交接")
            print(f"    [PASS] 切回后走 SWITCH 分支")
            wait_for_log(adb, serial, "hev tunnel started", timeout=45)
            if not wait_for_tun(adb, serial, should_exist=True, timeout=15):
                dump_vpn_log(adb, serial)
                raise RuntimeError(f"恢复 {label} 后 tun0 未恢复")
            verify_tcp_echo(adb, serial, host_ip, f"HANDOVER_ON_{kind}")
            print(f"    [PASS] 切回后 TCP 穿透成功")
            wait_and_assert_underlying_alive(adb, serial)
            return True
        print(
            "    [SKIP] svc wifi/data 都未造成可观测的网络变化"
            "（模拟器 Wi-Fi 由 netsim 托管，svc 可能无效），降级飞行模式"
        )
        return False
    except Exception as exc:
        # 交接是"更强"的用例：条件不成立时降级，而不是让整条流水线红掉。
        # 想把它当硬门槛跑（真机/有蜂窝的镜像）时设 E2E_STRICT_HANDOVER=1。
        if os.environ.get("E2E_STRICT_HANDOVER") == "1":
            raise
        print(f"    [WARN] 交接用例未成立（{exc}），降级飞行模式切网")
        return False
    finally:
        svc_set(adb, serial, "wifi", True)
        svc_set(adb, serial, "data", True)


def wait_for_tun(adb_cmd, serial, should_exist=True, timeout=15):
    start = time.time()
    while time.time() - start < timeout:
        # 用 sysfs 判断，避免 ip 报错文本里带 tun0 造成误判。
        res = run_adb(adb_cmd, serial, ["shell", "test", "-d", "/sys/class/net/tun0"], check=False)
        has_tun = res.returncode == 0
        if has_tun == should_exist:
            return True
        time.sleep(0.5)
    return False


def assert_tun_default_routes(adb_cmd, serial, expect_ipv6=False):
    """新分流架构下 VPN 必须声明默认路由（全量抓包，CN/LAN 分流下沉用户态）。

    Android 把 VPN 路由放在 LinkProperties/策略路由表，``ip route show dev tun0``
    在部分镜像只返回接口直连路由，因此用两个系统权威来源取证：
      1. dumpsys connectivity 中 VPN 网络块的 Routes（LinkProperties 原文）；
      2. ``ip route show table all`` 中所有指向 tun0 的默认路由。
    都取不到时不判失败：紧随其后的八协议穿透是更强的行为裁判。
    """
    dump = read_connectivity(adb_cmd, serial)
    v4_ok = v6_ok = False
    if "VPN:com.esrrhs.pingtunnel.client" in dump:
        # VPN 网络块的 LinkProperties（取标记后一段，避免扫到无关网络）
        block = dump.split("VPN:com.esrrhs.pingtunnel.client", 1)[1][:8000]
        v4_ok = "0.0.0.0/0" in block
        v6_ok = "::/0" in block

    if not v4_ok:
        routes = run_adb(adb_cmd, serial, ["shell", "ip", "route", "show", "table", "all"], check=False)
        rtext = (routes.stdout or "") + (routes.stderr or "")
        tun_default = [ln for ln in rtext.splitlines() if "tun0" in ln
                       and ("default" in ln or "0.0.0.0/0" in ln)]
        v6_ok = v6_ok or any(("::/0" in ln or ("default" in ln and ":" in ln)) for ln in tun_default)
        v4_ok = len(tun_default) > 0

    if v4_ok:
        print("    [PASS] VPN 声明 IPv4 默认路由（全量抓包，用户态分流）")
    else:
        print("    [WARN] 未从 dumpsys/路由表读到默认路由，改由后续全协议穿透做行为验证")
    if expect_ipv6:
        print("    [PASS] VPN 声明 IPv6 默认路由" if v6_ok else "    [WARN] 未读到 IPv6 默认路由")

def run_probe(adb, serial, shell_cmd, timeout=12):
    """单次设备侧探测，返回 stdout；设备侧命令自带 timeout，adb 异常不向上抛。"""
    return (run_adb(adb, serial, ["shell", shell_cmd], check=False,
                    timeout=timeout).stdout or "")


CORE_CHILD_PATTERN = "libpingtunnel.so"

# pingtunnel 服务端（跑在模拟器内部）使用的共享密码；v3 要求最少 8 个字符
E2E_SERVER_PASSWORD = "e2e-test-pass"


def get_guest_ip(adb, serial):
    """取模拟器 eth0 的 IPv4（标准镜像为 10.0.2.15），App 的 ICMP 隧道直连它。"""
    out = run_adb(adb, serial, ["shell", "ip", "-f", "inet", "-o", "addr", "show", "eth0"], check=False).stdout
    m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", out)
    if not m:
        raise RuntimeError(f"无法获取模拟器 eth0 IP：{out!r}")
    return m.group(1)


def start_in_emulator_pingtunnel_server(adb, serial, password=E2E_SERVER_PASSWORD):
    """把 pingtunnel server 构建并推进模拟器，以 root 常驻运行。

    两个关键点（实测 Android 15 模拟器）：
    1. 服务端要接收任意 ICMP echo 请求，必须 raw socket。默认的 Android 构建
       （icmp_listen_android.go）优先用 dgram ping socket——它只能收 echo
       回复，服务端会"聋"。这里用 go build -overlay 把该文件替换为 raw 版本
       （scripts/e2e/icmp_listen_android_raw.go）再交叉编译。
    2. 宿主机方案不可行：raw ICMP 需要 root，且 QEMU slirp 对自定义 ICMP
       回复会截短 2 字节、丢弃重复回复，payload 敏感的协议无法工作；模拟器
       内部（adb root）回环不经过 slirp，最稳定。
    3. 常驻用 setsid：模拟器上 nohup 后台进程会随 adb shell 会话退出被杀。
    """
    abi = run_adb(adb, serial, ["shell", "getprop", "ro.product.cpu_abi"]).stdout.strip()
    if not abi:
        # 部分镜像上该属性为空（CI 的 API34 google_apis 实测）：退到 abilist 首项，再退到 uname -m
        abilist = run_adb(adb, serial, ["shell", "getprop", "ro.product.cpu.abilist"], check=False).stdout.strip()
        abi = abilist.split(",")[0].strip() if abilist else ""
    if not abi:
        machine = run_adb(adb, serial, ["shell", "uname", "-m"], check=False).stdout.strip()
        abi = {"x86_64": "x86_64", "aarch64": "arm64-v8a", "i686": "x86", "armv8l": "armeabi-v7a"}.get(machine, "")
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_dir = os.path.join(repo_root, "third_party", "pingtunnel")
    if not os.path.isdir(os.path.join(src_dir, ".git")):
        # build_pingtunnel.sh 用 PINGTUNNEL_DIR 时可能指向仓库外，此处兜底克隆
        subprocess.run(
            ["git", "clone", "https://github.com/esrrhs/pingtunnel.git", src_dir],
            check=True,
        )
    overlay_src = os.path.join(repo_root, "scripts", "e2e", "icmp_listen_android_raw.go")
    overlay = {
        "Replace": {
            os.path.join(src_dir, "icmp_listen_android.go"): os.path.abspath(overlay_src),
        }
    }
    overlay_file = os.path.join(repo_root, "scripts", "e2e", "overlay.json")
    with open(overlay_file, "w", encoding="utf-8") as f:
        json.dump(overlay, f)
    goarch = {"arm64-v8a": "arm64", "x86_64": "amd64", "x86": "386", "armeabi-v7a": "arm"}.get(abi)
    if not goarch:
        raise RuntimeError(f"未知 ABI: {abi}")
    out_bin = os.path.join(repo_root, "scripts", "e2e", f"pingtunnel-server-{abi}")
    print(f"==> 构建模拟器内服务端 ({abi})")
    subprocess.run(
        ["go", "build", "-overlay", overlay_file, "-trimpath", "-o", out_bin, "."],
        cwd=os.path.join(src_dir, "cmd"),
        env={**os.environ, "GOOS": "android", "GOARCH": goarch, "CGO_ENABLED": "0"},
        check=True,
    )
    run_adb(adb, serial, ["push", out_bin, "/data/local/tmp/pingtunnel-server"])
    run_adb(adb, serial, ["shell", "chmod", "755", "/data/local/tmp/pingtunnel-server"])
    run_adb(adb, serial, ["shell", "pkill", "-f", "pingtunnel-server -type server"], check=False)
    time.sleep(1)
    run_adb(
        adb, serial,
        ["shell", f"setsid /data/local/tmp/pingtunnel-server -type server -password {password} -nolog 1"
                  " >/data/local/tmp/pt-server.log 2>&1 &"],
        check=False,
    )
    time.sleep(2)
    out = run_adb(adb, serial, ["shell", "pgrep", "-f", "pingtunnel-server -type server"], check=False).stdout.strip()
    if not out:
        srv_log = run_adb(adb, serial, ["shell", "cat", "/data/local/tmp/pt-server.log"], check=False).stdout
        raise RuntimeError(f"模拟器内 pingtunnel 服务端启动失败（adb root 是否生效？）日志：\n{srv_log}")
    print(f"==> 模拟器内 pingtunnel 服务端已启动 (password=***, pid={out.splitlines()[0]})")


def core_child_pids(adb, serial):
    """当前存活的 libpingtunnel.so 子进程 PID 列表（看门狗重启后 PID 会变）。"""
    out = run_adb(adb, serial, ["shell", f"pgrep -f {CORE_CHILD_PATTERN}"],
                  check=False).stdout or ""
    return [p.strip() for p in out.split() if p.strip()]


def wait_for_core_child(adb, serial, timeout=25):
    """等看门狗把新的 pingtunnel 子进程拉起来，返回新 PID；超时返回 None。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        pids = core_child_pids(adb, serial)
        if pids:
            return pids[0]
        time.sleep(0.5)
    return None


def network_event_since_scene_start(adb, serial):
    """本场景内看门狗是否报告过物理网络变化/丢失。

    App 收到这类事件会拆掉整个数据面重建（含 SIGTERM 掉 pingtunnel 子进程），这是合法
    行为——前一个场景的飞行模式切网刚把网络拉回来，晚到的事件会落到下一个场景里。
    调用前须清 logcat，否则会读到上一个场景的陈旧事件。
    """
    log = read_vpn_logs(adb, serial)
    return any(e in log for e in NETWORK_EVENT_LOGS)


def assert_core_child_alive(adb, serial, expected_pid=None, where=""):
    """pingtunnel 子进程必须还活着——它一退出，后面的探测全变成"没有代理"，与协议无关。

    两种情况要分开：
    - 本场景内有过物理网络事件：App 正在按设计重建数据面，等它把新子进程拉起来
      再继续。这不是协议回归，硬失败只会掩盖真正的原因。
    - 没有任何网络事件却进程没了：真故障，立刻抛明确错误并附日志。

    之前这里只会表现为"连续 3 次未收到预期响应"，看起来像协议回归，实际是
    native 子进程起来没多久就被（合法地）拆掉了。
    """
    pids = core_child_pids(adb, serial)
    if pids:
        if expected_pid and expected_pid not in pids:
            print(
                f"    [WARN] pingtunnel 子进程 PID 已变（{expected_pid} -> {'/'.join(pids)}）："
                f"看门狗重启过，本次探测可能打在重启窗口上"
            )
        return pids[0]

    if network_event_since_scene_start(adb, serial):
        print("    [DEBUG] 本场景内出现过网络切换事件：App 正在重建数据面，等待新的 pingtunnel 子进程")
        restored = wait_for_core_child(adb, serial)
        if restored:
            print(f"    [PASS] 网络切换恢复完成，pingtunnel 子进程已重建（PID {restored}）")
            return restored

    dump_vpn_log(adb, serial, reason=f"{where}pingtunnel 子进程已退出")
    raise RuntimeError(
        f"{where}pingtunnel 子进程（{CORE_CHILD_PATTERN}）已退出"
        + (f"，原 PID {expected_pid}" if expected_pid else "")
        + "：后续探测必然失败。这是 native 进程退出，不是协议回归，见上方日志。"
    )


def probe_with_retries(adb, serial, mode_name, case_name, shell_cmd,
                       required, attempts=3, gap=0.8, timeout=12):
    """对端到端探测做有限次重试。

    共享 runner 上的软件渲染模拟器在隧道刚建立、UDP ASSOCIATE 刚完成时，
    首批报文可能在转发路径就绪前被丢弃（失败点随用例随机漂移）。
    有限重试只消除这类预热竞态，不掩盖真实回归——连续 attempts 次失败仍判 FAIL。
    """
    if isinstance(required, (str, bytes)):
        required = (required,)
    last = ""
    for i in range(1, attempts + 1):
        last = run_probe(adb, serial, shell_cmd, timeout)
        if all(token in last for token in required):
            return last
        if i < attempts:
            print(f"    [RETRY] {case_name} 第 {i}/{attempts} 次无预期响应，重试...")
            time.sleep(gap)
    # 失败必须带设备侧日志：只有一句"未收到预期响应"，在 CI 里根本无从下手
    dump_vpn_log(adb, serial, reason=f"[{mode_name}] {case_name} 连续失败")
    raise RuntimeError(
        f"[{mode_name}] {case_name} 连续 {attempts} 次未收到预期响应: {last}"
    )


def wait_for_data_plane(adb, serial, host_ip, mode_name, timeout=30):
    """等数据面真正能双向通，再开始正式验收。

    tun0 起来只说明 fd 建好了，不代表 hev ↔ 上游这条转发路径已经能回包。
    实测过一次偶发失败：服务端那边 open/Dial/close 全正常，请求也确实出去了，
    但回程数据没到 app，连续 3 次探测全空——看起来像协议回归，实际是刚建链时
    转发路径没收敛。把"等收敛"独立出来，收敛不了就报"数据面未收敛"并附日志，
    别让启动竞态伪装成某个协议的回归。
    """
    token = f"{mode_name}_READY"
    cmd = f"(printf '{token}\\n'; sleep 1) | timeout 4 toybox nc {host_ip} 19001"
    deadline = time.time() + timeout
    attempt = 0
    while time.time() < deadline:
        attempt += 1
        out = run_probe(adb, serial, cmd, 10)
        if token in out:
            print(f"    [PASS] 数据面已双向收敛（第 {attempt} 次握手成功）")
            return
        time.sleep(1.0)
    dump_vpn_log(adb, serial, reason=f"{mode_name} 数据面 {timeout}s 未收敛")
    raise RuntimeError(
        f"[{mode_name}] 数据面在 {timeout}s 内未收敛：tun0 已就绪但回程不通。"
        f"这是建链收敛问题，不是协议回归；见上方 logcat。"
    )


def verify_udp_session_export(adb, serial, host_ip, mode_name):
    """验证 UDP 会话被 native 实时导出。

    App 每 1.5s 把 hev 原生会话原文打到 logcat（tag ConnRecDiag）。
    在设备侧跑一条持续 ~6s 的 UDP 流，期间必然与多次轮询重叠；
    在整段 logcat 文本中正则匹配 17|...|host|19001| 行
    （logcat 会把会话原文中的换行输出成无 tag 的续行，故不可按行解析）。
    """
    token = f"{mode_name}_SESSION_UDP"
    sender = ("(for i in $(seq 1 10); do printf '"
              f"{token}\\n'; sleep 0.5; done) | "
              f"timeout 8 toybox nc -u {host_ip} 19001")
    # 17|srcIp|srcPort|dstIp|19001|...
    pattern = re.compile(
        r"17\|[^|\n]*\|\d+\|" + re.escape(host_ip) + r"\|19001\|"
    )
    for round_no in range(1, 4):
        run_adb(adb, serial, ["shell", "logcat -c"], check=False)
        run_adb(adb, serial, ["shell", sender], check=False, timeout=15)
        log = run_adb(adb, serial,
                      ["shell", "logcat -d -s ConnRecDiag"], check=False,
                      timeout=15).stdout or ""
        m = pattern.search(log)
        if m:
            print(f"    [PASS] UDP 会话已实时导出: {m.group(0)}")
            return
        time.sleep(0.5)
    raise RuntimeError(
        f"[{mode_name}] 未在 ConnRecDiag 日志中观察到目标 UDP 会话导出"
    )


def verify_all_protocols(adb, serial, host_ip, mode_name, liveness=None):
    """验证 TCP, UDP, HTTP/1.1, HTTP/2, DNS, WebSocket, gRPC, QUIC 八大常见网络协议穿透与回显。

    liveness：可选探活回调，每次探测前调用（用它确认 native 子进程还活着）。
    代理进程已经不在时重试毫无意义，还会把故障伪装成协议回归。
    """
    def probe(case_name, shell_cmd, required, attempts=3, gap=0.8, timeout=12):
        if liveness is not None:
            liveness()
        return probe_with_retries(adb, serial, mode_name, case_name, shell_cmd,
                                  required, attempts, gap, timeout)

    time.sleep(1) # 等待网络路由与规则完全收敛

    print(f"[{mode_name}] 1. 测试 TCP 报文穿透...")
    tcp_cmd = f"(printf '{mode_name}_TCP_TEST\\n'; sleep 1) | timeout 4 toybox nc {host_ip} 19001"
    probe("TCP 报文穿透",
                       tcp_cmd, f"{mode_name}_TCP_TEST")
    print(f"    [PASS] TCP 穿透成功！")

    print(f"[{mode_name}] 2. 测试 UDP 数据报穿透...")
    # UDP 中继首次建链存在竞态：同一条 nc 会话内连发 3 包覆盖 ASSOCIATE 建链窗口
    udp_cmd = ("(for i in 1 2 3; do printf '"
               f"{mode_name}_UDP_TEST\\n'; sleep 0.4; done; sleep 1) | "
               f"timeout 5 toybox nc -u {host_ip} 19001")
    probe("UDP 数据报穿透",
                       udp_cmd, f"{mode_name}_UDP_TEST")
    print(f"    [PASS] UDP 穿透成功！")

    # 新隧道的 UDP 中继此刻已热，顺势验证 UDP 会话被 native 实时导出
    verify_udp_session_export(adb, serial, host_ip, mode_name)

    print(f"[{mode_name}] 3. 测试 HTTP/1.1 请求穿透...")
    h1_cmd = f"(printf 'GET / HTTP/1.1\\r\\nHost: {host_ip}:19004\\r\\nConnection: close\\r\\n\\r\\n'; sleep 1) | timeout 4 toybox nc {host_ip} 19004"
    probe("HTTP/1.1 请求穿透",
                       h1_cmd, "HTTP1_OK")
    print(f"    [PASS] HTTP/1.1 穿透成功！")

    print(f"[{mode_name}] 4. 测试 HTTP/2 握手前奏与帧解析穿透...")
    h2_cmd = f"(printf 'PRI * HTTP/2.0\\r\\n\\r\\nSM\\r\\n\\r\\n\\x00\\x00\\x00\\x04\\x00\\x00\\x00\\x00\\x00'; sleep 1) | timeout 4 toybox nc {host_ip} 19005 | od -A n -t x1"
    probe("HTTP/2 帧握手",
                       h2_cmd, "04", attempts=3, gap=0.5)
    print(f"    [PASS] HTTP/2 前奏与帧握手穿透成功！")

    print(f"[{mode_name}] 5. 测试 DNS 域名解析报文穿透 (UDP 53/自定义端口)...")
    dns_cmd = ("(for i in 1 2 3; do printf '"
               "\\x12\\x34\\x01\\x00\\x00\\x01\\x00\\x00\\x00\\x00\\x00\\x00"
               "\\x04test\\x05local\\x00\\x00\\x01\\x00\\x01'; sleep 0.4; done; sleep 1) | "
               f"timeout 5 toybox nc -u {host_ip} 19006 | od -A n -t x1")
    probe("DNS 域名解析",
                       dns_cmd, ("12", "81"))
    print(f"    [PASS] DNS UDP 查询穿透与解析回包成功！")

    print(f"[{mode_name}] 6. 测试 WebSocket 握手升级与数据帧穿透...")
    ws_cmd = f"(printf 'GET /ws HTTP/1.1\\r\\nHost: {host_ip}:19007\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\\r\\nSec-WebSocket-Version: 13\\r\\n\\r\\n\\x81\\x84\\x00\\x00\\x00\\x00TEST'; sleep 1) | timeout 4 toybox nc {host_ip} 19007"
    probe("WebSocket 握手",
                       ws_cmd, ("101 Switching Protocols", "TEST"))
    print(f"    [PASS] WebSocket 协议握手与双向帧传输穿透成功！")

    print(f"[{mode_name}] 7. 测试 gRPC (HTTP/2 + application/grpc) 帧交互穿透...")
    grpc_cmd = f"(printf 'PRI * HTTP/2.0\\r\\n\\r\\nSM\\r\\n\\r\\n\\x00\\x00\\x00\\x04\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x04\\x01\\x04\\x00\\x00\\x00\\x01GRPC'; sleep 1) | timeout 4 toybox nc {host_ip} 19008 | od -A n -t c"
    probe("gRPC 帧交互",
                       grpc_cmd, ("G", "R", "P"), attempts=3, gap=0.5)
    print(f"    [PASS] gRPC 链路握手与 HEADERS 流穿透成功！")

    print(f"[{mode_name}] 8. 测试 QUIC / HTTP/3 (UDP Long Header) 初始包穿透...")
    # 与普通 UDP 同理：连发 4 包覆盖 UDP ASSOCIATE 首包竞态
    quic_cmd = ("(for i in 1 2 3 4; do printf '"
                "\\xc0\\x00\\x00\\x00\\x01\\x00\\x08\\x11\\x22\\x33\\x44\\x55\\x66\\x77\\x88"
                "QUIC_CLIENT_HELLO'; sleep 0.4; done; sleep 1) | "
               f"timeout 6 toybox nc -u {host_ip} 19009")
    probe("QUIC 初始包握手",
                       quic_cmd, "QUIC_SERVER_HANDSHAKE_ACK")
    print(f"    [PASS] QUIC / HTTP/3 初始报文与握手协商穿透成功！")

def verify_stress_and_throughput(adb, serial, host_ip, mode_name):
    """验证高并发连接 (50 并发) 与 5MB 大流量数据吞吐 (SHA-256 完整性与速率校验)"""
    print(f"\n[{mode_name}] 9. 运行高并发连接压力测试 (50 并发同时请求)...")
    concurrency_cmd = f"for i in $(seq 1 50); do (echo {mode_name}_STRESS_$i; sleep 1) | timeout 4 toybox nc -w 3 {host_ip} 19001 & done; wait"
    start_c = time.time()
    stress_res = run_adb(adb, serial, ["shell", concurrency_cmd], timeout=20).stdout
    elapsed_c = max(0.01, time.time() - start_c)
    matched = len(re.findall(rf"{mode_name}_STRESS_\d+", stress_res))
    print(f"    [PASS] 50 并发请求测试完成，成功率: {matched}/50 (耗时: {elapsed_c:.2f}s)")
    if matched < 45:
        raise RuntimeError(f"[{mode_name}] 并发测试成功率过低: {matched}/50")

    print(f"[{mode_name}] 10. 运行 5MB 大流量吞吐与 SHA-256 完整性压测...")
    # 5 MiB = 4096 * 1280。模拟器 toybox dd 用 1MiB 块会分配失败，管道得到空数据，
    # SHA-256 变成空串的散列。sleep 保持写端打开，避免 nc 在回显读完前关掉连接。
    throughput_cmd = (
        f"(toybox dd if=/dev/zero bs=4096 count=1280; sleep 2) | "
        f"timeout 20 toybox nc {host_ip} 19001 | "
        f"toybox dd bs=4096 count=1280 | toybox sha256sum"
    )
    start_t = time.time()
    tp = run_adb(adb, serial, ["shell", throughput_cmd], timeout=30)
    tp_res = tp.stdout.strip()
    elapsed_t = max(0.01, time.time() - start_t)

    actual_hash = tp_res.split()[0] if tp_res else ""
    expected_hash = "c036cbb7553a909f8b8877d4461924307f27ecb66cff928eeeafd569c3887e29"
    speed_mbps = 5.0 / elapsed_t

    if actual_hash != expected_hash:
        err = (tp.stderr or "").strip()
        raise RuntimeError(
            f"[{mode_name}] 5MB 大流量 SHA-256 校验失败！期望: {expected_hash}, 实际: {actual_hash}"
            + (f"\nstderr: {err}" if err else "")
        )

    print(f"    [PASS] 5MB 数据流完整穿透并回显！SHA-256 散列一致: {actual_hash[:16]}... (耗时: {elapsed_t:.2f}s, 吞吐量: {speed_mbps:.2f} MB/s)")


def main():
    parser = argparse.ArgumentParser(description="E2E Android Emulator VPN Test")
    parser.add_argument("--serial", default=None, help="Android device serial")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk", help="Path to debug APK")
    parser.add_argument("--skip-install", action="store_true", help="Skip installing APK")
    args = parser.parse_args()

    adb = find_adb()
    
    # 1. 设备检查与自动识别
    devices_out = run_adb(adb, "", ["devices"]).stdout
    attached = [line.split()[0] for line in devices_out.splitlines()[1:] if '\tdevice' in line]
    if not attached:
        print(f"错误：未检测到任何连接的 Android 设备或模拟器！当前输出：\n{devices_out}")
        sys.exit(1)

    serial = args.serial
    if not serial or serial not in attached:
        emulators = [d for d in attached if "emulator" in d]
        serial = emulators[0] if emulators else attached[0]

    print(f"==> 使用 ADB: {adb}, 目标设备: {serial}")

    # 尝试切换 root 权限以保障权限静默配置。root 会重启 adbd，需要等设备回来。
    subprocess.run([adb, "-s", serial, "root"], capture_output=True)
    subprocess.run([adb, "-s", serial, "wait-for-device"], capture_output=True)

    # 2. 安装最新 APK
    if not args.skip_install and os.path.exists(args.apk):
        assert_native_libs(args.apk)
        print(f"==> 安装 APK: {args.apk} ...")
        run_adb(adb, serial, ["install", "-r", args.apk])
        print("==> APK 安装成功")

    # 3. 授权 VPN 权限并前台激活应用
    print("==> 预授予 VPN 权限...")
    grant_vpn_consent(adb, serial)
    run_adb(adb, serial, ["shell", "am", "start", "-n", "com.esrrhs.pingtunnel.client/.MainActivity"], check=False)
    time.sleep(1)

    # 先断开可能残留的 VPN
    run_adb(adb, serial, [
        "shell", "am", "start-foreground-service",
        "-a", "com.esrrhs.pingtunnel.client.action.DISCONNECT",
        "com.esrrhs.pingtunnel.client/.vpn.PingtunnelVpnService"
    ], check=False)
    wait_for_tun(adb, serial, should_exist=False, timeout=5)

    host_lan_ip = get_host_lan_ip()
    print(f"==> 本机局域网 IP: {host_lan_ip}")

    # 4. 启动目标测试服务端
    tcp_server = TcpEchoServer(19001)
    tcp_server.start()
    print("==> TCP Echo 服务已在端口 19001 启动")

    udp_server = UdpEchoServer(19001)
    udp_server.start()
    print("==> UDP Echo 服务已在端口 19001 启动")

    h1_server = Http1EchoServer(19004)
    h1_server.start()
    print("==> HTTP/1.1 服务已在端口 19004 启动")

    h2_server = Http2MockServer(19005)
    h2_server.start()
    print("==> HTTP/2 握手服务已在端口 19005 启动")

    dns_server = DnsMockServer(19006)
    dns_server.start()
    print("==> DNS Mock 服务已在端口 19006 启动")

    ws_server = WebSocketMockServer(19007)
    ws_server.start()
    print("==> WebSocket 服务已在端口 19007 启动")

    grpc_server = GrpcMockServer(19008)
    grpc_server.start()
    print("==> gRPC 服务已在端口 19008 启动")

    quic_server = QuicMockServer(19009)
    quic_server.start()
    print("==> QUIC 服务已在端口 19009 启动")

    # pingtunnel 服务端：直接跑在模拟器内部（脚本开头 adb root 已提权，raw ICMP 可用）。
    # 不在宿主机跑：pingtunnel server 要接收任意 echo 请求（必须 raw socket / root），
    # 且 QEMU slirp 对自定义 ICMP 载荷的转发不可靠；走模拟器内部回环最真实稳定。
    guest_ip = get_guest_ip(adb, serial)
    print(f"==> 模拟器 eth0 IP: {guest_ip}")
    start_in_emulator_pingtunnel_server(adb, serial, password=E2E_SERVER_PASSWORD)

    try:
        # ==========================================================
        # 测试: pingtunnel 全协议端到端 (TUN -> hev -> [RuleSocks] -> libpingtunnel -> ICMP)
        # ==========================================================
        print("="*60)
        print("【测试场景】: 真实 pingtunnel ICMP 协议与 Native 进程全协议集成测试")
        print("="*60)

        # 网络事件的余波可能晚到并触发看门狗重建数据面。先等它平息，再清日志，
        # 使后续事件可归属本场景。
        settle_network_events(adb, serial)
        run_adb(adb, serial, ["shell", "logcat", "-c"], check=False)

        pingtunnel_profile = {
            "id": "e2e-pingtunnel",
            "name": "E2E_PINGTUNNEL",
            "bypassLan": False,
            "bypassCn": False,
            "config": {
                "serverHost": guest_ip,
                "password": E2E_SERVER_PASSWORD,
                "enableIpv6": False
            }
        }
        profile_json = json.dumps(pingtunnel_profile)
        print("==> 发送 pingtunnel CONNECT 意图...")
        start_vpn_service(adb, serial, "com.esrrhs.pingtunnel.client.action.CONNECT", profile_json)


        print("==> 等待 tun0 网卡与 libpingtunnel.so 启动...")
        if not wait_for_tun(adb, serial, should_exist=True, timeout=20):
            dump_vpn_log(adb, serial)
            raise RuntimeError("tun0 网卡未能按时建立！")
        print("==> tun0 虚拟网卡已就绪")

        # 检查 libpingtunnel.so 进程
        pids = core_child_pids(adb, serial)
        print(f"==> libpingtunnel.so 子进程 PID: {'/'.join(pids) or '(无)'}")
        if not pids:
            raise RuntimeError("libpingtunnel.so 进程未运行！")
        # native 子进程一退出，后面所有探测都会变成"没有代理"，与协议无关。
        # 每次探测前确认它还活着，死则立刻报明确原因（而不是让重试伪装成协议回归）。
        # PID 用盒子装：网络切换重建后 PID 会变，回写以免后续每次都刷"PID 已变"。
        core_pid_box = [pids[0]]

        def core_liveness():
            core_pid_box[0] = assert_core_child_alive(
                adb, serial, core_pid_box[0], "pingtunnel 模式："
            )

        # 纯全局代理模式下 hev 直连 pingtunnel（不经过用户态分流器），
        # 但 TUN 仍必须是默认路由全抓。
        assert_tun_default_routes(adb, serial, expect_ipv6=False)

        # tun0 就绪 ≠ 数据面能双向通：先等收敛，再跑八大协议验收
        wait_for_data_plane(adb, serial, host_lan_ip, "pingtunnel")

        # 验证八大协议
        verify_all_protocols(adb, serial, host_lan_ip, "pingtunnel", liveness=core_liveness)

        # 并发与大流量压测前再确认一次：长跑中途退出同样会被重试掩盖
        core_liveness()

        # 验证 50 并发压力与 5MB 大流量数据吞吐 (SHA-256 校验)
        verify_stress_and_throughput(adb, serial, host_lan_ip, "pingtunnel")

        # 断开 VPN
        print("==> 断开 pingtunnel VPN...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.pingtunnel.client.action.DISCONNECT",
            "-n", "com.esrrhs.pingtunnel.client/.vpn.PingtunnelVpnService"
        ])
        wait_for_tun(adb, serial, should_exist=False, timeout=8)
        print("==> pingtunnel 模式全协议测试全部通过！\n")

        print("="*60)
        print("🎉 全部 Android 模拟器真实端到端集成测试通过 (含切网恢复，TCP/UDP/HTTP1/HTTP2/DNS/WS/gRPC/QUIC 100% PASS)！")
        print("="*60)

    finally:
        print("==> 清理环境与后台服务...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.pingtunnel.client.action.DISCONNECT",
            "-n", "com.esrrhs.pingtunnel.client/.vpn.PingtunnelVpnService"
        ], check=False)
        tcp_server.stop()
        udp_server.stop()
        h1_server.stop()
        h2_server.stop()
        dns_server.stop()
        ws_server.stop()
        grpc_server.stop()
        quic_server.stop()
        run_adb(adb, serial, ["shell", "pkill", "-f", "pingtunnel -type server"], check=False)

if __name__ == "__main__":
    main()
