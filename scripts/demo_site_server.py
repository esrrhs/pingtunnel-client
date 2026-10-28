#!/usr/bin/env python3
"""演示用本地站点 + 限速长流服务（Google Play 审核视频使用）。

浏览器通过 VPN 打开 http://10.0.2.2:19080/ 时，页面会持续从 /slow 读取
数据，形成一条稳定的长连接：Chrome 页面计数持续增长，App 的 Sessions /
History / Stats 也能看到对应的流量与记录。

用法: python3 demo_site_server.py [port]
"""
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 19080
CHUNK_KB = 48
INTERVAL = 0.12  # ≈400KB/s，模拟器 ICMP 隧道下足够稳定
TOTAL_MB = 200

INDEX_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PingTunnel Demo Network</title>
<style>
:root{--bg:#0b0b0c;--card:#17140f;--line:#31281b;--fg:#f2e8dc;--muted:#b9ae9e;--amber:#ffb74d;}
*{box-sizing:border-box}
body{margin:0;background:radial-gradient(120% 60% at 50% 0%,#241a0b 0%,var(--bg) 55%);
color:var(--fg);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;padding:22px}
.wrap{max-width:820px;margin:0 auto}
header{display:flex;align-items:center;gap:10px;font-weight:700;letter-spacing:.3px}
.dot{width:10px;height:10px;border-radius:50%;background:var(--amber);box-shadow:0 0 14px var(--amber)}
.badge{margin-left:auto;font-size:12px;font-weight:600;color:var(--amber);
border:1px solid rgba(255,183,77,.45);background:rgba(255,183,77,.12);
padding:3px 10px;border-radius:999px}
h1{font-size:30px;line-height:1.22;margin:26px 0 8px}
p.sub{color:var(--muted);margin:0 0 22px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px;margin:14px 0}
.row{display:flex;justify-content:space-between;gap:12px;align-items:baseline}
.big{font-size:30px;font-weight:700;color:var(--amber);font-variant-numeric:tabular-nums}
.meter{height:8px;border-radius:999px;background:#2a2114;overflow:hidden;margin:14px 0 8px}
.meter i{display:block;height:100%;width:0%;background:linear-gradient(90deg,#ffb74d,#ff8a65);transition:width .3s}
.kv{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:6px}
.kv div{background:#120f0a;border:1px solid var(--line);border-radius:12px;padding:12px}
.kv b{display:block;font-size:12px;color:var(--muted);font-weight:600;text-transform:uppercase;letter-spacing:.6px}
.kv span{font-size:15px}
nav{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0}
button{font:inherit;font-weight:600;color:#2a1a00;background:var(--amber);border:0;
border-radius:999px;padding:10px 18px;cursor:pointer}
button.ghost{background:transparent;color:var(--amber);border:1px solid rgba(255,183,77,.45)}
.log{font-size:13px;color:var(--muted)}
.log div{padding:6px 0;border-bottom:1px dashed #2a2114}
code{color:var(--fg);background:#221a0e;border-radius:6px;padding:1px 6px}
footer{color:var(--muted);font-size:12px;margin-top:28px;text-align:center}
</style></head><body><div class="wrap">
<header><span class="dot"></span> PingTunnel Demo Network <span class="badge">VIA ICMP TUNNEL</span></header>
<h1>Your traffic is flowing through a pingtunnel server.</h1>
<p class="sub">This page is loaded inside Chrome while the Android VPN is on.
The transfer below keeps a live connection open so you can watch it in the app.</p>

<div class="card">
  <div class="row"><span>Live transfer through the tunnel</span><span class="log" id="rate">0 KB/s</span></div>
  <div class="big"><span id="mb">0.00</span> MB</div>
  <div class="meter"><i id="bar"></i></div>
  <div class="log" id="status">Connecting to /slow …</div>
</div>

<div class="kv">
  <div><b>Tunnel protocol</b><span>ICMP · ChaCha20-Poly1305</span></div>
  <div><b>Server</b><span>10.0.2.2 · demo backend</span></div>
  <div><b>Client</b><span>PingTunnel for Android</span></div>
  <div><b>Exit hop</b><span>pingtunnel server</span></div>
</div>

<div class="card">
  <div class="row"><span>Recent activity</span><span class="log">live</span></div>
  <div class="log" id="log"><div>Chrome opened the demo site through the VPN.</div></div>
</div>

<nav>
  <button onclick="document.getElementById('log').scrollIntoView({behavior:'smooth'})">Show sessions</button>
  <button class="ghost" onclick="location.reload()">Reconnect page</button>
</nav>

<footer>Demo content served locally for the Google Play review video.<br>
No user data is collected by PingTunnel.</footer>
</div>
<script>
let bytes=0, last=performance.now(), lastBytes=0;
const mb=document.getElementById('mb'), bar=document.getElementById('bar'),
      rate=document.getElementById('rate'), status=document.getElementById('status'),
      log=document.getElementById('log');
function addLog(t){const d=document.createElement('div');d.textContent=t;log.prepend(d);
  while(log.children.length>7)log.lastChild.remove();}
async function run(){
  try{
    const r=await fetch('/slow',{cache:'no-store'});
    status.textContent='Streaming through the tunnel…';
    addLog('TCP 10.0.2.2:19080 · stream opened');
    const reader=r.body.getReader();
    for(;;){
      const {done,value}=await reader.read();
      if(done)break;
      bytes+=value.length; mb.textContent=(bytes/1048576).toFixed(2);
      bar.style.width=Math.min(100,(bytes/104857600)*100)+'%';
    }
  }catch(e){status.textContent='Stream ended: '+e}
}
setInterval(()=>{
  const now=performance.now(), dt=(now-last)/1000;
  const kbps=((bytes-lastBytes)/1024)/dt;
  rate.textContent=kbps.toFixed(0)+' KB/s';
  last=now; lastBytes=bytes;
},1000);
run();
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            body = INDEX_HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/favicon.ico":
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path == "/api/tick":
            body = b'{"ok":true}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/slow"):
            total = TOTAL_MB * 1024 * 1024
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(total))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            chunk = b"\0" * (CHUNK_KB * 1024)
            sent = 0
            try:
                while sent < total:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    sent += len(chunk)
                    time.sleep(INTERVAL)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        self.send_error(404)

    def log_message(self, *args):
        pass


class QuietServer(ThreadingHTTPServer):
    """浏览器切页/后台会主动断开长连接，忽略这类正常 reset，别刷屏。"""

    def handle_error(self, request, client_address):
        import sys as _sys
        if isinstance(_sys.exc_info()[1], (ConnectionResetError, BrokenPipeError)):
            return
        super().handle_error(request, client_address)


if __name__ == "__main__":
    print(f"demo site on http://0.0.0.0:{PORT}/ (~{CHUNK_KB / INTERVAL:.0f}KB/s stream)",
          flush=True)
    QuietServer(("0.0.0.0", PORT), Handler).serve_forever()
