# Gestion Stock MA — application Android hors ligne

Gestion commerciale et de stock pour petite entreprise au Maroc (MAD / DH).
Kotlin · Jetpack Compose · Room/SQLite · PDF A4 natif · Français / العربية (RTL).

## Points clés
- **100 % hors ligne** : aucune permission `INTERNET` dans le manifeste, aucun serveur ni compte.
  Sauvegarde cloud Android désactivée (`allowBackup=false` + règles d'exclusion).
- **Stock négatif toujours autorisé** (avertissement + indicateur rouge, filtre « négatif »).
- **Règles de stock** (`domain/Repository.kt`) :
  devis = aucun mouvement · BL validé = sortie · facture directe = sortie ·
  facture issue d'un BL validé = réutilise les mouvements du BL (pas de double déduction) ·
  avoir = demande si retour physique avant remise en stock.
- **Validation atomique** : numéro unique par type et par année (`FA-2026-00001`), totaux,
  mouvements et acompte dans une seule transaction SQLite + verrou anti double clic. Une 2ᵉ
  validation d'un document déjà validé ne fait rien.
- **PDF archivé à la validation** (`files/pdf/`) : les anciens documents restent réimprimables tels
  qu'émis, même après un changement de logo, cachet ou coordonnées.
- **PDF A4** multipage : en-têtes de colonnes répétés, « Page x / y », bloc totaux jamais coupé,
  cachet confiné à son cadre de signature (jamais sur les totaux ni les lignes).
  Police intégrée DejaVu Sans (arabe + latin), bidi et ligatures via `StaticLayout`.
- **Logo / cachet** : PNG (transparence conservée) ou JPEG, aperçu avant enregistrement, copiés
  dans le stockage privé (indépendants de la galerie).
- **Sauvegarde `.gsbak`** (ZIP) : base SQLite + images + PDF archivés + `manifest.json` (SHA-256).
  Restauration : vérification complète du fichier puis confirmation avant remplacement.
- **Langue** : FR / AR pour l'interface (mémorisée, RTL) et, séparément, pour chaque document.

## Compiler
Prérequis : Android Studio (Koala ou plus récent) ou JDK 17 + SDK Android 34.

```bash
./gradlew assembleDebug          # APK de test : app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sans Android Studio : poussez ce dossier sur un dépôt GitHub → l'onglet **Actions** compile
automatiquement l'APK de test (workflow `.github/workflows/build.yml`, artefact téléchargeable).

## Version de production signée
```bash
# 1) Créer la clé (une seule fois — GARDER ce fichier et les mots de passe en lieu sûr)
keytool -genkeypair -v -keystore gestion-release.jks -alias gestion \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) Créer keystore.properties à la racine (non versionné)
storeFile=../gestion-release.jks
storePassword=VOTRE_MOT_DE_PASSE
keyAlias=gestion
keyPassword=VOTRE_MOT_DE_PASSE

# 3) Compiler
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
# Vérifier la signature
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
⚠️ Les mises à jour doivent être signées avec la même clé, sinon il faudra désinstaller
(et donc restaurer une sauvegarde).

## Scénario de recette (mode avion)
1. Réglages → Ma société : saisir les coordonnées, importer logo + cachet.
2. Clients → créer un client. Articles → créer un article, quantité initiale **2**.
3. Documents → + → Bon de livraison, ajouter l'article, quantité **5** → Valider →
   avertissement stock négatif → Valider. Stock = **−3** (rouge).
4. Ouvrir le BL → Transformer en facture → Valider. Stock reste **−3**
   (aucun nouveau mouvement, voir Articles → mouvements).
5. Facture → langue العربية → aperçu PDF.
6. Fermer l'application (forcer l'arrêt), rouvrir : données, logo et cachet présents.
7. Réglages → Sauvegarder → copier le `.gsbak`. Sur une installation neuve : Restaurer →
   vérifier documents, PDF archivés et images.

## Structure
```
app/src/main/java/ma/gestion/stock/
  data/      entités Room, DAO, base
  domain/    règles métier (stock, validation, transformations), calculs DH
  pdf/       générateur PDF A4 FR/AR
  backup/    sauvegarde / restauration vérifiée
  ui/        écrans Compose
app/src/main/assets/fonts/   DejaVu Sans (licence libre, voir LICENSE-DejaVu.txt)
samples/     exemples de factures A4 FR et AR (logo et cachet fictifs)
```
