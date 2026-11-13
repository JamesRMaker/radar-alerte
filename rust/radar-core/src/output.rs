//! Phrases à prononcer et sérialisation JSON pour l'interface Android.

use crate::engine::{AlertMode, Event, Status, Update};
use crate::radar::RadarKind;

/// Distance arrondie, lisible à voix haute : « 650 mètres », « 1,5 kilomètre ».
pub fn spoken_distance(m: f64) -> String {
    if m < 1000.0 {
        let r = ((m / 50.0).round() * 50.0).max(50.0);
        format!("{} mètres", r as i64)
    } else {
        let km = (m / 100.0).round() / 10.0;
        let s = if km.fract() == 0.0 { format!("{}", km as i64) } else { format!("{km:.1}").replace('.', ",") };
        if km < 2.0 { format!("{s} kilomètre") } else { format!("{s} kilomètres") }
    }
}

fn short_distance(m: f64) -> String {
    if m < 1000.0 {
        format!("{} m", ((m / 10.0).round() * 10.0) as i64)
    } else {
        format!("{:.1} km", m / 1000.0).replace('.', ",")
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// Phrase à prononcer pour un événement.
pub fn message(e: &Event, mode: AlertMode) -> String {
    match e {
        Event::Approach { kind, distance_m, limit_kmh } => {
            let mut s = format!("{} dans {}", capitalize(kind.spoken()), spoken_distance(*distance_m));
            if let Some(l) = limit_kmh {
                if !matches!(kind, RadarKind::FeuRouge | RadarKind::PassageNiveau) {
                    s += &format!(", limité à {l}");
                }
            }
            s
        }
        Event::Imminent { kind } => match kind {
            RadarKind::FeuRouge => "Feu rouge contrôlé, attention".into(),
            _ => "Radar".into(),
        },
        Event::ZoneEnter { limit_kmh } => match limit_kmh {
            Some(l) => format!("Zone de danger. Vitesse limitée à {l}"),
            None => "Zone de danger".into(),
        },
        Event::ZoneLimit { limit_kmh } => format!("Vitesse limitée à {limit_kmh}"),
        Event::ZoneExit => "Fin de zone de danger".into(),
        Event::Overspeed { limit_kmh, average } => {
            if *average && mode == AlertMode::Precise {
                format!("Moyenne trop élevée, limitée à {limit_kmh}")
            } else {
                format!("Attention, vitesse limitée à {limit_kmh}")
            }
        }
        Event::SectionStart { limit_kmh, length_m } => match limit_kmh {
            Some(l) => format!("Début de tronçon sur {}, moyenne limitée à {l}", spoken_distance(*length_m)),
            None => format!("Début de tronçon sur {}", spoken_distance(*length_m)),
        },
        Event::SectionEnd { average_kmh, .. } => {
            format!("Fin de tronçon. Votre moyenne : {}", average_kmh.round() as i64)
        }
    }
}

/// Texte d'état affiché à l'écran et dans la notification.
pub fn status_text(st: &Status) -> String {
    if !st.gps_ok {
        return "Signal GPS imprécis…".into();
    }
    if let (Some(avg), Some(rem)) = (st.section_avg_kmh, st.section_remaining_m) {
        return format!("Tronçon : moyenne {} km/h · reste {}", avg.round() as i64, short_distance(rem));
    }
    if let Some((kind, d, limit)) = st.nearest {
        let mut s = format!("{} à {}", capitalize(kind.spoken()), short_distance(d));
        if let Some(l) = limit {
            s += &format!(" · {l} km/h");
        }
        return s;
    }
    if st.danger_zone {
        return match st.zone_remaining_m {
            Some(m) => format!("Zone de danger · fin dans {}", short_distance(m)),
            None => "Zone de danger".into(),
        };
    }
    "Aucun radar à l'approche".into()
}

pub fn json_escape(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

/// JSON transmis à Kotlin après chaque position.
pub fn update_json(u: &Update, mode: AlertMode, radars: usize, mobiles: usize) -> String {
    let say: Vec<String> = u.events.iter().map(|e| json_escape(&message(e, mode))).collect();
    let beep = u.events.iter().any(|e| {
        matches!(
            e,
            Event::Approach { .. }
                | Event::ZoneEnter { .. }
                | Event::ZoneLimit { .. }
                | Event::Overspeed { .. }
                | Event::Imminent { .. }
        )
    });
    let opt = |v: Option<f64>| v.map_or("null".to_string(), |x| format!("{}", x.round() as i64));
    // Pour le widget : quoi (titre) et à quelle distance.
    let st = &u.status;
    let (title, dist) = if let (Some(_), Some(rem)) = (st.section_avg_kmh, st.section_remaining_m) {
        ("Radar tronçon · fin dans".to_string(), Some(rem))
    } else if let Some((kind, d, _)) = st.nearest {
        (capitalize(kind.spoken()), Some(d))
    } else if st.danger_zone {
        ("Zone de danger · fin dans".to_string(), st.zone_remaining_m)
    } else {
        (String::new(), None)
    };
    format!(
        "{{\"gps\":{},\"speed\":{},\"limit\":{},\"danger\":{},\"text\":{},\"title\":{},\"dist\":{},\"say\":[{}],\"beep\":{},\"radars\":{},\"mobiles\":{}}}",
        u.status.gps_ok,
        opt(u.status.speed_kmh),
        opt(u.status.limit_kmh.map(|l| l as f64)),
        u.status.danger_zone || u.status.nearest.is_some(),
        json_escape(&status_text(&u.status)),
        json_escape(&title),
        opt(dist),
        say.join(","),
        beep,
        radars,
        mobiles
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distances() {
        assert_eq!(spoken_distance(640.0), "650 mètres");
        assert_eq!(spoken_distance(20.0), "50 mètres");
        assert_eq!(spoken_distance(1000.0), "1 kilomètre");
        assert_eq!(spoken_distance(1530.0), "1,5 kilomètre");
        assert_eq!(spoken_distance(3960.0), "4 kilomètres");
    }

    #[test]
    fn echappement() {
        assert_eq!(json_escape("a\"b\\c\n"), "\"a\\\"b\\\\c\\n\"");
    }
}
