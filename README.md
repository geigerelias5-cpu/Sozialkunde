# Sozialkunde – Prüfungstrainer

280 Prüfungsfragen Sozialkunde zum Lernen, Üben und Prüfung simulieren. Nothing-Design.

- **Download-Seite:** https://geigerelias5-cpu.github.io/Sozialkunde/
- **iPhone-Web-App:** https://geigerelias5-cpu.github.io/Sozialkunde/app/ – in Safari öffnen → Teilen → „Zum Home-Bildschirm“
- **Android-App (APK):** https://github.com/geigerelias5-cpu/Sozialkunde/releases/latest/download/Sozialkunde.apk

Der Lernfortschritt bleibt nur auf dem Gerät. Nichts wird hochgeladen.

## Aufbau

- `app/` – Android-App (WebView), die APK baut GitHub Actions bei jedem Push und veröffentlicht sie als Release. Signiert mit `app/sozialkunde-release.p12`, Passwort nur als Secret `SK_KEY_PASSWORD`.
- `docs/` – GitHub Pages: Download-Seite und iPhone-Web-App (`docs/app/`, offline-fähig).
- Die Fragen stecken in `index.html` (Android: `app/src/main/assets/`, Web: `docs/app/`).
