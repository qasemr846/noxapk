package com.nox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import hev.htproxy.TProxyService;

/**
 * VPN pipeline:  phone traffic -> tun fd -> hev-socks5-tunnel -> Xray SOCKS (127.0.0.1:10808) -> server.
 * The whole app (and the Xray child process, which shares its UID) is excluded from the VPN,
 * so Xray's own outgoing connections never loop back into the tunnel.
 */
public class NoxVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.nox.app.CONNECT";
    public static final String ACTION_DISCONNECT = "com.nox.app.DISCONNECT";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_NAME = "name";

    public interface Listener { void onState(String json); }

    public static volatile Listener listener;
    public static volatile String lastJson = stateJson("disconnected", "", "", 0, 0, 0, 0, 0, 0, null);

    private static final String CHANNEL = "nox_vpn";
    private static final int NOTIF_ID = 7;

    private final Object lock = new Object();
    private ParcelFileDescriptor tun;
    private Process xray;
    private Thread statsThread;
    private volatile boolean running;
    private volatile boolean stopping;
    private String curUri = "", curName = "";
    private final ArrayDeque<String> logTail = new ArrayDeque<>();

    /* ---------- state publishing ---------- */

    static String stateJson(String state, String name, String uri, long down, long up,
                            long sRx, long sTx, long tRx, long tTx, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("name", name);
            o.put("uri", uri);
            o.put("down", down);
            o.put("up", up);
            o.put("sRx", sRx);
            o.put("sTx", sTx);
            o.put("tRx", tRx);
            o.put("tTx", tTx);
            if (error != null) o.put("error", error);
            return o.toString();
        } catch (Exception e) {
            return "{\"state\":\"disconnected\"}";
        }
    }

    private void publish(String json) {
        lastJson = json;
        Listener l = listener;
        if (l != null) l.onState(json);
    }

    private long[] totals() {
        SharedPreferences p = getSharedPreferences("nox_vpn_stats", MODE_PRIVATE);
        return new long[]{p.getLong("rx", 0), p.getLong("tx", 0)};
    }

    private void saveTotals(long rx, long tx) {
        getSharedPreferences("nox_vpn_stats", MODE_PRIVATE).edit().putLong("rx", rx).putLong("tx", tx).apply();
    }

    /* ---------- lifecycle ---------- */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopAll(null);
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            final String uri = intent.getStringExtra(EXTRA_URI);
            final String name = intent.getStringExtra(EXTRA_NAME);
            startForegroundCompat(name == null ? "" : name);
            new Thread(() -> startAll(uri == null ? "" : uri, name == null ? "" : name), "nox-start").start();
            return START_NOT_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        stopAll(null);
    }

    @Override
    public void onDestroy() {
        stopAll(null);
        super.onDestroy();
    }

    private void startForegroundCompat(String name) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "NOX VPN", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("NOX VPN")
                .setContentText(name)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /* ---------- connect ---------- */

    private void startAll(String uri, String name) {
        synchronized (lock) {
            stopping = false;
            curUri = uri;
            curName = name;
            long[] t = totals();
            publish(stateJson("connecting", name, uri, 0, 0, 0, 0, t[0], t[1], null));
            try {
                String config = XrayConfig.fromUri(uri);
                File dir = getFilesDir();
                write(new File(dir, "xray.json"), config);

                Builder b = new Builder()
                        .setSession("NOX")
                        .setMtu(1500)
                        .addAddress("198.18.0.1", 32)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8");
                try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
                tun = b.establish();
                if (tun == null) throw new Exception("VPN permission was not granted");

                // Xray core (shipped as libxray.so so Android extracts it as an executable)
                String bin = getApplicationInfo().nativeLibraryDir + "/libxray.so";
                if (!new File(bin).exists()) throw new Exception("Xray binary missing in the APK");
                ProcessBuilder pb = new ProcessBuilder(bin, "run", "-c", new File(dir, "xray.json").getAbsolutePath());
                pb.environment().put("XRAY_LOCATION_ASSET", dir.getAbsolutePath());
                pb.redirectErrorStream(true);
                pb.directory(dir);
                xray = pb.start();
                final Process proc = xray;
                synchronized (logTail) { logTail.clear(); }
                new Thread(() -> readLog(proc), "nox-xray-log").start();
                new Thread(() -> {
                    try { proc.waitFor(); } catch (InterruptedException ignored) {}
                    if (running || !stopping) {
                        String why;
                        synchronized (logTail) { why = String.join("\n", logTail); }
                        stopAll(why.isEmpty() ? "Xray stopped unexpectedly" : why);
                    }
                }, "nox-xray-wait").start();

                if (!waitForSocks(proc)) {
                    String why;
                    synchronized (logTail) { why = String.join("\n", logTail); }
                    throw new Exception(why.isEmpty() ? "Xray did not start" : why);
                }

                File yaml = new File(dir, "tunnel.yml");
                write(yaml, "tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n"
                        + "socks5:\n  port: " + XrayConfig.SOCKS_PORT + "\n  address: 127.0.0.1\n  udp: 'udp'\n"
                        + "misc:\n  log-level: warn\n");
                boolean tunnelOk = TProxyService.TProxyStartService(yaml.getAbsolutePath(), tun.getFd());
                if (!tunnelOk) throw new Exception("The tunnel failed to start (hev-socks5-tunnel returned false)");

                running = true;
                startStats(uri, name);
            } catch (Throwable e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                stopAll(msg);
            }
        }
    }

    private boolean waitForSocks(Process proc) {
        for (int i = 0; i < 60; i++) {
            if (!proc.isAlive()) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT), 150);
                return true;
            } catch (Exception e) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { return false; }
            }
        }
        return false;
    }

    private void readLog(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (logTail) {
                    logTail.addLast(line);
                    while (logTail.size() > 12) logTail.removeFirst();
                }
            }
        } catch (Exception ignored) {}
    }

    private void startStats(final String uri, final String name) {
        final long[] base = totals();
        statsThread = new Thread(() -> {
            long prevRx = 0, prevTx = 0;
            while (running) {
                try {
                    long[] s = TProxyService.TProxyGetStats();
                    long tx = s != null && s.length > 1 ? s[1] : 0;
                    long rx = s != null && s.length > 3 ? s[3] : 0;
                    long down = Math.max(0, rx - prevRx), up = Math.max(0, tx - prevTx);
                    prevRx = rx;
                    prevTx = tx;
                    saveTotals(base[0] + rx, base[1] + tx);
                    publish(stateJson("connected", name, uri, down, up, rx, tx, base[0] + rx, base[1] + tx, null));
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    // stats are best effort
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "nox-stats");
        statsThread.start();
    }

    /* ---------- stop ---------- */

    private void stopAll(String error) {
        synchronized (lock) {
            if (stopping) return;
            stopping = true;
            boolean wasRunning = running;
            running = false;
            if (statsThread != null) statsThread.interrupt();
            if (wasRunning) {
                try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
            }
            if (xray != null) { xray.destroy(); xray = null; }
            if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
            long[] t = totals();
            if (error == null) {
                publish(stateJson("disconnected", "", "", 0, 0, 0, 0, t[0], t[1], null));
            } else {
                publish(stateJson("error", curName, curUri, 0, 0, 0, 0, t[0], t[1], error));
            }
            stopForeground(true);
            stopSelf();
        }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
