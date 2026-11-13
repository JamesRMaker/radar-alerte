//! Radar Alerte — cœur de l'application, écrit en Rust pur sans dépendance.
//!
//! Il est indépendant d'Android : on peut le tester sur PC (`cargo test`) et
//! rejouer un trajet (`cargo run --example simulation`).

pub mod engine;
pub mod geo;
pub mod output;
pub mod radar;

pub use engine::{AlertMode, Config, Engine, Event, Fix, Status, Update};
pub use radar::{parse_csv, Radar, RadarKind};

#[cfg(test)]
mod tests;
