# StreamFlix pour Android

Portage Android du projet **StreamFlix Desktop 1.0.8** (licence Apache-2.0, voir `LICENSE`).
Le code des providers/extracteurs (`app/src/main/kotlin/.../providers`, `.../extractors`) est repris tel quel de
`shared/` ; l'interface est le front Next.js de `web/`, converti en site statique.

> ⚠️ **État du projet — à lire.** Ce projet a été préparé **sans pouvoir compiler l'APK** (le SDK Android et
> Maven ne sont pas accessibles depuis l'environnement où il a été écrit). Ce qui a été **réellement testé** et
> ce qui **ne l'a pas été** est détaillé en bas de page. Prévoyez de corriger éventuellement quelques erreurs de
> compilation au premier build.

## Obtenir l'APK

### Option A — Android Studio (recommandé)

1. Installer [Android Studio](https://developer.android.com/studio) (version récente, JDK 17+ inclus).
2. `File > Open` et choisir ce dossier. Accepter l'installation du SDK 35 si Android Studio la propose.
3. Attendre la fin de la synchronisation Gradle.
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
5. APK produit : `app/build/outputs/apk/release/app-release.apk` (signé avec la clé de debug, donc installable tel quel).

### Option B — Ligne de commande

Prérequis : JDK 17 et SDK Android (variable `ANDROID_HOME`).

```bash
./gradlew assembleRelease        # Windows : gradlew.bat assembleRelease
```

### Option C — Sans rien installer : GitHub Actions

1. Créer un dépôt GitHub (privé suffit) et y pousser ce dossier.
2. Onglet **Actions > Build APK > Run workflow**.
3. Télécharger l'APK dans les *Artifacts* de l'exécution.

### Installer sur le téléphone

Copier l'APK sur le téléphone, l'ouvrir et autoriser l'installation depuis cette source. Android 8.0 (API 26) minimum.

## Comment ça marche

```
┌────────────── APK ──────────────────────────────────────────────┐
│ MainActivity ──► WebView plein écran                            │
│                     │  http://127.0.0.1:38417/?k=<jeton>        │
│                     ▼                                           │
│ Serveur HTTP local (Kotlin, 127.0.0.1 uniquement)               │
│   ├─ /               interface web (web.zip décompressé)        │
│   ├─ /api/*          providers, recherche, fiches, flux         │
│   └─ /manifest.m3u8, /segment, /direct, /image   proxys média   │
│ Providers + extracteurs (code partagé de StreamFlix)            │
└─────────────────────────────────────────────────────────────────┘
```

Ce qui a dû être adapté par rapport au desktop :

| Desktop | Android |
|---|---|
| `com.sun.net.httpserver` | `mobile/http/HttpServer.kt` : même API au-dessus d'un `ServerSocket` (testé) |
| `java.net.http` | `mobile/nethttp/NetHttp.kt` : même API au-dessus d'OkHttp |
| Next.js en serveur Node | Export statique (`web/`), servi par le backend ; pages film/série chargées côté client |
| Playwright (Chromium) | `utils/HeadlessBrowserResolver.kt` : WebView invisible, même API publique |
| Électron (mises à jour, Discord) | Absent : ces composants s'effacent seuls hors Électron |
| Téléchargements (ffmpeg) | **Désactivés** (boutons masqués, routes en 501) |
| CORS ouvert (`*`) | Supprimé, remplacé par un **jeton de session** : seule la WebView accède au serveur |
| Fichiers dans `~/.streamflix` | `user.home` redirigé vers le stockage privé de l'app |

## Reconstruire l'interface

`app/src/main/assets/web.zip` est déjà fourni. Si vous modifiez `web/` : Node.js 20+, puis `./build-web.sh`
(`build-web.bat` sous Windows). Pourquoi un zip : Android ignore les dossiers commençant par `_` dans les assets, et
Next.js produit `_next/`.

## Ce qui a été testé… et ce qui ne l'a pas été

**Testé pour de vrai** (compilé et exécuté) :
- Le build statique Next.js complet (`next build`), 44 pages.
- Le serveur HTTP de compatibilité : 17 tests (taille fixe, chunked, HEAD, POST, SSE, chemins encodés, préfixe le plus long, 30 requêtes parallèles…).
- `StaticSite`, `SessionGuard`, `WebBundle` (dont protection zip-slip) : 10 tests + parcours navigateur Chromium réel : redirection de langue, accueil, fiche film (clic et chargement direct), fiche série, réglages, recherche, favoris, 0 erreur JavaScript côté chargement des pages.
- Le code Android (`MainActivity`, `BackendHost`, `StreamflixApp`, `HeadlessBrowserResolver`…) **vérifié au typage** contre le vrai `android.jar` (API 33).

**Non testé / à surveiller** :
- ❗ La compilation Gradle complète (providers + `Backend.kt` + `NetHttp.kt` n'ont pas pu être compilés ici : dépendances Maven inaccessibles). `Backend.kt` est l'original desktop avec uniquement ses imports et son démarrage modifiés ; `NetHttp.kt` reproduit exactement les appels utilisés. Une erreur de compilation mineure reste possible.
- ❗ Aucun test sur un vrai téléphone/émulateur : lecture vidéo HLS dans la WebView, plein écran, retour arrière, `HeadlessBrowserResolver`.
- `org.json` : Android embarque sa propre version, légèrement plus limitée que celle du desktop.
- Rhino 1.7.15 (utilisé par `CloseloadExtractor`) peut avertir à propos de classes `java.beans` absentes d'Android ; seul cet extracteur est concerné.
- Un avertissement React #418 (hydratation) apparaît en production sur la page Réglages ; l'affichage se récupère seul.
- Les providers dépendent de sites tiers qui changent ou disparaissent : certains seront en panne, comme sur desktop.
- Dans l'interface, une partie des libellés reste en anglais (texte original du projet).

## Contenu et légalité

StreamFlix est un agrégateur : il n'héberge aucun contenu, mais ses providers pointent vers des sites tiers dont
certains diffusent des œuvres sans autorisation. Vous êtes responsable de l'usage que vous en faites et de la
légalité de l'accès à ces contenus dans votre pays.
