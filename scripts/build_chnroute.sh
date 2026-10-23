#!/usr/bin/env bash
# 生成中国大陆 IPv4/IPv6 CIDR 列表（chnroute）到 app assets，供「CN 直连」智能分流使用。
# 数据源：APNIC delegated stats（https://ftp.apnic.net/stats/apnic/delegated-apnic-latest）
# 用法：./scripts/build_chnroute.sh [delegated-stats-file]
#   无参数时自动下载（已缓存则离线复用，删缓存可更新）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/third_party/delegated-apnic-latest"
OUT_DIR="$ROOT/app/src/main/assets"
OUT_V4="$OUT_DIR/cn_ipv4_cidr.txt"
OUT_V6="$OUT_DIR/cn_ipv6_cidr.txt"
mkdir -p "$(dirname "$CACHE")" "$OUT_DIR"

if [ "${1:-}" != "" ]; then
    SRC="$1"
elif [ -f "$CACHE" ]; then
    SRC="$CACHE"
else
    URL="https://ftp.apnic.net/stats/apnic/delegated-apnic-latest"
    echo "==> 下载 $URL"
    if command -v curl >/dev/null; then
        curl -fsSL --retry 3 -o "$CACHE" "$URL"
    else
        wget -q -O "$CACHE" "$URL"
    fi
    SRC="$CACHE"
fi

TMP_GEN="$(mktemp -d)/gen.go"
cat > "$TMP_GEN" <<'EOF'
package main

import (
	"bufio"
	"fmt"
	"net/netip"
	"os"
	"sort"
	"strings"
)

func ipToUint32(s string) uint32 {
	var v uint32
	for _, p := range strings.Split(s, ".") {
		var n uint32
		fmt.Sscanf(p, "%d", &n)
		v = v<<8 | n
	}
	return v
}

func main() {
	f, _ := os.Open(os.Args[1])
	defer f.Close()

	v4seen := map[uint32]bool{}
	type v4rng struct{ start, count uint32 }
	var v4ranges []v4rng
	var v6 []string

	sc := bufio.NewScanner(f)
	for sc.Scan() {
		parts := strings.Split(sc.Text(), "|")
		if len(parts) < 5 || parts[1] != "CN" {
			continue
		}
		switch parts[2] {
		case "ipv4":
			var count uint32
			fmt.Sscanf(parts[4], "%d", &count)
			v4ranges = append(v4ranges, v4rng{ipToUint32(parts[3]), count})
		case "ipv6":
			// 规范化为压缩形式后拼前缀长度；start 形如 2001:250::
			addr, err := netip.ParseAddr(strings.TrimSpace(parts[3]))
			if err == nil {
				v6 = append(v6, addr.String()+"/"+parts[4])
			}
		}
	}

	var v4out []string
	for _, r := range v4ranges {
		ip, remaining := r.start, r.count
		for remaining > 0 {
			block := uint32(1)
			for block<<1 <= remaining && ip%(block<<1) == 0 {
				block <<= 1
			}
			prefix := 32
			for b := block; b > 1; b >>= 1 {
				prefix--
			}
			if !v4seen[ip] {
				v4seen[ip] = true
				v4out = append(v4out,
					fmt.Sprintf("%d.%d.%d.%d/%d",
						(ip>>24)&255, (ip>>16)&255, (ip>>8)&255, ip&255, prefix))
			}
			ip += block
			remaining -= block
		}
	}
	sort.Strings(v4out)
	sort.Strings(v6)

	writeFile(os.Args[2], v4out)
	writeFile(os.Args[3], v6)
	fmt.Fprintf(os.Stderr, "CN v4 cidrs: %d, v6 prefixes: %d\n", len(v4out), len(v6))
}

func writeFile(path string, lines []string) {
	f, _ := os.Create(path)
	defer f.Close()
	w := bufio.NewWriter(f)
	for _, l := range lines {
		fmt.Fprintln(w, l)
	}
	w.Flush()
}
EOF

go run "$TMP_GEN" "$SRC" "$OUT_V4" "$OUT_V6"
rm -rf "$(dirname "$TMP_GEN")"

echo "==> v4 $OUT_V4 ($(wc -l < "$OUT_V4") prefixes)"
echo "==> v6 $OUT_V6 ($(wc -l < "$OUT_V6") prefixes)"
