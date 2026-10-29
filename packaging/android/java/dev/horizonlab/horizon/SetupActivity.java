package dev.horizonlab.horizon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Main Launcher & Resource Setup Activity for Horizon (Minecraft 26.3).
 */
public class SetupActivity extends Activity
        implements View.OnClickListener, DialogInterface.OnClickListener, Runnable {
    static {
        System.loadLibrary("horizon");
    }

    static native int nativeReady(String home);
    static native String nativeDefaultUrl();
    static native void nativeStart(String home, String derivedUrl);
    static native String nativeStatus();

    private static final long NEED_BYTES = 1500L * 1024 * 1024;
    private static final String APP_VERSION = "0.2.5 (26.3)";

    private String home;
    private TextView stage, detail, note, statusText;
    private Ui.Bar bar;
    private Button btnPlay, btnDownload, btnUnpackDerived, btnProfile, btnForce;
    private final Handler handler = new Handler();
    private long lastBytes = -1, lastTime;
    private double speed;

    static String home(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        dir.mkdirs();
        // Also ensure horizon-mods folder exists for users
        new File(dir, "horizon-mods").mkdirs();
        return dir.getAbsolutePath();
    }

    /** Extracts embedded horizon-derived.zip from APK assets if not yet extracted. */
    private void extractEmbeddedDerived() {
        File target = new File(home, "horizon-derived.zip");
        if (target.exists() && target.length() > 0) return;
        try (InputStream in = getAssets().open("horizon-derived.zip");
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isReady() {
        File res = new File(home, "resources");
        boolean readyInRes = new File(res, "horizon-resources.json").isFile()
                && new File(res, "facts.json").isFile();
        boolean readyInHome = new File(home, "horizon-resources.json").isFile()
                && new File(home, "facts.json").isFile();

        if (readyInHome && !readyInRes) {
            res.mkdirs();
            new File(home, "horizon-resources.json").renameTo(new File(res, "horizon-resources.json"));
            new File(home, "facts.json").renameTo(new File(res, "facts.json"));
            readyInRes = true;
        }

        return readyInRes || nativeReady(home) == 1;
    }

    private void clearIncompleteFlag() {
        File inc = new File(new File(home, "resources"), ".horizon-setup-incomplete");
        if (inc.exists()) inc.delete();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.hideSystemUi(this);
        home = home(this);
        extractEmbeddedDerived();

        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Horizon", 32, Ui.TEXT, true));
        card.addView(Ui.text(this, "Minecraft Java Edition 26.3 • v" + APP_VERSION, 14, Ui.MUTED, false));

        statusText = Ui.text(this, "", 15, Ui.TEXT, true);
        card.addView(statusText, Ui.gap(this, 12));

        note = Ui.text(this, "", 14, Ui.WARN, false);
        note.setVisibility(View.GONE);
        card.addView(note, Ui.gap(this, 8));

        // Primary Play button
        btnPlay = Ui.button(this, Ui.t("▶ Запустить игру", "▶ Play Game"), true);
        btnPlay.setOnClickListener(this);
        card.addView(btnPlay, Ui.gap(this, 14));

        // Unpack derived pack button (if horizon-derived.zip exists)
        btnUnpackDerived = Ui.button(this, Ui.t("📦 Распаковать horizon-derived.zip", "📦 Unpack horizon-derived.zip"), false);
        btnUnpackDerived.setOnClickListener(this);
        card.addView(btnUnpackDerived, Ui.gap(this, 10));

        // Download / Reinstall resources button
        btnDownload = Ui.button(this, Ui.t("⚙ Установка и обновление файлов (~700 МБ)", "⚙ Setup & Download game files (~700 MB)"), false);
        btnDownload.setOnClickListener(this);
        card.addView(btnDownload, Ui.gap(this, 10));

        // Profile button
        btnProfile = Ui.button(this, Ui.t("👤 Профиль: ник и скин", "👤 Profile: Name & Skin"), false);
        btnProfile.setOnClickListener(this);
        card.addView(btnProfile, Ui.gap(this, 10));

        // Force launch button (shown on error or partial resources)
        btnForce = Ui.button(this, Ui.t("🚀 Принудительный запуск", "🚀 Force Launch"), false);
        btnForce.setOnClickListener(this);
        btnForce.setVisibility(View.GONE);
        card.addView(btnForce, Ui.gap(this, 10));

        // Progress bar
        bar = new Ui.Bar(this);
        bar.setVisibility(View.GONE);
        card.addView(bar, Ui.gap(this, 16));

        stage = Ui.text(this, "", 15, Ui.TEXT, true);
        stage.setVisibility(View.GONE);
        card.addView(stage, Ui.gap(this, 6));

        detail = Ui.text(this, "", 13, Ui.MUTED, false);
        detail.setVisibility(View.GONE);
        card.addView(detail);

        updateState();

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (nativeStatus().contains("\trun\t")) {
            running();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
        updateState();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemUi(this);
    }

    private void updateState() {
        boolean ready = isReady();
        boolean hasZip = new File(home, "horizon-derived.zip").isFile();

        if (ready) {
            statusText.setTextColor(Ui.GREEN);
            statusText.setText(Ui.t("✅ Все ресурсы готовы к игре", "✅ All game resources are ready"));
            btnPlay.setVisibility(View.VISIBLE);
            btnPlay.setText(Ui.t("▶ Запустить игру", "▶ Play Game"));
            btnDownload.setText(Ui.t("🔄 Переустановить / обновить ресурсы", "🔄 Reinstall / Update resources"));
        } else {
            statusText.setTextColor(Ui.TEXT);
            statusText.setText(Ui.t("ℹ️ Требуется установка ресурсов Minecraft 26.3", "ℹ️ Minecraft 26.3 resources setup required"));
            btnPlay.setVisibility(View.GONE);
            btnDownload.setText(Ui.t("📥 Скачать ресурсы игры (~700 МБ)", "📥 Download game files (~700 MB)"));
        }

        btnUnpackDerived.setVisibility(hasZip ? View.VISIBLE : View.GONE);
    }

    private void showNote(String s) {
        note.setText(s);
        note.setVisibility(View.VISIBLE);
    }

    public void onClick(View v) {
        if (v == btnProfile) {
            startActivity(new Intent(this, ProfileActivity.class));
        } else if (v == btnPlay || v == btnForce) {
            clearIncompleteFlag();
            launch();
        } else if (v == btnUnpackDerived) {
            unpackDerived();
        } else if (v == btnDownload) {
            confirm();
        }
    }

    private void unpackDerived() {
        File zip = new File(home, "horizon-derived.zip");
        if (!zip.isFile()) {
            showNote(Ui.t("Файл horizon-derived.zip не найден в папке игры.", "horizon-derived.zip not found in game folder."));
            return;
        }
        bar.setVisibility(View.VISIBLE);
        stage.setVisibility(View.VISIBLE);
        detail.setVisibility(View.VISIBLE);
        stage.setText(Ui.t("Распаковка horizon-derived.zip...", "Unpacking horizon-derived.zip..."));
        new Thread(() -> {
            File resDir = new File(home, "resources");
            resDir.mkdirs();
            boolean ok = unpack(zip, resDir);
            runOnUiThread(() -> {
                bar.setVisibility(View.GONE);
                stage.setVisibility(View.GONE);
                detail.setVisibility(View.GONE);
                if (ok) {
                    clearIncompleteFlag();
                    updateState();
                    showNote(Ui.t("Ресурсы успешно распакованы!", "Resources unpacked successfully!"));
                } else {
                    showNote(Ui.t("Ошибка при распаковке архива.", "Error unpacking zip file."));
                }
            });
        }).start();
    }

    private boolean unpack(File zipFile, File targetDir) {
        byte[] buf = new byte[65536];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                if (name.contains("..")) continue;
                File out = new File(targetDir, name);
                if (ze.isDirectory()) {
                    out.mkdirs();
                } else {
                    File p = out.getParentFile();
                    if (p != null) p.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void onClick(DialogInterface d, int w) {
        begin();
    }

    private void confirm() {
        StatFs fs = new StatFs(home);
        if (fs.getAvailableBytes() < NEED_BYTES) {
            showNote(Ui.t("Мало места. Нужно около 1.5 ГБ, свободно: ", "Not enough space. About 1.5 GB needed, free: ")
                    + (fs.getAvailableBytes() >> 20) + Ui.t(" МБ.", " MB."));
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean wifi = cm != null && cm.getNetworkCapabilities(cm.getActiveNetwork()) != null
                && cm.getNetworkCapabilities(cm.getActiveNetwork())
                        .hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        if (!wifi) {
            new AlertDialog.Builder(this)
                    .setTitle(Ui.t("Загрузка через мобильную сеть?", "Download over cellular?"))
                    .setMessage(Ui.t("Будет скачано около 700 МБ. Продолжить?", "About 700 MB will be downloaded. Continue?"))
                    .setPositiveButton(Ui.t("Скачать", "Download"), this)
                    .setNegativeButton(Ui.t("Отмена", "Cancel"), null)
                    .show();
        } else {
            begin();
        }
    }

    private void begin() {
        Intent service = new Intent(this, SetupService.class);
        service.putExtra("home", home);
        service.putExtra("url", nativeDefaultUrl());
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        running();
    }

    private void running() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnDownload.setEnabled(false);
        btnDownload.setAlpha(0.5f);
        bar.setVisibility(View.VISIBLE);
        stage.setVisibility(View.VISIBLE);
        detail.setVisibility(View.VISIBLE);
        note.setVisibility(View.GONE);
        lastBytes = -1;
        handler.removeCallbacks(this);
        handler.post(this);
    }

    static String stageName(String s) {
        if (s.equals("version") || s.equals("index")) return Ui.t("Подготовка...", "Preparing...");
        if (s.equals("client")) return Ui.t("Скачивание Minecraft...", "Downloading Minecraft...");
        if (s.equals("extract")) return Ui.t("Распаковка...", "Unpacking...");
        if (s.equals("assets")) return Ui.t("Звуки, шрифты и языки...", "Sounds, fonts and languages...");
        if (s.equals("derived")) return Ui.t("Данные Horizon...", "Horizon data...");
        if (s.equals("done")) return Ui.t("Готово", "Ready");
        return Ui.t("Подготовка...", "Preparing...");
    }

    static String friendlyError(String e) {
        if (e.startsWith("No derived pack")) {
            return Ui.t("Не найден архив данных Horizon. Положите horizon-derived.zip в папку игры и нажмите «Распаковать».",
                    "Horizon data pack was not found. Put horizon-derived.zip in the game folder and tap Unpack.");
        }
        if (e.contains("SHA-1")) {
            return Ui.t("Файл повреждён при передаче. Нажмите «Повторить».", "A file arrived damaged. Tap Retry.");
        }
        if (e.contains("Dns") || e.contains("onnect") || e.contains("timed out") || e.contains("Network")) {
            return Ui.t("Нет связи с сервером Mojang. Проверьте подключение к сети.",
                    "Could not reach Mojang server. Check your network connection.");
        }
        if (e.contains("No space") || e.contains("ENOSPC")) {
            return Ui.t("Недостаточно места на телефоне.", "Out of disk space.");
        }
        return Ui.t("Произошла ошибка: " + e, "Error: " + e);
    }

    public void run() {
        String[] p = nativeStatus().split("\t", -1);
        if (p.length < 7) {
            handler.postDelayed(this, 300);
            return;
        }
        long files = Long.parseLong(p[1]), filesTotal = Long.parseLong(p[2]);
        long bytes = Long.parseLong(p[3]), bytesTotal = Long.parseLong(p[4]);
        long now = SystemClock.elapsedRealtime();
        if (lastBytes >= 0 && now > lastTime && bytes >= lastBytes) {
            double s = (bytes - lastBytes) * 1000.0 / (now - lastTime);
            speed = speed == 0 ? s : speed * 0.8 + s * 0.2;
        }
        lastBytes = bytes;
        lastTime = now;
        int permille = 0;
        String line = "";
        if (bytesTotal > 0) {
            permille = (int) (1000 * bytes / Math.max(1, bytesTotal));
            line = (bytes >> 20) + " / " + (bytesTotal >> 20) + Ui.t(" МБ", " MB");
            if (speed > 1024) line += "  •  " + String.format(java.util.Locale.ROOT, "%.1f", speed / 1048576) + Ui.t(" МБ/с", " MB/s");
        } else if (filesTotal > 0) {
            permille = (int) (1000 * files / Math.max(1, filesTotal));
            line = files + " / " + filesTotal + Ui.t(" файлов", " files");
        }
        bar.set(permille);
        stage.setText(stageName(p[0]) + "  " + (permille / 10) + "%");
        detail.setText(line);
        if (p[5].equals("done")) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            clearIncompleteFlag();
            updateState();
            launch();
            return;
        }
        if (p[5].equals("error")) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stage.setText(Ui.t("Загрузка остановлена", "Download stopped"));
            showNote(friendlyError(p[6]));
            btnDownload.setText(Ui.t("Повторить загрузку", "Retry download"));
            btnDownload.setEnabled(true);
            btnDownload.setAlpha(1f);
            btnForce.setVisibility(View.VISIBLE);
            return;
        }
        handler.postDelayed(this, 500);
    }

    private void launch() {
        startActivity(new Intent(this, HorizonActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(this);
        super.onDestroy();
    }
}
