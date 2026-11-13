//! Types de radars et lecture des fichiers CSV (data.gouv.fr, export OpenStreetMap…).
//!
//! Le parseur se repère aux noms de colonnes plutôt qu'à leur position, pour
//! accepter les différents formats publiés au fil des années :
//! - « Liste des radars fixes en France » (Ministère de l'Intérieur, codes ETF, ETVM…)
//! - « Radars automatiques » (format historique : latitude, longitude, type,
//!   vitesse_vehicules_legers_kmh, longueur_troncon_km…)
//! - export Overpass d'OpenStreetMap (`@lat`, `@lon`, `maxspeed`)

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum RadarKind {
    /// Radar fixe classique (ETF)
    Fixe,
    /// Radar discriminant VL / poids lourds (ETD)
    Discriminant,
    /// Radar « tourelle » nouvelle génération (ETT)
    Tourelle,
    /// Radar urbain (ETU)
    Urbain,
    /// Radar tronçon, vitesse moyenne (ETVM)
    Troncon,
    /// Radar feu rouge (ETFR)
    FeuRouge,
    /// Radar passage à niveau (ETPN)
    PassageNiveau,
    /// Radar mobile signalé par l'utilisateur
    Mobile,
}

impl RadarKind {
    /// Reconnaît un type de radar à partir du texte de la colonne « type ».
    pub fn from_label(s: &str) -> RadarKind {
        let n = normalize(s);
        match n.as_str() {
            "etf" => return RadarKind::Fixe,
            "etd" => return RadarKind::Discriminant,
            "ett" => return RadarKind::Tourelle,
            "etu" => return RadarKind::Urbain,
            "etvm" => return RadarKind::Troncon,
            "etfr" => return RadarKind::FeuRouge,
            "etpn" => return RadarKind::PassageNiveau,
            _ => {}
        }
        if n.contains("troncon") || n.contains("moyenne") || n.contains("etvm") {
            RadarKind::Troncon
        } else if n.contains("feu") || n.contains("etfr") {
            RadarKind::FeuRouge
        } else if n.contains("passage") || n.contains("etpn") {
            RadarKind::PassageNiveau
        } else if n.contains("discrimin") {
            RadarKind::Discriminant
        } else if n.contains("tourelle") {
            RadarKind::Tourelle
        } else if n.contains("urbain") {
            RadarKind::Urbain
        } else if n.contains("mobile") {
            RadarKind::Mobile
        } else {
            RadarKind::Fixe
        }
    }

    /// Libellé parlé, ex. « radar feu rouge ».
    pub fn spoken(self) -> &'static str {
        match self {
            // Discriminant, tourelle, urbain… sont tous des radars fixes qui flashent : un seul libellé.
            RadarKind::Fixe | RadarKind::Discriminant | RadarKind::Tourelle | RadarKind::Urbain => "radar fixe",
            RadarKind::Troncon => "radar tronçon",
            RadarKind::FeuRouge => "radar feu rouge",
            RadarKind::PassageNiveau => "radar passage à niveau",
            RadarKind::Mobile => "radar mobile",
        }
    }

    pub fn code(self) -> &'static str {
        match self {
            RadarKind::Fixe => "fixe",
            RadarKind::Discriminant => "discriminant",
            RadarKind::Tourelle => "tourelle",
            RadarKind::Urbain => "urbain",
            RadarKind::Troncon => "troncon",
            RadarKind::FeuRouge => "feu_rouge",
            RadarKind::PassageNiveau => "passage_niveau",
            RadarKind::Mobile => "mobile",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Radar {
    pub lat: f64,
    pub lon: f64,
    pub kind: RadarKind,
    /// Vitesse limite pour les véhicules légers, si connue.
    pub limit_kmh: Option<u16>,
    /// Longueur du tronçon (radars vitesse moyenne), si connue.
    pub section_m: Option<f64>,
    /// Route / lieu, pour l'affichage (peut être vide).
    pub label: String,
}

impl Radar {
    /// Nombre pseudo-aléatoire stable dans [0, 1) propre à ce radar
    /// (sert à ne pas centrer exactement la zone de danger sur le radar).
    pub fn stable_noise(&self) -> f64 {
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for b in self.lat.to_bits().to_le_bytes().iter().chain(self.lon.to_bits().to_le_bytes().iter()) {
            h ^= *b as u64;
            h = h.wrapping_mul(0x0000_0100_0000_01b3);
        }
        (h >> 11) as f64 / (1u64 << 53) as f64
    }
}

/// Minuscules, sans accents ni ponctuation (`_` à la place), sans `@`.
pub fn normalize(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.trim().trim_start_matches('\u{feff}').chars() {
        let c = match c {
            'à' | 'â' | 'ä' | 'À' | 'Â' | 'Ä' => 'a',
            'é' | 'è' | 'ê' | 'ë' | 'É' | 'È' | 'Ê' | 'Ë' => 'e',
            'î' | 'ï' | 'Î' | 'Ï' => 'i',
            'ô' | 'ö' | 'Ô' | 'Ö' => 'o',
            'ù' | 'û' | 'ü' | 'Ù' | 'Û' | 'Ü' => 'u',
            'ç' | 'Ç' => 'c',
            '@' | '"' => continue,
            c => c,
        };
        if c.is_ascii_alphanumeric() {
            out.push(c.to_ascii_lowercase());
        } else if !out.ends_with('_') && !out.is_empty() {
            out.push('_');
        }
    }
    while out.ends_with('_') {
        out.pop();
    }
    out
}

/// Découpe une ligne CSV en champs (gère les guillemets et `""`).
pub fn split_csv_line(line: &str, sep: char) -> Vec<String> {
    let mut fields = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    cur.push('"');
                    chars.next();
                } else {
                    in_quotes = false;
                }
            } else {
                cur.push(c);
            }
        } else if c == '"' {
            in_quotes = true;
        } else if c == sep {
            fields.push(std::mem::take(&mut cur));
        } else {
            cur.push(c);
        }
    }
    fields.push(cur);
    fields
}

