//! Moteur d'alerte : reçoit les positions GPS une par une et décide quand prévenir.
//!
//! Deux modes :
//! - [`AlertMode::DangerZone`] (par défaut) : annonce une « zone de danger » dont la
//!   longueur dépend du type de route (500 m en ville, 2 km sur route, 4 km sur
//!   autoroute), sans donner l'emplacement exact ni le type du radar. C'est le
//!   fonctionnement imposé en France aux assistants d'aide à la conduite.
//! - [`AlertMode::Precise`] : annonce le type de radar et la distance (usage hors de France).

use std::collections::HashMap;
use std::path::PathBuf;

use crate::geo::{angle_diff, bearing_deg, distance_m};
use crate::radar::{Radar, RadarKind};

/// Taille d'une case de l'index spatial, en degrés (~5,5 km en latitude).
const CELL_DEG: f64 = 0.05;
/// Rayon de recherche autour de la voiture (doit couvrir la plus grande distance d'alerte).
const SEARCH_RADIUS_M: f64 = 4_500.0;
/// Deux signalements mobiles à moins de cette distance sont considérés identiques.
const MOBILE_MERGE_M: f64 = 150.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AlertMode {
    DangerZone,
    Precise,
}

#[derive(Clone, Debug)]
pub struct Config {
    pub mode: AlertMode,
    /// Durée de vie d'un radar mobile signalé, en ms (0 = permanent).
    pub mobile_ttl_ms: i64,
    /// Marge au-dessus de la limite avant l'alerte de survitesse (km/h, vitesse GPS).
    pub overspeed_margin_kmh: f64,
    /// Au-delà de cette imprécision GPS (m), aucune alerte n'est calculée.
    pub max_accuracy_m: f64,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            mode: AlertMode::DangerZone,
            mobile_ttl_ms: 3 * 3600 * 1000,
            overspeed_margin_kmh: 3.0,
            max_accuracy_m: 80.0,
        }
    }
}

