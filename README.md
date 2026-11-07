# MTX Build Control

Eigenständige Android-App (Kotlin, Jetpack Compose, Material 3), Android 9 oder neuer. AMOLED-Schwarz, matte Karten und ultraviolette Akzente.

## Einrichtung

1. APK aus einem erfolgreichen **Android APK**-Lauf oder einem `build-N`-Release herunterladen. Bei einem ZIP die APK entpacken. Android-Systeminstallation bestätigen.
2. In GitHub unter **Settings → Developer settings → Personal access tokens → Fine-grained tokens** einen befristeten Token erstellen. Resource owner: eigener GitHub-Account. Nur die gewünschten Repositories auswählen, einschließlich `MTXBuildControl` für private Updatezugriffe.
3. Repository permissions: **Contents: Read and write**, **Actions: Read and write**, **Administration: Read-only**. Metadata: Read ist automatisch dabei. Keine Secrets-, Account- oder Administration-Schreibrechte vergeben. Workflows-Schreibrechte sind für die App nicht nötig, da sie nur die JSON-Konfiguration ändert.
4. Token ausschließlich in der App eingeben. **Sicher verbinden** prüft den GitHub-Account. Token weder in Issues noch in Chats oder Quellcode eintragen.
5. Projekt wählen, Branch prüfen. Ein Runner-Wechsel zeigt Repository, Branch und Ziel zur ausdrücklichen Bestätigung an. Für PC muss RICCI eindeutig online sein und die Labels `self-hosted`, `Windows`, `X64`, `RICCI` haben.

Die App verwendet bewusst einen Fine-grained Token: Es ist noch kein eigener GitHub-OAuth-Client registriert. Sie enthält keine OAuth-Secrets oder eingebauten Zugangsdaten. Ein späterer OAuth-Client kann die Authentifizierung ersetzen, ohne den Runner-Vertrag zu ändern.

## Vorhandene Runner-Steuerung

Die Quelle ist `mellowtraxer/RiccisTidalPlaylistCreator`, Branch `feature/mtx-studio-core`: `MTX-PC-Runner.bat`, `MTX-GitHub-Runner.bat`, `scripts/switch-runner.ps1`, `scripts/runner-policy.cjs` und `.github/mtx-runner.json` wurden untersucht.

Die App reproduziert denselben Vertrag, keine zweite Umschaltlogik:

- GET der Workflow-Dateien und `.github/mtx-runner.json` auf dem ausgewählten Branch.
- Nur `mode: pc` oder `mode: github` akzeptieren.
- Den bestehenden `select-runner`-Vertrag und dessen dynamisches `runs-on` erkennen.
- Für PC alle Runner-Seiten prüfen, einschließlich Name, OS, Onlinezustand, Labels und kollidierender RICCI-Labels.
- PUT ausschließlich der JSON-Datei mit ihrem aktuellen Blob-SHA als optimistischer Sperre.
- Konfiguration nach erfolgreichem Commit erneut laden und vergleichen.
- Bereits aktiver Modus erzeugt keinen Commit. Keine Cancel-, Delete-, Runner-Registrierungs- oder Service-Endpunkte.

Ein laufender oder bereits wartender Build behält seinen ursprünglichen Commit und Runner. RICCI erst ausschalten, wenn sämtliche PC-Jobs abgeschlossen sind. Cloud startet den PC nicht und fährt ihn nicht herunter.

Weitere Projekte können hinzugefügt werden. Branches werden gegen GitHub geprüft und je Projekt gespeichert. Projekte ohne kompatiblen Vertrag bleiben für Builds/APKs verwendbar; Runner-Wechsel bleibt dort ausdrücklich gesperrt. Es werden keine unbekannten Workflow-Dateien automatisch umgeschrieben. SleepSync ist auf dem aktuellen Branch `sleepsync-compose-v2` voreingestellt. Dort verwendet der Workflow denselben JSON-Vertrag; der bestehende Cloud-Build läuft weiterhin unter Linux, der PC-Pfad gesondert unter Windows. RICCI muss für das jeweilige Repository registriert und online sein. Ein nicht registrierter oder offline gemeldeter PC wird durch die App abgelehnt.

