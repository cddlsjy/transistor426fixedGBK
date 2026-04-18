/*
 * AudioHelper.kt
 * Implements the AudioHelper object
 * A AudioHelper provides helper methods for handling audio files
 *
 * This file is part of
 * TRANSISTOR - Radio App for Android
 *
 * Copyright (c) 2015-25 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.transistor.helpers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import org.y20k.transistor.Keys
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.min


/*
 * AudioHelper object
 */
object AudioHelper {

    /* Define log tag */
    private val TAG: String = AudioHelper::class.java.simpleName


    /* Extract duration metadata from audio file */
    fun getDuration(context: Context, audioFileUri: Uri): Long {
        val metadataRetriever: MediaMetadataRetriever = MediaMetadataRetriever()
        var duration: Long = 0L
        try {
            metadataRetriever.setDataSource(context, audioFileUri)
            val durationString = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: String()
            duration = durationString.toLong()
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to extract duration metadata from audio file")
        }
        return duration
    }


    /* Extract audio stream metadata from Metadata (works only for IceCast metadata) */
    fun getMetadataString(metadata: Metadata): String {
        var metadataString: String = String()
        val selectedCharset = PreferencesHelper.loadMetadataCharset()

        for (i in 0 until metadata.length()) {
            val entry: Metadata.Entry = metadata.get(i)
            // extract IceCast metadata
            if (entry is IcyInfo) {
                var rawTitle = entry.title.toString()
                // 如果用户选择的是 GBK，尝试将当前字符串视为 UTF-8 误解码的结果进行恢复
                if (selectedCharset == Charset.forName("GBK")) {
                    try {
                        // 常见修复：将乱码字符串按 ISO-8859-1 转回字节，再用 GBK 解码
                        val bytes = rawTitle.toByteArray(StandardCharsets.ISO_8859_1)
                        rawTitle = String(bytes, Charset.forName("GBK"))
                    } catch (e: Exception) {
                        // 转换失败时保持原样
                    }
                }
                metadataString = rawTitle
            } else if (entry is IcyHeaders) {
                Log.i(TAG, "icyHeaders:" + entry.name + " - " + entry.genre)
            } else {
                Log.w(TAG, "Unsupported metadata received (type = ${entry.javaClass.simpleName})")
            }
            // TODO implement HLS metadata extraction (Id3Frame / PrivFrame)
            // https://exoplayer.dev/doc/reference/com/google/android/exoplayer2/metadata/Metadata.Entry.html
        }
        // ensure a max length of the metadata string
        if (metadataString.isNotEmpty()) {
            metadataString = metadataString.substring(0, min(metadataString.length, Keys.DEFAULT_MAX_LENGTH_OF_METADATA_ENTRY))
        }
        return metadataString
    }


    /* Extract audio stream metadata from MediaMetadata */
    fun getMetadataString(metadata: MediaMetadata): String {
        var metadataString: String = String()
        if (!metadata.title.isNullOrEmpty()) {
            if (!metadataString.contains(metadata.title.toString())) {
                metadataString += metadata.title.toString().trim()
            }
        }
// MediaMetadata often contains station, genre, etc. - but adding those strings to the metadata Transistor displays would produce long and messy strings
//        if (!metadata.artist.isNullOrEmpty()) {
//            if (!metadataString.contains(metadata.artist.toString())) {
//                if (metadataString.isNotEmpty()) {
//                    metadataString += " - "
//                }
//                metadataString += metadata.artist.toString().trim()
//            }
//        }
//        if (!metadata.albumTitle.isNullOrEmpty()) {
//            if (!metadataString.contains(metadata.albumTitle.toString())) {
//                if (metadataString.isNotEmpty()) {
//                    metadataString += " - "
//                }
//                metadataString += metadata.albumTitle.toString().trim()
//            }
//        }
//        if (!metadata.genre.isNullOrEmpty()) {
//            if (!metadataString.contains(metadata.genre.toString())) {
//                if (metadataString.isNotEmpty()) {
//                    metadataString += " - "
//                }
//                metadataString += metadata.genre.toString().trim()
//            }
//        }
//        if (!metadata.station.isNullOrEmpty()) {
//            if (!metadataString.contains(metadata.station.toString())) {
//                if (metadataString.isNotEmpty()) {
//                    metadataString += " - "
//                }
//                metadataString += metadata.station.toString().trim()
//            }
//        }
        // ensure a max length of the metadata string
        if (metadataString.isNotEmpty()) {
            metadataString = metadataString.substring(0, min(metadataString.length, Keys.DEFAULT_MAX_LENGTH_OF_METADATA_ENTRY))
        }
        return metadataString
    }

}
