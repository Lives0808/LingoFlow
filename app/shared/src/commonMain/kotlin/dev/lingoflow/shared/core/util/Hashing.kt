package dev.lingoflow.shared.core.util

/**
 * Small pure-Kotlin helpers so the shared module has no JVM-only dependencies.
 */

/** SHA-256 (FIPS 180-4), used for translation-memory and corpus keys. */
object Sha256 {
    private val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    fun hex(input: String): String = hex(input.encodeToByteArray())

    fun hex(bytes: ByteArray): String {
        val digest = digest(bytes)
        val out = StringBuilder(64)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    fun digest(bytes: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9774
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val bitLength = bytes.size.toLong() * 8
        val paddedSize = ((bytes.size + 9 + 63) / 64) * 64
        val message = ByteArray(paddedSize)
        bytes.copyInto(message)
        message[bytes.size] = 0x80.toByte()
        for (i in 0 until 8) {
            message[paddedSize - 1 - i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
        }

        val w = IntArray(64)
        for (chunk in 0 until paddedSize / 64) {
            val offset = chunk * 64
            for (i in 0 until 16) {
                w[i] = ((message[offset + i * 4].toInt() and 0xff) shl 24) or
                    ((message[offset + i * 4 + 1].toInt() and 0xff) shl 16) or
                    ((message[offset + i * 4 + 2].toInt() and 0xff) shl 8) or
                    (message[offset + i * 4 + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
        }

        val out = ByteArray(32)
        val words = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
        for (i in words.indices) {
            out[i * 4] = (words[i] ushr 24).toByte()
            out[i * 4 + 1] = (words[i] ushr 16).toByte()
            out[i * 4 + 2] = (words[i] ushr 8).toByte()
            out[i * 4 + 3] = words[i].toByte()
        }
        return out
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    private const val HEX = "0123456789abcdef"
}

/** Epoch millis -> "2026-09-29 10:11" in UTC, without platform date libraries. */
object SimpleDate {
    fun format(epochMillis: Long): String {
        var days = epochMillis / 86_400_000L
        var millisOfDay = epochMillis % 86_400_000L
        if (millisOfDay < 0) {
            millisOfDay += 86_400_000L
            days -= 1
        }
        val hours = millisOfDay / 3_600_000L
        val minutes = (millisOfDay % 3_600_000L) / 60_000L
        val (year, month, day) = civilFromDays(days)
        return buildString {
            append(year.toString().padStart(4, '0'))
            append('-')
            append(month.toString().padStart(2, '0'))
            append('-')
            append(day.toString().padStart(2, '0'))
            append(' ')
            append(hours.toString().padStart(2, '0'))
            append(':')
            append(minutes.toString().padStart(2, '0'))
        }
    }

    /** Howard Hinnant's civil-from-days algorithm. */
    private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
        var z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        z = if (m <= 2) y + 1 else y
        return Triple(z, m.toInt(), d.toInt())
    }
}