/// Une position GPS. Les champs optionnels absents valent `None`.
#[derive(Clone, Copy, Debug)]
pub struct Fix {
    pub lat: f64,
    pub lon: f64,
    pub speed_mps: Option<f64>,
    pub bearing_deg: Option<f64>,
    pub accuracy_m: Option<f64>,
    pub time_ms: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum Key {
    Fixed(u32),
    Mobile(u64),
}

#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    /// Mode précis : radar devant à `distance_m`.
    Approach { kind: RadarKind, distance_m: f64, limit_kmh: Option<u16> },
    /// Mode précis : radar tout proche.
    Imminent { kind: RadarKind },
    /// Mode zone : entrée dans une zone de danger.
    ZoneEnter { limit_kmh: Option<u16> },
    /// Mode zone : nouvelle limite dans une zone déjà annoncée.
    ZoneLimit { limit_kmh: u16 },
    /// Mode zone : sortie de zone.
    ZoneExit,
    /// Vitesse au-dessus de la limite (instantanée ou moyenne sur tronçon).
    Overspeed { limit_kmh: u16, average: bool },
    /// Mode précis : début d'un tronçon à vitesse moyenne.
    SectionStart { limit_kmh: Option<u16>, length_m: f64 },
    /// Mode précis : fin du tronçon.
    SectionEnd { average_kmh: f64, limit_kmh: Option<u16> },
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Status {
    pub gps_ok: bool,
    pub speed_kmh: Option<f64>,
    pub danger_zone: bool,
    /// Mode précis : radar le plus proche devant (type, distance, limite).
    pub nearest: Option<(RadarKind, f64, Option<u16>)>,
    /// Limite connue applicable (zone ou radar en approche).
    pub limit_kmh: Option<u16>,
    pub section_avg_kmh: Option<f64>,
    pub section_remaining_m: Option<f64>,
}

#[derive(Clone, Debug, Default)]
pub struct Update {
    pub events: Vec<Event>,
    pub status: Status,
}

#[derive(Debug)]
struct Track {
    alert_m: f64,
    zone_after_m: f64,
    alerted: bool,
    imminent_done: bool,
    min_dist: f64,
    passed: bool,
}

#[derive(Debug)]
struct SectionRun {
    key: Key,
    limit: Option<u16>,
    length_m: f64,
    travelled_m: f64,
    start_ms: i64,
}

#[derive(Clone, Debug)]
struct MobileReport {
    id: u64,
    lat: f64,
    lon: f64,
    time_ms: i64,
}

pub struct Engine {
    pub config: Config,
    radars: Vec<Radar>,
    grid: HashMap<(i32, i32), Vec<u32>>,
    mobiles: Vec<MobileReport>,
    next_mobile_id: u64,
    tracks: HashMap<Key, Track>,
    section: Option<SectionRun>,
    last_fix: Option<Fix>,
    last_heading: Option<f64>,
    last_overspeed_ms: i64,
    overspeeding: bool,
    zone_limit: Option<u16>,
    zone_active: bool,
    store_path: Option<PathBuf>,
}

impl Default for Engine {
    fn default() -> Self {
        Self::new(Config::default())
    }
}

fn cell_of(lat: f64, lon: f64) -> (i32, i32) {
    ((lat / CELL_DEG).floor() as i32, (lon / CELL_DEG).floor() as i32)
}

/// Tolérance angulaire pour considérer qu'un radar est « devant » : large de près,
/// étroite de loin.
fn ahead_tolerance(d: f64) -> f64 {
    if d > 1000.0 {
        25.0
    } else if d > 300.0 {
        35.0
    } else if d > 100.0 {
        50.0
    } else {
        75.0
    }
}

/// Longueur de zone de danger selon le type de route, estimé par la limite du
/// radar (ou à défaut la vitesse actuelle).
pub fn zone_length_m(limit_kmh: Option<u16>, speed_mps: f64) -> f64 {
    let kmh = limit_kmh.map(|l| l as f64).unwrap_or(speed_mps * 3.6);
    if kmh <= 50.0 {
        500.0
    } else if kmh >= 110.0 {
        4000.0
    } else {
        2000.0
    }
}

impl Engine {
    pub fn new(config: Config) -> Self {
        Engine {
            config,
            radars: Vec::new(),
            grid: HashMap::new(),
            mobiles: Vec::new(),
            next_mobile_id: 1,
            tracks: HashMap::new(),
            section: None,
            last_fix: None,
            last_heading: None,
            last_overspeed_ms: i64::MIN / 2,
            overspeeding: false,
            zone_limit: None,
            zone_active: false,
            store_path: None,
        }
    }

    // ---------------------------------------------------------------- données

    /// Remplace la base de radars fixes.
    pub fn set_radars(&mut self, radars: Vec<Radar>) {
        self.grid.clear();
        for (i, r) in radars.iter().enumerate() {
            self.grid.entry(cell_of(r.lat, r.lon)).or_default().push(i as u32);
        }
        self.radars = radars;
        self.reset_session();
    }

    pub fn radar_count(&self) -> usize {
        self.radars.len()
    }

    pub fn mobile_count(&self) -> usize {
        self.mobiles.len()
    }

    /// Oublie l'état du trajet en cours (à appeler au démarrage de la surveillance).
    pub fn reset_session(&mut self) {
        self.tracks.clear();
        self.section = None;
        self.last_fix = None;
        self.last_heading = None;
        self.zone_active = false;
        self.last_overspeed_ms = i64::MIN / 2;
        self.overspeeding = false;
        self.zone_limit = None;
    }

