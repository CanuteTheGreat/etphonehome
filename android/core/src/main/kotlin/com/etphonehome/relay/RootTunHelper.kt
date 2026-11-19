package com.etphonehome.relay

import java.io.IOException

/** Starts a process from an argv. Swappable so [RootTunHelper] is testable without su. */
fun interface ProcessStarter {
    fun start(argv: List<String>): Process
}

/**
 * Root mode, step 6b: launches the root helper that opens the already-created
 * tun device ([TunDevicePlan]) and speaks the [StreamPacketPort] framing on its
 * stdin/stdout. The helper runs as `su -c "exec <helper> tun-pipe <ifname>"`;
 * every argument is single-quoted by [SuExecutor.quote], and the interface name
 * is validated first, so neither can become shell syntax.
 *
 * Closing the returned port destroys the helper, which releases the tun fd.
 */
object RootTunHelper {

    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")

    /** The argv handed to the process starter (`su -c <quoted command>`). */
    fun argv(helperPath: String, tunInterface: String, su: String = "su"): List<String> {
        require(helperPath.startsWith("/") && '\u0000' !in helperPath) { "helperPath must be an absolute path" }
        require(IFACE.matches(tunInterface)) { "tunInterface is not a plain interface name" }
        val cmd = listOf("exec", SuExecutor.quote(helperPath), "tun-pipe", SuExecutor.quote(tunInterface))
        return listOf(su, "-c", cmd.joinToString(" "))
    }

    /**
     * Starts the helper and returns a [PacketPort] over its pipes.
     * @throws IOException when the process cannot be started or is already dead
     */
    fun open(
        helperPath: String,
        tunInterface: String,
        start: ProcessStarter = ProcessStarter { ProcessBuilder(it).redirectErrorStream(false).start() },
        su: String = "su",
    ): PacketPort {
        val p = try {
            start.start(argv(helperPath, tunInterface, su))
        } catch (e: IOException) {
            throw IOException("cannot start the root tun helper: ${e.message}", e)
        }
        if (!p.isAlive) throw IOException("root tun helper exited at once (code ${p.exitValue()})")
        return StreamPacketPort(p.inputStream, p.outputStream) { p.destroyForcibly() }
    }
}
