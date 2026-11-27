package dev.local.netspeed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MonitorService extends Service {
    private WindowManager wm;
    private TextView view;
    private LinearLayout panel;
    private TextView panelPkg;
    private LinearLayout panelHead;
    private View.OnTouchListener panelDrag;
    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler worker;
    private Process su;
    private BufferedWriter suIn;
    private BufferedReader suOut;
    private SharedPreferences sp;

    private long lastRx = -1, lastTx = -1;
    private static final int NOTIF_ID = 1001;

    private boolean panelOpen = false;
    private String currentPkg = "";
    private PerAppRules rules;
    private final java.util.List<View[]> panelModeRows = new java.util.ArrayList<>();
    private final java.util.List<ToggleSwitch> panelToggles = new java.util.ArrayList<>();

    private int dragX, dragY;
    private float downRawX, downRawY;
    private boolean dragged;

    private static final Pattern PKG = Pattern.compile("u0\\s+([^/\\s]+)/");
    private static final String MODE_FILE = "/sdcard/Android/yc/uperf/cur_powermode.txt";

    private boolean protectedByTemp = false;
    private boolean chargeAlerted = false;
    private boolean chargerConnected = false;
    private BroadcastReceiver chargerReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());
        sp = getSharedPreferences("ns", MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        buildOverlay();
        buildPanel();

        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("ns-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        startSu();
        new Thread(this::loadRules).start();
        registerChargerReceiver();
        worker.postDelayed(tick, 1000);
    }

    private void registerChargerReceiver() {
        chargerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                    chargerConnected = true;
                    if (sp.getBoolean("autoCharge", false))
                        applyMode(sp.getString("chargeMode", "balance"));
                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                    chargerConnected = false;
                    if (sp.getBoolean("autoCharge", false))
                        applyMode("balance");
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(chargerReceiver, f);
    }

    private void applyMode(String m) {
        if (m == null || m.isEmpty()) return;
        try {
            suIn.write("echo " + m + " > " + MODE_FILE + "\n");
            suIn.flush();
            Log.d("NetSpeed", "auto mode -> " + m);
        } catch (Exception e) {
            Log.w("NetSpeed", "auto mode fail: " + e);
            restartSu();
        }
    }

    private void notifyAlert(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel("ns-alert", "调度包提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(c);
        nm.notify(1002, new Notification.Builder(this, "ns-alert")
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true).build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if ("dev.local.netspeed.SHOW_PANEL".equals(a)) {
                mainHandler.post(() -> setPanel(true));
            } else if ("dev.local.netspeed.MODE".equals(a)) {
                applyMode(intent.getStringExtra("mode"));
            }
        }
        return START_STICKY;
    }

    private void buildOverlay() {
        view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(10f);
        view.setPadding(24, 14, 24, 14);
        view.setBackgroundColor(0x80000000);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("floatX", 12);
        lp.y = sp.getInt("floatY", 120);
        view.setOnTouchListener(overlayTouch);
        wm.addView(view, lp);
    }

    private final View.OnTouchListener overlayTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    dragX = lp.x;
                    dragY = lp.y;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(e.getRawX() - downRawX);
                    int dy = Math.round(e.getRawY() - downRawY);
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) dragged = true;
                    lp.x = dragX + dx;
                    lp.y = dragY + dy;
                    wm.updateViewLayout(view, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) togglePanel();
                    else sp.edit().putInt("floatX", lp.x).putInt("floatY", lp.y).apply();
                    return true;
            }
            return false;
        }
    };

    /* ---------- 面板样式工具 ---------- */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundBg(int fill, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private GradientDrawable circleBg(int fill, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    /** 自绘开关：圆角轨道 + 白色滑块，和网页端一致。 */
    private class ToggleSwitch extends FrameLayout {
        final String key;
        final View track, knob;
        boolean on;

        ToggleSwitch(String k) {
            super(MonitorService.this);
            key = k;
            setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(22)));
            track = new View(MonitorService.this);
            addView(track, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            knob = new View(MonitorService.this);
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(16), dp(16));
            kp.leftMargin = dp(3);
            kp.topMargin = dp(3);
            addView(knob, kp);
            knob.setBackground(circleBg(0xFFF2F6FB, 8, 0, 0));
            setOnState(sp.getBoolean(key, key.equals("showSpeed")));
            setClickable(false);
        }

        void toggle() {
            setOnState(!on);
            sp.edit().putBoolean(key, on).apply();
        }

        void setOnState(boolean v) {
            on = v;
            track.setBackground(roundBg(v ? 0xFF3E70B4 : 0xFF22303F, 11, 0, 0));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) knob.getLayoutParams();
            lp.leftMargin = dp(v ? 21 : 3);
            knob.setLayoutParams(lp);
        }
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setMinimumWidth(dp(320));
        panel.setBackground(roundBg(0xFF121A24, 20, 0xFF26344A, 1));
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        panelHead = head;
        panelDrag = new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams p =
                        (WindowManager.LayoutParams) panel.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = p.x;
                        startY = p.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - downX);
                        int dy = Math.round(e.getRawY() - downY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        if (moved) {
                            p.x = startX + dx;
                            p.y = startY + dy;
                            wm.updateViewLayout(panel, p);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            sp.edit().putInt("panelX", p.x).putInt("panelY", p.y).apply();
                            Log.d("NetSpeed", "panel moved to " + p.x + "," + p.y);
                        }
                        return true;
                }
                return false;
            }
        };
        head.setOnTouchListener(panelDrag);
        panel.setOnTouchListener(panelDrag);   /* 空白处（标题、分组文字、内边距）也能拖 */
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("调度面板");
        title.setTextColor(0xFFE9F1FA);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView xBtn = new TextView(this);
        xBtn.setText("✕");
        xBtn.setTextSize(16f);
        xBtn.setTextColor(0xFF8296AC);
        xBtn.setPadding(dp(10), dp(2), dp(6), dp(8));
        xBtn.setOnClickListener(v -> setPanel(false));
        head.addView(xBtn);
        panel.addView(head);

        panelPkg = new TextView(this);
        panelPkg.setTextColor(0xFF7E8FA3);
        panelPkg.setTextSize(11f);
        panelPkg.setPadding(dp(2), 0, 0, dp(12));
        panel.addView(panelPkg);

        TextView secModes = new TextView(this);
        secModes.setText("本应用档位");
        secModes.setTextColor(0xFF8296AC);
        secModes.setTextSize(11f);
        secModes.setPadding(dp(2), 0, 0, dp(4));
        panel.addView(secModes);

        for (int i = 0; i < ModeUtil.MODES.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            row.setBackground(roundBg(0x00000000, 12, 0, 0));

            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(14), dp(14));
            dlp.rightMargin = dp(10);
            dot.setLayoutParams(dlp);
            dot.setBackground(circleBg(0x00000000, 7, 0xFF314052, 1));
            row.addView(dot);

            TextView nm = new TextView(this);
            nm.setText(ModeUtil.LABELS[i]);
            nm.setTextColor(0xFFD7E3F2);
            nm.setTextSize(14f);
            row.addView(nm, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView en = new TextView(this);
            en.setText(ModeUtil.MODES[i]);
            en.setTextColor(0xFF6E8199);
            en.setTextSize(10f);
            row.addView(en);

            row.setOnClickListener(v -> {
                if (currentPkg.isEmpty() || rules == null) return;
                rules.setRule(currentPkg, ModeUtil.MODES[idx]);
                rules.save();
                applyPanelMode(ModeUtil.MODES[idx]);
                Log.d("NetSpeed", "panel rule " + currentPkg + " -> " + ModeUtil.MODES[idx]);
            });
            panelModeRows.add(new View[]{row, dot, nm, en});
            panel.addView(row);
        }

        String[][] toggles = {
                {"showSpeed", "网速"},
                {"showFps", "帧率"},
                {"showCpu", "CPU 频率"},
                {"showGpu", "GPU 频率"},
                {"showCpuTemp", "CPU 温度"},
                {"showBattTemp", "电池温度"},
        };
        for (int i = 0; i < toggles.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < toggles.length; j++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(j > i ? dp(14) : 0, dp(4), dp(6), dp(4));
                TextView lb = new TextView(this);
                lb.setText(toggles[j][1]);
                lb.setTextColor(0xFFC6D4E4);
                lb.setTextSize(12.5f);
                cell.addView(lb, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                ToggleSwitch sw = new ToggleSwitch(toggles[j][0]);
                cell.addView(sw);
                cell.setOnClickListener(v -> sw.toggle());
                row.addView(cell, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                panelToggles.add(sw);
            }
            panel.addView(row);
        }

        View line = new View(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(12);
        llp.bottomMargin = dp(10);
        line.setLayoutParams(llp);
        line.setBackgroundColor(0xFF1E2833);
        panel.addView(line);

        TextView close = new TextView(this);
        close.setText("关闭面板");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(0xFFB9CDE2);
        close.setTextSize(13f);
        close.setPadding(0, dp(11), 0, dp(11));
        close.setBackground(roundBg(0xFF1A2432, 12, 0xFF27354A, 1));
        close.setOnClickListener(v -> setPanel(false));
        panel.addView(close);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(336),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("panelX", dp(14));
        lp.y = sp.getInt("panelY", dp(44));
        panel.setVisibility(View.GONE);
        wm.addView(panel, lp);
    }

    private void togglePanel() {
        setPanel(!panelOpen);
    }

    private void setPanel(boolean open) {
        panelOpen = open;
        if (open) {
            syncPanelSwitches();
            worker.post(this::loadRules);
            refreshPanelMode();
            panel.setVisibility(View.VISIBLE);
        } else {
            panel.setVisibility(View.GONE);
        }
    }

    /** 面板每次打开时按偏好回填开关，避免与网页端开关状态不一致。 */
    private void syncPanelSwitches() {
        for (ToggleSwitch t : panelToggles) {
            t.setOnState(sp.getBoolean(t.key, t.key.equals("showSpeed")));
        }
    }

    private void loadRules() {
        rules = PerAppRules.load();
        mainHandler.post(this::refreshPanelMode);
    }

    private void refreshPanelMode() {
        if (panel == null) return;
        panelPkg.setText("当前应用 · " + (currentPkg.isEmpty() ? "-" : currentPkg));
        if (rules == null || currentPkg.isEmpty()) {
            applyPanelMode("");
            return;
        }
        applyPanelMode(rules.resolve(currentPkg));
    }

    private void applyPanelMode(String mode) {
        int sel = ModeUtil.indexOf(mode);
        for (int i = 0; i < panelModeRows.size(); i++) {
            View[] r = panelModeRows.get(i);
            boolean on = (i == sel);
            r[0].setBackground(roundBg(on ? 0xFF17283C : 0x00000000, 12,
                    on ? 0xFF2C4A70 : 0, on ? 1 : 0));
            r[1].setBackground(circleBg(on ? 0xFF5B9CFF : 0x00000000, 7,
                    on ? 0xFF5B9CFF : 0xFF314052, 1));
            TextView nm = (TextView) r[2];
            nm.setTextColor(on ? 0xFFD9E8FF : 0xFFD7E3F2);
            nm.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            ((TextView) r[3]).setTextColor(on ? 0xFF7FA9E0 : 0xFF6E8199);
        }
    }

    private Notification buildNotification() {
        String ch = "ns";
        NotificationChannel c = new NotificationChannel(ch, "调度包",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(c);

        Intent show = new Intent(this, MonitorService.class)
                .setAction("dev.local.netspeed.SHOW_PANEL");
        PendingIntent showPi = PendingIntent.getService(this, 0, show,
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, ch)
                .setContentTitle("调度包")
                .setContentText("监控中，点击打开调度面板")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(showPi);

        String[][] acts = {
                {"performance", "性能"},
                {"balance", "均衡"},
                {"powersave", "省电"},
        };
        for (int i = 0; i < acts.length; i++) {
            Intent mi = new Intent(this, MonitorService.class)
                    .setAction("dev.local.netspeed.MODE")
                    .putExtra("mode", acts[i][0]);
            PendingIntent mpi = PendingIntent.getService(this, i + 1, mi,
                    PendingIntent.FLAG_IMMUTABLE);
            b.addAction(android.R.drawable.stat_sys_warning, acts[i][1], mpi);
        }
        return b.build();
    }

    private void startSu() {
        try {
            su = new ProcessBuilder("su").start();
            suIn = new BufferedWriter(new OutputStreamWriter(su.getOutputStream()));
            suOut = new BufferedReader(new InputStreamReader(su.getInputStream()));
            Log.d("NetSpeed", "su shell started");
        } catch (Exception e) {
            Log.w("NetSpeed", "su start fail: " + e);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean sSpeed = sp.getBoolean("showSpeed", true);
            boolean sFps = sp.getBoolean("showFps", false);
            boolean sCpu = sp.getBoolean("showCpu", false);
            boolean sGpu = sp.getBoolean("showGpu", false);
            boolean sCt = sp.getBoolean("showCpuTemp", false);
            boolean sBt = sp.getBoolean("showBattTemp", false);
            boolean needTemp = sp.getBoolean("autoTemp", false);

            Data d = readRoot(sSpeed, sFps, sCpu, sGpu, needTemp || sCt, needTemp || sBt);

            if (d != null) checkThermal(d);
            if (d != null) checkCharge(d);

            final StringBuilder text = new StringBuilder();
            if (d != null) {
                if (sSpeed) {
                    if (lastRx < 0) { lastRx = d.rx; lastTx = d.tx; }
                    else {
                        long dr = Math.max(0, d.rx - lastRx);
                        long dt = Math.max(0, d.tx - lastTx);
                        lastRx = d.rx; lastTx = d.tx;
                        text.append("\u2193").append(fmt(dr)).append("  \u2191").append(fmt(dt)).append("\n");
                    }
                }
                if (sFps && d.fps >= 0) text.append("FPS ").append(String.format("%.1f", d.fps / 100.0)).append("\n");
                if (sCpu && d.cpuKhz > 0) text.append("CPU ").append(d.cpuKhz / 1000).append("MHz\n");
                if (sGpu && d.gpuHz > 0) text.append("GPU ").append(Math.round(d.gpuHz / 1000000.0)).append("MHz\n");
                if (sCt && d.cpuTemp >= 0) text.append("CPU温度 ").append(String.format("%.1f", d.cpuTemp / 1000.0)).append("°C\n");
                if (sBt && d.battTemp >= 0) text.append("电池 ").append(String.format("%.1f", d.battTemp / 10.0)).append("°C\n");
            }

            if (d != null && !d.pkg.equals(currentPkg)) {
                currentPkg = d.pkg;
                mainHandler.post(MonitorService.this::refreshPanelMode);
            }

            final String t = text.toString().trim();
            Log.d("NetSpeed", "[" + t.replace("\n", " | ") + "]");
            mainHandler.post(() -> {
                if (view == null) return;
                if (t.isEmpty()) view.setVisibility(View.GONE);
                else { view.setVisibility(View.VISIBLE); view.setText(t); }
            });
            worker.postDelayed(this, 1000);
        }
    };

    private void checkThermal(Data d) {
        if (!sp.getBoolean("autoTemp", false)) { protectedByTemp = false; return; }
        double cpu = d.cpuTemp > 0 ? d.cpuTemp / 1000.0 : -1;
        double bat = d.battTemp > 0 ? d.battTemp / 10.0 : -1;
        double maxT = Math.max(cpu, bat);
        if (maxT < 0) return;
        int thr = sp.getInt("tempThreshold", 45);
        String tempMode = sp.getString("tempMode", "powersave");
        if (!protectedByTemp && maxT >= thr) {
            protectedByTemp = true;
            applyMode(tempMode);
            notifyAlert("温度保护", String.format("%.1f°C 超阈值 %d°C，已切 %s",
                    maxT, thr, ModeUtil.label(tempMode)));
        } else if (protectedByTemp && maxT <= thr - 3) {
            protectedByTemp = false;
            applyMode(chargerConnected ? sp.getString("chargeMode", "balance") : "balance");
        }
    }

    /** 充电阈值提醒：本机内核没有停充节点，到阈值只提醒、不切断充电。 */
    private void checkCharge(Data d) {
        if (!sp.getBoolean("chargeAlert", false)) { chargeAlerted = false; return; }
        if (d == null || d.cap < 0) return;
        int limit = sp.getInt("chargeLimit", 80);
        if (d.charging && d.cap >= limit) {
            if (!chargeAlerted) {
                chargeAlerted = true;
                notifyAlert("充电提醒", "电量 " + d.cap + "% 已达阈值 " + limit + "%，可以拔掉充电器了");
            }
        } else if (!d.charging || d.cap <= limit - 3) {
            chargeAlerted = false;
        }
    }

    private static class Data {
        long rx = -1, tx = -1, cpuKhz = -1, gpuHz = -1, fps = -1;
        long cpuTemp = -1, battTemp = -1;
        int cap = -1;
        boolean charging = false;
        String pkg = "";
    }

    private Data readRoot(boolean speed, boolean fps, boolean cpu, boolean gpu, boolean ct, boolean bt) {
        if (su == null) return null;
        Data d = new Data();
        StringBuilder cmd = new StringBuilder();
        if (speed) {
            cmd.append("echo R:$(cat /sys/class/net/wlan0/statistics/rx_bytes)\n");
            cmd.append("echo T:$(cat /sys/class/net/wlan0/statistics/tx_bytes)\n");
        }
        if (cpu) {
            cmd.append("echo C:$(for c in 0 1 2 3 4 5 6 7; do cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq; done | sort -n | tail -1)\n");
        }
        if (gpu) {
            cmd.append("echo G:$(cat /sys/kernel/debug/clk/mfg_sel/clk_rate)\n");
        }
        if (fps) {
            cmd.append("echo F:$(dumpsys SurfaceFlinger | sed -n '/FPS ring buffer:/,/^$/p' | grep -m1 -oE 'fps=[0-9.]+')\n");
        }
        if (ct) {
            cmd.append("echo CT:$(for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type); [ \"$t\" = \"mtktscpu\" ] && cat $z/temp; done | head -1)\n");
        }
        if (bt) {
            cmd.append("echo BT:$(cat /sys/class/power_supply/battery/temp)\n");
        }
        cmd.append("echo BS:$(cat /sys/class/power_supply/battery/capacity)/$(cat /sys/class/power_supply/battery/status)\n");
        cmd.append("echo P:$(dumpsys window | grep mFocusedApp | head -1)\n");
        cmd.append("echo __NS_END__\n");
        try {
            suIn.write(cmd.toString());
            suIn.flush();
            String line;
            while ((line = suOut.readLine()) != null) {
                line = line.trim();
                if (line.equals("__NS_END__")) break;
                if (line.startsWith("R:")) d.rx = parseNum(line.substring(2));
                else if (line.startsWith("T:")) d.tx = parseNum(line.substring(2));
                else if (line.startsWith("C:")) d.cpuKhz = parseNum(line.substring(2));
                else if (line.startsWith("G:")) d.gpuHz = parseNum(line.substring(2));
                else if (line.startsWith("CT:")) d.cpuTemp = parseNum(line.substring(3));
                else if (line.startsWith("BT:")) d.battTemp = parseNum(line.substring(3));
                else if (line.startsWith("BS:")) {
                    String v = line.substring(3).trim();
                    int slash = v.indexOf('/');
                    if (slash > 0) {
                        d.cap = (int) parseNum(v.substring(0, slash));
                        String st = v.substring(slash + 1).trim();
                        d.charging = st.contains("Charg") || st.equalsIgnoreCase("Full");
                    }
                }
                else if (line.startsWith("F:")) {
                    String f = line.substring(2).replace("fps=", "");
                    try { d.fps = (long) (Double.parseDouble(f) * 100); } catch (Exception ignored) {}
                } else if (line.startsWith("P:")) {
                    Matcher m = PKG.matcher(line);
                    if (m.find()) d.pkg = m.group(1);
                }
            }
            return d;
        } catch (Exception e) {
            Log.w("NetSpeed", "readRoot fail: " + e);
            restartSu();
            return null;
        }
    }

    private long parseNum(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    private void restartSu() {
        try { if (su != null) su.destroy(); } catch (Exception ignored) {}
        su = null;
        startSu();
    }

    private String fmt(long bps) {
        double b = bps;
        String[] u = {"B/s", "K/s", "M/s", "G/s"};
        int i = 0;
        while (b >= 1024 && i < 3) { b /= 1024; i++; }
        return String.format("%.1f%s", b, u[i]);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (worker != null) worker.removeCallbacks(tick);
        if (workerThread != null) workerThread.quitSafely();
        if (chargerReceiver != null) {
            try { unregisterReceiver(chargerReceiver); } catch (Exception ignored) {}
        }
        if (su != null) su.destroy();
        if (view != null && wm != null) wm.removeView(view);
        if (panel != null && wm != null) wm.removeView(panel);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
