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

    fun validPackage(p: String): Boolean = pkgRegex.matches(p)

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
        } catch (e: Exception) {}
        try {
            Settings.Secure.getString(ctx.contentResolver, "default_input_method")
                ?.substringBefore('/')?.let { s.add(it) }
        } catch (e: Exception) {}
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let { s.add(it) }
        } catch (e: Exception) {}
        return s
    }

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

    fun freezeScript(pkgs: List<String>, method: String): String {
        val sb = StringBuilder()
        for (p in pkgs) {
            if (!validPackage(p)) continue
            if (method == "disable") {
                sb.append("R=\$(pm disable-user --user 0 $p 2>&1)\n")
                sb.append("case \"\$R\" in *disabled*) echo \"@@OKD $p\";; *) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n")
            } else {
                sb.append("R=\$(pm suspend --user 0 $p 2>&1)\n")
                sb.append("case \"\$R\" in *\"suspended state: true\"*) echo \"@@OKS $p\";; *)\n")
                sb.append("R2=\$(pm disable-user --user 0 $p 2>&1)\n")
                sb.append("case \"\$R2\" in *disabled*) echo \"@@OKD $p\";; *) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac;; esac\n")
            }
        }
        return sb.toString()
    }

    fun restoreScript(frozen: Map<String, String>): String {
        val sb = StringBuilder()
        for ((p, method) in frozen) {
            if (!validPackage(p)) continue
            if (method == "disable") {
                sb.append("R=\$(pm enable --user 0 $p 2>&1)\n")
                sb.append("case \"\$R\" in *enabled*) echo \"@@OK $p\";; *) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n")
            } else {
                sb.append("R=\$(pm unsuspend --user 0 $p 2>&1)\n")
                sb.append("case \"\$R\" in *\"suspended state: false\"*) echo \"@@OK $p\";; *) echo \"@@FAIL $p \$(printf %s \"\$R\" | head -n 1)\";; esac\n")
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

    fun cacheScript(pkg: String, preferRoot: Boolean = true): String {
        return """
exec </dev/null
PKG=\"$pkg\"
OUT=/data/local/tmp/stargazer_cache.out
REPORT=/data/local/tmp/stargazer_cache_report.txt
rm -f \"\$OUT\" \"\$REPORT\"
touch \"\$REPORT\"

echo \"=== Stargazer cache clear for \$PKG ===\" | tee -a \"\$REPORT\"

echo \"[1/4] Clearing shared storage caches...\" | tee -a \"\$REPORT\"
EXT_OK=0
for d in \\
  \"/sdcard/Android/data/\$PKG/cache\" \\
  \"/sdcard/Android/data/\$PKG/code_cache\" \\
  \"/storage/emulated/0/Android/data/\$PKG/cache\" \\
  \"/storage/emulated/0/Android/data/\$PKG/code_cache\"
do
  if [ -d \"\$d\" ]; then
    rm -rf \"\$d\"/* \"\$d\"/.[!.]* \"\$d\"/..?* 2>/dev/null
    EXT_OK=1
  fi
done
if [ \"\$EXT_OK\" = 1 ]; then
  echo \"  Shared storage cache cleared.\" | tee -a \"\$REPORT\"
else
  echo \"  No shared storage cache directories found (or already empty).\" | tee -a \"\$REPORT\"
fi

echo \"[2/4] Trying cmd package clear --cache-only...\" | tee -a \"\$REPORT\"
timeout 12 cmd package clear --cache-only \"\$PKG\" >\"\$OUT\" 2>&1
R=\$(cat \"\$OUT\" 2>/dev/null)
rm -f \"\$OUT\"
case \"\$R\" in
  *Success*|*success*)
    echo \"  Official cache clear succeeded.\" | tee -a \"\$REPORT\"
    OFFICIAL_OK=1
    ;;
  *)
    echo \"  Official clear blocked or timed out (common on many ROMs).\" | tee -a \"\$REPORT\"
    OFFICIAL_OK=0
    ;;
esac

echo \"[3/4] Forcing global cache trim (pm trim-caches 999G)...\" | tee -a \"\$REPORT\"
timeout 25 pm trim-caches 999G >\"\$OUT\" 2>&1
rm -f \"\$OUT\"
echo \"  Global trim finished.\" | tee -a \"\$REPORT\"

echo \"[4/4] Checking for root (KernelSU / Magisk / su)...\" | tee -a \"\$REPORT\"
ROOT_OK=0
SU_CMD=\"\"
for c in /system/bin/su /system/xbin/su /sbin/su /data/adb/ksu/bin/ksud /data/adb/magisk/magisk /data/adb/ksu/bin/su; do
  if [ -x \"\$c\" ]; then SU_CMD=\"\$c\"; break; fi
done
if [ -z \"\$SU_CMD\" ] && command -v su >/dev/null 2>&1; then SU_CMD=\"su\"; fi

if [ -n \"\$SU_CMD\" ]; then
  if timeout 4 \"\$SU_CMD\" -c \"id\" 2>/dev/null | grep -q \"uid=0\"; then
    echo \"  Root available via \$SU_CMD — clearing private cache dirs...\" | tee -a \"\$REPORT\"
    timeout 15 \"\$SU_CMD\" -c \"
      rm -rf /data/data/\$PKG/cache/* /data/data/\$PKG/cache/.[!.]* /data/data/\$PKG/cache/..?* 2>/dev/null
      rm -rf /data/data/\$PKG/code_cache/* /data/data/\$PKG/code_cache/.[!.]* /data/data/\$PKG/code_cache/..?* 2>/dev/null
      rm -rf /data/user/0/\$PKG/cache/* /data/user/0/\$PKG/code_cache/* 2>/dev/null
      echo done
    \" >/dev/null 2>&1
    ROOT_OK=1
    echo \"  Private /data/data cache cleared with root.\" | tee -a \"\$REPORT\"
  else
    echo \"  su binary found but not usable (no root grant or temp root inactive).\" | tee -a \"\$REPORT\"
  fi
else
  echo \"  No root binary found — private cache left to the system trim.\" | tee -a \"\$REPORT\"
fi

echo \"\" | tee -a \"\$REPORT\"
echo \"=== Summary ===\" | tee -a \"\$REPORT\"
if [ \"\$OFFICIAL_OK\" = 1 ]; then
  echo \"• Official single-app clear: OK\" | tee -a \"\$REPORT\"
else
  echo \"• Official single-app clear: blocked (normal on many phones)\" | tee -a \"\$REPORT\"
fi
echo \"• Shared storage cache: cleared\" | tee -a \"\$REPORT\"
echo \"• Global system trim: completed\" | tee -a \"\$REPORT\"
if [ \"\$ROOT_OK\" = 1 ]; then
  echo \"• Private data cache (root): cleared\" | tee -a \"\$REPORT\"
else
  echo \"• Private data cache: not cleared (no usable root)\" | tee -a \"\$REPORT\"
fi
echo \"\" | tee -a \"\$REPORT\"
echo \"Roblox cache clear finished. Restart Roblox for the cleanest result.\" | tee -a \"\$REPORT\"
cat \"\$REPORT\"
rm -f \"\$REPORT\"
\"\"".trimIndent()
    }

    fun rootProbeScript(): String = \"\"\"
exec </dev/null
SU_CMD=\"\"
for c in /system/bin/su /system/xbin/su /sbin/su /data/adb/ksu/bin/ksud /data/adb/magisk/magisk /data/adb/ksu/bin/su; do
  if [ -x \"\$c\" ]; then SU_CMD=\"\$c\"; break; fi
done
if [ -z \"\$SU_CMD\" ] && command -v su >/dev/null 2>&1; then SU_CMD=\"su\"; fi
if [ -z \"\$SU_CMD\" ]; then
  echo \"none\"
  exit 0
fi
if timeout 3 \"\$SU_CMD\" -c \"id\" 2>/dev/null | grep -q \"uid=0\"; then
  if [ -d /data/adb/ksu ] || [ -x /data/adb/ksu/bin/ksud ]; then
    echo \"kernelsu\"
  elif [ -d /data/adb/magisk ] || [ -f /data/adb/magisk.db ]; then
    echo \"magisk\"
  else
    echo \"su\"
  fi
else
  echo \"present-but-inactive\"
fi
\"\"\".trimIndent()
}
