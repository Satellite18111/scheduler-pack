package dev.local.netspeed;

import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一次性采集静态设备信息（CPU型号/架构/核心/GPU型号）。 */
public class InfoCollector {

    public static class Info {
        public String soc = "-";
        public String cpuArch = "-";
        public String abi = "-";
        public String cores = "-";
        public String threads = "-";
        public String cpuModel = "-";
        public String gpuName = "-";
        public String gpuCores = "-";
        public String androidVer = "-";
    }

    private static final Map<String, String> PART = new LinkedHashMap<>();
    static {
        PART.put("d03", "Cortex-A53");
        PART.put("d09", "Cortex-A73");
        PART.put("d05", "Cortex-A55");
        PART.put("d0a", "Cortex-A75");
        PART.put("d0b", "Cortex-A76");
        PART.put("d0c", "Cortex-A77");
        PART.put("d41", "Cortex-A510");
        PART.put("d4a", "Cortex-A710");
        PART.put("d4b", "Cortex-A715");
        PART.put("d02", "Cortex-A57");
        PART.put("d04", "Cortex-A72");
    }

    public static Info collect() {
        Info i = new Info();
        i.androidVer = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        i.abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "-";
        int n = Runtime.getRuntime().availableProcessors();
        i.cores = n + " 核";
        i.threads = n + " 线程（ARM 无超线程）";

        String cpuinfo = su("cat /proc/cpuinfo");
        String gpuinfo = su("cat /sys/devices/platform/13040000.mali/gpuinfo");

        // CPU part 分组
        Map<String, Integer> parts = new LinkedHashMap<>();
        Matcher m = Pattern.compile("CPU part\\s*:\\s*0x([0-9a-f]+)").matcher(cpuinfo);
        while (m.find()) {
            String p = m.group(1);
            parts.merge(p, 1, Integer::sum);
        }
        StringBuilder model = new StringBuilder();
        for (Map.Entry<String, Integer> e : parts.entrySet()) {
            if (model.length() > 0) model.append(" + ");
            String name = PART.getOrDefault(e.getKey(), "0x" + e.getKey());
            model.append(e.getValue()).append("×").append(name);
        }
        if (model.length() > 0) i.cpuModel = model.toString();

        Matcher ma = Pattern.compile("CPU architecture:\\s*(\\d+)").matcher(cpuinfo);
        if (ma.find()) i.cpuArch = "ARMv" + ma.group(1) + "-A";

        Matcher mh = Pattern.compile("Hardware\\s*:\\s*(.+)").matcher(cpuinfo);
        if (mh.find()) i.soc = mh.group(1).trim();

        // GPU: Mali-G72 3 cores r0p3 0x6221
        Matcher mg = Pattern.compile("(Mali-\\S+)\\s+(\\d+)\\s+cores").matcher(gpuinfo);
        if (mg.find()) {
            i.gpuName = mg.group(1);
            i.gpuCores = mg.group(2) + " 核心";
        } else if (!gpuinfo.isEmpty()) {
            i.gpuName = gpuinfo.trim();
        }
        return i;
    }

    private static String su(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
