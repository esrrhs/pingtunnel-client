package com.esrrhs.pingtunnel.client.ui

/**
 * 检测任务的"代数"闸门：解决长耗时检测（自检/防泄漏/查 IP）在结果回来前
 * 已被用户关闭对话框的竞态。
 *
 * 用法：协程启动时取 [token]；用户 dismiss 调 [bump] 使旧代数失效；
 * 结果回来时仅当 [isCurrent] 为真才写回 UI 状态，避免已关闭的弹窗自行复活。
 * 新启动的检测不需要 bump——它会拿到当前代数作为新 token。
 */
internal class GenerationGate {
    private var generation = 0

    /** 使此前发出的所有 token 失效。 */
    fun bump() {
        generation++
    }

    /** 当前代数 token（单调递增，Int 回绕不影响相等比较的实际语义）。 */
    fun token(): Int = generation

    fun isCurrent(token: Int): Boolean = token == generation
}
