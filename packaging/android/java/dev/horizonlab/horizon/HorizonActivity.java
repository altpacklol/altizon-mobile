package dev.horizonlab.horizon;

import android.app.NativeActivity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
        Ui.hideSystemUi(this);
        sink = new TextSink(this);
        addContentView(sink, new ViewGroup.LayoutParams(1, 1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Ui.hideSystemUi(this);
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
            if (sink == null) return;
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
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
