package dev.local.netspeed;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;

/** uperf 分应用规则文件读写（/sdcard/Android/yc/uperf/perapp_powermode.txt）。 */
public class PerAppRules {
    public static final String FILE = "/sdcard/Android/yc/uperf/perapp_powermode.txt";

    public String defaultMode = "balance";
    public String offscreenMode = "powersave";
    public final Map<String, String> rules = new HashMap<>();

    public static PerAppRules load() {
        PerAppRules r = new PerAppRules();
        String text = suCat();
        if (text == null) return r;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p.length < 2) continue;
            if (p[0].equals("-")) r.offscreenMode = p[1];
            else if (p[0].equals("*")) r.defaultMode = p[1];
            else r.rules.put(p[0], p[1]);
        }
        return r;
    }

    public String resolve(String pkg) {
        String m = rules.get(pkg);
        return m != null ? m : defaultMode;
    }

    public String explicit(String pkg) {
        return rules.get(pkg);
    }

    public void setRule(String pkg, String mode) {
        if (mode == null || mode.equals(defaultMode)) rules.remove(pkg);
        else rules.put(pkg, mode);
    }

    public void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 分应用性能模式配置\n");
        sb.append("# Per-app dynamic power mode rule\n");
        sb.append("# '-' offscreen rule, '*' default rule\n");
        for (Map.Entry<String, String> e : rules.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        sb.append("- ").append(offscreenMode).append('\n');
        sb.append("* ").append(defaultMode).append('\n');
        suWrite(sb.toString());
    }

    private static String suCat() {
        try {
            Process p = new ProcessBuilder("su", "-c", "cat " + FILE).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void suWrite(String text) {
        try {
            Process p = new ProcessBuilder("su", "-c", "cat > " + FILE).start();
            OutputStreamWriter w = new OutputStreamWriter(p.getOutputStream());
            w.write(text);
            w.flush();
            w.close();
            p.waitFor();
        } catch (Exception ignored) {
        }
    }
}
