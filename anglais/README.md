# Révisions d'anglais

Appli Android de révision du vocabulaire anglais, rangée ici à part de Radar Alerte.

- **Installer** : [RevisionsAnglais.apk](https://github.com/JamesRMaker/radar-alerte/releases/download/anglais/RevisionsAnglais.apk)
- `site/` : la page (`index.html`, liste des mots dans `DATA`) et les sons (`audio/<mot>.mp3`).
  L'appli va chercher ces fichiers ici quand il y a Internet : un mot ajouté dans `site/`
  arrive dans l'appli sans réinstaller l'APK.
- `site/ajout.js` : onglet « + Ajouter » (seulement dans l'APK) : traduction en ligne (MyMemory),
  voix du téléphone pour les nouveaux mots, mots gardés sur le téléphone, liste à envoyer à Claude.
  Ce fichier n'est pas dans l'appli Claude : ne pas le supprimer en recopiant `index.html`.
- `android/` : l'appli Android (WebView). Un changement dans `android/` refait l'APK
  (workflow « Révisions d'anglais - APK »). La page et les sons sont aussi copiés dans l'APK
  pour marcher hors connexion.
