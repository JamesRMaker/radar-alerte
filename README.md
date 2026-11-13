# Radar Alerte — appli Android en Rust

Prévient à la voix quand vous approchez d'un radar **fixe** (base officielle data.gouv.fr)
ou d'un radar **mobile** que vous avez signalé, et vous avertit si vous dépassez la limite.

## Ce que fait l'appli

- **Radars fixes** : charge la « Liste des radars fixes en France » publiée par le Ministère de
  l'Intérieur sur data.gouv.fr (bouton *Mettre à jour*), ou n'importe quel CSV avec des colonnes
  latitude/longitude (bouton *Importer*).
- **Radars mobiles** : bouton *Signaler un radar mobile ici* quand vous en croisez un. Le
  signalement est gardé 1 h, 2 h, 3 h, 6 h ou en permanence (réglable), et un bouton le supprime
  s'il n'est plus là. Les signalements restent sur votre téléphone : il n'y a pas de serveur
  partagé (voir *Limites*).
- **Alertes vocales** sur la musique (le volume baisse pendant l'annonce), avec un bip, même écran
  éteint ou avec Maps/Waze au premier plan (service GPS au premier plan + notification).
- Seuls les radars **devant vous** dans votre sens de marche déclenchent l'alerte.
- **Survitesse** : « Attention, vitesse limitée à 90 » si vous dépassez la limite du radar
  (+3 km/h par défaut, réglable), répété toutes les 30 s tant que vous êtes trop rapide.
- **Radars tronçons** : calcul de votre vitesse moyenne sur le tronçon (mode précis).

### Deux modes

| | Zones de danger (par défaut) | Mode précis |
|---|---|---|
| Annonce | « Zone de danger. Vitesse limitée à 90 » | « Radar fixe dans 750 mètres, limité à 90 » |
| Longueur | 500 m en ville, 2 km sur route, 4 km sur autoroute | ~30 s avant le radar (300 m à 1,2 km) |
| Position du radar | jamais exacte (placé entre 40 et 80 % de la zone) | distance exacte |
| Usage | **France** | hors de France |

En France, signaler l'emplacement précis d'un radar pendant la conduite est interdit
(article R413-15 du Code de la route). Les assistants d'aide à la conduite comme Coyote ou Waze
annoncent donc des « zones de danger », et c'est le comportement par défaut de l'appli.
Le mode précis demande une confirmation avant d'être activé.

## Architecture

```
rust/
  radar-core/      ← toute la logique, en Rust pur, sans dépendance, testée sur PC
    src/radar.rs     lecture des CSV (data.gouv.fr ancien et nouveau format, OpenStreetMap)
    src/geo.rs       distances, caps
    src/engine.rs    moteur d'alerte : index spatial, radars devant/derrière, zones,
                     survitesse, tronçons, radars mobiles (expiration, sauvegarde)
    src/output.rs    phrases en français + JSON pour l'interface
    examples/simulation.rs   rejoue un trajet et affiche les annonces
  radar-jni/       ← pont JNI : produit libradar_alerte.so
android/           ← couche Android minimale en Kotlin (aucune dépendance externe)
    RadarCore.kt     déclarations des fonctions natives Rust
    RadarService.kt  GPS, synthèse vocale, notification
    MainActivity.kt  écran (boutons, réglages)
```

Pourquoi un peu de Kotlin ? Sur Android, un service qui tourne en arrière-plan, la synthèse
vocale et les notifications ne sont accessibles qu'à travers l'API Java. Le Kotlin se limite
donc à relayer la position GPS au moteur Rust et à prononcer ce qu'il renvoie : toutes les
décisions (quand alerter, quoi dire) sont prises en Rust.

## Compiler

### 1. Outils à installer (une seule fois)

1. **Android Studio** (ou les outils en ligne de commande Android SDK), avec dans le
   *SDK Manager* : Android SDK Platform 35 et **NDK (Side by side)**.
2. **Rust** : https://rustup.rs, puis :
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   cargo install cargo-ndk
   ```

### 2. Tester le cœur Rust sur votre PC (facultatif)

```bash
cd rust/radar-core
cargo test                                   # 21 tests
cargo run --example simulation               # trajet fictif, mode zones de danger
cargo run --example simulation -- precis     # même trajet, mode précis
```

### 3. Construire l'APK

**Avec Android Studio** : *File → Open* → dossier `android/`, puis *Run ▶* avec le téléphone
branché (débogage USB activé). La compilation Rust est lancée automatiquement avant chaque build.

**En ligne de commande** :
```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # chemin de votre SDK
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Si `cargo ndk` ne trouve pas le NDK, définissez `ANDROID_NDK_HOME`, par exemple
`export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.2.12479018`.

## Utilisation

1. Au premier lancement, acceptez le téléchargement de la base de radars (~170 Ko).
2. *Démarrer la surveillance* → autorisez la localisation **précise** et les notifications.
3. Lancez votre GPS habituel : l'appli continue en arrière-plan (notification « Radar Alerte »,
   avec un bouton *Arrêter*).
4. Sur certains téléphones (Xiaomi, Huawei, Samsung…), désactivez l'optimisation de batterie
   pour Radar Alerte, sinon le système peut couper le service.

### Autres sources de radars

N'importe quel CSV avec des colonnes `latitude`/`longitude` (ou `lat`/`lon`) est accepté,
séparateur `,` ou `;`, virgule décimale tolérée. Colonnes facultatives reconnues : type
(ETF, ETVM, ETFR… ou « Radar fixe », « Radar tronçon »…), vitesse (`vma`,
`vitesse_vehicules_legers_kmh`, `maxspeed`), longueur de tronçon, route, emplacement.

Pour un autre pays, exportez les radars d'OpenStreetMap avec https://overpass-turbo.eu :
```
[out:csv(::lat,::lon,maxspeed;true;",")];
area["ISO3166-1"="BE"]->.pays;
node["highway"="speed_camera"](area.pays);
out;
```
puis importez le fichier obtenu.

Si l'adresse de téléchargement data.gouv.fr change, mettez à jour `RADARS_URL` dans
`RadarCore.kt`.

## Limites

- **Pas de partage des radars mobiles entre utilisateurs** : il faudrait un serveur commun
  (c'est ce que font Waze et Coyote avec leurs communautés). Le moteur est prêt à recevoir
  d'autres signalements via `reportMobile`.
- Le jeu de données officiel n'indique pas le sens de contrôle de façon exploitable : un radar
  qui contrôle l'autre sens peut déclencher une alerte.
- Sans carte routière, « devant vous » est estimé à vol d'oiseau : un radar sur une route
  parallèle proche peut être annoncé.
- Voitures radars banalisées : aucune donnée de position n'est disponible.
- L'appli est une aide : la vitesse affichée par le GPS reste indicative, et respecter les
  limitations reste votre responsabilité.
