package com.esrrhs.pingtunnel.client.vpn

/**
 * [VpnState] 到 Quick Settings 磁贴状态的纯映射，便于单测。
 *
 * VPN 会话存在（含连接/断开过渡态）时磁贴为激活态；
 * 未连接或出错时为非激活态（错误文案在 App 内展示）。
 */
object VpnTileState {

    enum class Tile { ACTIVE, INACTIVE }

    fun of(state: VpnState): Tile = when (state) {
        VpnState.Connected,
        VpnState.Connecting,
        VpnState.Disconnecting -> Tile.ACTIVE
        VpnState.Disconnected,
        VpnState.Paused,
        is VpnState.Error -> Tile.INACTIVE
    }
}