    /// Fichier où sont conservés les radars mobiles signalés. Charge son contenu.
    pub fn set_store_path(&mut self, path: impl Into<PathBuf>) -> usize {
        let path = path.into();
        self.mobiles.clear();
        if let Ok(text) = std::fs::read_to_string(&path) {
            for line in text.lines() {
                let p: Vec<&str> = line.split(';').collect();
                if p.len() < 3 {
                    continue;
                }
                if let (Ok(lat), Ok(lon), Ok(t)) = (p[0].parse(), p[1].parse(), p[2].parse()) {
                    let id = self.next_mobile_id;
                    self.next_mobile_id += 1;
                    self.mobiles.push(MobileReport { id, lat, lon, time_ms: t });
                }
            }
        }
        self.store_path = Some(path);
        self.mobiles.len()
    }

    fn save_mobiles(&self) {
        if let Some(path) = &self.store_path {
            let text: String = self
                .mobiles
                .iter()
                .map(|m| format!("{};{};{}\n", m.lat, m.lon, m.time_ms))
                .collect();
            let tmp = path.with_extension("tmp");
            if std::fs::write(&tmp, text).is_ok() {
                let _ = std::fs::rename(&tmp, path);
            }
        }
    }

    /// Signale un radar mobile à cette position. Renvoie le nombre de radars mobiles actifs.
    pub fn report_mobile(&mut self, lat: f64, lon: f64, time_ms: i64) -> usize {
        if let Some(m) = self
            .mobiles
            .iter_mut()
            .find(|m| distance_m(m.lat, m.lon, lat, lon) < MOBILE_MERGE_M)
        {
            m.time_ms = time_ms;
        } else {
            let id = self.next_mobile_id;
            self.next_mobile_id += 1;
            self.mobiles.push(MobileReport { id, lat, lon, time_ms });
        }
        self.save_mobiles();
        self.mobiles.len()
    }

    /// Supprime le radar mobile le plus proche dans `radius_m`. Renvoie `true` si supprimé.
    pub fn remove_nearest_mobile(&mut self, lat: f64, lon: f64, radius_m: f64) -> bool {
        let best = self
            .mobiles
            .iter()
            .enumerate()
            .map(|(i, m)| (i, distance_m(m.lat, m.lon, lat, lon)))
            .filter(|(_, d)| *d <= radius_m)
            .min_by(|a, b| a.1.total_cmp(&b.1));
        match best {
            Some((i, _)) => {
                let m = self.mobiles.remove(i);
                self.tracks.remove(&Key::Mobile(m.id));
                self.save_mobiles();
                true
            }
            None => false,
        }
    }

    fn purge_mobiles(&mut self, now_ms: i64) {
        let ttl = self.config.mobile_ttl_ms;
        if ttl <= 0 {
            return;
        }
        let before = self.mobiles.len();
        let expired: Vec<u64> = self
            .mobiles
            .iter()
            .filter(|m| now_ms - m.time_ms > ttl)
            .map(|m| m.id)
            .collect();
        if expired.is_empty() {
            return;
        }
        self.mobiles.retain(|m| !expired.contains(&m.id));
        for id in expired {
            self.tracks.remove(&Key::Mobile(id));
        }
        if self.mobiles.len() != before {
            self.save_mobiles();
        }
    }

    fn radar(&self, key: Key) -> Option<Radar> {
        match key {
            Key::Fixed(i) => self.radars.get(i as usize).cloned(),
            Key::Mobile(id) => self.mobiles.iter().find(|m| m.id == id).map(|m| Radar {
                lat: m.lat,
                lon: m.lon,
                kind: RadarKind::Mobile,
                limit_kmh: None,
                section_m: None,
                label: String::new(),
            }),
        }
    }