## Builds, APKs und Updates

- Workflows aus dem gewählten Branch, manuelle Starts nur bei `workflow_dispatch`; Eingaben inklusive Defaults, Pflichtfeldern und Auswahlwerten.
- Letzte 30 Builds des Branches, Status, Nummer, SHA, Dauer, GitHub-Link; rote Jobs und verfügbare Check-Annotations.
- Veröffentlichte APK-Assets und Artefakte der letzten fünf Branch-Builds. Nicht jedes Artefakt enthält eine APK; die App meldet dies beim Prüfen.
- Authentifizierter Download, APK-Extraktion mit Größenlimit und selbst erzeugten Dateinamen, kein ZIP-Pfad wird übernommen.
- APK-Paket, Version und Buildnummer aus der heruntergeladenen APK. Android-Systeminstaller mit FileProvider und expliziter Bestätigung. Androids Berechtigung für diese Installationsquelle muss einmal erteilt werden.
- Klick auf die Versionsanzeige prüft `MTXBuildControl`-Releases `build-N`. Eigene Updates benötigen eine höhere Version und dieselbe Signatur wie die installierte App. Android prüft zusätzlich die Paketinstallation.
- Keine Releases, bestehenden APKs oder Nutzerdaten werden gelöscht. Keine optionalen Hintergrundbenachrichtigungen in dieser ersten Version. Aktualisieren erfolgt bewusst über **Laden**.

## Sicherheit

Token verschlüsselt mit AES-256/GCM und einem nicht exportierten Android-Keystore-Schlüssel; App-Backup deaktiviert, Screenshots und Vorschauen für die App geschützt. Keine HTTP-Verbindungen in der Android-App. GitHub-Authentifizierung wird bei einem Hostwechsel durch den HTTP-Client nicht an die Download-Domain weitergereicht. Beim Abmelden wird der lokale Token entfernt; vollständiger Widerruf erfolgt in GitHub.

Alle schreibenden GitHub-Aktionen erfordern einen Dialog in der App. Branchschutz, Token-Auswahl und GitHub-Berechtigungen werden nicht umgangen. Netzwerkausfälle während eines Schreibzugriffs können den Erfolg unklar lassen: neu laden und den tatsächlichen GitHub-Zustand prüfen, bevor erneut bestätigt wird.

## Bauen und testen

JDK 17, Android SDK 35, Gradle 8.13:

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
gradle :app:connectedDebugAndroidTest
```

CI läuft ausschließlich auf `ubuntu-latest` und einem GitHub-gehosteten Android-Emulator. APK-Artifact wird nach dem Build hochgeladen; ein Release erst nach dem Emulator-Starttest. Keine CI-Abhängigkeit von RICCI.

**Debug-Signatur:** CI bewahrt den automatisch erzeugten Debug-Keystore über den GitHub-Actions-Cache auf. Das ist keine dauerhafte Release-Signaturgarantie: Falls GitHub den Cache entfernt, kann sich die Debug-Signatur ändern. Die App lehnt dann ein eigenes OTA-Update ab, statt eine ungültige Aktualisierung zu behaupten. Für dauerhaft verlässliche Produktions-OTA muss ein eigener privater Release-Keystore als Repository-Secret eingerichtet werden. Keine privaten Signierschlüssel im Repository. Eine lokale Debug-APK kann eine andere Signatur als CI besitzen.

Tests umfassen Runner-Validierung, YAML-Dispatch-Erkennung, SHA-gesichertes Schreiben, Konfliktabbruch ohne Retry, Idempotenz und Pagination mit MockWebServer. Der Android-Instrumentierungstest startet die echte Activity und prüft den Verbindungsbildschirm. Ein zusätzlicher CI-Integrationstest liest Workflows und den aktuellen Build über die echte GitHub-API mit dem auf dieses Repository begrenzten CI-Token. Lokal wird dieser Test ausdrücklich übersprungen. Authentifiziertes Umschalten auf einem echten Pixel ist damit nicht nachgewiesen und wird gesondert ausgewiesen.
