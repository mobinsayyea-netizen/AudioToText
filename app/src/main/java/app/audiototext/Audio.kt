package app.audiototext

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One slice of an audio file that is sent to Gemini in a single request. */
class Piece(val file: File, val off: Long, val len: Int, val mime: String)

object Audio {
    private const val MAX_BYTES = 9L * 1024 * 1024

    fun isMp3(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp3") || n.endsWith(".mpga") || n.endsWith(".mpeg")
    }

    fun durationSec(f: File): Double {
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                ms / 1000.0
            } finally {
                r.release()
            }
        } catch (e: Exception) {
            0.0
        }
    }

    /** Moves a cut point forward to the next MP3 frame header so each piece starts cleanly. */
    private fun nextFrame(raf: RandomAccessFile, from: Long, size: Long): Long {
        val end = minOf(size - 1, from + 8192)
        if (from >= end) return from
        val buf = ByteArray((end - from + 1).toInt())
        raf.seek(from)
        val n = raf.read(buf)
        for (i in 0 until n - 1) {
            if (buf[i] == 0xFF.toByte() && (buf[i + 1].toInt() and 0xE0) == 0xE0) return from + i
        }
        return from
    }

    /** Cuts an MP3 by bytes. MP3 frames are independent, so every piece plays on its own. */
    fun sliceMp3(f: File, chunkMin: Int): List<Piece> {
        val size = f.length()
        val dur = durationSec(f)
        val byTime = if (dur > 0) Math.ceil(dur / (chunkMin * 60.0)).toInt() else 1
        val bySize = Math.ceil(size.toDouble() / MAX_BYTES).toInt()
        val n = maxOf(1, byTime, bySize)
        val step = Math.ceil(size.toDouble() / n).toLong()
        val cuts = ArrayList<Long>()
        cuts.add(0L)
        RandomAccessFile(f, "r").use { raf ->
            for (i in 1 until n) cuts.add(nextFrame(raf, i * step, size))
        }
        cuts.add(size)
        val out = ArrayList<Piece>()
        for (i in 0 until n) {
            if (cuts[i + 1] > cuts[i]) out.add(Piece(f, cuts[i], (cuts[i + 1] - cuts[i]).toInt(), "audio/mp3"))
        }
        return out
    }

    fun read(p: Piece): ByteArray {
        val b = ByteArray(p.len)
        RandomAccessFile(p.file, "r").use {
            it.seek(p.off)
            it.readFully(b)
        }
        return b
    }

    private fun wavHeader(dataLen: Int, rate: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(rate)
        bb.putInt(rate * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        return bb.array()
    }

    /**
     * For m4a, wav, aac, ogg, flac, mp4 and others: decodes with the phone's own decoder,
     * makes mono 16 kHz audio and writes WAV pieces (at most 4 minutes each).
     */
    fun decodeToWavPieces(src: File, chunkMin: Int, outDir: File, stop: () -> Boolean): List<Piece> {
        outDir.mkdirs()
        val pieces = ArrayList<Piece>()
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(src.absolutePath)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IOException("No audio track found in this file")
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(inFmt, null, null, 0)
            dec.start()

            var channels = if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var inRate = if (inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var outRate = minOf(inRate, 16000)
            var ratio = inRate.toDouble() / outRate
            val maxMin = minOf(chunkMin, 4)
            var maxSamples = maxMin * 60 * outRate

            fun applyFormat(f: MediaFormat) {
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (channels < 1) channels = 1
                outRate = minOf(inRate, 16000)
                ratio = inRate.toDouble() / outRate
                maxSamples = maxMin * 60 * outRate
            }

            val pcm = ByteArrayOutputStream()
            var outCount = 0
            var frameIdx = 0L
            var nextEmit = 0.0
            var sum = 0L
            var cnt = 0
            var pieceNo = 0

            fun flush() {
                if (outCount == 0) return
                val data = pcm.toByteArray()
                val f = File(outDir, "piece_${pieceNo++}.wav")
                f.outputStream().use {
                    it.write(wavHeader(data.size, outRate))
                    it.write(data)
                }
                pieces.add(Piece(f, 0L, f.length().toInt(), "audio/wav"))
                pcm.reset()
                outCount = 0
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (stop()) break
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    applyFormat(dec.outputFormat)
                } else if (oi >= 0) {
                    val ob = dec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val total = sb.remaining()
                    var i = 0
                    while (i + channels <= total) {
                        var s = 0
                        for (c in 0 until channels) s += sb.get(i + c).toInt()
                        s /= channels
                        sum += s
                        cnt++
                        frameIdx++
                        if (frameIdx >= nextEmit) {
                            val v = (sum / cnt).toInt()
                            pcm.write(v and 0xFF)
                            pcm.write((v shr 8) and 0xFF)
                            outCount++
                            sum = 0
                            cnt = 0
                            nextEmit += ratio
                            if (outCount >= maxSamples) flush()
                        }
                        i += channels
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            flush()
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // ignore
            }
            ex.release()
        }
        return pieces
    }
}
