#!/usr/bin/env python3
"""录制 Google Play 审核演示视频：PingTunnel VPN 一键连接 + 实时流量/连接记录。

一镜到底策略（连续录屏，不切出）：
  从主界面停留开始录，覆盖：连接 → 系统 VPN 授权 → 已连接（状态栏钥匙图标）
  → 常驻通知实时速率 → Chrome 经隧道加载页面 → Sessions / History / Stats
  → QR 分享 → 设置 → 防泄漏页 → 断开。
  screenrecord 单次上限 180s，ContinuousRecorder 到 168s 自动滚动到下一段。

前置条件（由 scripts/ci_record_demo.sh 或本地手动准备）：
  - 模拟器已启动、adb root 可用
  - 设备内 pingtunnel server 已跑在 guest IP，且 root 走 eth0 直连
  - 宿主机 demo_site_server.py 已监听 19080

MARK 时间戳用于后期字幕对齐。
"""
import base64
import json
import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or next(
    (p for p in (
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        "adb",
    ) if p == "adb" or os.path.exists(p)),
    "adb",
)
PKG = "com.esrrhs.pingtunnel.client"
ACT = PKG + "/.MainActivity"
CHROME = "com.android.chrome/com.google.android.apps.chrome.Main"
SITE_URL = os.environ.get("DEMO_SITE_URL", "http://10.0.2.2:19080/")
PROFILE_NAME = "PingTunnel Demo"
PASSWORD = "e2e-test-pass"
APK = os.environ.get("DEMO_APK", "app/build/outputs/apk/debug/app-debug.apk")

t0 = time.time()


def log(msg):
    print(f"[{time.time() - t0:6.1f}s] {msg}", flush=True)


def adb(*args, check=True, timeout=60):
    res = subprocess.run([ADB, *args], capture_output=True, text=True,
                         errors="ignore", timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr or res.stdout}")
    return res.stdout


def sh(cmd, check=False):
    return adb("shell", cmd, check=check)


# ---------------- 连续录屏（自动滚动分段） ----------------
class ContinuousRecorder:
    SEG_SECONDS = 168  # screenrecord 单次上限 180s，留 12s 给封装

    def __init__(self, size="1080x2340"):
        self.size = size
        self.proc = None
        self.seg = 0
        self.stop_evt = threading.Event()
        self.files = []
        self.thr = None

    def _finalize(self):
        try:
            if self.proc and self.proc.stdin:
                self.proc.stdin.close()
        except Exception:
            pass
        sh("killall -INT screenrecord 2>/dev/null")
        try:
            if self.proc:
                self.proc.wait(timeout=20)
        except Exception:
            sh("killall -INT screenrecord 2>/dev/null")
            try:
                if self.proc:
                    self.proc.wait(timeout=12)
            except Exception:
                pass

    def _run(self):
        while not self.stop_evt.is_set():
            self.seg += 1
            name = f"take{self.seg}.mp4"
            sh(f"rm -f /sdcard/{name}")
            self.proc = subprocess.Popen(
                [ADB, "shell", "screenrecord", "--size", self.size,
                 "--bit-rate", "12000000", "--time-limit", "179", f"/sdcard/{name}"],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            deadline = time.time() + self.SEG_SECONDS
            while (not self.stop_evt.is_set()
                   and time.time() < deadline
                   and self.proc.poll() is None):
                time.sleep(0.3)
            self._finalize()
            self.files.append(name)
            time.sleep(0.8)

    def start(self):
        self.thr = threading.Thread(target=self._run, daemon=True)
        self.thr.start()
        time.sleep(1.2)
        log("MARK rec-start")

    def stop(self):
        self.stop_evt.set()
        if self.thr:
            self.thr.join(timeout=40)
        time.sleep(2.0)
        for name in self.files:
            adb("pull", f"/sdcard/{name}", name)
            log(f"saved {name} ({os.path.getsize(name)} bytes)")
        return self.files


# ---------------- UI 操作 ----------------
def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    return adb("exec-out", "cat", "/sdcard/ui.xml", check=False)


def nodes():
    try:
        return list(ET.fromstring(dump_ui()).iter("node"))
    except Exception:
        return []


def node_bounds(node):
    m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 - x1 <= 0 or y2 - y1 <= 0:
        return None
    return x1, y1, x2, y2


def find_any(*needles, prefer_y_min=None):
    needles = [s.lower() for s in needles]
    for n in nodes():
        if node_bounds(n) is None:
            continue
        if prefer_y_min is not None:
            b = node_bounds(n)
            if (b[1] + b[3]) // 2 < prefer_y_min:
                continue
        hay = ((n.get("text") or "") + "\n" + (n.get("content-desc") or "")).lower()
        if any(k in hay for k in needles):
            return n
    return None


def find_exact_text(*labels):
    """按完整文本精确匹配，避免把对话框正文里的长句当成按钮。"""
    labels = {s.strip().lower() for s in labels}
    for n in nodes():
        t = (n.get("text") or "").strip().lower()
        if t in labels and node_bounds(n) is not None:
            return n
    return None


def center(node):
    x1, y1, x2, y2 = node_bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(n):
    x, y = center(n)
    label = n.get("text") or n.get("content-desc")
    adb("shell", "input", "tap", str(x), str(y))
    log(f"tap ({x},{y}) '{label}'")


def wait_any(*needles, timeout=60, period=0.7, prefer_y_min=None):
    end = time.time() + timeout
    while time.time() < end:
        dismiss_anr()
        n = find_any(*needles, prefer_y_min=prefer_y_min)
        if n is not None:
            return n
        time.sleep(period)
    return None


def wait_tap(*needles, timeout=60, prefer_y_min=None):
    n = wait_any(*needles, timeout=timeout, prefer_y_min=prefer_y_min)
    if n is None:
        raise RuntimeError(f"node not found: {needles}")
    tap_node(n)
    return n


def swipe_up():
    adb("shell", "input", "swipe", "540", "1700", "540", "800", "320")


def open_url(url):
    log(f"open {url}")
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url,
        "-n", CHROME, check=False)