    fn candidates(&self, lat: f64, lon: f64) -> Vec<Key> {
        let (cy, cx) = cell_of(lat, lon);
        let cell_m = CELL_DEG * 111_320.0;
        let dy = (SEARCH_RADIUS_M / cell_m).ceil() as i32;
        let dx = (SEARCH_RADIUS_M / (cell_m * lat.to_radians().cos().max(0.1))).ceil() as i32;
        let mut out = Vec::new();
        for y in cy - dy..=cy + dy {
            for x in cx - dx..=cx + dx {
                if let Some(v) = self.grid.get(&(y, x)) {
                    out.extend(v.iter().map(|i| Key::Fixed(*i)));
                }
            }
        }
        out.extend(
            self.mobiles
                .iter()
                .filter(|m| distance_m(m.lat, m.lon, lat, lon) <= SEARCH_RADIUS_M)
                .map(|m| Key::Mobile(m.id)),
        );
        out
    }

    /// Distance d'alerte avant le radar, et longueur de zone restante après lui.
    fn alert_distances(&self, r: &Radar, speed_mps: f64) -> (f64, f64) {
        match self.config.mode {
            // ~30 secondes avant le radar, entre 300 m et 1,2 km.
            AlertMode::Precise => ((speed_mps * 30.0).clamp(300.0, 1200.0), 0.0),
            AlertMode::DangerZone => {
                let len = zone_length_m(r.limit_kmh, speed_mps);
                // Le radar n'est pas au centre exact de la zone : entre 40 % et 80 % de sa longueur.
                let before = len * (0.4 + 0.4 * r.stable_noise());
                (before, (len - before).max(150.0))
            }
        }
    }

    // ---------------------------------------------------------------- trajet

    /// Traite une nouvelle position GPS.
    pub fn update(&mut self, fix: Fix) -> Update {
        let mut events = Vec::new();
        let now = fix.time_ms;
        self.purge_mobiles(now);

        let prev = self.last_fix;
        let step = prev.and_then(|p| {
            let dt = (now - p.time_ms) as f64 / 1000.0;
            (dt > 0.0 && dt < 30.0).then(|| (distance_m(p.lat, p.lon, fix.lat, fix.lon), dt, p))
        });
        let speed = fix
            .speed_mps
            .filter(|s| *s >= 0.0)
            .or_else(|| step.and_then(|(m, dt, _)| (dt >= 0.5).then_some(m / dt)));
        let speed_v = speed.unwrap_or(0.0);
        let heading = fix
            .bearing_deg
            .filter(|b| *b >= 0.0 && speed_v >= 2.0)
            .or_else(|| step.and_then(|(m, _, p)| (m >= 10.0).then(|| bearing_deg(p.lat, p.lon, fix.lat, fix.lon))))
            .or(self.last_heading);

        let gps_ok = fix.accuracy_m.is_none_or(|a| a <= self.config.max_accuracy_m);
        self.last_fix = Some(fix);
        self.last_heading = heading;

        // Distance parcourue sur un tronçon en cours.
        if let (Some(sec), Some((m, _, _))) = (self.section.as_mut(), step) {
            sec.travelled_m += m;
        }

        if gps_ok {
            self.detect(&fix, heading, speed_v, &mut events);
        }
        let section_speed = self.handle_section(now, &mut events);

        // Zone de danger globale (les zones qui se chevauchent fusionnent).
        if self.config.mode == AlertMode::DangerZone {
            let in_zone = self.tracks.values().any(|t| t.alerted) || self.section.is_some();
            let limit = self.active_limit(false);
            if in_zone && !self.zone_active {
                events.insert(0, Event::ZoneEnter { limit_kmh: limit });
            } else if in_zone && limit.is_some() && limit != self.zone_limit && self.section.is_none() {
                events.insert(0, Event::ZoneLimit { limit_kmh: limit.unwrap() });
            } else if !in_zone && self.zone_active {
                events.push(Event::ZoneExit);
            }
            self.zone_limit = if in_zone { limit.or(self.zone_limit) } else { None };
            self.zone_active = in_zone;
        }

        // Survitesse : moyenne sur tronçon en priorité, sinon vitesse instantanée près d'un radar.
        let speed_kmh = speed.map(|s| s * 3.6);
        let margin = self.config.overspeed_margin_kmh;
        let instant = match (self.active_limit(true), speed_kmh) {
            (Some(l), Some(v)) => Some((l, v > l as f64 + margin, false)),
            _ => None,
        };
        let check = section_speed
            .filter(|(_, over)| *over)
            .map(|(l, over)| (l, over, true))
            .or(instant);
        match check {
            Some((limit, true, average)) => {
                // La limite vient d'être annoncée : inutile de la répéter tout de suite.
                let just_announced = events.iter().any(|e| {
                    matches!(
                        e,
                        Event::ZoneEnter { limit_kmh: Some(_) }
                            | Event::ZoneLimit { .. }
                            | Event::Approach { limit_kmh: Some(_), .. }
                            | Event::SectionStart { limit_kmh: Some(_), .. }
                    )
                });
                let gap = if self.overspeeding { 30_000 } else { 5_000 };
                self.overspeeding = true;
                if just_announced {
                    self.last_overspeed_ms = now;
                } else if now - self.last_overspeed_ms >= gap {
                    self.last_overspeed_ms = now;
                    events.push(Event::Overspeed { limit_kmh: limit, average });
                }
            }
            _ => self.overspeeding = false,
        }

        let status = self.status(&fix, heading, gps_ok, speed_kmh);
        Update { events, status }
    }

