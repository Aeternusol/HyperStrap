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
 * Shell scripts and helpers for freezing apps and clearing Roblox's cache through Shizuku.
 *
 * "Freezing" here means the app is stopped and can't start again until it is restored, either
 * by suspending it (`pm suspend`, the icon stays but is greyed out) or by disabling it
 * (`pm disable-user`). A stopped app uses no CPU and gives back the memory it was using; its
 * data is kept. It is not the same as pausing a running process in place.
 */
object ResourceTools {
    private val pkgRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

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
            "com.google.android.inputmethod.latin"
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

    /**
     * One line per app: @@OKS (suspended), @@OKD (disabled) or @@FAIL with the reason. When
     * suspending isn't available for an app, it is disabled instead.
     */
    fun freezeScript(pkgs: List<String>, method: String): String {
        val sb = StringBuilder()
        for (p in pkgs) {
            if (!validPackage(p)) continue
            if (method == "disable") {
                sb.append("R=\$(pm disable-user --user 0 $p 2>&1)\n")
                sb.append(
                    "case \"\$R\" in *disabled*) echo \"@@OKD $p\";; " +
                        "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n"
                )
            } else {
                sb.append("R=\$(pm suspend --user 0 $p 2>&1)\n")
                sb.append("case \"\$R\" in *\"suspended state: true\"*) echo \"@@OKS $p\";; *)\n")
                sb.append("R2=\$(pm disable-user --user 0 $p 2>&1)\n")
                sb.append(
                    "case \"\$R2\" in *disabled*) echo \"@@OKD $p\";; " +
                        "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac;; esac\n"
                )
            }
        }
        return sb.toString()
    }

    /** Undoes each freeze the way it was done. One @@OK or @@FAIL line per app. */
    fun restoreScript(frozen: Map<String, String>): String {
        val sb = StringBuilder()
        for ((p, method) in frozen) {
            if (!validPackage(p)) continue
            if (method == "disable") {
                sb.append("R=\$(pm enable --user 0 $p 2>&1)\n")
                sb.append(
                    "case \"\$R\" in *enabled*) echo \"@@OK $p\";; " +
                        "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n"
                )
            } else {
                sb.append("R=\$(pm unsuspend --user 0 $p 2>&1)\n")
                sb.append(
                    "case \"\$R\" in *\"suspended state: false\"*) echo \"@@OK $p\";; " +
                        "*) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n"
                )
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

    /**
     * Clears only Roblox's cache, never its data or login.
     *
     * First it asks Android to clear just the cache (`cmd package clear --cache-only`). That call
     * has no time limit of its own and on some phones never answers, so it runs under `timeout`
     * and writes to a file rather than a pipe (a pipe could be held open by a stuck process).
     * If Android doesn't confirm, the cache on shared storage is emptied instead. That can be
     * thousands of files, so it runs in the background with its output detached, and this script
     * returns straight away instead of waiting for it.
     */
    fun cacheScript(pkg: String): String =
        "exec </dev/null\n" +
            "OUT=/data/local/tmp/stargazer_cache.out\n" +
            "timeout 10 cmd package clear --cache-only $pkg >\$OUT 2>&1\n" +
            "R=\$(cat \$OUT 2>/dev/null)\n" +
            "rm -f \$OUT\n" +
            "case \"\$R\" in\n" +
            "*Success*) echo 'Roblox cache cleared.';;\n" +
            "*) (rm -rf /sdcard/Android/data/$pkg/cache/* >/dev/null 2>&1 </dev/null &)\n" +
            "echo 'Android did not confirm a full clear, so Roblox cache files on shared storage are being deleted in the background.';;\n" +
            "esac\n"
}
