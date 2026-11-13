//! Rejoue un trajet fictif et affiche ce que l'appli dirait.
//!
//!     cargo run --example simulation              # mode zone de danger
//!     cargo run --example simulation -- precis    # mode précis
//!     cargo run --example simulation -- precis radars.csv 48.85 2.35 90 130
//!         (fichier de radars, départ lat lon, cap en degrés, vitesse km/h)

use radar_core::geo::destination;
use radar_core::output::{message, status_text};
use radar_core::{parse_csv, AlertMode, Config, Engine, Fix, Radar, RadarKind};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = if args.first().map(|s| s.starts_with("prec")).unwrap_or(false) {
        AlertMode::Precise
    } else {
        AlertMode::DangerZone
    };
    let mut engine = Engine::new(Config { mode, ..Config::default() });

    let (start, bearing, kmh) = if args.len() >= 6 {
        let text = std::fs::read_to_string(&args[1]).expect("lecture du fichier");
        let report = parse_csv(&text).expect("format CSV");
        println!("{} radars chargés ({} lignes ignorées)", report.radars.len(), report.skipped_lines);
        engine.set_radars(report.radars);
        let p = |i: usize| args[i].parse::<f64>().expect("nombre");
        ((p(2), p(3)), p(4), p(5))
    } else {
        let start = (46.0, 3.0);
        let at = |d: f64, kind, limit, section: Option<f64>| {
            let (lat, lon) = destination(start.0, start.1, 0.0, d);
            Radar { lat, lon, kind, limit_kmh: limit, section_m: section, label: String::new() }
        };
        engine.set_radars(vec![
            at(5_000.0, RadarKind::Fixe, Some(110), None),
            at(12_000.0, RadarKind::Troncon, Some(110), Some(4_000.0)),
            at(22_000.0, RadarKind::Discriminant, Some(90), None),
        ]);
        let (lat, lon) = destination(start.0, start.1, 0.0, 19_000.0);
        engine.report_mobile(lat, lon, 0);
        (start, 0.0, 120.0)
    };

    println!("Mode : {:?} — {} km/h, cap {}°\n", mode, kmh, bearing);
    let v = kmh / 3.6;
    let mut last_text = String::new();
    for s in 0..(30_000.0 / v) as i64 {
        let (lat, lon) = destination(start.0, start.1, bearing, v * s as f64);
        let u = engine.update(Fix {
            lat,
            lon,
            speed_mps: Some(v),
            bearing_deg: Some(bearing),
            accuracy_m: Some(5.0),
            time_ms: s * 1000,
        });
        let km = v * s as f64 / 1000.0;
        for e in &u.events {
            println!("{:>5.2} km  🔊 {}", km, message(e, mode));
        }
        let text = status_text(&u.status);
        if text != last_text {
            println!("{:>5.2} km     [{}]", km, text);
            last_text = text;
        }
    }
}
