package org.thunderdog.challegram.solana

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Embeds and reads OndoZero metadata as SEI NAL units in an H.264 video bitstream.
 *
 * SEI format (H.264 spec, payloadType 5 — user_data_unregistered):
 *   nal_unit_type = 0x06 (SEI)
 *   payloadType   = 0x05 (user_data_unregistered)
 *   payloadSize   = EBSP encoded (0xFF per 255 bytes, finale byte for remainder)
 *   payload       = UUID (16 bytes) + JSON UTF-8 bytes
 *   stop bit      = 0x80 (RBSP trailing)
 *
 * The entire NAL is wrapped in AVCC format (4-byte big-endian length prefix) so it
 * can be prepended directly to keyframe samples from MediaExtractor / MediaMuxer.
 *
 * UUID: "ONDOZERO\0SEI_V100" — 16 bytes, identifies the OndoZero SEI payload.
 *
 * Injection strategy:
 *   Re-mux the MP4 via MediaExtractor + MediaMuxer. For every video keyframe
 *   (SAMPLE_FLAG_SYNC), prepend the pre-built SEI AVCC NAL before the existing data.
 *   Audio and other tracks are copied verbatim.
 *   After muxing to a temp file, the result is written back to the original URI.
 *
 * Note: Only H.264 (video/avc) tracks are supported for SEI injection. H.265 videos
 * fall back silently to the SIMZ trailer (OndoZeroMeta). Reading also returns null for
 * H.265, so VerifyScreen falls through to the SIMZ trailer path.
 *
 * Note on emulation prevention: JSON payloads consist of printable ASCII only, so the
 * byte sequences 0x000000 and 0x000001 (forbidden in H.264 AnnexB) cannot appear. In
 * AVCC format (no start codes), emulation prevention is not required.
 */
object OndoZeroSei {

    private const val TAG = "OndoZeroSei"

