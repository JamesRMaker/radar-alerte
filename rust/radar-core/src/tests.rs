//! Scénarios de conduite simulés.

use crate::engine::*;
use crate::geo::{destination, distance_m};
use crate::radar::{Radar, RadarKind};

const START: (f64, f64) = (47.0, 2.0);

fn radar_at(dist_m: f64, bearing: f64, kind: RadarKind, limit: Option<u16>) -> Radar {
    let (lat, lon) = destination(START.0, START.1, bearing, dist_m);
    Radar { lat, lon, kind, limit_kmh: limit, section_m: None, label: String::new() }
}

/// Roule en ligne droite (cap `bearing`) à `kmh` pendant `seconds`, 1 position/s.
/// Renvoie (secondes, distance au premier radar, événement).
fn drive(engine: &mut Engine, bearing: f64, kmh: f64, seconds: usize, target: (f64, f64)) -> Vec<(usize, f64, Event)> {
    let v = kmh / 3.6;
    let mut out = Vec::new();
    for s in 0..seconds {
        let (lat, lon) = destination(START.0, START.1, bearing, v * s as f64);
        let u = engine.update(Fix {
            lat,
            lon,
            speed_mps: Some(v),
            bearing_deg: Some(bearing),
            accuracy_m: Some(5.0),
            time_ms: 1_000_000 + s as i64 * 1000,
        });
        let d = distance_m(lat, lon, target.0, target.1);
        out.extend(u.events.into_iter().map(|e| (s, d, e)));
    }
    out
}

fn precise() -> Engine {
    Engine::new(Config { mode: AlertMode::Precise, ..Config::default() })
}

#[test]
fn precis_radar_fixe_devant() {
    let mut e = precise();
    let r = radar_at(3000.0, 90.0, RadarKind::Fixe, Some(90));
    let target = (r.lat, r.lon);
    e.set_radars(vec![r]);
    let ev = drive(&mut e, 90.0, 90.0, 160, target);
    let approaches: Vec<_> = ev.iter().filter(|(_, _, e)| matches!(e, Event::Approach { .. })).collect();
    assert_eq!(approaches.len(), 1, "{ev:?}");
    // 90 km/h -> 25 m/s * 30 s = 750 m
    let d = approaches[0].1;
    assert!((725.0..=750.0).contains(&d), "alerte à {d} m");
    assert_eq!(ev.iter().filter(|(_, _, e)| matches!(e, Event::Imminent { .. })).count(), 1);
    // 90 km/h n'est pas au-dessus de 90 + 3 : pas de survitesse
    assert!(!ev.iter().any(|(_, _, e)| matches!(e, Event::Overspeed { .. })));
}

#[test]
fn precis_survitesse() {
    let mut e = precise();
    let r = radar_at(2000.0, 0.0, RadarKind::Fixe, Some(70));
    let target = (r.lat, r.lon);
    e.set_radars(vec![r]);
    let ev = drive(&mut e, 0.0, 100.0, 80, target);
    let over: Vec<_> = ev.iter().filter(|(_, _, e)| matches!(e, Event::Overspeed { limit_kmh: 70, .. })).collect();
    assert!(!over.is_empty());
    // répétée au plus toutes les 10 s
    for w in over.windows(2) {
        assert!(w[1].0 - w[0].0 >= 10);
    }
    // et plus aucune après le radar
    assert!(over.iter().all(|(_, d, _)| *d < 1300.0));
}

#[test]
fn radar_derriere_ou_sur_le_cote_ignore() {
    let mut e = precise();
    let derriere = radar_at(500.0, 270.0, RadarKind::Fixe, Some(90));
    let cote = radar_at(600.0, 0.0, RadarKind::Fixe, Some(90));
    e.set_radars(vec![derriere.clone(), cote]);
    let ev = drive(&mut e, 90.0, 90.0, 60, (derriere.lat, derriere.lon));
    assert!(ev.is_empty(), "{ev:?}");
}

#[test]
fn zone_de_danger_autoroute() {
    let mut e = Engine::default();
    let r = radar_at(6000.0, 45.0, RadarKind::Fixe, Some(130));
    let target = (r.lat, r.lon);
    let noise = r.stable_noise();
    e.set_radars(vec![r]);
    let ev = drive(&mut e, 45.0, 130.0, 300, target);
    let kinds: Vec<_> = ev.iter().map(|(_, _, e)| e.clone()).collect();
    assert_eq!(kinds.first(), Some(&Event::ZoneEnter { limit_kmh: Some(130) }));
    assert_eq!(kinds.last(), Some(&Event::ZoneExit));
    assert!(!kinds.iter().any(|k| matches!(k, Event::Approach { .. } | Event::Imminent { .. })));
    // Entrée entre 40 % et 80 % de 4 km avant le radar, selon le bruit propre au radar
    let enter_d = ev[0].1;
    let expected = 4000.0 * (0.4 + 0.4 * noise);
    assert!((enter_d - expected).abs() < 50.0, "{enter_d} vs {expected}");
    // Longueur totale de la zone ~4 km
    let exit_d = ev.last().unwrap().1;
    assert!((enter_d + exit_d - 4000.0).abs() < 80.0, "{enter_d} + {exit_d}");
}

#[test]
fn zone_en_ville_sans_limite_utilise_la_vitesse() {
    let mut e = Engine::default();
    let r = radar_at(1500.0, 180.0, RadarKind::FeuRouge, None);
    let target = (r.lat, r.lon);
    e.set_radars(vec![r]);
    let ev = drive(&mut e, 180.0, 40.0, 200, target);
    assert_eq!(ev.first().map(|x| &x.2), Some(&Event::ZoneEnter { limit_kmh: None }));
    assert!(ev.first().unwrap().1 <= 400.0);
}

