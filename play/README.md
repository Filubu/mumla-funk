# Mumla Funk bei Google Play

Diese Anleitung führt vom Entwicklerkonto bis zum internen Test. Alle Texte und Antworten für
die Play Console stehen hier zum Kopieren bereit. Der Ordner `grafiken/` enthält Icon,
Feature-Grafik und Screenshots.

Im Repo vorbereitet:

- Eine einzige Build-Variante für GitHub und Play: ohne Spenden-Links, ohne Billing-Bibliothek,
  ohne Bedienungshilfe, ohne Absturzbericht und ohne die Berechtigung für die Akku-Ausnahme.
  Damit fallen die Erklärungen zu Bedienungshilfe und Zahlungen weg (seit 1.0.5).
- Workflow `.github/workflows/play.yml`: baut das signierte App Bundle, optional mit Upload in den internen Test
- Link zur Datenschutzerklärung unter Einstellungen › Über
- Version 1.0.5 (Versionscode 1000500) mit Release Notes in `fastlane/metadata/android/*/changelogs/1000500.txt`
- Website mit Datenschutzerklärung im Repo [mumla-funk-website](https://github.com/Filubu/mumla-funk-website)

## Prüfung der Play-Anforderungen

Stand: November 2026.

| Anforderung | Status |
|---|---|
| Ziel-API 36, Pflicht für neue Apps und Updates seit 31.08.2026 | erfüllt, `targetSdk 36` |
| Format App Bundle (`.aab`) | `./gradlew bundlePlayRelease` oder Workflow |
| 16-KB-Speicherseiten für 64-Bit-Code, Pflicht seit 01.11.2025 | erfüllt, die Codecs werden mit `max-page-size=16384` gelinkt, im Test-Build geprüft |
| Zahlungsrichtlinie | erfüllt: keine Spenden-Links, kein Billing |
| Bedienungshilfe (Accessibility API) | entfällt: seit 1.0.5 nicht mehr in der App |
| Akku-Optimierung (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | entfällt: Berechtigung entfernt, die App öffnet nur die Systemliste |
| Datensicherheit | „Keine Daten erhoben“, siehe [Datensicherheit](#datensicherheit) |
| Vordergrunddienst Typ Mikrofon | Erklärung mit Video in der Play Console offen. Pflicht für jede App, die im Hintergrund Sprache überträgt, lässt sich nicht vermeiden |
| Datenschutzerklärung | erfüllt: <https://funk.filubu.de/datenschutz.html> (netcup), auch in der App verlinkt |
| Werbe-ID | nicht genutzt, die Berechtigung `AD_ID` fehlt im Manifest |
| Paketname | `de.filubu.funk`, nach dem ersten Upload nicht mehr änderbar |

Der interne Test braucht davon nur das App Bundle. Google prüft interne Tests in der Regel nicht
und gibt sie nach wenigen Minuten frei. Die Erklärungen unter
[App-Inhalte](#6-app-inhalte) sollten trotzdem gleich ausgefüllt werden, da die Play Console
eine Freigabe mit Mikrofon-Dienst teilweise erst danach zulässt.

## 1. Entwicklerkonto

1. Unter <https://play.google.com/console/signup> ein Konto vom Typ **Privatperson** anlegen.
   Die Gebühr beträgt einmalig 25 USD.
2. Identität mit Ausweis bestätigen, Telefonnummer bestätigen.
3. Zugriff auf ein echtes Android-Gerät bestätigen: Die App *Play Console* auf dem Handy
   installieren und dort anmelden.
4. Öffentliche Angaben setzen:
   - Name des Entwicklers: `Filubu`
   - E-Mail-Adresse: `info@filubu.de` (wird im Play Store öffentlich angezeigt)
   - Website: `https://funk.filubu.de/`
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
2. Unter „Tag or branch“ `master` oder einen Tag wie `v1.0.5` eintragen, „upload“ leer lassen.
3. Nach dem Lauf unter **Artifacts** `mumla-funk-play` herunterladen und entpacken. Darin
   liegen `mumla-funk-1.0.5.aab` und die Release Notes unter `whatsnew/`.

Ein gepushter Tag `v*` baut das Bundle ebenfalls, parallel zum GitHub-Release.

Lokal:

```bash
(cd libraries/humla/libs/humla-spongycastle && ../../gradlew jar)
```

```bash
./gradlew bundleRelease
```

Das Bundle liegt danach unter `app/build/outputs/bundle/release/mumla-funk-release.aab`.
Signiert wird es nur mit `app/signing.gradle`, siehe Haupt-README.

## 5. Interner Test

1. **Test und Release › Testen › Interner Test › Tester**: eine E-Mail-Liste anlegen, z. B.
   „Funkgruppe“, und die Google-Konten der Tester eintragen. Maximal 100 Tester.
2. **Neuen Release erstellen**, die `.aab` hochladen.
3. Release-Name: `1.0.5`. Release Notes aus `whatsnew/` oder hier kopieren:

   ```text
   <de-DE>
   * Neuer Startbildschirm mit Hilfe für den Einstieg
   * Pro Server wählbar: Funk-Server mit großem Sprechknopf oder normale Mumble-Ansicht
   * Server einrichten mit Beispielen und klaren Hinweisen
   * Besser lesbar im dunklen Design
   * Entfernt: Leiser-Taste 2 s (Bedienungshilfe), Absturzbericht, Spenden-Links
   * Andere Apps können keine Verbindung und kein Senden mehr auslösen
   </de-DE>
   <en-US>
   * New start screen with help for getting started
   * Per server: walkie-talkie server with a big talk button, or the usual Mumble view
   * Server setup with examples and clear hints
   * Better readable in the dark theme
   * Removed: hold volume-down for 2 s (accessibility service), crash report, donation links
   * Other apps can no longer start a connection or a transmission
   </en-US>
   ```

4. **Weiter › Speichern › Release überprüfen › Einführung im internen Test starten**.
5. Unter **Tester** den Link „Tester-Einladung kopieren“ an die Gruppe schicken. Jeder Tester
   öffnet den Link mit seinem Google-Konto, nimmt teil und installiert über den Play Store.

Hinweise:

- Seit 1.0.4 ist die Paket-ID `de.filubu.funk`. Wer die GitHub-APK 1.0.3 oder älter hat
  (`io.github.filubu.mumlafunk`), bekommt die Play-Version als eigene, zweite App und muss Server
  und Zertifikat einmal übertragen. Ab 1.0.4 sind GitHub-APK und Play-Version gegenseitig
  updatefähig, da Paket-ID und Schlüssel gleich sind.
- Jeder neue Upload braucht einen höheren Versionscode als der vorherige. Version und Code stehen
  in `app/build.gradle` (`funkVersion`, `versionCode`).
- Für einen schnellen Test ohne Track gibt es zusätzlich **Interne App-Freigabe**: Bundle hochladen
  und Link teilen, ohne Erklärungen und ohne Versionscode-Regel.

## 6. App-Inhalte

Play Console › **Richtlinien › App-Inhalte**. Die Antworten gelten für die aktuelle Version.

### Datenschutzerklärung

```text
https://funk.filubu.de/datenschutz.html
```

Die Seite muss erreichbar sein, bevor sie eingetragen wird, siehe README der Website.

### App-Zugriff

Für den internen Test egal, vor einer Prüfung aber nötig: Ohne Mumble-Server lässt sich die App
nicht sinnvoll prüfen. Auswahl **Alle oder einige Funktionen sind eingeschränkt** und eine
Anleitung mit Testserver eintragen:

```text
Mumla Funk is a client for Mumble voice servers. To test it, add this server:
On the start screen, tap "Add server" and enter
  Address: [SERVER-ADRESSE]
  Port: [PORT, Standard 64738]
  Username: any name without spaces, e.g. GoogleReview
  Password: [leer lassen oder PASSWORT]
  Walkie-talkie server: on
Tap "Add", then tap the server to connect and choose "Trust and connect" for the
server certificate. Hold the big button ("Hold to talk") to talk.
Alternatively, "Browse public servers" on the start screen lists public Mumble
servers; they open in the regular Mumble view with channels and chat.
```

Offen: welcher Server für die Prüfung erreichbar ist, eigener Server oder ein öffentlicher.
Die öffentliche Serverliste ist eine Notlösung, sie zeigt aber nicht die Funk-Bedienung.

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

Diese Erklärung lässt sich nicht vermeiden: Ohne Vordergrunddienst beendet Android die
Verbindung, sobald die App im Hintergrund ist. Jede Sprach- und Funk-App braucht sie.

Video (YouTube „nicht gelistet“ oder Google-Drive-Link) mit der Bildschirmaufnahme von Android:

1. Mit einem Server verbinden, Benachrichtigung zeigen.
2. Zum Homescreen wechseln, über das Widget das Funk-Fenster öffnen.
3. Sprechtaste halten und loslassen.
4. In der Benachrichtigung trennen.

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
Mumla Funk macht Mumble zum Walkie-Talkie. Die App verbindet sich mit jedem Mumble-Server, eigenem oder öffentlichem. Für jeden Server legst du fest, ob er ein Funk-Server ist: Dann gibt es einen großen Sprechknopf, das Funk-Fenster und Widgets. Sonst zeigt die App die gewohnte Mumble-Ansicht mit Kanälen und Chat.

Voraussetzung ist ein Mumble-Server. Mumla Funk betreibt selbst keine Server.

Funktionen:
• Funk-Fenster: ein großer Knopf, halten zum Sprechen, loslassen beendet. Es zeigt Server, Verbindung und wer gerade spricht und erscheint auch über dem Sperrbildschirm. Solange es offen ist, ist die Leiser-Taste die Sprechtaste.
• Ein/Aus-Taste gedrückt halten öffnet das Funk-Fenster, wenn Mumla Funk als digitaler Assistent festgelegt ist.
• Zwei Homescreen-Widgets, jeweils an einen Server gebunden: ein kleiner Sprechknopf und ein großes Widget mit allen Teilnehmern.
• Zuverlässiges Sende-Ende: Beim Loslassen kommt das Ende der Durchsage sofort an, wie beim Desktop-Client von Mumble.
• Energiesparen: Mikrofon nur beim Senden, Ruhezustand, solange niemand spricht.
• Kanäle, Chat, Zertifikate, Zugriffstoken und Tor bleiben über das Menü erreichbar.

Datenschutz: keine Werbung, kein Tracking, kein Konto, keine Datensammlung. Sprache, Chat und Benutzername gehen nur an die Mumble-Server, die du selbst einträgst.

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
Mumla Funk turns Mumble into a walkie-talkie. It connects to any Mumble server, your own or a public one. For each server you choose whether it is a walkie-talkie server: then you get a big hold-to-talk button, the walkie-talkie popup and widgets. Otherwise the app shows the usual Mumble view with channels and chat.

You need a Mumble server. Mumla Funk does not run any servers itself.

Features:
• Walkie-talkie popup: one big button, hold to talk, release to stop. It shows the server, the connection and who is talking, and also appears over the lock screen. While it is open, volume-down is the talk key.
• Hold the power button to open the popup, with Mumla Funk set as digital assistant app.
• Two home screen widgets, each bound to a server: a small talk button and a large widget with everyone online.
• Reliable end of transmission: when you release the button, the end of the transmission arrives right away, like with the desktop Mumble client.
• Energy saving: microphone only while talking, idle mode while nobody talks.
• Channels, chat, certificates, access tokens and Tor stay available in the menu.

Privacy: no ads, no tracking, no account, no data collection. Voice, chat and your username only go to the Mumble servers you add yourself.

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
  Video für den Mikrofon-Dienst.
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
- Spenden: Seit 1.0.5 enthält die App keine Spenden-Links mehr, auch die GitHub-APK nicht.
- Store-Texte im Repo: `fastlane/metadata/android/` enthält außer `de` und `en-US` noch die
  Mumla-Texte mit dem Titel „Mumla“. Diese Ordner daher nicht mit `fastlane supply` hochladen.
- Updates übernehmen: Mumla Funk hat seit 1.0.5 keine Build-Varianten (Flavors) mehr. Konflikte
  entstehen daher in `app/build.gradle`, bei den Flavor-Ordnern (`app/src/foss` usw., einfach
  gelöscht lassen), in `MumlaActivity.java`, `MumlaService.java`, `AndroidManifest.xml`, den
  Icons und `settings_about.xml`.
- Entwicklerverifizierung: Google verlangt seit September 2026 in ersten Ländern, dass auch
  Apps außerhalb von Play von verifizierten Entwicklern stammen, weltweit ab 2027. Für die
  GitHub-APK sollte der Paketname mit dem Schlüssel dann im Entwicklerkonto registriert sein.
  Wie das für Play-Konten genau abläuft, ist noch offen.
