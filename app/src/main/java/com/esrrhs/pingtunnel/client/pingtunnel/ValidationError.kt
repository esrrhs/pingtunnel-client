package com.esrrhs.pingtunnel.client.pingtunnel

/** 配置校验错误码；具体文案由资源文件按语言提供。 */
enum class ValidationError {
    NAME_REQUIRED,
    APPS_REQUIRED,
    HOST_REQUIRED,
    /** -key 为 int（0..2147483647），超出范围会被 pingtunnel 拒绝启动。 */
    KEY_RANGE,
    /** 加密模式不在 pingtunnel 支持列表内（aes128/aes256/chacha20）。 */
    ENCRYPT_MODE_UNSUPPORTED,
    /** 指定了加密模式但密钥为空，pingtunnel 会直接退出。 */
    ENCRYPT_KEY_REQUIRED,
    /** tcp 最大窗口受 FrameMgr FRAME_MAX_ID 限制（*10 <= 1000000）。 */
    TCP_MAXWIN_TOO_BIG,
    TCP_PARAMS_RANGE,
}
