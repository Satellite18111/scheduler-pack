package dev.local.netspeed;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页 UI 与原生能力之间的桥：每秒回传快照、写 uperf 模式、分应用规则、悬浮窗偏好、日志导出。
 */
public class NativeBridge {
    private static final String TAG = "SchedulerPack";
    private static final String MODE_FILE = "/sdcard/Android/yc/uperf/cur_powermode.txt";
    private static final String LOG_DIR = "/sdcard/NetSpeedLogs";
    private static final String[] BOOL_KEYS = {
            "showSpeed", "showFps", "showCpu", "showGpu", "showCpuTemp", "showBattTemp",
            "autoCharge", "autoTemp", "chargeAlert"
    };
    private static final Pattern CPU_LINE = Pattern.compile("cpu\\((\\d+)\\).*=\\s*\\d+/(\\d+)");

    private final Context ctx;
    private final SharedPreferences sp;
    private final WebView web;
    private final HandlerThread thread;
    private final Handler worker;

    private Process su;
    private BufferedWriter suIn;
    private BufferedReader suOut;
    private volatile boolean alive = true;

    public NativeBridge(Context context, WebView webView) {
        ctx = context.getApplicationContext();
        web = webView;
        sp = ctx.getSharedPreferences("ns", Context.MODE_PRIVATE);
        thread = new HandlerThread("sp-bridge");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::startSu);
        worker.post(this::emitInfo);
        worker.postDelayed(tick, 800);
    }

    // ---------------- 每秒快照 ----------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            if (su == null) startSu();
            JSONObject snap = readSnapshot();
            if (snap != null) emit(snap);
            worker.postDelayed(this, 1000);
        }
    };

    private JSONObject readSnapshot() {
        if (su == null || suIn == null) return null;
        int[] cores = new int[8];
        int[] freq = new int[8];
        long memTotal = -1, memAvail = -1;
        long battNow = 0, battAvg = 0, battVolt = 0, battTemp = -1, cpuTemp = -1;
        int gpu = -1, daemon = 0, cap = -1;
        boolean charging = false;
        long pmin0 = -1, pmax0 = -1, pmin4 = -1, pmax4 = -1;
        double fps = -1;
        String mode = "";

        StringBuilder cmd = new StringBuilder();
        cmd.append("echo L:\n");
        cmd.append("cat /sys/devices/system/cpu/rq-stats/cpu_normalized_load\n");
        cmd.append("echo G:$(cat /sys/module/ged/parameters/gpu_loading)\n");
        cmd.append("echo P:$(ps -A -o NAME | grep -c '^uperf$')\n");
        cmd.append("echo M:$(cat ").append(MODE_FILE).append(")\n");
        cmd.append("echo FQ:$(for c in 0 1 2 3 4 5 6 7; do cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq; done | tr '\\n' ' ')\n");
        cmd.append("echo K:\n");
        cmd.append("grep -E 'MemTotal|MemAvailable' /proc/meminfo\n");
        cmd.append("echo BB:$(cat /sys/class/power_supply/battery/current_now)/$(cat /sys/class/power_supply/battery/current_avg)/$(cat /sys/class/power_supply/battery/voltage_now)/$(cat /sys/class/power_supply/battery/temp)\n");
        cmd.append("echo BS:$(cat /sys/class/power_supply/battery/capacity)/$(cat /sys/class/power_supply/battery/status)\n");
        cmd.append("echo PM:$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq) $(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq) $(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq) $(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq)\n");
        cmd.append("echo CT:$(for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type); [ \"$t\" = \"mtktscpu\" ] && cat $z/temp; done | head -1)\n");
        cmd.append("echo F:$(dumpsys SurfaceFlinger | sed -n '/FPS ring buffer:/,/^$/p' | grep -m1 -oE 'fps=[0-9.]+')\n");
        cmd.append("echo __SP_END__\n");

        try {
            suIn.write(cmd.toString());
            suIn.flush();
            String line;
            boolean inLoad = false, inMem = false;
            while ((line = suOut.readLine()) != null) {
                line = line.trim();
                if (line.equals("L:")) { inLoad = true; inMem = false; continue; }
                if (line.equals("K:")) { inLoad = false; inMem = true; continue; }
                if (line.equals("__SP_END__")) break;
                if (line.startsWith("G:")) { inLoad = false; gpu = parseInt(line.substring(2)); continue; }
                if (line.startsWith("P:")) { daemon = parseInt(line.substring(2)); continue; }
                if (line.startsWith("M:")) { mode = line.substring(2).trim(); continue; }
                if (line.startsWith("FQ:")) {
                    inLoad = false;
                    String[] f = line.substring(3).trim().split("\\s+");
                    for (int i = 0; i < f.length && i < 8; i++) freq[i] = parseInt(f[i]);
                    continue;
                }
                if (line.startsWith("BB:")) {
                    inLoad = false;
                    String[] b = line.substring(3).split("/");
                    if (b.length > 0) battNow = parseLong(b[0]);
                    if (b.length > 1) battAvg = parseLong(b[1]);
                    if (b.length > 2) battVolt = parseLong(b[2]);
                    if (b.length > 3) battTemp = parseLong(b[3]);
                    continue;
                }
                if (line.startsWith("BS:")) {
                    inLoad = false;
                    String v = line.substring(3).trim();
                    int slash = v.indexOf('/');
                    if (slash > 0) {
                        cap = parseInt(v.substring(0, slash));
                        String st = v.substring(slash + 1).trim();
                        charging = st.contains("Charg") || st.equalsIgnoreCase("Full");
                    }
                    continue;
                }
                if (line.startsWith("PM:")) {
                    inLoad = false;
                    String[] p = line.substring(3).trim().split("\\s+");
                    if (p.length >= 4) {
                        pmin0 = parseLong(p[0]); pmax0 = parseLong(p[1]);
                        pmin4 = parseLong(p[2]); pmax4 = parseLong(p[3]);
                    }
                    continue;
                }
                if (line.startsWith("CT:")) { cpuTemp = parseLong(line.substring(3)); continue; }
                if (line.startsWith("F:")) {
                    inLoad = false;
                    String f = line.substring(2).replace("fps=", "");
                    try { fps = Double.parseDouble(f.trim()); } catch (Exception ignored) {}
                    continue;
                }
                if (inLoad) {
                    Matcher m = CPU_LINE.matcher(line);
                    if (m.find()) {
                        int idx = Integer.parseInt(m.group(1));
                        if (idx >= 0 && idx < cores.length) cores[idx] = parseInt(m.group(2));
                    }
                } else if (inMem) {
                    if (line.startsWith("MemTotal:")) memTotal = parseLong(line.split("\\s+")[1]);
                    else if (line.startsWith("MemAvailable:")) memAvail = parseLong(line.split("\\s+")[1]);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "snapshot fail: " + e);
            restartSu();
            return null;
        }

        int sum = 0;
        for (int c : cores) sum += c;
        int avg = Math.round(sum / (float) cores.length);

        try {
            JSONObject o = new JSONObject();
            o.put("type", "snap");
            o.put("mode", mode);
            o.put("daemon", daemon > 0);
            o.put("load", avg);
            o.put("gpu", gpu);
            o.put("fps", fps);
            o.put("cores", arr(cores));
            o.put("freqs", arr(freq));
            o.put("memTotal", memTotal);
            o.put("memAvail", memAvail);
            o.put("battMa", battNow / 1000.0);
            o.put("battAvgMa", battAvg / 1000.0);
            o.put("battV", battVolt / 1000000.0);
            o.put("battTemp", battTemp > 0 ? battTemp / 10.0 : -1);
            o.put("cpuTemp", cpuTemp > 0 ? cpuTemp / 1000.0 : -1);
            o.put("cap", cap);
            o.put("charging", charging);
            o.put("pmin0", pmin0);
            o.put("pmax0", pmax0);
            o.put("pmin4", pmin4);
            o.put("pmax4", pmax4);
            o.put("overlay", Settings.canDrawOverlays(ctx));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONArray arr(int[] v) {
        JSONArray a = new JSONArray();
        for (int x : v) a.put(x);
        return a;
    }

    // ---------------- 设备信息 ----------------

    private void emitInfo() {
        InfoCollector.Info i = InfoCollector.collect();
        try {
            JSONObject o = new JSONObject();
            o.put("type", "info");
            o.put("soc", i.soc);
            o.put("cpuModel", i.cpuModel);
            o.put("cpuArch", i.cpuArch);
            o.put("abi", i.abi);
            o.put("cores", i.cores);
            o.put("threads", i.threads);
            o.put("gpuName", i.gpuName);
            o.put("gpuCores", i.gpuCores);
            o.put("androidVer", i.androidVer);
            o.put("root", su != null);
            emit(o);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void refreshInfo() {
        worker.post(this::emitInfo);
    }

    // ---------------- 偏好 ----------------

    @JavascriptInterface
    public String getPrefs() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            try { o.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
        SharedPreferences.Editor ed = sp.edit();
        boolean isBool = false;
        for (String k : BOOL_KEYS) if (k.equals(key)) isBool = true;
        if (isBool) {
            ed.putBoolean(key, "true".equals(value) || "1".equals(value));
        } else if ("tempThreshold".equals(key)) {
            try { ed.putInt(key, Math.max(30, Math.min(70, Integer.parseInt(value)))); } catch (Exception ignored) {}
        } else if ("chargeLimit".equals(key)) {
            try { ed.putInt(key, Math.max(50, Math.min(100, Integer.parseInt(value)))); } catch (Exception ignored) {}
        } else {
            ed.putString(key, value);
        }
        ed.apply();
    }

    // ---------------- 调度 ----------------

    @JavascriptInterface
    public void setMode(final String m) {
        worker.post(() -> {
            if (!writeMode(m)) {
                emitToast("写模式失败，检查 root 是否授权");
            }
        });
    }

    private boolean writeMode(String m) {
        if (m == null || m.isEmpty()) return false;
        if (suIn == null) { restartSu(); if (suIn == null) return false; }
        try {
            suIn.write("echo " + m + " > " + MODE_FILE + "\n");
            suIn.flush();
            Log.d(TAG, "uperf -> " + m);
            return true;
        } catch (Exception e) {
            restartSu();
            return false;
        }
    }

    // ---------------- 分应用规则 ----------------

    @JavascriptInterface
    public void requestApps() {
        worker.post(this::emitApps);
    }

    private void emitApps() {
        PerAppRules rules = PerAppRules.load();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<JSONObject> list = new ArrayList<>();
            for (ApplicationInfo a : apps) {
                if ((a.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if ((a.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", a.packageName);
                o.put("label", pm.getApplicationLabel(a).toString());
                o.put("mode", rules.resolve(a.packageName));
                String ex = rules.explicit(a.packageName);
                o.put("explicit", ex == null ? "" : ex);
                list.add(o);
            }
            Collections.sort(list, Comparator.comparing(o -> o.optString("label").toLowerCase(Locale.ROOT)));
            JSONObject out = new JSONObject();
            out.put("type", "apps");
            out.put("defaultMode", rules.defaultMode);
            out.put("offscreenMode", rules.offscreenMode);
            out.put("list", new JSONArray(list));
            emit(out);
        } catch (Exception e) {
            Log.w(TAG, "apps fail: " + e);
        }
    }

    @JavascriptInterface
    public void setRule(final String pkg, final String mode) {
        worker.post(() -> {
            PerAppRules r = PerAppRules.load();
            if (mode == null || mode.isEmpty()) r.rules.remove(pkg);
            else r.setRule(pkg, mode);
            r.save();
            emitApps();
        });
    }

    @JavascriptInterface
    public void setDefaultRule(final String mode) {
        worker.post(() -> {
            PerAppRules r = PerAppRules.load();
            r.defaultMode = mode;
            r.rules.entrySet().removeIf(e -> e.getValue().equals(mode));
            r.save();
            emitApps();
        });
    }

    @JavascriptInterface
    public void setOffscreenRule(final String mode) {
        worker.post(() -> {
            PerAppRules r = PerAppRules.load();
            r.offscreenMode = mode;
            r.save();
            emitApps();
        });
    }

    // ---------------- 悬浮窗 / 面板 ----------------

    @JavascriptInterface
    public void showPanel() {
        ctx.startService(new Intent(ctx, MonitorService.class)
                .setAction("dev.local.netspeed.SHOW_PANEL"));
        emitToast("已请求弹出调度面板");
    }

    @JavascriptInterface
    public void openOverlaySettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void startServiceNow() {
        ctx.startService(new Intent(ctx, MonitorService.class));
    }

    // ---------------- 日志导出 ----------------

    @JavascriptInterface
    public void exportDiag() {
        worker.post(() -> {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String file = LOG_DIR + "/diag-" + stamp + ".txt";
            String script =
                    "mkdir -p " + LOG_DIR + "\n" +
                    "echo '=== 设备属性 ===' > " + file + "\n" +
                    "getprop >> " + file + "\n" +
                    "echo '=== 崩溃 crash ===' >> " + file + "\n" +
                    "logcat -b crash -d >> " + file + " 2>&1\n" +
                    "echo '=== ANR ===' >> " + file + "\n" +
                    "ls -l /data/anr/ >> " + file + " 2>&1\n" +
                    "cat /data/anr/*.txt >> " + file + " 2>&1\n" +
                    "echo '=== 系统 system ===' >> " + file + "\n" +
                    "logcat -b system -d >> " + file + " 2>&1\n" +
                    "echo '=== 内核 dmesg ===' >> " + file + "\n" +
                    "dmesg >> " + file + " 2>&1\n";
            int code = runSuScript(script);
            try {
                JSONObject o = new JSONObject();
                o.put("type", "log");
                o.put("ok", code == 0);
                o.put("path", code == 0 ? file : "");
                o.put("text", code == 0 ? "已保存：" + file : "导出失败（退出码 " + code + "）");
                emit(o);
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void exportCsv(final String name, final String csv) {
        worker.post(() -> {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String file = LOG_DIR + "/" + name + "-" + stamp + ".csv";
            int code = runSuScript("mkdir -p " + LOG_DIR);
            if (code == 0) code = writeFileViaSu(file, csv);
            try {
                JSONObject o = new JSONObject();
                o.put("type", "log");
                o.put("ok", code == 0);
                o.put("path", code == 0 ? file : "");
                o.put("text", code == 0 ? "已保存：" + file : "导出失败（退出码 " + code + "）");
                emit(o);
            } catch (Exception ignored) {}
        });
    }

    private int runSuScript(String script) {
        try {
            Process p = new ProcessBuilder("su", "-c", script).start();
            p.getOutputStream().close();
            p.waitFor();
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private int writeFileViaSu(String file, String content) {
        try {
            Process p = new ProcessBuilder("su", "-c", "cat > " + file).start();
            java.io.OutputStream os = p.getOutputStream();
            os.write(content.getBytes());
            os.flush();
            os.close();
            p.waitFor();
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 底层 ----------------

    private void startSu() {
        try {
            su = new ProcessBuilder("su").start();
            suIn = new BufferedWriter(new OutputStreamWriter(su.getOutputStream()));
            suOut = new BufferedReader(new InputStreamReader(su.getInputStream()));
            Log.d(TAG, "su shell started");
        } catch (Exception e) {
            Log.w(TAG, "su fail: " + e);
            su = null;
        }
    }

    private void restartSu() {
        try { if (su != null) su.destroy(); } catch (Exception ignored) {}
        su = null;
        startSu();
    }

    private void emitToast(String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "toast");
            o.put("text", text);
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        final String s = o.toString();
        web.post(() -> {
            try {
                web.evaluateJavascript("window.__ev&&window.__ev(" + JSONObject.quote(s) + ")", null);
            } catch (Exception ignored) {}
        });
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    public void shutdown() {
        alive = false;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        try { if (su != null) su.destroy(); } catch (Exception ignored) {}
    }
}
