# Mumla Funk bei Google Play

Diese Anleitung führt vom Entwicklerkonto bis zum internen Test. Alle Texte und Antworten für
die Play Console stehen hier zum Kopieren bereit. Der Ordner `grafiken/` enthält Icon,
Feature-Grafik und Screenshots.

Im Repo vorbereitet:

- Build-Variante `play`: wie `foss`, aber ohne Spenden-Link und ohne Billing-Bibliothek
- Workflow `.github/workflows/play.yml`: baut das signierte App Bundle, optional mit Upload in den internen Test
- Hinweisdialog vor dem Einschalten der Bedienungshilfe, wie Google ihn verlangt
- Link zur Datenschutzerklärung unter Einstellungen › Über
- Version 1.0.4 (Versionscode 1000400) mit Release Notes in `fastlane/metadata/android/*/changelogs/1000400.txt`
- Website mit Datenschutzerklärung im Repo [mumla-funk-website](https://github.com/Filubu/mumla-funk-website)

## Prüfung der Play-Anforderungen

Stand: November 2026.

| Anforderung | Status |
|---|---|
| Ziel-API 36, Pflicht für neue Apps und Updates seit 31.08.2026 | erfüllt, `targetSdk 36` |
| Format App Bundle (`.aab`) | `./gradlew bundlePlayRelease` oder Workflow |
| 16-KB-Speicherseiten für 64-Bit-Code, Pflicht seit 01.11.2025 | erfüllt, die Codecs werden mit `max-page-size=16384` gelinkt, im Test-Build geprüft |
| Zahlungsrichtlinie | erfüllt durch `play`: kein Liberapay-Link, kein Spendendialog |
| Bedienungshilfe für Nicht-Hilfs-Apps | Hinweis und Zustimmung in der App, Erklärung mit Video in der Play Console offen |
| Vordergrunddienst Typ Mikrofon | Erklärung mit Video in der Play Console offen |
| Datenschutzerklärung | Website und Link in der App, Seite muss noch live gehen |
| Werbe-ID | nicht genutzt, die Berechtigung `AD_ID` fehlt im Manifest |
| Paketname | `io.github.filubu.mumlafunk`, nach dem ersten Upload nicht mehr änderbar |

Der interne Test braucht davon nur das App Bundle. Google prüft interne Tests in der Regel nicht
und gibt sie nach wenigen Minuten frei. Die Erklärungen unter
[App-Inhalte](#6-app-inhalte) sollten trotzdem gleich ausgefüllt werden, da die Play Console
eine Freigabe mit Mikrofon-Dienst und Bedienungshilfe teilweise erst danach zulässt.

## 1. Entwicklerkonto

1. Unter <https://play.google.com/console/signup> ein Konto vom Typ **Privatperson** anlegen.
   Die Gebühr beträgt einmalig 25 USD.
2. Identität mit Ausweis bestätigen, Telefonnummer bestätigen.
3. Zugriff auf ein echtes Android-Gerät bestätigen: Die App *Play Console* auf dem Handy
   installieren und dort anmelden.
4. Öffentliche Angaben setzen:
   - Name des Entwicklers: `Filubu`
   - E-Mail-Adresse: `info@filubu.de` (wird im Play Store öffentlich angezeigt)
   - Website: `https://filubu.github.io/mumla-funk-website/`
5. Händlerstatus (EU, Digital Services Act): **kein Händler** angeben. Mumla Funk ist kostenlos
   und ohne Einnahmen, daher erscheinen weder Anschrift noch Telefonnummer im Play Store.

Neue private Konten brauchen vor der Produktion einen geschlossenen Test, siehe
[Später: Produktion](#9-später-produktion). Für den internen Test gilt das nicht.

## 2. App anlegen

1. Play Console › **App erstellen**.
2. Angaben:
   - App-Name: `Mumla Funk`
   - Standardsprache: `Deutsch (de-DE)`
   - App oder Spiel: App
   - Kostenlos oder kostenpflichtig: kostenlos
3. Erklärungen zu Programmrichtlinien und US-Exportgesetzen bestätigen.

## 3. Signaturschlüssel

Entschieden: Google Play signiert mit dem bestehenden GitHub-Schlüssel (Alias `mumlafunk`).
GitHub-APK und Play-Version bleiben damit austauschbar, Updates laufen über beide Wege ohne
Neuinstallation. Die Wahl ist nach dem ersten Upload nicht mehr änderbar, daher zuerst diesen
Schritt erledigen.

1. Play Console › Mumla Funk › **Test und Release › App-Integrität** (oder beim ersten Release
   unter „App-Signaturschlüssel“) › **Signaturschlüssel ändern**.
2. **Schlüssel aus Java-Schlüsselspeicher exportieren und hochladen** wählen.
3. `pepk.jar` und den öffentlichen Verschlüsselungsschlüssel herunterladen.
4. Auf dem eigenen Rechner im Ordner mit dem Keystore ausführen:

   ```bash
   java -jar pepk.jar --keystore=mumla-funk-release.jks --alias=mumlafunk --output=mumlafunk-private-key.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```

5. `mumlafunk-private-key.zip` hochladen und speichern.

Der gleiche Schlüssel dient danach auch als Upload-Schlüssel, daher signiert der Workflow mit den
vorhandenen Secrets `MUMLA_FUNK_KEYSTORE_BASE64` und `MUMLA_FUNK_KEYSTORE_PASSWORD`. Ein eigener
Upload-Schlüssel ist sicherer, da Google ihn bei Verlust zurücksetzen kann. Für den Anfang ist
er aber nicht nötig.

## 4. App Bundle bauen

Über GitHub:

1. Auf GitHub › **Actions › Google Play › Run workflow** öffnen.
2. Unter „Tag or branch“ `master` oder einen Tag wie `v1.0.4` eintragen, „upload“ leer lassen.
3. Nach dem Lauf unter **Artifacts** `mumla-funk-play` herunterladen und entpacken. Darin
   liegen `mumla-funk-1.0.4-play.aab` und die Release Notes unter `whatsnew/`.

Ein gepushter Tag `v*` baut das Bundle ebenfalls, parallel zum GitHub-Release.

Lokal:

```bash
(cd libraries/humla/libs/humla-spongycastle && ../../gradlew jar)
```

```bash
./gradlew bundlePlayRelease
```

Das Bundle liegt danach unter `app/build/outputs/bundle/playRelease/mumla-play-release.aab`.
Signiert wird es nur mit `app/signing.gradle`, siehe Haupt-README.

## 5. Interner Test

1. **Test und Release › Testen › Interner Test › Tester**: eine E-Mail-Liste anlegen, z. B.
   „Funkgruppe“, und die Google-Konten der Tester eintragen. Maximal 100 Tester.
2. **Neuen Release erstellen**, die `.aab` hochladen.
3. Release-Name: `1.0.4`. Release Notes aus `whatsnew/` oder hier kopieren:

   ```text
   <de-DE>
   * Erste Version für Google Play (ohne Spenden-Links).
   * Bevor die Bedienungshilfe für die Leiser-Taste eingeschaltet wird, erklärt die App, was sie damit tut und was nicht.
   * Link zur Datenschutzerklärung unter Einstellungen › Über.
   </de-DE>
   <en-US>
   * First version for Google Play (without donation links).
   * Before you switch on the accessibility service for the volume-down key, the app explains what it does with it and what it does not.
   * Link to the privacy policy under Settings › About.
   </en-US>
   ```

4. **Weiter › Speichern › Release überprüfen › Einführung im internen Test starten**.
5. Unter **Tester** den Link „Tester-Einladung kopieren“ an die Gruppe schicken. Jeder Tester
   öffnet den Link mit seinem Google-Konto, nimmt teil und installiert über den Play Store.

Hinweise:

- Wer die GitHub-APK schon installiert hat, bekommt die Play-Version als normales Update, da der
  Schlüssel gleich ist. Server und Zertifikate bleiben erhalten.
- Jeder neue Upload braucht einen höheren Versionscode als der vorherige. Version und Code stehen
  in `app/build.gradle` (`funkVersion`, `versionCode`).
- Für einen schnellen Test ohne Track gibt es zusätzlich **Interne App-Freigabe**: Bundle hochladen
  und Link teilen, ohne Erklärungen und ohne Versionscode-Regel.

## 6. App-Inhalte

Play Console › **Richtlinien › App-Inhalte**. Die Antworten gelten für die aktuelle Version.

### Datenschutzerklärung

```text
https://filubu.github.io/mumla-funk-website/datenschutz.html
```

Die Seite muss erreichbar sein, bevor sie eingetragen wird, siehe README der Website.

### App-Zugriff

Für den internen Test egal, vor einer Prüfung aber nötig: Ohne Mumble-Server lässt sich die App
nicht sinnvoll prüfen. Auswahl **Alle oder einige Funktionen sind eingeschränkt** und eine
Anleitung mit Testserver eintragen:

```text
Mumla Funk is a client for Mumble voice servers. To test it, add this server:
Tap the menu (top left) > Servers > + and enter
  Address: [SERVER-ADRESSE]
  Port: [PORT, Standard 64738]
  Username: any name, e.g. GoogleReview
  Password: [leer lassen oder PASSWORT]
Then tap the server to connect, accept the server certificate, and use the big
button ("Halten zum Sprechen" / "Hold to talk") to talk.
```

Offen: welcher Server für die Prüfung erreichbar ist, eigener Server oder ein öffentlicher.

### Werbung

Enthält die App Werbung: **Nein**

### Inhaltsbewertung

Fragebogen ausfüllen, E-Mail `info@filubu.de`. Kategorie: **Andere App-Typen** (Kommunikation).

- Gewalt, Sexualität, Sprache, Drogen, Glücksspiel: Nein
- Können Nutzer miteinander interagieren oder Inhalte austauschen: **Ja** (Sprache und Text)
- Wird der Standort mit anderen Nutzern geteilt: Nein
- Können digitale Waren gekauft werden: Nein
- Ist die App ein Webbrowser oder eine Suchmaschine: Nein

Erwartetes Ergebnis: USK 0 bzw. PEGI 3 mit dem Hinweis „Nutzer interagieren“.

### Zielgruppe und Inhalte

- Altersgruppen: nur **18 und älter**
- Spricht die App Kinder an: Nein

Mit 18+ fallen die Familienrichtlinien komplett weg.

### Datensicherheit

Empfehlung: die gleichen Angaben wie Mumla im Play Store („Keine Daten erhoben, keine Daten
geteilt“). Grund: Der Entwickler betreibt keine Server und bekommt keine Daten. Sprache, Chat,
Benutzername, Zertifikat sowie Android-Version und App-Version gehen nur an Mumble-Server, die
Nutzer selbst eintragen. Gespeichert wird alles nur auf dem Gerät.

- Erhebt oder teilt die App erforderliche Nutzerdatentypen: **Nein**
- Falls trotzdem gefragt, Verschlüsselung bei der Übertragung: **Ja** (TLS, Sprache über UDP mit OCB-AES128)

Google definiert „Erheben“ allerdings als jede Übertragung vom Gerät weg, auch an Server Dritter.
Ob die Selbst-gehostet-Begründung bei einer Prüfung anerkannt wird, ist noch offen. Mumla ist
damit aktuell im Play Store. Die vorsichtigere Variante wäre:

- Audio › Sprach- oder Tonaufnahmen: erhoben, nicht geteilt, nur für App-Funktionen, nicht flüchtig, erforderlich
- Persönliche Daten › Name (Benutzername): erhoben, nicht geteilt, App-Funktionen, erforderlich
- Nachrichten › Andere In-App-Nachrichten (Chat): erhoben, nicht geteilt, App-Funktionen, optional
- Verschlüsselung bei der Übertragung: Ja
- Löschung beantragen: Nein, da beim Entwickler keine Daten liegen; Daten auf dem Server löscht der Serverbetreiber

### Werbe-ID

Verwendet die App eine Werbe-ID: **Nein**

### Vordergrunddienst-Berechtigungen

Typ: **Mikrofon** (`FOREGROUND_SERVICE_MICROPHONE`). Auswahl: Sprach- oder Videoanrufe bzw.
VoIP. Beschreibung:

```text
Mumla Funk is a push-to-talk walkie-talkie for Mumble voice servers. The user starts the
foreground service by connecting to a server. It keeps the voice connection alive while the
app is in the background, so that the user hears incoming transmissions and can talk from the
walkie-talkie popup, a home screen widget or a hardware key. The microphone is only recorded
while the user holds the talk button (push-to-talk). A persistent notification shows the
connection and lets the user disconnect, which stops the service.
```

Video (YouTube „nicht gelistet“ oder Google-Drive-Link) mit der Bildschirmaufnahme von Android:

1. Mit einem Server verbinden, Benachrichtigung zeigen.
2. Zum Homescreen wechseln, über das Widget das Funk-Fenster öffnen.
3. Sprechtaste halten und loslassen.
4. In der Benachrichtigung trennen.

### Bedienungshilfe (Accessibility API)

Die Erklärung erscheint, sobald ein Bundle mit `FunkKeyService` hochgeladen ist.

- Ist die App ein Tool für Barrierefreiheit (`isAccessibilityTool`): **Nein**
- Zweck: App-Funktionalität

```text
Mumla Funk is a push-to-talk walkie-talkie. Its optional accessibility service
"Mumla Funk volume key" only filters hardware key events (canRequestFilterKeyEvents) and
reacts to a single key: holding volume-down for 2 seconds opens the walkie-talkie popup, even
when the app is in the background. A short press is passed on as a normal volume change.
Android only delivers hardware keys to background apps through an accessibility service.
The service does not retrieve window content (canRetrieveWindowContent=false), does not
observe other apps, does not perform actions, and does not store, send or share any data.
Before the user is sent to the system settings, the app shows a prominent disclosure that
explains this and asks for consent (Agree / Decline).
```

Video: Einstellungen › Funk › „Leiser-Taste 2 s halten öffnet Funk“ antippen, Dialog zeigen,
**Ablehnen** (nichts passiert), erneut antippen, **Zustimmen**, Bedienungshilfe einschalten,
zurück, App schließen, Leiser-Taste 2 s halten, Funk-Fenster öffnet sich.

Das ist der Punkt mit dem höchsten Ablehnungsrisiko bei einer echten Prüfung. Falls Google die
Bedienungshilfe ablehnt, bleibt die Leiser-Taste nur im offenen Funk-Fenster als Sprechtaste, die
Ein/Aus-Taste und die Widgets funktionieren weiter.

### Weitere Erklärungen

- Behörden-App: Nein
- Finanzfunktionen: keine
- Gesundheit: keine
- Nachrichten-App: Nein

## 7. Store-Eintrag

Für den internen Test nicht nötig, für jeden öffentlichen Test schon. Play Console ›
**Wachsen › Store-Präsenz › Haupt-Store-Eintrag**. Englisch über „Übersetzungen verwalten“
hinzufügen.

### Texte Deutsch (de-DE)

App-Name (max. 30 Zeichen):

```text
Mumla Funk
```

Kurzbeschreibung (max. 80, aktuell 72 Zeichen):

```text
Walkie-Talkie für Mumble-Server: halten zum Sprechen, loslassen beendet.
```

Vollständige Beschreibung (max. 4000 Zeichen):

```text
Mumla Funk macht Mumble zum Walkie-Talkie. Die App verbindet sich mit normalen Mumble-Servern und ist für Gruppen gedacht, die einen eigenen Mumble-Server als Funknetz nutzen: Jeder spricht per Push-to-Talk, schnell und ohne Hinschauen.

Voraussetzung ist ein Mumble-Server, eigener oder öffentlicher. Mumla Funk betreibt selbst keine Server.

Funktionen:
• Funk-Fenster: ein großer Knopf, halten zum Sprechen, loslassen beendet. Es zeigt Server, Verbindung und wer gerade spricht und erscheint über jeder App und über dem Sperrbildschirm.
• Ein/Aus-Taste gedrückt halten öffnet das Funk-Fenster, wenn Mumla Funk als digitaler Assistent festgelegt ist.
• Leiser-Taste 2 Sekunden halten öffnet das Funk-Fenster, kurz drücken regelt weiterhin die Lautstärke. Dafür nutzt die App eine Bedienungshilfe, die nur die Leiser-Taste auswertet und keine Bildschirminhalte liest.
• Zwei Homescreen-Widgets, jeweils an einen Server gebunden: ein kleiner Sprechknopf und ein großes Widget mit allen Teilnehmern.
• Einfacher Startbildschirm mit Server, Status, Sprechtaste und Teilnehmerliste.
• Zuverlässiges Sende-Ende: Beim Loslassen kommt das Ende der Durchsage sofort an, wie beim Desktop-Client von Mumble.
• Energiesparen: Mikrofon nur beim Senden, Ruhezustand, solange niemand spricht.
• Kanäle, Chat, Zertifikate, Zugriffstoken und Tor bleiben über das Menü erreichbar.

Datenschutz: keine Werbung, kein Tracking, kein Konto. Sprache, Chat und Benutzername gehen nur an die Mumble-Server, die selbst eingetragen werden.

Mumla Funk ist ein inoffizieller Fork von Mumla und lässt sich parallel dazu installieren. Die App ist nicht mit Mumla oder dem Mumble-Projekt verbunden. Freie Software unter der GNU GPL v3, Quellcode: https://github.com/Filubu/mumla-funk
```

### Texte Englisch (en-US)

```text
Mumla Funk
```

```text
Walkie-talkie for Mumble servers: hold to talk, release to stop.
```

```text
Mumla Funk turns Mumble into a walkie-talkie. It connects to regular Mumble servers and is made for groups that use their own Mumble server as a radio network: everyone talks with push-to-talk, quickly and without looking.

You need a Mumble server, your own or a public one. Mumla Funk does not run any servers itself.

Features:
• Walkie-talkie popup: one big button, hold to talk, release to stop. It shows the server, the connection and who is talking, and appears over any app and over the lock screen.
• Hold the power button to open the popup, with Mumla Funk set as digital assistant app.
• Hold volume-down for 2 seconds to open the popup, a short press still changes the volume. For this, the app uses an accessibility service that only evaluates the volume-down key and does not read screen content.
• Two home screen widgets, each bound to a server: a small talk button and a large widget with everyone online.
• Simple home screen with server, status, talk button and user list.
• Reliable end of transmission: when you release the button, the end of the transmission arrives right away, like with the desktop Mumble client.
• Energy saving: microphone only while talking, idle mode while nobody talks.
• Channels, chat, certificates, access tokens and Tor stay available in the menu.

Privacy: no ads, no tracking, no account. Voice, chat and your username only go to the Mumble servers you add yourself.

Mumla Funk is an unofficial fork of Mumla and can be installed next to it. It is not affiliated with Mumla or the Mumble project. Free software under the GNU GPL v3, source code: https://github.com/Filubu/mumla-funk
```

### Grafiken

| Feld | Datei | Vorgabe |
|---|---|---|
| App-Symbol | `grafiken/icon-512.png` | 512 × 512, PNG mit Alpha |
| Feature-Grafik | `grafiken/feature-graphic-de.png`, `-en.png` | 1024 × 500 |
| Screenshots Smartphone | `grafiken/screenshots-de/1.png` bis `4.png`, englisch unter `screenshots-en/` | 2 bis 8 Stück, 1080 × 1920 |

Die Screenshots basieren auf den gerenderten Vorschaubildern aus `docs/images` und sind leicht
unscharf. Für einen öffentlichen Eintrag sollten echte Screenshots vom Handy die Bilder ersetzen,
am besten mit englischer Oberfläche für `en-US`.

Kategorie: **Kommunikation**. Tags: Kommunikation, Walkie-Talkie.

## 8. Automatischer Upload

Optional, erst nach dem ersten Upload von Hand möglich.

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen und die
   **Google Play Android Developer API** aktivieren.
2. Ein Dienstkonto anlegen und einen JSON-Schlüssel herunterladen.
3. Play Console › **Nutzer und Berechtigungen › Neue Nutzer einladen**: die E-Mail des
   Dienstkontos eintragen, für Mumla Funk die Rechte „Releases für Test-Tracks verwalten“ geben.
4. Auf GitHub › Settings › Secrets › Actions das Secret `PLAY_SERVICE_ACCOUNT_JSON` mit dem
   Inhalt der JSON-Datei anlegen.

Danach lädt jeder gepushte Tag `v*` das Bundle in den internen Test. Bei einem manuellen Lauf
passiert das nur mit Haken bei „upload“.

## 9. Später: Produktion

- Neue private Konten (nach dem 13.11.2023 angelegt) brauchen einen geschlossenen Test mit
  mindestens **12 Testern**, die **14 Tage am Stück** dabei sind. Erst danach lässt sich der
  Zugang zur Produktion beantragen.
- Vor der Prüfung: Store-Eintrag mit echten Screenshots, App-Zugriff mit erreichbarem Testserver,
  Videos für Mikrofon-Dienst und Bedienungshilfe.
- Icon und Name sind sehr nah an Mumla, siehe nächster Abschnitt. Das sollte vor einem
  öffentlichen Release geklärt sein.
- Ziel-API: Google hebt die Vorgabe jedes Jahr zum 31.08. an. Upstream-Updates von Mumla bringen
  diese Anpassungen meist mit.

## 10. Upstream Mumla

Geprüft am 03.11.2026: Der Fork enthält alle Commits von Mumla bis `b352860` (21.10.2026),
<https://github.com/quite/mumla> ist ein Spiegel von <https://gitlab.com/quite/mumla> mit dem
gleichen Stand.

- Lizenz: Mumla steht unter der GPL v3. Die Verteilung über Google Play ist damit erlaubt, solange
  der Quellcode jeder veröffentlichten Version verfügbar ist. Daher sollte jede Play-Version
  einen Tag `vX.Y.Z` auf GitHub bekommen. Lizenzdatei, Copyright-Hinweise und der Hinweis auf
  die Änderungen müssen bleiben, die App zeigt sie unter Einstellungen › Über.
- Name: „Mumla Funk“ enthält den Namen des Originals. Google verbietet Apps, die sich als eine
  andere App ausgeben, und Mumla ist selbst im Play Store (`se.lublin.mumla`). Der Store-Text
  sagt daher klar „inoffizieller Fork, nicht verbunden“. Eine kurze Nachricht an den Mumla-Autor
  Daniel Lublin (`mumla@lublin.se`) vor einem öffentlichen Release ist sinnvoll.
- Icon: Das Icon ist das Mumla-Motiv mit Funkgerät statt Headset, gleiche Farbe, gleiche Form.
  Für den internen Test unkritisch. Vor einem öffentlichen Release sollte ein eigenes Icon her,
  da Google das als Verwechslung werten kann.
- Android-Roboter: Das Motiv ist eine Abwandlung des Android-Roboters von Google und darf unter
  CC BY 3.0 genutzt werden, mit Namensnennung. „Android“ darf nicht im App-Namen stehen.
  Hinweis: „The Android robot is reproduced or modified from work created and shared by Google and
  used according to terms described in the Creative Commons 3.0 Attribution License.“
- Mumble: Der Name wird nur beschreibend genutzt („für Mumble-Server“). Das Mumble-Logo aus der
  Mumla-Feature-Grafik ist in den neuen Grafiken bewusst nicht enthalten.
- Spenden: Die Build-Variante `foss` verlinkt auf das Liberapay-Konto von Mumla (`quite`). Das
  bleibt für GitHub, im Play-Build ist der Link entfernt.
- Store-Texte im Repo: `fastlane/metadata/android/` enthält außer `de` und `en-US` noch die
  Mumla-Texte mit dem Titel „Mumla“. Diese Ordner daher nicht mit `fastlane supply` hochladen.
- Updates übernehmen: Die Variante `play` ist neu und kollidiert nicht mit Upstream. Konflikte
  entstehen wie bisher in `app/build.gradle`, `MumlaActivity.java`, `MumlaService.java`,
  `AndroidManifest.xml`, den Icons und jetzt auch `settings_about.xml`.
- Entwicklerverifizierung: Google verlangt seit September 2026 in ersten Ländern, dass auch
  Apps außerhalb von Play von verifizierten Entwicklern stammen, weltweit ab 2027. Für die
  GitHub-APK sollte der Paketname mit dem Schlüssel dann im Entwicklerkonto registriert sein.
  Wie das für Play-Konten genau abläuft, ist noch offen.
