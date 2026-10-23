package com.esrrhs.pingtunnel.client.vpn

/**
 * 在一组非 VPN 物理网络里选出当前出口，并判断这次变化要不要改绑 / 重建隧道。
 *
 * 已验证的 Wi-Fi、以太网优先于蜂窝；都未验证时不选，避免把出口钉在还没通的网上。
 * 第一次选出的网络只作基线（隧道刚建好，不应立刻重连）；之后换成另一张已验证的网才是切换。
 */
internal class PhysicalNetworkTracker {

    data class Candidate(
        val key: String,
        val validated: Boolean,
        val preferTransport: Boolean,
        val downstreamKbps: Int,
    )

    private val candidates = LinkedHashMap<String, Candidate>()
    private var baselineSet = false

    var currentKey: String? = null
        private set

    /** 一次放入当前全部候选，只产生一次基线/切换，避免按枚举顺序误触发重建。 */
    fun seed(incoming: Collection<Candidate>): UpstreamAction {
        incoming.forEach { candidates[it.key] = it }
        return select(pick())
    }

    fun upsert(candidate: Candidate): UpstreamAction {
        candidates[candidate.key] = candidate
        return select(pick())
    }

    fun remove(key: String): UpstreamAction {
        candidates.remove(key)
        return select(pick())
    }

    fun reset() {
        candidates.clear()
        baselineSet = false
        currentKey = null
    }

    private fun pick(): String? =
        candidates.values
            .filter { it.validated }
            .maxWithOrNull(
                compareBy<Candidate> { it.preferTransport }
                    .thenBy { it.downstreamKbps }
                    .thenBy { it.key },
            )
            ?.key

    private fun select(next: String?): UpstreamAction {
        if (!baselineSet) {
            if (next == null) return UpstreamAction.NONE
            baselineSet = true
            currentKey = next
            return UpstreamAction.BASELINE
        }
        if (next == currentKey) return UpstreamAction.NONE
        currentKey = next
        return if (next == null) UpstreamAction.CLEAR else UpstreamAction.SWITCH
    }
}

internal enum class UpstreamAction {
    /** 选择没变，或还没有任何已验证网络。 */
    NONE,

    /** 建链后第一次看到出口，只改绑，不重建。 */
    BASELINE,

    /** 出口从一张已验证的网换到另一张，需要改绑并重建数据面。 */
    SWITCH,

    /** 当前出口消失且没有替代，先松开绑定，等下一张网再重建。 */
    CLEAR,
}
