package com.esrrhs.pingtunnel.client.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.util.ProfileShare
import com.esrrhs.pingtunnel.client.util.QrCodes
import com.esrrhs.pingtunnel.client.pingtunnel.Profile

/** 展示某配置的分享二维码，支持把分享文本发送给其它应用。 */
@Composable
fun QrShareDialog(
    profile: Profile,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val payload = ProfileShare.encode(profile)
    // 位图生成较重（800x800 逐像素），按 payload 缓存只算一次；
    // 内容超出二维码容量时返回 null，降级为文案提示，绝不让 WriterException 崩在 composition 里
    val bitmap = remember(payload) { QrCodes.toBitmap(payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_title, profile.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_content_description),
                        modifier = Modifier.size(260.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.qr_too_large),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.qr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                context.startActivity(
                    Intent.createChooser(send, context.getString(R.string.qr_share_chooser)),
                )
            }) { Text(stringResource(R.string.action_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
