package com.ulap.ui.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ulap.domain.model.MediaItem

object GalleryShareHelper {
    private val botTokenPathRegex = Regex("""/bot\d+:[A-Za-z0-9_-]+/""")

    /**
     * @return true if a share sheet was started
     */
    fun shareMedia(context: Context, item: MediaItem): Boolean {
        val uriStr = item.contentUri.trim()
        if (uriStr.isNotEmpty()) {
            val uri = Uri.parse(uriStr)
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = item.mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(Intent.createChooser(intent, null))
            return true
        }
        val url = item.streamUrl?.trim()
        if (!url.isNullOrEmpty() && isShareableRemoteUrl(url)) {
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
            context.startActivity(Intent.createChooser(intent, null))
            return true
        }
        return false
    }

    private fun isShareableRemoteUrl(url: String): Boolean {
        if (url.contains("api.telegram.org", ignoreCase = true)) return false
        if (botTokenPathRegex.containsMatchIn(url)) return false
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }
}
