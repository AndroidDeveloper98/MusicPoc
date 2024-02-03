package com.think.music.util

import android.media.MediaMetadataRetriever

object PlayerHelper {
    fun getSongThumbnail(songPath: String): ByteArray? {
        var imgByte: ByteArray?
        MediaMetadataRetriever().also {
            try {
                it.setDataSource(songPath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            imgByte = it.embeddedPicture
            it.release()
        }
        return imgByte
    }
}
