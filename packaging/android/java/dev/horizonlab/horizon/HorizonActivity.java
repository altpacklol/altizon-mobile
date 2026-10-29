package dev.horizonlab.horizon;

import android.app.NativeActivity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * NativeActivity plus a text bridge, mouse pointer capture for FPS controls,
 * and full-screen sticky immersive mode to prevent accidental navigation gestures.
 */
public class HorizonActivity extends NativeActivity {
    static {
        System.loadLibrary("horizon");
    }

    static native void nativeText(String text);
    /** 1 = backspace, 2 = enter. */
    static native void nativeKey(int key);

    private TextSink sink;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hideSystemUi();
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        sink = new TextSink(this);
        addContentView(sink, new ViewGroup.LayoutParams(1, 1));
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.navigationBars() | android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                getWindow().getDecorView().requestPointerCapture();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getWindow().getDecorView().hasPointerCapture()) {
            getWindow().getDecorView().requestPointerCapture();
        }
        return super.onTouchEvent(event);
    }

    /** Called from the game thread: the profile screen (name, skin). */
    public void openProfile() {
        startActivity(new android.content.Intent(this, ProfileActivity.class));
    }

    /** Called from the game thread. */
    public void showKeyboard(final boolean show) {
        runOnUiThread(new Toggle(this, show));
    }

    static class Toggle implements Runnable {
        private final HorizonActivity a;
        private final boolean show;

        Toggle(HorizonActivity a, boolean show) {
            this.a = a;
            this.show = show;
        }

        public void run() {
            TextSink sink = a.sink;
            {
                InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (show) {
                    sink.setFocusable(true);
                    sink.setFocusableInTouchMode(true);
                    sink.requestFocus();
                    imm.restartInput(sink);
                    imm.showSoftInput(sink, InputMethodManager.SHOW_FORCED);
                } else {
                    imm.hideSoftInputFromWindow(sink.getWindowToken(), 0);
                    sink.clearFocus();
                }
            }
        }
    }

    static class TextSink extends View {
        TextSink(Context c) {
            super(c);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo info) {
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            info.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new Connection(this);
        }
    }

    static class Connection extends BaseInputConnection {
        private String composing = "";

        Connection(View view) {
            super(view, false);
        }

        @Override
        public boolean commitText(CharSequence text, int pos) {
            composing = "";
            if (text.length() > 0) nativeText(text.toString());
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int pos) {
            String t = text.toString();
            int common = 0;
            while (common < t.length() && common < composing.length()
                    && t.charAt(common) == composing.charAt(common)) common++;
            for (int i = common; i < composing.length(); i++) nativeKey(1);
            if (common < t.length()) nativeText(t.substring(common));
            composing = t;
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) nativeKey(1);
            return true;
        }

        @Override
        public boolean performEditorAction(int action) {
            composing = "";
            nativeKey(2);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int code = e.getKeyCode();
                if (code == KeyEvent.KEYCODE_DEL) {
                    nativeKey(1);
                } else if (code == KeyEvent.KEYCODE_ENTER) {
                    nativeKey(2);
                } else {
                    int ch = e.getUnicodeChar();
                    if (ch != 0) nativeText(new String(Character.toChars(ch)));
                }
            }
            return true;
        }
    }
}
