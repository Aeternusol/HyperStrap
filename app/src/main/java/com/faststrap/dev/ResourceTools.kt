package com.faststrap.dev

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.telecom.TelecomManager

class AppEntry(val label: String, val pkg: String)

/** Outcome of a freeze or restore run: package to method (or empty) for successes, package to reason for failures. */
class FreezeResult(val ok: Map<String, String>, val failed: Map<String, String>)

/**
 * Shell scripts and helpers for freezing apps, clearing Roblox's cache and a few root-only
 * system tools.
 *
 * Freezing has three methods. Suspend (`pm suspend`) and Disable (`pm disable-user`) need only
 * Shizuku: the app is stopped and can't start again until restored, so it uses no CPU and
 * gives its memory back. Pause needs root: the app's processes are stopped in place with
 * SIGSTOP and continued with SIGCONT, so they keep their state but use no CPU.
 */
object ResourceTools {
    private val pkgRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val devRegex = Regex("^/[A-Za-z0-9_./-]+$")

    /** Package names are only letters, digits, dots and underscores, which keeps them safe in a shell. */
    fun validPackage(p: String): Boolean = pkgRegex.matches(p)

    /** Apps that must never be frozen: this app, Shizuku, Roblox, the system UI and basic phone parts. */
    fun protectedPackages(ctx: Context, robloxPkg: String): Set<String> {
        val s = hashSetOf(
            ctx.packageName, robloxPkg, "moe.shizuku.privileged.api", "android",
            "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.server.telecom", "com.android.shell", "com.android.providers.settings",
            "com.android.providers.telephony", "com.android.providers.contacts",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "me.weishu.kernelsu", "com.topjohnwu.magisk"
        )
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName?.let { s.add(it) }
        } catch (e: Exception) {
            // Keep going with what we have.
        }
        try {
            Settings.Secure.getString(ctx.contentResolver, "default_input_method")
                ?.substringBefore('/')?.let { s.add(it) }
        } catch (e: Exception) {
            // Keep going with what we have.
        }
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let { s.add(it) }
        } catch (e: Exception) {
            // Keep going with what we have.
        }
        return s
    }

    /** Apps the user could freeze. System apps are only listed on request, and only ones with an icon. */
    fun installedApps(ctx: Context, includeSystem: Boolean, blocked: Set<String>): List<AppEntry> {
        val pm = ctx.packageManager
        val out = ArrayList<AppEntry>()
        for (ai in pm.getInstalledApplications(0)) {
            val p = ai.packageName
            if (p in blocked || !ai.enabled) continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system && (!includeSystem || pm.getLaunchIntentForPackage(p) == null)) continue
            out.add(AppEntry(ai.loadLabel(pm).toString(), p))
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** Shell that lists the process ids of a package, including its sub-processes (pkg:name). */
    private fun pidsLine(p: String): String =
        "PIDS=\$(ps -A -o PID,NAME 2>/dev/null | while read P N; do case \"\$N\" in $p|$p:*) echo \$P;; esac; done)\n"

    /**
     * One line per app: @@OKS (suspended), @@OKD (disabled), @@OKP (paused, root) or @@FAIL with
     * the reason. When suspending isn't available for an app, it is disabled instead.
     */
    fun freezeScript(pkgs: List<String>, method: String): String {
        val sb = StringBuilder()
        for (p in pkgs) {
            if (!validPackage(p)) continue
            when (method) {
                "pause" -> {
                    sb.append(pidsLine(p))
                    sb.append(
                        "if [ -n \"\$PIDS\" ]; then kill -STOP \$PIDS && echo \"@@OKP $p\"; " +
                            "else echo \"@@FAIL $p not running right now\"; fi\n"
                    )
                }
                "disable" -> {
                    sb.append("R=\$(pm disable-user --user 0 $p 2>&1)\n")
                    sb.append(
                        "case \"\$R\" in *disabled*) echo \"@@OKD $p\";; " +
                            "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n"
                    )
                }
                else -> {
                    sb.append("R=\$(pm suspend --user 0 $p 2>&1)\n")
                    sb.append("case \"\$R\" in *\"suspended state: true\"*) echo \"@@OKS $p\";; *)\n")
                    sb.append("R2=\$(pm disable-user --user 0 $p 2>&1)\n")
                    sb.append(
                        "case \"\$R2\" in *disabled*) echo \"@@OKD $p\";; " +
                            "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac;; esac\n"
                    )
                }
            }
        }
        return sb.toString()
    }

    /** Undoes each freeze the way it was done. One @@OK or @@FAIL line per app. */
    fun restoreScript(frozen: Map<String, String>): String {
        val sb = StringBuilder()
        for ((p, method) in frozen) {
            if (!validPackage(p)) continue
            when (method) {
                "pause" -> {
                    sb.append(pidsLine(p))
                    sb.append("if [ -n \"\$PIDS\" ]; then kill -CONT \$PIDS; fi\n")
                    sb.append("echo \"@@OK $p\"\n")
                }
                "disable" -> {
                    sb.append("R=\$(pm enable --user 0 $p 2>&1)\n")
                    sb.append(
                        "case \"\$R\" in *enabled*) echo \"@@OK $p\";; " +
                            "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n"
                    )
                }
                else -> {
                    sb.append("R=\$(pm unsuspend --user 0 $p 2>&1)\n")
                    sb.append(
                        "case \"\$R\" in *\"suspended state: false\"*) echo \"@@OK $p\";; " +
                            "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n"
                    )
                }
            }
        }
        return sb.toString()
    }

    fun parse(output: String): FreezeResult {
        val ok = LinkedHashMap<String, String>()
        val failed = LinkedHashMap<String, String>()
        for (line in output.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("@@OKS ") -> ok[l.substring(6).trim()] = "suspend"
                l.startsWith("@@OKD ") -> ok[l.substring(6).trim()] = "disable"
                l.startsWith("@@OKP ") -> ok[l.substring(6).trim()] = "pause"
                l.startsWith("@@OK ") -> ok[l.substring(5).trim()] = ""
                l.startsWith("@@FAIL ") -> {
                    val rest = l.substring(7).trim()
                    val sp = rest.indexOf(' ')
                    if (sp > 0) failed[rest.substring(0, sp)] = rest.substring(sp + 1) else failed[rest] = ""
                }
            }
        }
        return FreezeResult(ok, failed)
    }

    // ---- Roblox cache ----
    // Each step is its own script so the app can measure the cache between steps.

    /**
     * Asks Android to clear just this app's cache. It has no time limit of its own and on some
     * phones never answers or refuses, so it runs under `timeout` and writes to a file rather
     * than a pipe. Prints "Success" when Android confirms.
     */
    fun cmdCacheScript(pkg: String): String =
        "exec </dev/null\n" +
            "OUT=/data/local/tmp/stargazer_cache.out\n" +
            "timeout 8 cmd package clear --cache-only $pkg >\$OUT 2>&1\n" +
            "cat \$OUT 2>/dev/null\n" +
            "rm -f \$OUT\n"

    /**
     * The global trim: asks Android to free storage by deleting cached files of every app until
     * a huge amount is free, which in practice empties all app caches, Roblox's included. Android
     * allows this from Shizuku even when it refuses to clear a single app's cache.
     * Prints "@@RC 0" when the command finished normally.
     */
    fun trimScript(): String =
        "exec </dev/null\n" +
            "timeout 25 cmd package trim-caches 1000G >/dev/null 2>&1\n" +
            "echo \"@@RC \$?\"\n"

    /** Deletes Roblox's cache on shared storage in the background (output detached so nothing waits on it). */
    fun sharedCacheScript(pkg: String): String =
        "(rm -rf /sdcard/Android/data/$pkg/cache/* >/dev/null 2>&1 </dev/null &)\n"

    /** Root: deletes Roblox's cache folders directly. */
    fun rootCacheScript(pkg: String): String =
        "am force-stop $pkg\n" +
            "rm -rf /data/user/0/$pkg/cache/* /data/user/0/$pkg/code_cache/* /sdcard/Android/data/$pkg/cache/* 2>/dev/null\n" +
            "echo done\n"

    // ---- Root-only system tools ----

    /** Stops (or continues) every running background compiler process. */
    fun compilersScript(stop: Boolean): String {
        val sig = if (stop) "STOP" else "CONT"
        val verb = if (stop) "Paused" else "Resumed"
        return "N=0\n" +
            "for P in \$(pidof dex2oat dex2oat32 dex2oat64 dex2oatd 2>/dev/null); do kill -$sig \$P && N=\$((N+1)); done\n" +
            "if [ \$N -eq 0 ]; then echo 'No background compiler is running right now.'; " +
            "else echo \"$verb \$N compiler process(es).\"; fi\n"
    }

    /**
     * Turns swap (virtual RAM) off, but only when there is enough free RAM to take what is in it.
     * Prints one @@SWAP line per device turned off so it can be turned back on.
     */
    fun swapOffScript(): String =
        "AV=\$(grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2)\n" +
            "ST=\$(grep SwapTotal /proc/meminfo | tr -s ' ' | cut -d' ' -f2)\n" +
            "SF=\$(grep SwapFree /proc/meminfo | tr -s ' ' | cut -d' ' -f2)\n" +
            "if [ -z \"\$ST\" ] || [ \"\$ST\" = \"0\" ]; then echo 'No virtual RAM (swap) is active.'\n" +
            "else\n" +
            "SU=\$((ST-SF))\n" +
            "if [ \"\$SU\" -gt \$((AV*70/100)) ]; then echo \"Not turned off: \$((SU/1024)) MB of virtual RAM is in use and only \$((AV/1024)) MB of real RAM is free, so the phone could freeze.\"\n" +
            "else\n" +
            "for D in \$(tail -n +2 /proc/swaps | while read F T S U P; do echo \"\$F\"; done); do swapoff \"\$D\" && echo \"@@SWAP \$D\"; done\n" +
            "echo 'Virtual RAM is off until you turn it back on or reboot.'\n" +
            "fi\n" +
            "fi\n"

    fun validSwapDevice(d: String): Boolean = devRegex.matches(d)

    fun swapOnScript(devices: List<String>): String {
        val sb = StringBuilder()
        for (d in devices) {
            if (!validSwapDevice(d)) continue
            sb.append("swapon \"$d\" && echo \"Turned on $d\"\n")
        }
        sb.append("echo 'Virtual RAM is back on.'\n")
        return sb.toString()
    }
}