fn detect_separator(header: &str) -> char {
    let count = |sep: char| split_csv_line(header, sep).len();
    [';', ',', '\t', '|']
        .into_iter()
        .max_by_key(|s| count(*s))
        .unwrap_or(',')
}

/// Lit un nombre en acceptant la virgule décimale et un suffixe (« 130 km/h »).
pub fn parse_number(s: &str) -> Option<f64> {
    let t = s.trim().replace(',', ".");
    let end = t
        .char_indices()
        .find(|(i, c)| !(c.is_ascii_digit() || *c == '.' || (*i == 0 && (*c == '-' || *c == '+'))))
        .map(|(i, _)| i)
        .unwrap_or(t.len());
    t[..end].parse::<f64>().ok().filter(|v| v.is_finite())
}

#[derive(Default, Debug)]
struct Columns {
    lat: Option<usize>,
    lon: Option<usize>,
    kind: Option<usize>,
    limit: Option<usize>,
    section_km: Option<usize>,
    section_m: Option<usize>,
    route: Option<usize>,
    place: Option<usize>,
}

fn find_columns(headers: &[String]) -> Columns {
    let h: Vec<String> = headers.iter().map(|s| normalize(s)).collect();
    // Les noms sont essayés dans l'ordre de priorité donné.
    let exact = |names: &[&str]| names.iter().find_map(|n| h.iter().position(|c| c == n));
    let contains = |pat: &str, not: &[&str]| {
        h.iter()
            .position(|c| c.contains(pat) && !not.iter().any(|n| c.contains(n)))
    };
    let mut cols = Columns {
        lat: exact(&["latitude", "lat", "y", "y_wgs84", "lat_wgs84"]).or_else(|| contains("latitude", &[])),
        lon: exact(&["longitude", "lon", "lng", "long", "x", "x_wgs84", "lon_wgs84"])
            .or_else(|| contains("longitude", &[])),
        kind: exact(&["type", "type_radar", "type_equipement", "categorie", "radar_type"])
            .or_else(|| contains("type", &["date", "vehicule"])),
        limit: exact(&[
            "vitesse_vehicules_legers_kmh",
            "vma",
            "vitesse_vl",
            "vitesse_vl_kmh",
            "vitesse_max",
            "vitesse_maximale",
            "vitesse_maximale_autorisee",
            "maxspeed",
            "vitesse",
            "limite",
        ])
        .or_else(|| contains("vma", &[]))
        .or_else(|| contains("vitesse", &["poids", "pl"])),
        section_km: None,
        section_m: None,
        route: exact(&["route", "voie", "axe", "ref"]),
        place: exact(&["emplacement", "commune", "lieu", "localisation", "name", "nom"]),
    };
    if let Some(i) = contains("longueur", &[]) {
        if h[i].ends_with("_m") || h[i].contains("metre") {
            cols.section_m = Some(i);
        } else {
            cols.section_km = Some(i);
        }
    }
    cols
}

/// Résultat de lecture d'un fichier de radars.
#[derive(Debug, Default)]
pub struct ParseReport {
    pub radars: Vec<Radar>,
    pub skipped_lines: usize,
}