#[test]
fn radar_mobile_signale_puis_expire() {
    let mut e = precise();
    e.config.mobile_ttl_ms = 60_000;
    let (lat, lon) = destination(START.0, START.1, 90.0, 1500.0);
    e.report_mobile(lat, lon, 1_000_000);
    e.report_mobile(lat + 0.0005, lon, 1_000_000); // à ~55 m : fusionné
    assert_eq!(e.mobile_count(), 1);
    let ev = drive(&mut e, 90.0, 90.0, 40, (lat, lon));
    assert!(ev.iter().any(|(_, _, e)| matches!(e, Event::Approach { kind: RadarKind::Mobile, .. })));

    // Une heure plus tard il a expiré
    let mut e2 = precise();
    e2.config.mobile_ttl_ms = 60_000;
    e2.report_mobile(lat, lon, 0);
    let ev = drive(&mut e2, 90.0, 90.0, 40, (lat, lon));
    assert!(ev.is_empty());
    assert_eq!(e2.mobile_count(), 0);
}

#[test]
fn suppression_et_persistance_mobiles() {
    let dir = std::env::temp_dir().join(format!("radar_test_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("mobiles.csv");
    let _ = std::fs::remove_file(&path);
    let mut e = Engine::default();
    e.set_store_path(&path);
    e.report_mobile(47.0, 2.0, 5);
    e.report_mobile(47.1, 2.0, 6);
    let mut e2 = Engine::default();
    assert_eq!(e2.set_store_path(&path), 2);
    assert!(e2.remove_nearest_mobile(47.0005, 2.0, 300.0));
    assert!(!e2.remove_nearest_mobile(48.0, 2.0, 300.0));
    let mut e3 = Engine::default();
    assert_eq!(e3.set_store_path(&path), 1);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn troncon_vitesse_moyenne() {
    let mut e = precise();
    let mut r = radar_at(1000.0, 0.0, RadarKind::Troncon, Some(110));
    r.section_m = Some(3000.0);
    let target = (r.lat, r.lon);
    e.set_radars(vec![r]);
    let ev = drive(&mut e, 0.0, 126.0, 200, target);
    let names: Vec<_> = ev.iter().map(|(_, _, e)| e.clone()).collect();
    assert!(names.iter().any(|e| matches!(e, Event::SectionStart { limit_kmh: Some(110), .. })), "{names:?}");
    assert!(names.iter().any(|e| matches!(e, Event::Overspeed { average: true, .. })));
    let end = names.iter().find_map(|e| match e {
        Event::SectionEnd { average_kmh, .. } => Some(*average_kmh),
        _ => None,
    });
    let avg = end.expect("fin de tronçon");
    assert!((avg - 126.0).abs() < 3.0, "{avg}");
}

#[test]
fn gps_imprecis_pas_d_alerte() {
    let mut e = precise();
    let r = radar_at(300.0, 90.0, RadarKind::Fixe, Some(50));
    e.set_radars(vec![r]);
    let u = e.update(Fix {
        lat: START.0,
        lon: START.1,
        speed_mps: Some(10.0),
        bearing_deg: Some(90.0),
        accuracy_m: Some(250.0),
        time_ms: 0,
    });
    assert!(u.events.is_empty());
    assert!(!u.status.gps_ok);
}

#[test]
fn json_valide() {
    let mut e = Engine::default();
    let r = radar_at(800.0, 90.0, RadarKind::Fixe, Some(50));
    e.set_radars(vec![r]);
    let fix = |s: i64| Fix {
        lat: destination(START.0, START.1, 90.0, s as f64 * 14.0).0,
        lon: destination(START.0, START.1, 90.0, s as f64 * 14.0).1,
        speed_mps: Some(14.0),
        bearing_deg: Some(90.0),
        accuracy_m: Some(4.0),
        time_ms: s * 1000,
    };
    let mut saw_zone = false;
    for s in 0..60 {
        let u = e.update(fix(s));
        let j = crate::output::update_json(&u, e.config.mode, 1, 0);
        assert!(j.starts_with('{') && j.ends_with('}'));
        if j.contains("\"say\":[\"Zone de danger") {
            saw_zone = true;
            assert!(j.contains("\"beep\":true"));
            assert!(j.contains("\"limit\":50"));
        }
    }
    assert!(saw_zone);
}

#[test]
fn grand_volume_rapide() {
    // 4 000 radars répartis sur la France : chaque mise à jour doit rester très rapide.
    let mut radars = Vec::new();
    for i in 0..4000 {
        let lat = 42.5 + (i % 80) as f64 * 0.11;
        let lon = -4.5 + (i / 80) as f64 * 0.25;
        radars.push(Radar { lat, lon, kind: RadarKind::Fixe, limit_kmh: Some(80), section_m: None, label: String::new() });
    }
    let mut e = Engine::default();
    e.set_radars(radars);
    let t = std::time::Instant::now();
    for s in 0..3600 {
        e.update(Fix {
            lat: 45.0 + s as f64 * 0.0003,
            lon: 2.0,
            speed_mps: Some(30.0),
            bearing_deg: Some(0.0),
            accuracy_m: Some(5.0),
            time_ms: s * 1000,
        });
    }
    assert!(t.elapsed().as_millis() < 2000, "{:?}", t.elapsed());
}
