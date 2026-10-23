package com.esrrhs.pingtunnel.client.util

import android.content.Context
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.pingtunnel.ValidationError

/** 校验错误码的本地化文案。 */
object ValidationMessages {

    fun text(context: Context, error: ValidationError): String = context.getString(
        when (error) {
            ValidationError.NAME_REQUIRED -> R.string.err_name_required
            ValidationError.APPS_REQUIRED -> R.string.err_apps_required
            ValidationError.HOST_REQUIRED -> R.string.err_host_required
            ValidationError.KEY_RANGE -> R.string.err_key_range
            ValidationError.ENCRYPT_MODE_UNSUPPORTED -> R.string.err_encrypt_mode_unsupported
            ValidationError.ENCRYPT_KEY_REQUIRED -> R.string.err_encrypt_key_required
            ValidationError.TCP_MAXWIN_TOO_BIG -> R.string.err_tcp_maxwin_too_big
            ValidationError.TCP_PARAMS_RANGE -> R.string.err_tcp_params_range
        },
    )
}
