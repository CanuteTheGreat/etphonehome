package com.etphonehome.pro

import com.etphonehome.session.ClientIdentity

/** A computer or router that has used this phone. */
data class PairedDevice(
    val id: String,
    val name: String,
    val router: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/**
 * The phone's list of computers/routers (shown under "Paired computers", each with Forget).
 * Kept as a small line format so the pure-Kotlin core needs no JSON library; the app stores
 * the string in SharedPreferences.
 */
object PairedDevices {
    /** At most this many are remembered (oldest dropped). */
    const val MAX = 32

    fun seen(list: List<PairedDevice>, client: ClientIdentity, nowMs: Long): List<PairedDevice> {
        val existing = list.firstOrNull { it.id == client.id }
        val updated = existing?.copy(name = client.name, router = client.router, lastSeenMs = nowMs)
            ?: PairedDevice(client.id, client.name, client.router, nowMs, nowMs)
        return (list.filter { it.id != client.id } + updated)
            .sortedByDescending { it.lastSeenMs }
            .take(MAX)
    }

    fun forget(list: List<PairedDevice>, id: String): List<PairedDevice> = list.filter { it.id != id }

    fun encode(list: List<PairedDevice>): String = list.joinToString("\n") {
        listOf(esc(it.id), esc(it.name), if (it.router) "1" else "0", it.firstSeenMs, it.lastSeenMs).joinToString("\t")
    }

    fun decode(s: String?): List<PairedDevice> = s.orEmpty().lines().mapNotNull { line ->
        val f = line.split("\t")
        if (f.size != 5) return@mapNotNull null
        val first = f[3].toLongOrNull() ?: return@mapNotNull null
        val last = f[4].toLongOrNull() ?: return@mapNotNull null
        PairedDevice(unesc(f[0]), unesc(f[1]), f[2] == "1", first, last)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