/// Lit un CSV de radars. Renvoie une erreur si les colonnes de position sont introuvables.
pub fn parse_csv(text: &str) -> Result<ParseReport, String> {
    let mut lines = text.lines().filter(|l| !l.trim().is_empty());
    let header = lines.next().ok_or("fichier vide")?;
    let sep = detect_separator(header);
    let headers = split_csv_line(header, sep);
    let cols = find_columns(&headers);
    let (lat_i, lon_i) = match (cols.lat, cols.lon) {
        (Some(a), Some(b)) => (a, b),
        _ => {
            return Err(format!(
                "colonnes latitude/longitude introuvables (en-tête : {})",
                header.chars().take(200).collect::<String>()
            ))
        }
    };

    let mut report = ParseReport::default();
    for line in lines {
        let f = split_csv_line(line, sep);
        let get = |i: Option<usize>| i.and_then(|i| f.get(i)).map(|s| s.trim()).unwrap_or("");
        let lat = parse_number(get(Some(lat_i)));
        let lon = parse_number(get(Some(lon_i)));
        let (lat, lon) = match (lat, lon) {
            (Some(a), Some(b))
                if (-90.0..=90.0).contains(&a)
                    && (-180.0..=180.0).contains(&b)
                    && !(a == 0.0 && b == 0.0) =>
            {
                (a, b)
            }
            _ => {
                report.skipped_lines += 1;
                continue;
            }
        };
        let kind = match cols.kind {
            Some(_) if !get(cols.kind).is_empty() => RadarKind::from_label(get(cols.kind)),
            _ => RadarKind::Fixe,
        };
        let limit_kmh = parse_number(get(cols.limit))
            .filter(|v| (5.0..=150.0).contains(v))
            .map(|v| v.round() as u16);
        let section_m = parse_number(get(cols.section_km))
            .map(|km| km * 1000.0)
            .or_else(|| parse_number(get(cols.section_m)))
            .filter(|m| *m >= 100.0 && *m <= 100_000.0);
        let label = [get(cols.route), get(cols.place)]
            .iter()
            .filter(|s| !s.is_empty())
            .cloned()
            .collect::<Vec<_>>()
            .join(" · ");
        report.radars.push(Radar { lat, lon, kind, limit_kmh, section_m, label });
    }
    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn format_historique() {
        let csv = "date_heure_dernier_changement,date_heure_creation,departement,latitude,longitude,id,direction,equipement,date_installation,type,emplacement,route,longueur_troncon_km,vitesse_poids_lourds_kmh,vitesse_vehicules_legers_kmh\n\
2018-06-28T12:18:22Z,2018-06-28T11:02:09Z,80,49.95842,2.85479,10354,PARIS VERS LILLE,MORPHO,2003-11-05T00:00:00Z,Radar fixe,HEM MONACU,A1,,,130\n\
2018-06-28T12:18:22Z,2018-06-28T11:02:09Z,91,48.67029,2.27976,10355,PARIS VERS MONTLHERY,MORPHO,2003-10-31T00:00:00Z,Radar tronçon,LA VILLE DU BOIS,RN20,3.2,,70\n";
        let r = parse_csv(csv).unwrap();
        assert_eq!(r.radars.len(), 2);
        assert_eq!(r.radars[0].kind, RadarKind::Fixe);
        assert_eq!(r.radars[0].limit_kmh, Some(130));
        assert_eq!(r.radars[0].label, "A1 · HEM MONACU");
        assert_eq!(r.radars[1].kind, RadarKind::Troncon);
        assert_eq!(r.radars[1].section_m, Some(3200.0));
    }

    #[test]
    fn format_ministere_point_virgule() {
        let csv = "\u{feff}\"Numéro de radar\";\"Type de radar\";\"Latitude\";\"Longitude\";\"Date de mise en service\";\"VMA\"\n\
\"1001\";\"ETF\";\"48,8566\";\"2,3522\";\"01/01/2010\";\"50\"\n\
\"1002\";\"ETVM\";\"45,1\";\"5,7\";\"01/01/2012\";\"110 km/h\"\n\
\"1003\";\"ETFR\";\"\";\"\";\"\";\"\"\n";
        let r = parse_csv(csv).unwrap();
        assert_eq!(r.radars.len(), 2);
        assert_eq!(r.skipped_lines, 1);
        assert!((r.radars[0].lat - 48.8566).abs() < 1e-9);
        assert_eq!(r.radars[0].limit_kmh, Some(50));
        assert_eq!(r.radars[1].kind, RadarKind::Troncon);
        assert_eq!(r.radars[1].limit_kmh, Some(110));
    }

    #[test]
    fn format_overpass() {
        let csv = "@lat,@lon,maxspeed\n43.6,1.44,90\n43.7,1.45,\n";
        let r = parse_csv(csv).unwrap();
        assert_eq!(r.radars.len(), 2);
        assert_eq!(r.radars[1].limit_kmh, None);
    }

    #[test]
    fn codes_types() {
        assert_eq!(RadarKind::from_label("ETFR"), RadarKind::FeuRouge);
        assert_eq!(RadarKind::from_label("Radar feu rouge"), RadarKind::FeuRouge);
        assert_eq!(RadarKind::from_label("ETPN"), RadarKind::PassageNiveau);
        assert_eq!(RadarKind::from_label("Radar vitesse moyenne"), RadarKind::Troncon);
        assert_eq!(RadarKind::from_label("Radar discriminant"), RadarKind::Discriminant);
    }

    #[test]
    fn sans_colonnes() {
        assert!(parse_csv("a,b,c\n1,2,3\n").is_err());
    }
}