    fn detect(&mut self, fix: &Fix, heading: Option<f64>, speed_v: f64, events: &mut Vec<Event>) {
        // 1. Nouveaux radars entrant dans la distance d'alerte, devant nous.
        for key in self.candidates(fix.lat, fix.lon) {
            if self.tracks.contains_key(&key) {
                continue;
            }
            let Some(r) = self.radar(key) else { continue };
            let d = distance_m(fix.lat, fix.lon, r.lat, r.lon);
            let ahead = heading
                .map(|h| angle_diff(h, bearing_deg(fix.lat, fix.lon, r.lat, r.lon)) <= ahead_tolerance(d))
                .unwrap_or(false);
            let (alert_m, zone_after_m) = self.alert_distances(&r, speed_v);
            if ahead && d <= alert_m {
                self.tracks.insert(
                    key,
                    Track { alert_m, zone_after_m, alerted: false, imminent_done: false, min_dist: d, passed: false },
                );
            }
        }

        // 2. Suivi des radars en approche.
        let keys: Vec<Key> = self.tracks.keys().copied().collect();
        let mut new_approaches: Vec<(f64, Event)> = Vec::new();
        for key in keys {
            let Some(r) = self.radar(key) else {
                self.tracks.remove(&key);
                continue;
            };
            let d = distance_m(fix.lat, fix.lon, r.lat, r.lon);
            let ahead = heading
                .map(|h| angle_diff(h, bearing_deg(fix.lat, fix.lon, r.lat, r.lon)) <= ahead_tolerance(d))
                .unwrap_or(false);
            let mode = self.config.mode;
            let section_here = self.section.as_ref().is_some_and(|s| s.key == key);
            let mut just_passed = false;
            let mut remove = false;
            {
                let t = self.tracks.get_mut(&key).unwrap();
                t.min_dist = t.min_dist.min(d);
                if !t.alerted {
                    t.alerted = true;
                    if mode == AlertMode::Precise {
                        new_approaches.push((
                            d,
                            Event::Approach { kind: r.kind, distance_m: d, limit_kmh: r.limit_kmh },
                        ));
                    }
                }
                if !t.passed && !ahead && d > t.min_dist + 25.0 {
                    t.passed = true;
                    just_passed = true;
                }
                let imminent_m = (speed_v * 8.0).clamp(80.0, 250.0);
                if mode == AlertMode::Precise && !t.passed && !t.imminent_done && ahead && d <= imminent_m {
                    t.imminent_done = true;
                    events.push(Event::Imminent { kind: r.kind });
                }
                if t.passed {
                    let limit = if mode == AlertMode::Precise { 150.0 } else { t.zone_after_m };
                    remove = d > limit && !section_here;
                } else if !ahead && d > t.alert_m + 500.0 {
                    // On a quitté la route menant au radar.
                    remove = true;
                }
            }
            if just_passed && r.kind == RadarKind::Troncon && self.section.is_none() {
                if let Some(len) = r.section_m {
                    self.section = Some(SectionRun {
                        key,
                        limit: r.limit_kmh,
                        length_m: len,
                        travelled_m: d,
                        start_ms: fix.time_ms,
                    });
                    if mode == AlertMode::Precise {
                        events.push(Event::SectionStart { limit_kmh: r.limit_kmh, length_m: len });
                    }
                    remove = false;
                }
            }
            if remove {
                self.tracks.remove(&key);
            }
        }
        // Le plus proche d'abord.
        new_approaches.sort_by(|a, b| a.0.total_cmp(&b.0));
        events.extend(new_approaches.into_iter().map(|(_, e)| e));
    }

