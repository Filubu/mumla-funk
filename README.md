# Mumla Funk

Mumla Funk ist eine Walkie-Talkie-Variante von [Mumla](https://gitlab.com/quite/mumla)
(Mumble-Client für Android). Das Mumble-Backend ist dasselbe, man verbindet sich mit ganz
normalen Mumble-Servern. Die App lässt sich parallel zu Mumla installieren
(Paket `io.github.filubu.mumlafunk`).

## Funktionen

- **Funk-Fenster**: ein großer Knopf, gedrückt halten zum Sprechen. Zeigt Server,
  Verbindung und wer gerade spricht. Nicht verbunden: Knopf antippen verbindet.
  Solange es offen ist, ist die Leiser-Taste die Sprechtaste (abschaltbar).
- **Ein/Aus-Taste gedrückt halten** öffnet das Funk-Fenster (über die Assistent-Funktion).
- **Leiser-Taste 2 Sekunden halten** öffnet das Funk-Fenster. Kurz drücken regelt
  weiterhin die Lautstärke.
- **Zwei Homescreen-Widgets**, jeweils fest an einen Server gebunden:
  ein kleiner runder Funk-Knopf und ein großes Widget mit Status, allen Teilnehmern
  und Sprechen/Verbinden. Nicht verbunden = durchgestrichener Funkmast.
- **Einfacher Startbildschirm** mit Server, Status, Sprechtaste und Teilnehmerliste.
  Die volle Mumla-Oberfläche (Kanäle, Chat, Server verwalten) bleibt im Menü.
- **Sauberes Sende-Ende**: Beim Loslassen wird immer das Ende-Flag (Terminator) gesendet,
  wie beim Desktop-Client. In Mumla fehlte es etwa bei jedem zweiten Loslassen.

## Einrichtung

1. App installieren, Server anlegen, verbinden (beim ersten Mal ggf. Zertifikat bestätigen).
2. **Ein/Aus-Taste**: Einstellungen › Funk › „Ein/Aus-Taste halten öffnet Funk“ antippen
   und Mumla Funk als *Standard-App für den digitalen Assistenten* wählen. Dann in den
   Android-Einstellungen unter System › Gesten › „Ein/Aus-Taste gedrückt halten“ den
   digitalen Assistenten einstellen (Pixel; bei anderen Herstellern ähnlich).
   Auf gesperrtem Gerät fragt Android dabei zuerst nach dem Entsperren.
3. **Leiser-Taste**: Einstellungen › Funk › „Leiser-Taste 2 s halten öffnet Funk“ antippen
   und die Bedienungshilfe „Mumla Funk“ aktivieren. Ist der Schalter ausgegraut
   (App nicht aus einem Store installiert): App-Info › ⋮ › „Eingeschränkte Einstellungen
   zulassen“, danach nochmal. Funktioniert nur bei eingeschaltetem Bildschirm.
4. **Widgets**: Homescreen lange drücken › Widgets › Mumla Funk, Server auswählen.
   Android-Widgets erkennen nur Antippen (kein Halten), deshalb öffnen sie das Funk-Fenster.

## Releases bauen

Beim Push eines Tags `v*` erstellt der Workflow `.github/workflows/release.yml`
den GitHub-Release. Liegt im getaggten Commit eine fertig signierte APK unter
`release/`, wird diese veröffentlicht. Sonst baut der Workflow selbst und signiert mit
dem Schlüssel aus den Repository-Secrets:

- `MUMLA_FUNK_KEYSTORE_BASE64`: der Keystore, base64-kodiert (`base64 -w0 mumla-funk-release.jks`)
- `MUMLA_FUNK_KEYSTORE_PASSWORD`: das Keystore-Passwort (Alias `mumlafunk`)

Updates lassen sich nur installieren, wenn sie mit **demselben** Schlüssel signiert sind.
Der Schlüssel gehört deshalb sicher aufbewahrt und **nie** ins Repository.

Lokal bauen: `git submodule update --init --recursive`, Android SDK + NDK 25.1.8937393,
dann in `libraries/humla/libs/humla-spongycastle` `../../gradlew jar` und im
Hauptordner `./gradlew assembleFossDebug`.

---


# Software maintenance situation

Current maintainer [Daniel Lublin](https://lublin.se)
([@quite@mstdn.social](https://mstdn.social/@quite) on fedi) has very
little time to do voluntary work on Mumla. My focus is strictly on
maintaining stability and security. This includes migrations to newer
Android SDKs, as they become requirements by Google/Alphabet for even
getting updates published on Google Play. There are also other
maintenance and administrative work, which barely gets done in a
timely manner.

At some point I expect Mumla to disappear from Google Play, because
there will be some requirement that I did not have time to fulfill.
Eventually it will also rot and no longer work well in general on
newer releases of Android.

Mumla needs a new maintainer that can allocate time to take on, to
begin with, all these tasks. To maintain stability and security. And
then hopefully also work with the community on for example protocol
parity with desktop Mumble, support for various hardware accessories,
general usability, and new features.

Until there is a new maintainer with time on their hands you cannot
expect new features, or even the continued existance of a usable
Mumble app for Android.

# Mumla

Mumla is a fork and continuation of
[Plumble](https://github.com/acomminos/Plumble), a robust GPLv3 Mumble
client for Android originally written by Andrew Comminos. It uses the
the [Humla](https://gitlab.com/quite/humla) protocol implementation
(forked from Comminos's
[Jumble](https://github.com/acomminos/Jumble)).

Mumla should run on Android 5.0 (Lollipop, "L", SDK/API 21) and later.

Mumla is available [on
F-Droid](https://f-droid.org/packages/se.lublin.mumla/).

There is a small [landing page](https://mumla-app.gitlab.io/).

## FAQs -- Frequently Asked Questions

### Action that my user has permission for does not show up in overflow menu

Question: The Mumble server I use has an ACL that should give my user
(or a group it's in) permission to carry out a specific action (like
"Move"). Why doesn't Mumla show this action in the overflow menu
(three dots) for a channel or user?

Answer: Try to disconnect and then reconnect to the server. The
decision to show a menu item depending on whether the user has the
required permission is done upon connecting, when the UI is set up. It
is *not* updated on the fly if permissions change while connected.

## Translations

If you want to help out translating Mumla, the project is [on
Weblate](https://hosted.weblate.org/engage/mumla/) -- thanks for
gratis hosting of our libre project!

## Repository submodules

Note that this Mumla git repository has submodule(s). You either need
to clone it using `git clone --recursive`, or you need to get the
submodule(s) in place after cloning:

    git submodule update --init --recursive

## Building on GNU/Linux

Building is verified to work using JDK 21. So you typically want to
set and export the JAVA_HOME environment variable like `export
JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.

The Android SDK need to be specified as usual, for example by setting
`ANDROID_SDK_ROOT`, or writing it to local.properties as `echo
>local.properties sdk.dir=/home/user/Android/Sdk`

TODO: humla-spongycastle should be built as a sub-project of Humla's
Gradle, but currently isn't.

    git submodule update --init --recursive

    pushd libraries/humla/libs/humla-spongycastle
    ../../gradlew jar
    popd

    ./gradlew assembleDebug

If you get an error running out of Java heap space, try raising the
-Xmx in `./gradle.properties`.

### Notes on NDK

The NDK is the toolchain used for building the native code (C/C++) of
Humla. We specify the version needed using `ndkVersion` in
`libraries/humla/build.gradle`.

We currently use Android Gradle Plugin (AGP) version 8.x, which should
come bundled with NDK 25.1.8937393 that we currently use. It is
typically installed in a directory in `~/Android/Sdk/ndk/`. Using
newer NDK might give build errors. See also:
https://developer.android.com/studio/projects/install-ndk

If Android Studio does not automatically install the mentioned version
of the NDK in the mentioned directory, then you may be able to get it
installed by using the SDK Manager:

- Click SDK Tools tab.
- Check "Show Package Details"
- In the list view, expand "NDK (Side by side)"
- Check 25.1.8937393
- Click OK

### Container image

I'm also maintaining a container image that is used to build Mumla in
[.gitlab-ci.yml](.gitlab-ci.yml). Source at
https://gitlab.com/quite/android-sdk-ndk

## License

Mumla's [LICENSE](LICENSE) is GNU GPL v3.
