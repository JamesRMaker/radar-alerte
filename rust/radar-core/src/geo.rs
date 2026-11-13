//! Calculs géographiques simples sur la sphère terrestre.

pub const EARTH_RADIUS_M: f64 = 6_371_000.0;

/// Distance orthodromique (formule de haversine), en mètres.
pub fn distance_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let (p1, p2) = (lat1.to_radians(), lat2.to_radians());
    let dp = (lat2 - lat1).to_radians();
    let dl = (lon2 - lon1).to_radians();
    let a = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * a.sqrt().min(1.0).asin()
}

/// Cap initial (0° = nord, sens horaire) pour aller du point 1 au point 2.
pub fn bearing_deg(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let (p1, p2) = (lat1.to_radians(), lat2.to_radians());
    let dl = (lon2 - lon1).to_radians();
    let y = dl.sin() * p2.cos();
    let x = p1.cos() * p2.sin() - p1.sin() * p2.cos() * dl.cos();
    (y.atan2(x).to_degrees() + 360.0) % 360.0
}

/// Écart angulaire absolu entre deux caps, dans [0, 180].
pub fn angle_diff(a: f64, b: f64) -> f64 {
    let d = (a - b).rem_euclid(360.0);
    if d > 180.0 { 360.0 - d } else { d }
}

/// Point atteint en partant de (lat, lon) avec un cap et une distance donnés.
pub fn destination(lat: f64, lon: f64, bearing: f64, dist_m: f64) -> (f64, f64) {
    let d = dist_m / EARTH_RADIUS_M;
    let b = bearing.to_radians();
    let p1 = lat.to_radians();
    let l1 = lon.to_radians();
    let p2 = (p1.sin() * d.cos() + p1.cos() * d.sin() * b.cos()).asin();
    let l2 = l1 + (b.sin() * d.sin() * p1.cos()).atan2(d.cos() - p1.sin() * p2.sin());
    (p2.to_degrees(), ((l2.to_degrees() + 540.0) % 360.0) - 180.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paris_lyon() {
        // Notre-Dame de Paris -> Place Bellecour, Lyon : ~392 km
        let d = distance_m(48.8530, 2.3499, 45.7578, 4.8320);
        assert!((d - 392_000.0).abs() < 3_000.0, "{d}");
    }

    #[test]
    fn caps() {
        assert!(bearing_deg(45.0, 2.0, 46.0, 2.0) < 0.5);
        assert!((bearing_deg(45.0, 2.0, 45.0, 3.0) - 90.0).abs() < 1.0);
        assert_eq!(angle_diff(350.0, 10.0), 20.0);
        assert_eq!(angle_diff(10.0, 190.0), 180.0);
    }

    #[test]
    fn aller_retour() {
        let (la, lo) = destination(48.0, 2.0, 37.0, 1500.0);
        assert!((distance_m(48.0, 2.0, la, lo) - 1500.0).abs() < 0.5);
        assert!((bearing_deg(48.0, 2.0, la, lo) - 37.0).abs() < 0.1);
    }
}
