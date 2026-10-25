package com.esrrhs.pingtunnel.client.pingtunnel

/** 配置校验错误码；具体文案由资源文件按语言提供。 */
enum class ValidationError {
    NAME_REQUIRED,
    APPS_REQUIRED,
    HOST_REQUIRED,
    /** v3 起密码必填（认证与加密一体）。 */
    PASSWORD_REQUIRED,
    /** pingtunnel 要求密码最少 8 个字符。 */
    PASSWORD_TOO_SHORT,
    /** 加密算法不在 v3 支持列表内（aes256/aes128，空为默认 ChaCha20）。 */
    CIPHER_UNSUPPORTED,
    /** tcp 最大窗口受 FrameMgr FRAME_MAX_ID 限制（*10 <= 1000000）。 */
    TCP_MAXWIN_TOO_BIG,
    TCP_PARAMS_RANGE,
}
