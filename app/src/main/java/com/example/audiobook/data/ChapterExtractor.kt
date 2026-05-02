package com.example.audiobook.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.FileDescriptor

/**
 * Reads chapter markers out of an MP4 / M4B file by walking the atom tree directly.
 * Supports the Apple-style chapter text track (a separate trak with handler_type "text"
 * and a tref/chap link from the audio track), which is what ffmpeg `-c copy` preserves
 * when transcoding from .aaxc.
 *
 * Android's MediaExtractor does NOT reliably expose this track on most devices, so we
 * parse the file ourselves over the SAF ContentResolver pfd.
 */
object ChapterExtractor {

    private const val TAG = "ChapterExtractor"

    fun extract(context: Context, uri: Uri): List<Chapter> {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                parsePfd(pfd)
            } ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "extract failed for $uri", t)
            emptyList()
        }
    }

    private fun parsePfd(pfd: ParcelFileDescriptor): List<Chapter> {
        val fd: FileDescriptor = pfd.fileDescriptor
        val totalSize = Os.fstat(fd).st_size
        val moov = findTopLevelAtom(fd, totalSize, "moov") ?: return emptyList()
        val moovBytes = readBytes(fd, moov.payloadStart, moov.payloadSize.toInt())

        val traks = childAtomsOf(moovBytes, "trak")
        val chapTrackId = findChapterTrackIdFromAudioTref(moovBytes, traks)

        val chapterTrak = traks.firstOrNull { trak ->
            val tkhd = findChild(moovBytes, trak.payloadStart, trak.payloadSize.toInt(), "tkhd")
                ?: return@firstOrNull false
            chapTrackId != null && readTkhdTrackId(moovBytes, tkhd) == chapTrackId
        } ?: traks.firstOrNull { trak ->
            handlerTypeOfTrak(moovBytes, trak) == "text"
        } ?: return emptyList()

        // Read mdia/mdhd timescale and stbl tables, then map samples to chapters.
        val trakOff = chapterTrak.payloadStart
        val trakSize = chapterTrak.payloadSize.toInt()
        val mdia = findChild(moovBytes, trakOff, trakSize, "mdia") ?: return emptyList()
        val mdhd = findChild(moovBytes, mdia.payloadStart, mdia.payloadSize.toInt(), "mdhd")
            ?: return emptyList()
        val timescale = readMdhdTimescale(moovBytes, mdhd)
        val minf = findChild(moovBytes, mdia.payloadStart, mdia.payloadSize.toInt(), "minf")
            ?: return emptyList()
        val stbl = findChild(moovBytes, minf.payloadStart, minf.payloadSize.toInt(), "stbl")
            ?: return emptyList()
        val stbStart = stbl.payloadStart
        val stbSize = stbl.payloadSize.toInt()

        val stts = findChild(moovBytes, stbStart, stbSize, "stts") ?: return emptyList()
        val stsz = findChild(moovBytes, stbStart, stbSize, "stsz") ?: return emptyList()
        val stsc = findChild(moovBytes, stbStart, stbSize, "stsc") ?: return emptyList()
        val stco = findChild(moovBytes, stbStart, stbSize, "stco")
            ?: findChild(moovBytes, stbStart, stbSize, "co64")
            ?: return emptyList()

        val sampleTimesUnits = readStts(moovBytes, stts)
        val sampleSizes = readStsz(moovBytes, stsz)
        val chunkOffsets = readChunkOffsets(moovBytes, stco)
        val sampleToChunk = readStsc(moovBytes, stsc)

        val sampleCount = minOf(sampleTimesUnits.size, sampleSizes.size)
        val sampleFileOffsets = computeSampleFileOffsets(sampleCount, sampleSizes, chunkOffsets, sampleToChunk)

        // Read each sample's bytes from the file and decode title.
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until sampleCount) {
            val size = sampleSizes[i]
            val off = sampleFileOffsets.getOrNull(i) ?: continue
            if (size <= 2 || size > 65536) continue
            val raw = readBytes(fd, off, size)
            val title = decodeAppleTitleSample(raw) ?: continue
            if (title.isBlank()) continue
            val ms = ((sampleTimesUnits[i] * 1000.0) / timescale).toLong()
            chapters += Chapter(index = chapters.size, title = title.trim(), startMs = ms.coerceAtLeast(0L))
        }
        return chapters.sortedBy { it.startMs }.mapIndexed { i, c -> c.copy(index = i) }
    }

    // --- Atom walking ---------------------------------------------------------

    private data class Atom(
        val type: String,
        val headerStart: Long,
        val payloadStart: Long,
        val payloadSize: Long,
    )

    private fun findTopLevelAtom(fd: FileDescriptor, total: Long, want: String): Atom? {
        var pos = 0L
        while (pos + 8 <= total) {
            val header = readBytes(fd, pos, 8)
            if (header.size < 8) break
            val rawSize = beU32(header, 0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val (payloadStart, payloadSize) = when (rawSize) {
                1L -> {
                    val ext = readBytes(fd, pos + 8, 8)
                    val big = beU64(ext, 0)
                    pos + 16 to (big - 16)
                }
                0L -> pos + 8 to (total - pos - 8)
                else -> pos + 8 to (rawSize - 8)
            }
            if (type == want) return Atom(type, pos, payloadStart, payloadSize)
            val nextPos = payloadStart + payloadSize
            if (nextPos <= pos) break
            pos = nextPos
        }
        return null
    }

    private fun childAtomsOf(parent: ByteArray, want: String): List<Atom> {
        val out = mutableListOf<Atom>()
        var p = 0
        while (p + 8 <= parent.size) {
            val size = (beU32(parent, p).toLong() and 0xFFFFFFFFL)
            val type = String(parent, p + 4, 4, Charsets.ISO_8859_1)
            val (payloadStart, payloadSize) = when (size) {
                1L -> {
                    val big = beU64(parent, p + 8)
                    (p + 16).toLong() to (big - 16)
                }
                0L -> (p + 8).toLong() to (parent.size - p - 8).toLong()
                else -> (p + 8).toLong() to (size - 8)
            }
            if (type == want) out += Atom(type, p.toLong(), payloadStart, payloadSize)
            val nextP = (payloadStart + payloadSize).toInt()
            if (nextP <= p) break
            p = nextP
        }
        return out
    }

    private fun findChild(parent: ByteArray, parentStart: Long, parentSize: Int, want: String): Atom? {
        val pStart = parentStart.toInt()
        var p = pStart
        val end = pStart + parentSize
        while (p + 8 <= end) {
            val size = (beU32(parent, p).toLong() and 0xFFFFFFFFL)
            val type = String(parent, p + 4, 4, Charsets.ISO_8859_1)
            val (payloadStart, payloadSize) = when (size) {
                1L -> {
                    val big = beU64(parent, p + 8)
                    (p + 16).toLong() to (big - 16)
                }
                0L -> (p + 8).toLong() to (end - p - 8).toLong()
                else -> (p + 8).toLong() to (size - 8)
            }
            if (type == want) return Atom(type, p.toLong(), payloadStart, payloadSize)
            val nextP = (payloadStart + payloadSize).toInt()
            if (nextP <= p) break
            p = nextP
        }
        return null
    }

    private fun handlerTypeOfTrak(moov: ByteArray, trak: Atom): String? {
        val mdia = findChild(moov, trak.payloadStart, trak.payloadSize.toInt(), "mdia") ?: return null
        val hdlr = findChild(moov, mdia.payloadStart, mdia.payloadSize.toInt(), "hdlr") ?: return null
        // hdlr layout: 4 (version+flags) + 4 (pre_defined) + 4 (handler_type) + ...
        val hStart = hdlr.payloadStart.toInt()
        if (hdlr.payloadSize < 12) return null
        return String(moov, hStart + 8, 4, Charsets.ISO_8859_1)
    }

    private fun findChapterTrackIdFromAudioTref(moov: ByteArray, traks: List<Atom>): Int? {
        for (trak in traks) {
            val handler = handlerTypeOfTrak(moov, trak)
            if (handler != "soun") continue
            val tref = findChild(moov, trak.payloadStart, trak.payloadSize.toInt(), "tref") ?: continue
            val chap = findChild(moov, tref.payloadStart, tref.payloadSize.toInt(), "chap") ?: continue
            // chap content: array of u32 trackIDs
            if (chap.payloadSize >= 4) {
                return beU32(moov, chap.payloadStart.toInt())
            }
        }
        return null
    }

    private fun readTkhdTrackId(moov: ByteArray, tkhd: Atom): Int {
        val s = tkhd.payloadStart.toInt()
        val ver = moov[s].toInt() and 0xFF
        // version 0: tkhd has u32 ts_creation, u32 ts_modification, u32 track_id
        // version 1: u64, u64, u32 track_id
        val off = if (ver == 1) 4 + 8 + 8 else 4 + 4 + 4
        return beU32(moov, s + off)
    }

    private fun readMdhdTimescale(moov: ByteArray, mdhd: Atom): Int {
        val s = mdhd.payloadStart.toInt()
        val ver = moov[s].toInt() and 0xFF
        val off = if (ver == 1) 4 + 8 + 8 else 4 + 4 + 4
        return beU32(moov, s + off)
    }

    // --- Sample table parsing -------------------------------------------------

    private fun readStts(moov: ByteArray, stts: Atom): LongArray {
        val s = stts.payloadStart.toInt()
        val entryCount = beU32(moov, s + 4)
        val list = ArrayList<Long>()
        var t = 0L
        var p = s + 8
        repeat(entryCount) {
            val cnt = beU32(moov, p)
            val delta = beU32(moov, p + 4)
            p += 8
            repeat(cnt) {
                list += t
                t += delta.toLong()
            }
        }
        return list.toLongArray()
    }

    private fun readStsz(moov: ByteArray, stsz: Atom): IntArray {
        val s = stsz.payloadStart.toInt()
        val sampleSize = beU32(moov, s + 4)
        val sampleCount = beU32(moov, s + 8)
        return if (sampleSize != 0) {
            IntArray(sampleCount) { sampleSize }
        } else {
            val arr = IntArray(sampleCount)
            var p = s + 12
            for (i in 0 until sampleCount) {
                arr[i] = beU32(moov, p)
                p += 4
            }
            arr
        }
    }

    private fun readChunkOffsets(moov: ByteArray, stco: Atom): LongArray {
        val s = stco.payloadStart.toInt()
        val entryCount = beU32(moov, s + 4)
        val arr = LongArray(entryCount)
        return when (stco.type) {
            "stco" -> {
                var p = s + 8
                for (i in 0 until entryCount) { arr[i] = beU32(moov, p).toLong(); p += 4 }
                arr
            }
            "co64" -> {
                var p = s + 8
                for (i in 0 until entryCount) { arr[i] = beU64(moov, p); p += 8 }
                arr
            }
            else -> arr
        }
    }

    private data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)

    private fun readStsc(moov: ByteArray, stsc: Atom): List<StscEntry> {
        val s = stsc.payloadStart.toInt()
        val entryCount = beU32(moov, s + 4)
        val out = ArrayList<StscEntry>(entryCount)
        var p = s + 8
        for (i in 0 until entryCount) {
            val firstChunk = beU32(moov, p)
            val samplesPerChunk = beU32(moov, p + 4)
            p += 12 // skip sample_description_index too
            out += StscEntry(firstChunk, samplesPerChunk)
        }
        return out
    }

    private fun computeSampleFileOffsets(
        sampleCount: Int,
        sampleSizes: IntArray,
        chunkOffsets: LongArray,
        stsc: List<StscEntry>,
    ): LongArray {
        // Expand stsc into per-chunk samplesPerChunk array.
        val chunkCount = chunkOffsets.size
        val samplesPerChunk = IntArray(chunkCount)
        var entryIdx = 0
        for (ci in 0 until chunkCount) {
            val chunkNum = ci + 1
            while (entryIdx + 1 < stsc.size && stsc[entryIdx + 1].firstChunk <= chunkNum) entryIdx++
            samplesPerChunk[ci] = stsc[entryIdx].samplesPerChunk
        }
        val offsets = LongArray(sampleCount)
        var sampleIdx = 0
        for (ci in 0 until chunkCount) {
            var off = chunkOffsets[ci]
            val n = samplesPerChunk[ci]
            for (s in 0 until n) {
                if (sampleIdx >= sampleCount) return offsets
                offsets[sampleIdx] = off
                off += sampleSizes[sampleIdx].toLong()
                sampleIdx++
            }
        }
        return offsets
    }

    private fun decodeAppleTitleSample(raw: ByteArray): String? {
        if (raw.size < 2) return null
        val len = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        if (len <= 0 || len + 2 > raw.size) return null
        return String(raw, 2, len, Charsets.UTF_8)
    }

    // --- Low-level I/O helpers ------------------------------------------------

    private fun readBytes(fd: FileDescriptor, offset: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = Os.pread(fd, buf, read, size - read, offset + read)
            if (n <= 0) break
            read += n
        }
        return if (read == size) buf else buf.copyOf(read)
    }

    private fun beU32(buf: ByteArray, p: Int): Int =
        ((buf[p].toInt() and 0xFF) shl 24) or
            ((buf[p + 1].toInt() and 0xFF) shl 16) or
            ((buf[p + 2].toInt() and 0xFF) shl 8) or
            (buf[p + 3].toInt() and 0xFF)

    private fun beU64(buf: ByteArray, p: Int): Long =
        ((buf[p].toLong() and 0xFF) shl 56) or
            ((buf[p + 1].toLong() and 0xFF) shl 48) or
            ((buf[p + 2].toLong() and 0xFF) shl 40) or
            ((buf[p + 3].toLong() and 0xFF) shl 32) or
            ((buf[p + 4].toLong() and 0xFF) shl 24) or
            ((buf[p + 5].toLong() and 0xFF) shl 16) or
            ((buf[p + 6].toLong() and 0xFF) shl 8) or
            (buf[p + 7].toLong() and 0xFF)
}

