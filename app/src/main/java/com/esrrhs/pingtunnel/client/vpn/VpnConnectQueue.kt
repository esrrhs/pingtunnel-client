package com.esrrhs.pingtunnel.client.vpn

internal enum class ConnectCommand {
    /** 当前空闲，立即开始连接。 */
    START,
    /** 正在断开。tun 已经拆掉，但状态还没落到 Disconnected，先排队。 */
    QUEUE,
    /** 已在连接或已连接，忽略重复请求。 */
    IGNORE,
}

/**
 * CONNECT 与 DISCONNECT 交叠时的决策。
 *
 * 数据面（含 tun）在状态写成 [VpnState.Disconnected] 之前就会拆掉。
 * 若这时直接丢掉 CONNECT，调用方看到网卡消失后立刻重连，服务会停在断开态。
 */
internal class VpnConnectQueue {
    private var queued = false

    fun onConnect(state: VpnState): ConnectCommand = when (state) {
        VpnState.Disconnecting -> {
            queued = true
            ConnectCommand.QUEUE
        }
        VpnState.Connecting, VpnState.Connected -> ConnectCommand.IGNORE
        else -> {
            queued = false
            ConnectCommand.START
        }
    }

    /**
     * @return 是否应启动 teardown。已经在断开或已断开时返回 false，并取消排队的连接。
     */
    fun onDisconnect(state: VpnState): Boolean {
        queued = false
        return state != VpnState.Disconnected && state != VpnState.Disconnecting
    }

    /** 数据面已拆完。有排队的 CONNECT、且这次不是失败收尾时返回 true。 */
    fun takeFollowUp(failed: Boolean): Boolean {
        val follow = queued && !failed
        queued = false
        return follow
    }

    /** 系统收回 VPN 等场景：丢掉排队的连接，不再自动接上。 */
    fun clear() {
        queued = false
    }
}
