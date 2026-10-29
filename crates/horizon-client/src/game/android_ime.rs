//! The soft keyboard on Android (packaging/android/java/.../HorizonActivity.java):
//! the Java side's invisible text view hands typed text and keys to
//! `nativeText`/`nativeKey`, which queue them for the game thread; the game
//! asks for the keyboard with `show`.
use std::sync::Mutex;

pub(super) enum ImeInput {
    Text(String),
    Backspace,
    Enter,
}

static QUEUE: Mutex<Vec<ImeInput>> = Mutex::new(Vec::new());

pub(super) fn take() -> Vec<ImeInput> {
    std::mem::take(&mut *QUEUE.lock().unwrap_or_else(std::sync::PoisonError::into_inner))
}

fn push(input: ImeInput) {
    QUEUE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .push(input);
}

#[unsafe(no_mangle)]
extern "system" fn Java_dev_horizonlab_horizon_HorizonActivity_nativeText(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    text: jni::objects::JString,
) {
    if let Ok(text) = env.get_string(&text) {
        push(ImeInput::Text(text.into()));
    }
}

#[unsafe(no_mangle)]
extern "system" fn Java_dev_horizonlab_horizon_HorizonActivity_nativeKey(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    key: jni::sys::jint,
) {
    push(match key {
        1 => ImeInput::Backspace,
        _ => ImeInput::Enter,
    });
}

/// Show or hide the soft keyboard (`HorizonActivity.showKeyboard`).
pub(super) fn show(show: bool) {
    let Some(app) = super::ANDROID_APP.get() else {
        return;
    };
    // SAFETY: the VM and activity pointers android-activity hands out live as long as the app.
    let result = (|| -> jni::errors::Result<()> {
        let vm = unsafe { jni::JavaVM::from_raw(app.vm_as_ptr().cast())? };
        let mut env = vm.attach_current_thread_permanently()?;
        let activity = unsafe { jni::objects::JObject::from_raw(app.activity_as_ptr().cast()) };
        env.call_method(&activity, "showKeyboard", "(Z)V", &[jni::objects::JValue::Bool(show.into())])?;
        Ok(())
    })();
    if let Err(e) = result {
        log::warn!("Horizon: soft keyboard: {e}");
    }
}

/// Open the profile screen (`HorizonActivity.openProfile`): name and skin.
pub(super) fn open_profile() {
    let Some(app) = super::ANDROID_APP.get() else {
        return;
    };
    // SAFETY: as in `show`.
    let result = (|| -> jni::errors::Result<()> {
        let vm = unsafe { jni::JavaVM::from_raw(app.vm_as_ptr().cast())? };
        let mut env = vm.attach_current_thread_permanently()?;
        let activity = unsafe { jni::objects::JObject::from_raw(app.activity_as_ptr().cast()) };
        env.call_method(&activity, "openProfile", "()V", &[])?;
        Ok(())
    })();
    if let Err(e) = result {
        log::warn!("Horizon: profile screen: {e}");
    }
}


static MOUSE_DELTA_X: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
static MOUSE_DELTA_Y: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

#[unsafe(no_mangle)]
extern "system" fn Java_dev_horizonlab_horizon_HorizonActivity_nativeCapturedMouseMove(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    dx: jni::sys::jfloat,
    dy: jni::sys::jfloat,
) {
    let idx = (dx * 1000.0) as i64;
    let idy = (dy * 1000.0) as i64;
    MOUSE_DELTA_X.fetch_add(idx, std::sync::atomic::Ordering::Relaxed);
    MOUSE_DELTA_Y.fetch_add(idy, std::sync::atomic::Ordering::Relaxed);
    super::touch::set_kbm_active(true);
}

pub(super) fn take_mouse_delta() -> (f64, f64) {
    let dx = MOUSE_DELTA_X.swap(0, std::sync::atomic::Ordering::Relaxed) as f64 / 1000.0;
    let dy = MOUSE_DELTA_Y.swap(0, std::sync::atomic::Ordering::Relaxed) as f64 / 1000.0;
    (dx, dy)
}

/// Dynamic pointer capture toggle (HorizonActivity.setPointerCaptureState).
pub(super) fn set_pointer_capture(capture: bool) {
    let Some(app) = super::ANDROID_APP.get() else {
        return;
    };
    let result = (|| -> jni::errors::Result<()> {
        let vm = unsafe { jni::JavaVM::from_raw(app.vm_as_ptr().cast())? };
        let mut env = vm.attach_current_thread_permanently()?;
        let activity = unsafe { jni::objects::JObject::from_raw(app.activity_as_ptr().cast()) };
        env.call_method(&activity, "setPointerCaptureState", "(Z)V", &[jni::objects::JValue::Bool(capture.into())])?;
        Ok(())
    })();
    if let Err(e) = result {
        log::warn!("Horizon: pointer capture: {e}");
    }
}