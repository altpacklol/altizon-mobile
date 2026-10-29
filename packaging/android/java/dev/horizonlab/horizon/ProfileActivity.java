package dev.horizonlab.horizon;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Name and skin profile screen.
 */
public class ProfileActivity extends Activity implements View.OnClickListener {
    private static final int PICK = 7;
    private File dir;
    private EditText name;
    private TextView message;
    private ImageView preview;
    private Button pick, model, save, back;
    private boolean slim;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.hideSystemUi(this);
        dir = new File(SetupActivity.home(this));
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, Ui.t("Профиль", "Profile"), 26, Ui.TEXT, true));
        card.addView(Ui.text(this, Ui.t("Имя игрока (ник)", "Player Name"), 14, Ui.MUTED, false), Ui.gap(this, 10));
        name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.MUTED);
        name.setHint("Steve_123");
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(16)});
        int p = Ui.dp(this, 12);
        name.setPadding(p, p, p, p);
        name.setBackground(Ui.rounded(this, android.graphics.Color.rgb(24, 24, 30), Ui.EDGE, 8));
        name.setText(readName());
        card.addView(name);
        card.addView(Ui.text(this, Ui.t("3-16 символов: буквы, цифры, _", "3-16 characters: letters, digits, _"),
                12, Ui.MUTED, false));
        card.addView(Ui.text(this, Ui.t("Скин", "Skin"), 14, Ui.MUTED, false), Ui.gap(this, 14));
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Ui.rounded(this, android.graphics.Color.rgb(24, 24, 30), 0, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 180));
        card.addView(preview, lp);
        pick = Ui.button(this, Ui.t("Выбрать PNG-скин", "Choose a PNG skin"), false);
        pick.setOnClickListener(this);
        card.addView(pick, Ui.gap(this, 10));
        slim = readModel();
        model = Ui.button(this, "", false);
        model.setOnClickListener(this);
        card.addView(model, Ui.gap(this, 8));
        message = Ui.text(this, "", 13, Ui.WARN, false);
        card.addView(message, Ui.gap(this, 6));
        save = Ui.button(this, Ui.t("Сохранить", "Save"), true);
        save.setOnClickListener(this);
        card.addView(save, Ui.gap(this, 12));
        back = Ui.button(this, Ui.t("Назад", "Back"), false);
        back.setOnClickListener(this);
        card.addView(back, Ui.gap(this, 8));
        updateModel();
        showPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemUi(this);
    }

    private String readName() {
        try {
            for (String l : new String(Files.readAllBytes(new File(dir, "horizon-player.txt").toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (l.startsWith("name:")) return l.substring(5).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean readModel() {
        try {
            return new String(Files.readAllBytes(new File(dir, "horizon-skin.txt").toPath()), StandardCharsets.UTF_8).contains("model:slim");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateModel() {
        model.setText(Ui.t("Модель рук: ", "Arms: ") + (slim ? Ui.t("тонкие (Alex)", "slim (Alex)") : Ui.t("классические (Steve)", "classic (Steve)")));
    }

    static boolean validName(String n) {
        return n.matches("[A-Za-z0-9_]{3,16}");
    }

    public void onClick(View v) {
        if (v == pick) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/png");
            startActivityForResult(i, PICK);
        } else if (v == model) {
            slim = !slim;
            updateModel();
        } else if (v == save) {
            String n = name.getText().toString().trim();
            if (!validName(n)) {
                message.setText(Ui.t("Имя: 3-16 символов, только латиница, цифры и _.", "Name: 3-16 characters, letters, digits and _ only."));
                return;
            }
            try {
                write(new File(dir, "horizon-player.txt"), ("name:" + n + "\n").getBytes(StandardCharsets.UTF_8));
                write(new File(dir, "horizon-skin.txt"), ("model:" + (slim ? "slim" : "classic") + "\n").getBytes(StandardCharsets.UTF_8));
                message.setTextColor(Ui.GREEN);
                message.setText(Ui.t("Сохранено. Скин изменится сразу, имя на серверах — после перезапуска.",
                        "Saved. The skin changes right away, the name on servers after a restart."));
            } catch (Exception e) {
                message.setText(Ui.t("Не удалось сохранить.", "Could not save."));
            }
        } else if (v == back) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        message.setTextColor(Ui.WARN);
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 1 << 20) throw new Exception("too big");
            }
            byte[] png = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(png, 0, png.length, o);
            boolean isPng = png.length > 8 && (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
            if (!isPng || o.outWidth != 64 || (o.outHeight != 64 && o.outHeight != 32)) {
                message.setText(Ui.t("Нужен PNG-файл 64x64 или 64x32.", "A 64x64 or 64x32 PNG is needed."));
                return;
            }
            write(new File(dir, "horizon-skin.png"), png);
            message.setTextColor(Ui.GREEN);
            message.setText(Ui.t("Скин выбран.", "Skin chosen."));
            showPreview();
        } catch (Exception e) {
            message.setText(Ui.t("Не удалось прочитать файл скина.", "Could not read the file."));
        }
    }

    private static void write(File f, byte[] bytes) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) throw new Exception("rename");
    }

    private void showPreview() {
        File f = new File(dir, "horizon-skin.png");
        Bitmap skin = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (skin == null) {
            preview.setImageDrawable(null);
            return;
        }
        boolean legacy = skin.getHeight() == 32;
        Bitmap fig = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(fig);
        Paint p = new Paint();
        p.setFilterBitmap(false);
        int arm = slim ? 3 : 4;
        part(c, skin, p, 8, 8, 8, 8, 4, 0);
        part(c, skin, p, 20, 20, 8, 12, 4, 8);
        part(c, skin, p, 44, 20, arm, 12, 4 - arm, 8);
        if (legacy) mirrored(c, skin, p, 44, 20, arm, 12, 12, 8);
        else part(c, skin, p, 36, 52, arm, 12, 12, 8);
        part(c, skin, p, 4, 20, 4, 12, 4, 20);
        if (legacy) mirrored(c, skin, p, 4, 20, 4, 12, 8, 20);
        else part(c, skin, p, 20, 52, 4, 12, 8, 20);
        part(c, skin, p, 40, 8, 8, 8, 4, 0);
        Bitmap big = Bitmap.createScaledBitmap(fig, 16 * 5, 32 * 5, false);
        preview.setImageBitmap(big);
    }

    private static void part(Canvas c, Bitmap s, Paint p, int x, int y, int w, int h, int dx, int dy) {
        c.drawBitmap(s, new Rect(x, y, x + w, y + h), new Rect(dx, dy, dx + w, dy + h), p);
    }

    private static void mirrored(Canvas c, Bitmap s, Paint p, int x, int y, int w, int h, int dx, int dy) {
        c.save();
        c.scale(-1, 1, dx + w / 2f, 0);
        part(c, s, p, x, y, w, h, dx, dy);
        c.restore();
    }
}
