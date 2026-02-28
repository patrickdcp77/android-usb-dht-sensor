# Android USB DHT Sensor + Géolocalisation (MyApplication2)

Cette app Android lit en USB-Serial (OTG) les mesures d’un capteur (ex: DHT via ESP8266/ESP32) et les affiche **avec la localisation du smartphone** (latitude/longitude).

## Mini schéma ASCII (architecture)

### Architecture Gradle (aujourd’hui)

```
MyApplication2
└─ :app  (application)
   ├─ UI Compose (SensorScreen)
   ├─ USB/Serial (usb-serial-for-android)
   └─ Location (play-services-location)
```

### Architecture recommandée si tu veux 2 apps (plus tard)

```
MyApplication2
├─ :core  (library)   -> USB + parsing + modèles de données
├─ :app   (application) -> UI/écran "capteur"
└─ :app2  (application) -> UI/écran "carto / logger / autre"

:app  -> dépend de :core
:app2 -> dépend de :core
```

### Flux runtime (lecture + affichage)

```
[Capteur / ESP] --USB OTG--> [Android USB Manager]
      |                           |
      |                           +--> Permission USB (broadcast USB_PERMISSION)
      |
      +-- "T=..;H=..\n" --> [SerialInputOutputManager] --> [Parser] ---> (tempState/humState/rawState)

[GPS / Wi‑Fi / Cell] ---> [FusedLocationProviderClient] ---> (locationState)

(states) ----> [UI Compose: SensorScreen]
```

## Vue d’ensemble (comment ça s’articule)

### Flux principal
1. **USB OTG branché** → Android détecte un périphérique USB.
2. L’app **demande la permission USB** (broadcast `USB_PERMISSION`).
3. Une fois la permission accordée, l’app ouvre le port série via la lib `usb-serial-for-android`.
4. Les données arrivent en continu → on reconstruit des lignes terminées par `\n` → parsing `T=...;H=...`.
5. En parallèle, l’app demande la **permission de localisation** et récupère une position via **Fused Location Provider**.
6. UI Jetpack Compose affiche **Temp/Hum + Lat/Lon**.

## Structure du projet (dossiers / fichiers)

### Racine
- `settings.gradle.kts`
  - Déclare les modules Gradle (ex: `:app`, et plus tard `:core`, `:app2` si tu ajoutes une 2ᵉ app).
- `build.gradle.kts`
  - Build Gradle **global** (plugins/versions si nécessaire).
- `gradle.properties`
  - Propriétés Gradle (perf, AndroidX, etc.).
- `gradlew` / `gradlew.bat`
  - Wrapper Gradle (recommandé pour compiler de manière reproductible).
- `gradle/wrapper/gradle-wrapper.properties`
  - Version de Gradle utilisée par le wrapper.
- `gradle/libs.versions.toml`
  - *Version Catalog* : centralise les versions des libs/plugins utilisés via `libs.xxx`.
- `local.properties`
  - **Local à ta machine** (chemin du SDK, etc.). Ne pas partager.
- `README.md`
  - Ce document.

### Module `app/` (l’application)
- `app/build.gradle.kts`
  - Configuration Android (namespace, sdk, compose) + dépendances.
  - Dépendances importantes :
    - `com.github.mik3y:usb-serial-for-android` → communication série USB.
    - `com.google.android.gms:play-services-location` → localisation (FusedLocationProviderClient).

- `app/src/main/AndroidManifest.xml`
  - Déclare les permissions et composants Android.
  - Ici notamment :
    - `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` (+ optionnel `ACCESS_BACKGROUND_LOCATION`)
    - `uses-feature android.hardware.usb.host`

- `app/src/main/java/com/example/myapplication/MainActivity.kt`
  - **Fichier central**.
  - Rôles :
    - Gère l’USB : demande permission, ouvre le port série, lit les données.
    - Gère la localisation : demande permission, récupère une position (current location + fallback update).
    - Contient l’UI Compose (`SensorScreen`) : affiche status, température, humidité, lat/lon, trame raw.

- `app/src/main/res/`
  - Ressources Android.
  - `values/strings.xml` : textes (nom app, etc.).
  - `values/themes.xml` : thème.
  - `mipmap-*/` : icônes.
  - `xml/backup_rules.xml`, `xml/data_extraction_rules.xml` : backup/data extraction.

### Dossier `.idea/`
- Config Android Studio/IntelliJ. Peut être commit selon ton usage, mais souvent on garde juste le minimum.

### Dossier `app/build/`
- Généré par Gradle (ne pas commit).

## Détails techniques importants

### 1) Permission USB (BroadcastReceiver)
- L’app utilise une action custom : `com.example.myapplication.USB_PERMISSION`.
- Android envoie un broadcast au moment où l’utilisateur accepte/refuse la permission.
- Sur Android 13+ (API 33), `registerReceiver` doit préciser `RECEIVER_NOT_EXPORTED` ou `RECEIVER_EXPORTED`.

### 2) Lecture série USB
- Lib : `usb-serial-for-android`.
- Étapes :
  - `UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)`
  - `driver.ports.firstOrNull()`
  - `port.open(connection)` + `port.setParameters(115200, 8, STOPBITS_1, PARITY_NONE)`
  - `SerialInputOutputManager` pour recevoir les bytes en callback.

### 3) Parsing des mesures
- Le code attend une ligne complète (terminée par `\n`).
- Format recommandé côté microcontrôleur :
  - `T=23.4;H=45.6\n`

### 4) Localisation
- API utilisée : `FusedLocationProviderClient` (Google Play Services).
- Pourquoi `lastLocation` ne suffit pas : peut être `null` si aucun fix récent.
- Stratégie :
  - `getCurrentLocation(...)`
  - si `null` → `requestLocationUpdates(... maxUpdates=1)`

## Prérequis (PC Windows)

### Java / Gradle (important)
Si Gradle dit `JAVA_HOME is not set`, pointe `JAVA_HOME` vers le JDK d’Android Studio.
Sur ta machine, le JDK existe ici :
- `C:\Program Files\Android\Android Studio\jbr`

Commandes PowerShell (à copier/coller) :

```powershell
setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
setx PATH "%PATH%;%JAVA_HOME%\bin"
```

Puis ferme/réouvre le terminal (ou Android Studio) et vérifie :

```powershell
java -version
```

## Compiler / lancer

```powershell
# depuis la racine du projet
.\gradlew.bat :app:assembleDebug
```

## GitHub (sauvegarder le projet)

Étapes typiques :
1. `git add .`
2. `git commit -m "..."`
3. `git push -u origin main`

Si Git demande ton identité :

```powershell
git config --global user.name "Ton Nom"
git config --global user.email "toi@example.com"
```

## Évolutions recommandées (2 apps + code partagé)
Si tu veux 2 apps qui réutilisent le même code USB/parsing :
- créer un module **library** `:core` (USB + parsing)
- créer un second module **application** `:app2`
- `:app` et `:app2` dépendent de `:core`

Bénéfices : une seule implémentation USB/parsing, deux UIs différentes.

## Dépannage rapide
- **Pas de coordonnées** : localisation désactivée dans les réglages, pas de fix GPS (intérieur), permission non accordée.
- **USB non détecté** : vérifier OTG, câble, alimentation, périphérique compatible.
- **Aucune mesure** : vérifier baud rate (115200), format `T=...;H=...\n`.
