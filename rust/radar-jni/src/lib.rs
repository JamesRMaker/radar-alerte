//! Fonctions natives appelées depuis Kotlin (`fr.radaralerte.RadarCore`).
//!
//! Le moteur vit dans une variable globale protégée par un `Mutex` : l'activité
//! et le service peuvent l'appeler depuis des threads différents.
//! Aucune panique ne doit traverser la frontière JNI : tout est enveloppé dans
//! `catch_unwind`.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use radar_core::output::{json_escape, update_json};
use radar_core::{parse_csv, AlertMode, Engine, Fix};

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

/// Exécute `f` sur le moteur (créé au besoin), sans jamais paniquer.
fn with_engine<R>(default: R, f: impl FnOnce(&mut Engine) -> R) -> R {
    catch_unwind(AssertUnwindSafe(|| {
        let mut guard = match ENGINE.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        f(guard.get_or_insert_with(Engine::default))
    }))
    .unwrap_or(default)
}

fn opt(v: jdouble) -> Option<f64> {
    (v.is_finite() && v >= 0.0).then_some(v)
}

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|s| s.into())
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Charge un fichier CSV de radars fixes. Renvoie le nombre de radars, ou -1 en cas d'erreur.
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_loadRadars<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
) -> jint {
    let Some(path) = read_string(&mut env, &path) else { return -1 };
    let Ok(bytes) = std::fs::read(&path) else { return -1 };
    // Certains fichiers publics sont en Latin-1 / Windows-1252 plutôt qu'en UTF-8.
    let text = match String::from_utf8(bytes) {
        Ok(s) => s,
        Err(e) => e.into_bytes().iter().map(|&b| b as char).collect(),
    };
    let report = match catch_unwind(|| parse_csv(&text)) {
        Ok(Ok(r)) => r,
        _ => return -1,
    };
    let n = report.radars.len() as jint;
    if n == 0 {
        return 0;
    }
    with_engine((), |e| e.set_radars(report.radars));
    n
}

/// Fichier de sauvegarde des radars mobiles signalés. Renvoie le nombre chargé.
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_setStorePath<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
) -> jint {
    let Some(path) = read_string(&mut env, &path) else { return -1 };
    with_engine(-1, |e| e.set_store_path(path) as jint)
}

/// Réglages : mode précis ou zones de danger, durée de vie des radars mobiles
/// (minutes, 0 = permanent), marge de survitesse (km/h).
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_configure<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    precise: jboolean,
    mobile_ttl_min: jint,
    margin_kmh: jint,
) {
    with_engine((), |e| {
        e.config.mode = if precise != JNI_FALSE { AlertMode::Precise } else { AlertMode::DangerZone };
        e.config.mobile_ttl_ms = mobile_ttl_min.max(0) as i64 * 60_000;
        e.config.overspeed_margin_kmh = margin_kmh.clamp(0, 30) as f64;
    });
}

/// Nouvelle position GPS. Les valeurs négatives signifient « inconnu ».
/// Renvoie un JSON : {"gps","speed","limit","danger","text","say":[…],"beep","radars","mobiles"}.
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_update<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    lat: jdouble,
    lon: jdouble,
    speed_mps: jdouble,
    bearing_deg: jdouble,
    accuracy_m: jdouble,
    time_ms: jlong,
) -> jstring {
    let json = with_engine(String::from("{}"), |e| {
        let u = e.update(Fix {
            lat,
            lon,
            speed_mps: opt(speed_mps),
            bearing_deg: opt(bearing_deg),
            accuracy_m: opt(accuracy_m),
            time_ms,
        });
        update_json(&u, e.config.mode, e.radar_count(), e.mobile_count())
    });
    to_jstring(&mut env, &json)
}

/// Signale un radar mobile ici. Renvoie le nombre de radars mobiles actifs.
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_reportMobile<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    lat: jdouble,
    lon: jdouble,
    time_ms: jlong,
) -> jint {
    with_engine(-1, |e| e.report_mobile(lat, lon, time_ms) as jint)
}

/// Supprime le radar mobile le plus proche (dans `radius_m`).
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_removeNearestMobile<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    lat: jdouble,
    lon: jdouble,
    radius_m: jdouble,
) -> jboolean {
    if with_engine(false, |e| e.remove_nearest_mobile(lat, lon, radius_m)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Statistiques : {"radars":n,"mobiles":m,"mode":"..."}
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_stats<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    let json = with_engine(String::from("{}"), |e| {
        let mode = match e.config.mode {
            AlertMode::Precise => "precis",
            AlertMode::DangerZone => "zone",
        };
        format!(
            "{{\"radars\":{},\"mobiles\":{},\"mode\":{}}}",
            e.radar_count(),
            e.mobile_count(),
            json_escape(mode)
        )
    });
    to_jstring(&mut env, &json)
}

/// Oublie l'état du trajet précédent.
#[no_mangle]
pub extern "system" fn Java_fr_radaralerte_RadarCore_resetSession<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) {
    with_engine((), |e| e.reset_session());
}
