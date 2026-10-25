//! Office native formula engine, exposed to Kotlin via JNI. Pure formula logic
//! is testable with host `cargo test`; the JNI surface lives in `jni_bindings`.

mod formula;
pub mod numfmt;
pub mod ooxml_package;
pub mod ooxml_units;
pub mod xlsx_strings;
pub mod xml;

use formula::Workbook;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

fn wb_registry() -> &'static Mutex<HashMap<i64, Workbook>> {
    static REG: OnceLock<Mutex<HashMap<i64, Workbook>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> i64 {
    static CTR: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = CTR.get_or_init(|| Mutex::new(1));
    let mut g = m.lock().unwrap();
    let h = *g;
    *g += 1;
    h
}

mod jni_bindings {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jdouble, jint, jlong, jstring};
    use jni::{Env, EnvUnowned};

    fn read_string(env: &Env, s: &JString) -> Option<String> {
        s.try_to_string(env).ok()
    }

    fn out_string(env: &mut Env, s: String) -> jstring {
        match env.new_string(s) {
            Ok(o) => o.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }

    // --- ODF formula engine ---

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_nativeCreateWorkbook<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        json: JString<'l>,
        now_millis: jlong,
    ) -> jlong {
        env.with_env(|env| -> jni::errors::Result<jlong> {
            let json = match read_string(env, &json) {
                Some(v) => v,
                None => return Ok(0),
            };
            let wb = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                Workbook::from_json(&json, now_millis)
            }))
            .unwrap_or(None);
            match wb {
                Some(w) => {
                    let h = next_handle();
                    wb_registry().lock().unwrap().insert(h, w);
                    Ok(h)
                }
                None => Ok(0),
            }
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_nativeDisplayValue<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
        sheet_idx: jint,
        row: jint,
        col: jint,
    ) -> jstring {
        env.with_env(|env| -> jni::errors::Result<jstring> {
            let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                wb_registry()
                    .lock()
                    .unwrap()
                    .get(&handle)
                    .map(|w| w.display_value(sheet_idx as usize, row, col))
            }))
            .unwrap_or(None);
            match out {
                Some(s) => Ok(out_string(env, s)),
                None => Ok(std::ptr::null_mut()),
            }
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_nativeIsNumeric<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
        sheet_idx: jint,
        row: jint,
        col: jint,
    ) -> jboolean {
        env.with_env(|env| -> jni::errors::Result<jboolean> {
            let _ = env;
            let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                wb_registry()
                    .lock()
                    .unwrap()
                    .get(&handle)
                    .map(|w| w.is_numeric(sheet_idx as usize, row, col))
            }))
            .unwrap_or(None);
            match out {
                Some(true) => Ok(true),
                _ => Ok(false),
            }
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_nativeFree<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
    ) {
        env.with_env(|env| -> jni::errors::Result<()> {
            let _ = env;
            wb_registry().lock().unwrap().remove(&handle);
            Ok(())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_nativeFormatValue<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        value: jdouble,
        number_format_json: JString<'l>,
    ) -> jstring {
        env.with_env(|env| -> jni::errors::Result<jstring> {
            let nf_json = read_string(env, &number_format_json).unwrap_or_default();
            let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                formula::format_value_json(value, &nf_json)
            }))
            .unwrap_or_else(|_| String::new());
            Ok(out_string(env, out))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    /// Excel number-format code → the ODF number-style model, as JSON. `null` (a JSON `null`)
    /// means "General"/text, i.e. no explicit style — matching `ExcelNumFmt.parse` returning null.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_numFmtParse<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        code: JString<'l>,
    ) -> jstring {
        env.with_env(|env| -> jni::errors::Result<jstring> {
            let Some(code) = read_string(env, &code) else {
                return Ok(out_string(env, "null".to_string()));
            };
            let json = crate::numfmt::parse(&code)
                .map(|f| serde_json::to_string(&f).unwrap_or_else(|_| "null".into()))
                .unwrap_or_else(|| "null".into());
            Ok(out_string(env, json))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    /// Builtin `numFmtId` → the ODF number-style model, as JSON, or `null`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_numFmtForBuiltin<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        id: jint,
    ) -> jstring {
        env.with_env(|env| -> jni::errors::Result<jstring> {
            let json = crate::numfmt::for_builtin(id)
                .map(|f| serde_json::to_string(&f).unwrap_or_else(|_| "null".into()))
                .unwrap_or_else(|| "null".into());
            Ok(out_string(env, json))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_office_util_OfficeNative_numFmtIsDateTimeBuiltin<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        id: jint,
    ) -> jboolean {
        env.with_env(|env| -> jni::errors::Result<jboolean> {
            let _ = env;
            Ok(crate::numfmt::is_date_time_builtin(id) as jboolean)
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }
}
