package pingtunnel

import "golang.org/x/net/icmp"

// E2E 专用变体：服务端必须用 raw socket 才能收到 echo 请求
// （dgram ping socket 只能收 echo 回复）。client 不受影响。
func listenICMP(addr string) (*icmp.PacketConn, error) {
	setICMPDatagram(false)
	return icmp.ListenPacket("ip4:icmp", addr)
}