def anr_subject():
    n = find_any("isn't responding", "未响应")
    if n is None:
        return None
    return (n.get("text") or n.get("content-desc") or "").strip()


def dismiss_anr():
    if find_any("isn't responding", "未响应") is None:
        return False
    n = find_any("Wait", "等待")
    if n is not None:
        tap_node(n)
    return True


FRE_LABELS = ("Use without an account", "No thanks", "Accept & continue",
              "使用时不登录账号", "不用了", "接受并继续")


def _find_in(ns, *labels):
    labels = [s.lower() for s in labels]
    for n in ns:
        if node_bounds(n) is None:
            continue
        hay = ((n.get("text") or "") + "\n" + (n.get("content-desc") or "")).lower()
        if any(k in hay for k in labels):
            return n
    return None


def dismiss_chrome_dialogs():
    ns = nodes()
    fre = _find_in(ns, *FRE_LABELS)
    if fre is None:
        allow = _find_in(ns, "Allow", "允许")
        if allow is not None:
            tap_node(allow)
            time.sleep(0.8)
        return True
    for labels in (("Use without an account", "使用时不登录账号"),
                   ("No thanks", "不用了"),
                   ("Accept & continue", "接受并继续")):
        n = _find_in(nodes(), *labels)
        if n is not None:
            tap_node(n)
            time.sleep(1.0)
    return _find_in(nodes(), *FRE_LABELS) is None


def make_deeplink(guest_ip):
    p = {
        "id": "demo-pingtunnel",
        "name": PROFILE_NAME,
        "config": {"serverHost": guest_ip, "password": PASSWORD, "enableIpv6": False},
        "perAppMode": "ALL",
        "perAppPackages": [],
        "bypassLan": False,
        "bypassCn": False,
        "pingMs": 8,
    }
    return "pingtunnel://" + base64.b64encode(
        json.dumps(p, separators=(",", ":")).encode()).decode()


def guest_ip():
    out = sh("ip -f inet -o addr show eth0").strip()
    m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", out)
    return m.group(1) if m else "10.0.2.15"