    // UUID "ONDOZERO\0SEI_V100" — exactly 16 bytes.
    internal val UUID_BYTES = byteArrayOf(
        'S'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte(), 'Z'.code.toByte(),
        'E'.code.toByte(), 'R'.code.toByte(), 'O'.code.toByte(), 0x00.toByte(),
        'S'.code.toByte(), 'E'.code.toByte(), 'I'.code.toByte(), '_'.code.toByte(),
        'V'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), '0'.code.toByte()
    )

    // Scan first SCAN_SAMPLES video samples when reading — first keyframe is usually sample 0.
    private const val SCAN_SAMPLES = 60
    // Max per-sample buffer; 4 MB covers 4K keyframes.
    private const val MAX_NAL_BUF  = 4 * 1024 * 1024

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Re-muxes the MP4 at [videoUri], injecting a SEI NAL at every H.264 keyframe.
     * Returns true on success. Returns false (without throwing) on any error so that
     * callers can fall back to the SIMZ trailer.
     * Must be called from a background thread.
     */
    fun injectSei(context: Context, videoUri: Uri, json: String): Boolean {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val seiAvcc   = buildSeiAvcc(jsonBytes)
        val tempFile  = File.createTempFile("sz_sei_", ".mp4", context.cacheDir)
        return try {
            if (!remux(context, videoUri, tempFile, seiAvcc)) return false
            copyBack(context, videoUri, tempFile).also {
                if (it) Log.i(TAG, "SEI injected: ${jsonBytes.size}B JSON at all keyframes")
                else    Log.w(TAG, "SEI copyBack failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "injectSei failed: ${e.message}")
            false
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Scans the first video samples of [videoUri] looking for a OndoZero SEI NAL.
     * Returns the embedded JSON string, or null if not found (or video is not H.264).
     * Must be called from a background thread.
     */
    fun readSei(context: Context, videoUri: Uri): String? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, videoUri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val mime = ex.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime != "video/avc") {
                Log.d(TAG, "Track MIME=$mime — SEI read only supported for H.264")
                return null
            }
            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(MAX_NAL_BUF)
            repeat(SCAN_SAMPLES) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) return null
                // After readSampleData, position = size; flip so position=0, limit=size.
                buf.position(size)
                buf.flip()
                findSeiJson(buf)?.let { return it }
                if (!ex.advance()) return null
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "readSei failed: ${e.message}")
            null
        } finally {
            ex.release()
        }
    }

    // ── SEI NAL construction ──────────────────────────────────────────────────

    /**
     * Builds a complete AVCC-framed SEI NAL unit ready to prepend to a keyframe sample:
     *   [4-byte BE length] [0x06][0x05][EBSP size][UUID 16B][json][0x80]
     */
    internal fun buildSeiAvcc(jsonBytes: ByteArray): ByteArray {
        val payloadSize = 16 + jsonBytes.size    // UUID + JSON data
        val nal = mutableListOf<Byte>()
        nal += 0x06.toByte()    // nal_unit_type = SEI
        nal += 0x05.toByte()    // payloadType 5: user_data_unregistered

        // EBSP-encode payloadSize: 0xFF bytes for each full 255, then the remainder.
        var rem = payloadSize
        while (rem >= 0xFF) { nal += 0xFF.toByte(); rem -= 0xFF }
        nal += rem.toByte()

        UUID_BYTES.forEach { nal += it }
        jsonBytes.forEach  { nal += it }
        nal += 0x80.toByte()    // RBSP stop bit

        val nalBytes = nal.toByteArray()
        return ByteBuffer.allocate(4 + nalBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(nalBytes.size)   // AVCC 4-byte BE length
            .put(nalBytes)
            .array()
    }

    // ── Re-mux with SEI injection ─────────────────────────────────────────────

    private fun remux(
        context: Context,
        srcUri: Uri,
        dest: File,
        seiAvcc: ByteArray
    ): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(context, srcUri, null)

        // Only inject SEI for H.264 tracks.
        val videoTrackIdx = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc"
        }
        if (videoTrackIdx == null) {
            Log.w(TAG, "No H.264 (video/avc) track — SEI injection skipped for $srcUri")
            ex.release()
            return false
        }

        val muxer    = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until ex.trackCount) {
            trackMap[i] = muxer.addTrack(ex.getTrackFormat(i))
            ex.selectTrack(i)
        }
        muxer.start()

        val buf  = ByteBuffer.allocate(maxOf(MAX_NAL_BUF, seiAvcc.size + MAX_NAL_BUF))
        val info = MediaCodec.BufferInfo()

        while (true) {
            buf.clear()
            val sz = ex.readSampleData(buf, 0)
            if (sz < 0) break

            val srcTrack = ex.sampleTrackIndex
            val pts      = ex.sampleTime
            val flags    = ex.sampleFlags
            val muxTrack = trackMap[srcTrack]
            if (muxTrack == null) { ex.advance(); continue }

            if (srcTrack == videoTrackIdx &&
                    (flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                // Keyframe in H.264 path — prepend SEI NAL.
                val combined = ByteBuffer.allocate(seiAvcc.size + sz)
                combined.put(seiAvcc)
                buf.position(0); buf.limit(sz)
                combined.put(buf)
                combined.flip()                              // position=0, limit=total
                info.set(0, combined.limit(), pts, flags)
                muxer.writeSampleData(muxTrack, combined, info)
            } else {
                // Non-keyframe or non-video track — copy verbatim.
                buf.position(0); buf.limit(sz)
                info.set(0, sz, pts, flags)
                muxer.writeSampleData(muxTrack, buf, info)
            }
            ex.advance()
        }

        muxer.stop()
        muxer.release()
        ex.release()
        return true
    }

    /** Atomically replaces the content of [videoUri] with the bytes from [src]. */
    private fun copyBack(context: Context, videoUri: Uri, src: File): Boolean =
        context.contentResolver.openOutputStream(videoUri, "wt")?.use { out ->
            FileInputStream(src).use { it.copyTo(out) }
            true
        } ?: false

    // ── SEI reading ───────────────────────────────────────────────────────────

    /**
     * Scans AVCC-framed NAL units in [buf] (position=0, limit=size) for a SEI
     * with the OndoZero UUID. Returns the JSON payload string or null.
     */
    internal fun findSeiJson(buf: ByteBuffer): String? {
        val arr = buf.array()
        var off = buf.position()
        val end = buf.limit()
        while (off + 5 <= end) {
            val nalLen = ((arr[off  ].toInt() and 0xFF) shl 24) or
                         ((arr[off+1].toInt() and 0xFF) shl 16) or
                         ((arr[off+2].toInt() and 0xFF) shl 8 ) or
                          (arr[off+3].toInt() and 0xFF)
            if (nalLen <= 0 || off + 4 + nalLen > end) break
            val nalStart = off + 4
            val nalType  = arr[nalStart].toInt() and 0x1F
            if (nalType == 6 && nalStart + 1 < end) {               // 0x06 = SEI
                parseSeiForUuid(arr, nalStart + 1, nalStart + nalLen)?.let { return it }
            }
            off += 4 + nalLen
        }
        return null
    }

    /**
     * Parses SEI RBSP starting at [start] (after nal_unit_type byte).
     * Iterates payloadType/payloadSize pairs looking for type=5 with OndoZero UUID.
     */
    internal fun parseSeiForUuid(arr: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos < end - 1) {
            // Read payloadType (EBSP encoded).
            var payloadType = 0
            while (pos < end && arr[pos] == 0xFF.toByte()) { payloadType += 255; pos++ }
            if (pos >= end) return null
            payloadType += arr[pos++].toInt() and 0xFF

            // Read payloadSize (EBSP encoded).
            var payloadSize = 0
            while (pos < end && arr[pos] == 0xFF.toByte()) { payloadSize += 255; pos++ }
            if (pos >= end) return null
            payloadSize += arr[pos++].toInt() and 0xFF

            if (payloadType == 5 && payloadSize >= 16 && pos + payloadSize <= end) {
                if (arr.sliceArray(pos until pos + 16).contentEquals(UUID_BYTES)) {
                    return String(arr, pos + 16, payloadSize - 16, Charsets.UTF_8)
                }
            }
            pos += payloadSize
        }
        return null
    }
}
