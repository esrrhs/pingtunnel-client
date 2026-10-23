package com.esrrhs.pingtunnel.client.vpn

import com.esrrhs.pingtunnel.client.pingtunnel.Profile

/**
 * 故障切换的备用配置选择（纯逻辑，便于单测）。
 *
 * 优先选择延迟最低且已实测成功（pingMs >= 0）的其它配置；
 * 全都没测过时退化为列表顺序。
 */
object Failover {

    fun pickNext(profiles: List<Profile>, currentId: String): Profile? =
        orderedOthers(profiles, currentId).firstOrNull()

    /** 当前配置之后的全部候选，按延迟（未测的在后）、再按列表顺序排列。 */
    fun orderedOthers(profiles: List<Profile>, currentId: String): List<Profile> {
        val others = profiles.filter { it.id != currentId }
        val measured = others.filter { it.pingMs >= 0 }.sortedBy { it.pingMs }
        val unmeasured = others.filter { it.pingMs < 0 }
        return measured + unmeasured
    }
}