def clear_notifications():
    """清掉系统里的其他通知，录制时通知栏只保留 PingTunnel 自己。"""
    sh("cmd statusbar expand-notifications")
    time.sleep(1.5)
    n = find_exact_text("Clear all", "全部清除")
    if n is not None:
        tap_node(n)
        time.sleep(1.2)
    sh("cmd statusbar collapse")
    time.sleep(0.8)


def main_screen():
    adb("shell", "am", "start", "-n", ACT, check=False)
    time.sleep(1.3)
    dismiss_anr()


def close_sheet():
    if find_any("Choose profile", "选择配置") is not None:
        adb("shell", "input", "keyevent", "4")
        time.sleep(1.0)


def open_sheet():
    n = find_any(PROFILE_NAME, prefer_y_min=900)
    if n is None:
        raise RuntimeError("profile card not found")
    tap_node(n)
    if wait_any("Choose profile", "选择配置", timeout=8) is None:
        raise RuntimeError("profile sheet did not open")
    time.sleep(1.0)


def open_overflow(label):
    adb("shell", "input", "tap", "970", "154")
    time.sleep(1.2)
    wait_tap(label, timeout=8)
    time.sleep(2.0)


def preflight(fresh=True):
    sh("svc power stayon usb")
    sh("input keyevent 82")
    sh("settings put global window_animation_scale 0")
    sh("settings put global transition_animation_scale 0")
    sh("settings put global animator_duration_scale 0")

    if fresh:
        sh(f"pm uninstall {PKG}", check=False)
        time.sleep(1.5)
        res = adb("install", "-r", APK, timeout=180)
        log(res.strip().splitlines()[-1] if res.strip() else "installed")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    # 演示视频不展示系统授权弹窗（一次性模拟器上很难稳定重现），直接预授权
    for op in ("ACTIVATE_VPN", "ACTIVATE_PLATFORM_VPN"):
        sh(f"appops set {PKG} {op} allow")
        sh(f"appops set --uid {PKG} {op} allow")
    # root 直连（模拟器内 pingtunnel server 不能被自己的 VPN 抓回去）
    sh("ip rule del pref 12999 2>/dev/null; "
       "ip rule add pref 12999 uidrange 0-0 lookup eth0")
    sh("logcat -c")

    ip = guest_ip()
    if not sh("pgrep -f '[p]ingtunnel-server -type'").strip():
        raise RuntimeError(
            "设备内 pingtunnel server 未运行，请先执行 scripts/ci_record_demo.sh 的 server 步骤")
    log(f"guest ip={ip}, pingtunnel server up")

    # 导入演示配置
    link = make_deeplink(ip)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", link, ACT, check=False)
    if wait_any(PROFILE_NAME, timeout=60) is None:
        raise RuntimeError("demo profile not imported")
    main_screen()

    # Chrome 预热：走完首次引导，确认本地 demo 站可达
    if "com.android.chrome" in sh("pm list packages com.android.chrome"):
        sh("am force-stop com.android.chrome")
        open_url(SITE_URL)
        time.sleep(6)
        dismiss_chrome_dialogs()
        time.sleep(2)
        sh("am force-stop com.android.chrome")
    else:
        log("WARNING chrome not installed; browser section will be skipped")
    main_screen()
    clear_notifications()
    main_screen()