    /// Fait avancer le tronçon en cours. Renvoie (limite, moyenne trop élevée ?).
    fn handle_section(&mut self, now: i64, events: &mut Vec<Event>) -> Option<(u16, bool)> {
        let sec = self.section.as_ref()?;
        let elapsed_s = (now - sec.start_ms) as f64 / 1000.0;
        let avg_kmh = if elapsed_s > 0.0 { sec.travelled_m / elapsed_s * 3.6 } else { 0.0 };
        if sec.travelled_m >= sec.length_m || elapsed_s > 3600.0 {
            let (key, limit) = (sec.key, sec.limit);
            self.section = None;
            self.tracks.remove(&key);
            if self.config.mode == AlertMode::Precise {
                events.push(Event::SectionEnd { average_kmh: avg_kmh, limit_kmh: limit });
            }
            return None;
        }
        // On laisse 20 s pour que la moyenne ait un sens.
        let limit = sec.limit?;
        Some((limit, elapsed_s >= 20.0 && avg_kmh > limit as f64 + self.config.overspeed_margin_kmh))
    }

    /// Plus petite limite parmi les radars concernés.
    /// `for_overspeed` : en mode précis, on ignore les radars déjà dépassés.
    fn active_limit(&self, for_overspeed: bool) -> Option<u16> {
        let precise = self.config.mode == AlertMode::Precise;
        self.tracks
            .iter()
            .filter(|(_, t)| t.alerted && !(for_overspeed && precise && t.passed))
            .filter(|(k, _)| !(for_overspeed && self.section.as_ref().is_some_and(|s| s.key == **k)))
            .filter_map(|(k, _)| self.radar(*k).and_then(|r| r.limit_kmh))
            .min()
    }

    fn status(&self, fix: &Fix, _heading: Option<f64>, gps_ok: bool, speed_kmh: Option<f64>) -> Status {
        let mut st = Status { gps_ok, speed_kmh, danger_zone: self.zone_active, ..Default::default() };
        st.limit_kmh = self.active_limit(false).or_else(|| self.section.as_ref().and_then(|s| s.limit));
        if self.config.mode == AlertMode::Precise {
            st.nearest = self
                .tracks
                .iter()
                .filter(|(_, t)| !t.passed)
                .filter_map(|(k, _)| self.radar(*k))
                .map(|r| (r.kind, distance_m(fix.lat, fix.lon, r.lat, r.lon), r.limit_kmh))
                .min_by(|a, b| a.1.total_cmp(&b.1));
            if let Some(sec) = &self.section {
                let elapsed_s = (fix.time_ms - sec.start_ms) as f64 / 1000.0;
                if elapsed_s > 0.0 {
                    st.section_avg_kmh = Some(sec.travelled_m / elapsed_s * 3.6);
                }
                st.section_remaining_m = Some((sec.length_m - sec.travelled_m).max(0.0));
            }
        }
        st
    }
}
