package com.comix

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class CipherMaterial(
    val sboxes: List<List<Int>>,
    val keys: List<List<Int>>,
) {
    fun isValid(): Boolean =
        sboxes.size == 3 && sboxes.all { it.size == 256 } &&
        keys.size == 3 && keys.all { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sboxes", JSONArray().apply { sboxes.forEach { put(JSONArray(it)) } })
        put("keys",   JSONArray().apply { keys.forEach   { put(JSONArray(it)) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): CipherMaterial? = runCatching {
            val sboxesArr = obj.getJSONArray("sboxes")
            val keysArr   = obj.getJSONArray("keys")
            CipherMaterial(
                sboxes = (0 until sboxesArr.length()).map { i ->
                    val inner = sboxesArr.getJSONArray(i)
                    (0 until inner.length()).map { inner.getInt(it) }
                },
                keys = (0 until keysArr.length()).map { i ->
                    val inner = keysArr.getJSONArray(i)
                    (0 until inner.length()).map { inner.getInt(it) }
                },
            )
        }.getOrNull()?.takeIf { it.isValid() }
    }
}

class ComixCipher(material: CipherMaterial) {

    private val sboxes: List<IntArray> = material.sboxes.map { it.toIntArray() }
    private val keys:   List<IntArray> = material.keys.map   { it.toIntArray() }

    init { require(material.isValid()) { "Invalid Comix cipher material" } }

    fun sign(path: String, query: String): String {
        var data = buildString {
            append(path.removePrefix("/api/v1"))
            if (query.isNotEmpty()) append("?$query")
        }.toByteArray(Charsets.UTF_8)

        repeat(3) { round ->
            data = substitute(data, sboxes[round], keys[round], PREVIOUS[round])
        }

        return Base64.encodeToString(
            data,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun decrypt(value: String): String {
        var data = Base64.decode(
            value,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        for (round in 2 downTo 0) {
            data = substituteInverse(data, sboxes[round], keys[round], PREVIOUS[round])
        }
        return data.toString(Charsets.UTF_8)
    }

    private fun substitute(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val out = ByteArray(data.size)
        var prev = previous
        for (i in data.indices) {
            val v = (data[i].toInt() and 0xff) xor key[i % key.size] xor prev
            val s = sbox[v and 0xff]
            out[i] = s.toByte()
            prev = s
        }
        return out
    }

    private fun substituteInverse(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val inv = IntArray(256)
        for (i in sbox.indices) inv[sbox[i] and 0xff] = i
        val out = ByteArray(data.size)
        var prev = previous
        for (i in data.indices) {
            val v = data[i].toInt() and 0xff
            val decoded = inv[v] xor key[i % key.size] xor prev
            out[i] = decoded.toByte()
            prev = v
        }
        return out
    }

    companion object {
        private val PREVIOUS = intArrayOf(189, 133, 32)
    }
}
