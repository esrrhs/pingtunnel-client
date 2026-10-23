#!/usr/bin/env bash
#
# 下载大陆直连域名表（felixonmars/dnsmasq-china-list），规整为每行一个域名，
# 打包进 APK assets/domain_direct_cn.txt，作为「域名直连规则」的内置列表。
#
# 源格式：server=/example.com/114.114.114.114
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/domain_direct_cn.txt"
# jsDelivr CDN（raw.githubusercontent.com 在部分网络下不可达）
BASE="https://cdn.jsdelivr.net/gh/felixonmars/dnsmasq-china-list@master"
LISTS=(accelerated-domains.china.conf google.china.conf apple.china.conf)

mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for list in "${LISTS[@]}"; do
    echo "==> 下载 $list"
    curl -fsSL "$BASE/$list" -o "$TMP/$list"
done

# 提取 server=/<domain>/... 中的域名，转小写、去重、排序。
# 丢弃裸通用 TLD（top/wang 等会误伤整个后缀的海外/私有站点），
# 仅保留地区性裸 TLD：cn 与中文国家 TLD（xn-- 开头）。
cat "$TMP"/*.conf \
    | grep -E '^server=/' \
    | sed -E 's#^server=/([^/]+)/.*#\1#' \
    | tr 'A-Z' 'a-z' \
    | awk -F. 'NF == 1 && $0 != "cn" && $0 !~ /^xn--/ { next } { print }' \
    | sort -u > "$OUT"

echo "完成：$(wc -l < "$OUT" | tr -d ' ') 个域名 -> $OUT"
