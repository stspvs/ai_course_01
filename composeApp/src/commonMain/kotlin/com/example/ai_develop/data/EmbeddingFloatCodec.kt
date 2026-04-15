package com.example.ai_develop.data

/**
 * Little-endian float32 packing for SQLite BLOB storage.
 */
object EmbeddingFloatCodec {

    fun floatArrayToLittleEndianBytes(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        var o = 0
        for (v in values) {
            val bits = v.toBits()
            out[o++] = (bits and 0xff).toByte()
            out[o++] = ((bits shr 8) and 0xff).toByte()
            out[o++] = ((bits shr 16) and 0xff).toByte()
            out[o++] = ((bits shr 24) and 0xff).toByte()
        }
        return out
    }

    fun littleEndianBytesToFloatArray(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "BLOB length must be multiple of 4, got ${bytes.size}" }
        val n = bytes.size / 4
        val out = FloatArray(n)
        var i = 0
        var o = 0
        while (o < n) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = bytes[i + 1].toInt() and 0xff
            val b2 = bytes[i + 2].toInt() and 0xff
            val b3 = bytes[i + 3].toInt() and 0xff
            val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            out[o++] = Float.fromBits(bits)
            i += 4
        }
        return out
    }
}
