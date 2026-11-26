package controllima.server.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Minimal ustar writer and reader for backup archives (regular files only). The JDK has gzip but no
 * tar, and the format is small enough that a library (a new dependency) is not worth it.
 */
object Tar {
    private const val BLOCK = 512

    fun gzip(entries: List<Pair<String, ByteArray>>, mtimeSeconds: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gz -> write(gz, entries, mtimeSeconds) }
        return bytes.toByteArray()
    }

    fun write(out: OutputStream, entries: List<Pair<String, ByteArray>>, mtimeSeconds: Long) {
        for ((name, data) in entries) {
            require(name.length in 1..100 && !name.startsWith("/") && ".." !in name.split('/')) { "bad tar entry name: $name" }
            val header = ByteArray(BLOCK)
            put(header, 0, 100, name)
            put(header, 100, 8, octal(0b110_100_000, 7))
            put(header, 108, 8, octal(0, 7))
            put(header, 116, 8, octal(0, 7))
            put(header, 124, 12, octal(data.size.toLong(), 11))
            put(header, 136, 12, octal(mtimeSeconds, 11))
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            put(header, 257, 6, "ustar")
            put(header, 263, 2, "00")
            val sum = header.sumOf { it.toInt() and 0xff }
            put(header, 148, 8, octal(sum.toLong(), 6) + "\u0000 ")
            out.write(header)
            out.write(data)
            val pad = (BLOCK - data.size % BLOCK) % BLOCK
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(BLOCK * 2))
    }

    /** Reads regular files into memory, at most [maxBytes] in total; names are returned as-is and never used as paths. */
    fun readGzip(input: InputStream, maxBytes: Long = 64L * 1024 * 1024): Map<String, ByteArray> =
        GZIPInputStream(input).use { read(it, maxBytes) }

    fun read(input: InputStream, maxBytes: Long): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var total = 0L
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            val name = str(header, 0, 100).let { n -> str(header, 345, 155).takeIf { it.isNotEmpty() }?.let { "$it/$n" } ?: n }
            val size = str(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            require(size >= 0) { "bad tar entry size" }
            total += size
            require(total <= maxBytes) { "archive too large" }
            val type = header[156].toInt().toChar()
            val data = ByteArray(size.toInt())
            require(readFully(input, data)) { "truncated archive" }
            val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
            if (pad > 0) require(readFully(input, ByteArray(pad))) { "truncated archive" }
            if (type == '0' || type == '\u0000') out[name] = data
        }
        return out
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off == 0 && buf.isEmpty()
            off += n
        }
        return true
    }

    private fun octal(value: Long, width: Int) = value.toString(8).padStart(width, '0')

    private fun put(header: ByteArray, offset: Int, length: Int, text: String) {
        val bytes = text.encodeToByteArray()
        require(bytes.size <= length) { "tar field too long" }
        bytes.copyInto(header, offset)
    }

    private fun str(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end].toInt() != 0) end++
        return header.copyOfRange(offset, end).decodeToString()
    }
}
