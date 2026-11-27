package dev.local.netspeed;

public class ModeUtil {
    public static final String[] MODES = {"auto", "balance", "powersave", "performance", "fast"};
    public static final String[] LABELS = {"自动", "均衡", "省电", "性能", "极速"};

    public static String label(String mode) {
        int i = indexOf(mode);
        return i < 0 ? (mode == null ? "-" : mode) : LABELS[i];
    }

    public static int indexOf(String mode) {
        if (mode == null) return -1;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return -1;
    }
}
