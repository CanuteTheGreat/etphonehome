package com.etphonehome.relay

import java.util.concurrent.TimeUnit

/** Result of one root command. */
data class ExecResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

/** Runs one argv as root. Swappable so [PlanRunner] is testable without a root shell. */
fun interface CommandExecutor {
    fun run(argv: List<String>): ExecResult
}

/**
 * Root mode, step 2: runs commands through `su -c`. The argv is quoted
 * element by element (single quotes), so a value can never become shell syntax.
 */
class SuExecutor(private val su: String = "su", private val timeoutSeconds: Long = 10) : CommandExecutor {
    override fun run(argv: List<String>): ExecResult {
        return try {
            val p = ProcessBuilder(su, "-c", argv.joinToString(" ") { quote(it) })
                .redirectErrorStream(true)
                .start()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return ExecResult(124, "timed out after ${timeoutSeconds}s")
            }
            ExecResult(p.exitValue(), p.inputStream.bufferedReader().readText().trim())
        } catch (e: Exception) {
            ExecResult(127, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        fun quote(arg: String) = "'" + arg.replace("'", "'\\''") + "'"
    }
}

object PlanRunner {

    sealed class Outcome {
        object Applied : Outcome()
        /** [step] failed with [result]; the whole plan was then torn down (every teardown step tolerates already-undone state). */
        data class Failed(val step: KernelNatPlan.Step, val result: ExecResult) : Outcome()
    }

    /** True when the executor really is uid 0 (a `su` that exists but denies access is not root). */
    fun hasRoot(exec: CommandExecutor): Boolean {
        val r = exec.run(listOf("id", "-u"))
        return r.ok && r.output.trim().lines().lastOrNull()?.trim() == "0"
    }

    /**
     * Runs [KernelNatPlan.enable]; on the first failing step it runs
     * [KernelNatPlan.disable] so no half-applied NAT stays behind.
     */
    fun enable(p: KernelNatPlan.Params, exec: CommandExecutor): Outcome {
        for (step in KernelNatPlan.enable(p)) {
            val r = exec.run(step.argv)
            if (!r.ok && !step.ignoreFailure) {
                teardown(p, exec)
                return Outcome.Failed(step, r)
            }
        }
        return Outcome.Applied
    }

    /** Runs [KernelNatPlan.disable]: every step, in order; failures are expected (already undone) and ignored. */
    fun disable(p: KernelNatPlan.Params, exec: CommandExecutor) = teardown(p, exec)

    private fun teardown(p: KernelNatPlan.Params, exec: CommandExecutor) {
        for (step in KernelNatPlan.disable(p)) exec.run(step.argv)
    }

    /**
     * Creates the tun device ([TunDevicePlan.enable]); on the first failing
     * step removes it again, so no half-configured interface stays behind.
     */
    fun enableTun(p: TunDevicePlan.Params, exec: CommandExecutor): Outcome {
        for (step in TunDevicePlan.enable(p)) {
            val r = exec.run(step.argv)
            if (!r.ok && !step.ignoreFailure) {
                disableTun(p, exec)
                return Outcome.Failed(step, r)
            }
        }
        return Outcome.Applied
    }

    fun disableTun(p: TunDevicePlan.Params, exec: CommandExecutor) {
        for (step in TunDevicePlan.disable(p)) exec.run(step.argv)
    }
}
