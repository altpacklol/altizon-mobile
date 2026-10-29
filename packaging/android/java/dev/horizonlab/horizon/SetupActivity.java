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

/**
 * The launcher activity. Starts the game when the resources are ready; otherwise shows the
 * first-run screen that downloads and prepares the game files on the phone (android_setup.rs).
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
    private TextView stage, detail, note;
    private Ui.Bar bar;
    private Button button, profile, btnForce, btnClear;
    private final Handler handler = new Handler();
    private long lastBytes = -1, lastTime;
    private double speed;

    static String home(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        dir.mkdirs();
        return dir.getAbsolutePath();
    }

    private void hideSystemUi() {
        try {
            if (getWindow() != null && getWindow().getDecorView() != null) {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
        } catch (Exception ignored) {
        }
    }

    /** Whether the derived pack can be found (a build URL or the local zip). */
    private boolean haveDerived() {
        return !nativeDefaultUrl().isEmpty() || new File(home, "horizon-derived.zip").isFile();
    }

    private boolean checkAndPrepareResources() {
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

        if (readyInRes) {
            File inc = new File(res, ".horizon-setup-incomplete");
            if (inc.exists()) {
                inc.delete();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hideSystemUi();
        home = home(this);

        if (nativeReady(home) == 1 || checkAndPrepareResources()) {
            launch();
            return;
        }

        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Horizon", 30, Ui.TEXT, true));
        card.addView(Ui.text(this, Ui.t("Версия: " + APP_VERSION, "Version: " + APP_VERSION), 13, Ui.MUTED, false));
        card.addView(Ui.text(this, Ui.t("Первый запуск", "First launch"), 15, Ui.MUTED, false));
        card.addView(Ui.text(this, Ui.t(
                "Игре нужны файлы Minecraft 26.3. Они скачаются автоматически, около 700 МБ. Лучше подключиться к Wi-Fi.",
                "The game needs the Minecraft 26.3 files. They will be downloaded automatically, about 700 MB. Wi-Fi is best."),
                15, Ui.TEXT, false), Ui.gap(this, 12));

        note = Ui.text(this, "", 14, Ui.WARN, false);
        note.setVisibility(View.GONE);
        card.addView(note, Ui.gap(this, 8));

        button = Ui.button(this, Ui.t("Скачать файлы игры", "Download game files"), true);
        button.setOnClickListener(this);
        card.addView(button, Ui.gap(this, 18));

        bar = new Ui.Bar(this);
        bar.setVisibility(View.GONE);
        card.addView(bar, Ui.gap(this, 18));

        stage = Ui.text(this, "", 15, Ui.TEXT, true);
        card.addView(stage, Ui.gap(this, 6));

        detail = Ui.text(this, "", 13, Ui.MUTED, false);
        card.addView(detail);

        btnForce = Ui.button(this, Ui.t("Принудительный запуск (пропустить)", "Force launch (skip download)"), false);
        btnForce.setOnClickListener(this);
        card.addView(btnForce, Ui.gap(this, 12));

        btnClear = Ui.button(this, Ui.t("Очистить и сбросить загрузку", "Clear & reset download"), false);
        btnClear.setOnClickListener(this);
        card.addView(btnClear, Ui.gap(this, 10));

        profile = Ui.button(this, Ui.t("Профиль: ник и скин", "Profile: name and skin"), false);
        profile.setOnClickListener(this);
        card.addView(profile, Ui.gap(this, 10));

        if (!haveDerived()) {
            showNote(Ui.t(
                    "В этой сборке нет адреса пакета данных Horizon. Положите horizon-derived.zip в папку игры (Android/data/dev.horizonlab.horizon/files) или установите сборку, где он указан.",
                    "This build has no Horizon data pack address. Put horizon-derived.zip in the game folder (Android/data/dev.horizonlab.horizon/files) or install a build that has one."));
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (nativeStatus().contains("\trun\t")) {
            running();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void showNote(String s) {
        note.setText(s);
        note.setVisibility(View.VISIBLE);
    }

    public void onClick(View v) {
        if (v == profile) {
            startActivity(new Intent(this, ProfileActivity.class));
        } else if (v == btnForce) {
            File inc = new File(new File(home, "resources"), ".horizon-setup-incomplete");
            if (inc.exists()) inc.delete();
            launch();
        } else if (v == btnClear) {
            confirmClear();
        } else {
            confirm();
        }
    }

    public void onClick(DialogInterface d, int w) {
        begin();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(Ui.t("Очистить файлы?", "Clear files?"))
                .setMessage(Ui.t("Удалить временные и незавершенные файлы загрузки, чтобы начать заново?",
                        "Delete temporary and incomplete download files to start fresh?"))
                .setPositiveButton(Ui.t("Очистить", "Clear"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearFiles();
                    }
                })
                .setNegativeButton(Ui.t("Отмена", "Cancel"), null)
                .show();
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }

    private void clearFiles() {
        try {
            stopService(new Intent(this, SetupService.class));
        } catch (Exception ignored) {}
        deleteRecursive(new File(home, "resources"));
        deleteRecursive(new File(home, "tmp"));
        deleteRecursive(new File(home, ".horizon-setup-incomplete"));
        new File(home, "resources").mkdirs();
        bar.setVisibility(View.GONE);
        bar.set(0);
        stage.setText("");
        detail.setText("");
        button.setEnabled(true);
        button.setAlpha(1.0f);
        button.setText(Ui.t("Скачать файлы игры", "Download game files"));
        showNote(Ui.t("Файлы очищены. Можно начать чистую установку.", "Files cleared. You can start fresh."));
    }

    private void confirm() {
        StatFs fs = new StatFs(home);
        if (fs.getAvailableBytes() < NEED_BYTES) {
            showNote(Ui.t("Мало места. Нужно около 1,5 ГБ, свободно ", "Not enough space. About 1.5 GB is needed, free: ")
                    + (fs.getAvailableBytes() >> 20) + Ui.t(" МБ.", " MB."));
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && cm.isActiveNetworkMetered()) {
            new AlertDialog.Builder(this)
                    .setTitle(Ui.t("Мобильный интернет", "Mobile data"))
                    .setMessage(Ui.t("Вы не подключены к Wi-Fi. Скачать около 700 МБ через мобильную сеть?",
                            "You are not on Wi-Fi. Download about 700 MB over mobile data?"))
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
        button.setEnabled(false);
        button.setAlpha(0.5f);
        bar.setVisibility(View.VISIBLE);
        if (haveDerived()) note.setVisibility(View.GONE);
        lastBytes = -1;
        handler.removeCallbacks(this);
        handler.post(this);
    }

    static String stageName(String s) {
        if (s.equals("version") || s.equals("index")) return Ui.t("Подготовка", "Preparing");
        if (s.equals("client")) return Ui.t("Скачивание игры", "Downloading the game");
        if (s.equals("extract")) return Ui.t("Распаковка", "Unpacking");
        if (s.equals("assets")) return Ui.t("Звуки, шрифты и языки", "Sounds, fonts and languages");
        if (s.equals("derived")) return Ui.t("Данные Horizon", "Horizon data");
        if (s.equals("done")) return Ui.t("Готово", "Ready");
        return Ui.t("Подготовка", "Preparing");
    }

    /** A message a player can act on, from the native error text. */
    static String friendlyError(String e) {
        if (e.startsWith("No derived pack")) {
            return Ui.t("Не найден пакет данных Horizon. Положите horizon-derived.zip в папку игры и нажмите «Повторить».",
                    "The Horizon data pack was not found. Put horizon-derived.zip in the game folder and tap Retry.");
        }
        if (e.contains("SHA-1")) {
            return Ui.t("Файл скачался с ошибкой. Нажмите «Повторить».", "A file arrived damaged. Tap Retry.");
        }
        if (e.contains("Dns") || e.contains("onnect") || e.contains("timed out") || e.contains("Network")) {
            return Ui.t("Нет связи с сервером. Проверьте интернет и нажмите «Повторить».",
                    "Could not reach the server. Check the connection and tap Retry.");
        }
        if (e.contains("No space") || e.contains("ENOSPC")) {
            return Ui.t("Закончилось место на телефоне.", "The phone ran out of space.");
        }
        return Ui.t("Что-то пошло не так. Нажмите «Повторить», загрузка продолжится с того же места.",
                "Something went wrong. Tap Retry, the download continues where it stopped.");
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
            if (speed > 1024) line += "  ·  " + String.format(java.util.Locale.ROOT, "%.1f", speed / 1048576) + Ui.t(" МБ/с", " MB/s");
        } else if (filesTotal > 0) {
            permille = (int) (1000 * files / Math.max(1, filesTotal));
            line = files + " / " + filesTotal + Ui.t(" файлов", " files");
        }
        bar.set(permille);
        stage.setText(stageName(p[0]) + "  " + (permille / 10) + "%");
        detail.setText(line);
        if (p[5].equals("done")) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            launch();
            return;
        }
        if (p[5].equals("error")) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stage.setText(Ui.t("Загрузка остановилась", "The download stopped"));
            detail.setText("");
            showNote(friendlyError(p[6]));
            button.setText(Ui.t("Повторить", "Retry"));
            button.setEnabled(true);
            button.setAlpha(1f);
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