def record():
    rec = ContinuousRecorder()
    rec.start()
    time.sleep(1.0)  # 片头：主界面

    # 1) 连接
    wait_tap("PingTunnel VPN", timeout=20)
    log("MARK connect-tap")

    # 2) 系统 VPN 授权（已预授权时不会出现，直接跳过）
    if wait_any("Connection request", "连接请求", timeout=6) is not None:
        log("MARK consent-visible")
        time.sleep(2.6)
        ok = find_exact_text("OK", "Allow", "允许", "确定")
        if ok is not None:
            tap_node(ok)
        else:
            adb("shell", "input", "tap", "830", "1500")
        log("MARK consent-ok")
    else:
        log("consent already granted; skip")

    # 3) 等待已连接
    if wait_any("Connected", "已连接", timeout=75) is None:
        raise RuntimeError("did not reach Connected")
    log("MARK tunnel-connected")
    main_screen()
    time.sleep(1.0)

    # 4) Chrome 经隧道访问本地演示站（页面持续拉流，制造真实流量）
    has_chrome = "com.android.chrome" in sh("pm list packages com.android.chrome")
    if has_chrome:
        open_url(SITE_URL)
        time.sleep(4.5)
        log("MARK chrome-open")
        # Chrome 在前台时速率最高：此时拉下通知栏展示实时速率与钥匙图标
        sh("cmd statusbar expand-notifications")
        log("MARK notification")
        time.sleep(2.0)
        sh("cmd statusbar collapse")
        time.sleep(0.8)
        swipe_up()
        time.sleep(1.0)
        swipe_up()
        time.sleep(1.0)
        log("MARK chrome-done")

    # 5) Sessions（刚产生的 Chrome 连接仍在列表里，尽快切入）
    adb("shell", "am", "start", "-n", ACT, check=False)
    time.sleep(0.8)
    adb("shell", "input", "tap", "970", "154")
    time.sleep(0.6)
    adb("shell", "input", "tap", "950", "575")
    time.sleep(1.2)
    wait_any("Active connections", "当前连接", timeout=8)
    log("MARK sessions-screen")
    time.sleep(4.0)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.0)

    # 6) History（连接记录）
    adb("shell", "am", "start", "-n", ACT, check=False)
    time.sleep(0.8)
    adb("shell", "input", "tap", "970", "154")
    time.sleep(0.6)
    adb("shell", "input", "tap", "950", "705")
    time.sleep(1.2)
    wait_any("Connection history", "连接记录", timeout=8)
    log("MARK history-screen")
    time.sleep(4.5)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)

    # 7) QR 分享（BACK 已回到主界面）
    open_sheet()
    wait_tap("QR", timeout=10)
    wait_any("Share profile", "分享配置", timeout=10)
    log("MARK qr-screen")
    time.sleep(2.5)
    wait_tap("Close", "关闭", timeout=6)
    time.sleep(0.8)
    close_sheet()

    # 8) 新增配置编辑器
    adb("shell", "input", "tap", "960", "2150")
    time.sleep(1.5)
    if wait_any("Profile name", "配置名称", timeout=8) is not None:
        log("MARK editor-screen")
        time.sleep(2.5)
        adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)

    # 9) 设置
    wait_tap("Settings", "设置", timeout=10)
    wait_any("Settings", "设置", timeout=10)
    log("MARK settings-screen")
    time.sleep(3)
    swipe_up()
    time.sleep(1.2)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)

    # 10) 防泄漏页
    wait_tap("Leak checks", "防泄漏", timeout=10)
    wait_any("Leak checks", "防泄漏", timeout=10)
    log("MARK leak-screen")
    time.sleep(2.5)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)

    # 11) 断开
    wait_tap("PingTunnel VPN", timeout=10)
    log("MARK disconnect-tap")
    if wait_any("Disconnected", "已断开", timeout=20) is not None:
        time.sleep(2.5)

    # 12) 断开后的累计流量（此时 Total traffic 已包含本次会话，避免显示 0B）
    open_sheet()
    wait_tap("Stats", "统计", timeout=10)
    wait_any("Traffic stats", "流量统计", timeout=10)
    log("MARK stats-screen")
    time.sleep(4.5)
    log("MARK end")

    files = rec.stop()
    log(f"DONE segments={files}")
    return files


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--no-fresh":
        fresh = False
    else:
        fresh = True
    subprocess.run([ADB, "root"], capture_output=True)
    subprocess.run([ADB, "wait-for-device"], capture_output=True)
    preflight(fresh=fresh)
    record()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        traceback.print_exc()
        try:
            for n in nodes():
                t = n.get("text") or n.get("content-desc")
                if t:
                    print(f"  UI: {t}", flush=True)
        except Exception:
            pass
        raise
