# RamPT — Pomiar i Trasowanie

Aplikacja Android dla wąskiego grona użytkowników, wydzielona z projektu TrasownicaBT.
Zawiera tylko dwa moduły:

1. **Trasowanie** — wyznaczanie i znakowanie punktów (Bluetooth: kąt/X/Y/L z Nucleo przez ESP32).
2. **Element cylindryczny** — pomiar walcowości, owalizacji i bicia płaszcza, przełamanie
   spoin wzdłużnych, raport PDF.

Dostęp jest ograniczony do zaproszonych osób na dwóch poziomach:

- **Google Play** — dystrybucja przez kanał testów zamkniętych (closed testing) z listą
  e-maili testerów, więc appka jest widoczna/pobieralna tylko dla nich.
- **W aplikacji** — logowanie kontem Google, a e-mail zalogowanej osoby musi być na liście
  dozwolonych w Firebase (Firestore). Bez wpisu na liście appka nie wpuści dalej niż ekran
  logowania, niezależnie od tego, skąd pochodzi APK.

Pełna instrukcja konfiguracji logowania i listy dostępu: [SETUP_FIREBASE.md](SETUP_FIREBASE.md)
(**wymagana przed pierwszym zbudowaniem projektu** — bez `google-services.json` build się nie powiedzie).

## Jak uruchomić

1. Wykonaj kroki z `SETUP_FIREBASE.md` (Firebase, `google-services.json`, lista dozwolonych e-maili).
2. Android Studio → **File → Open** → wskaż ten folder (`RamPT`).
3. Poczekaj na „Gradle sync" (pobierze wtyczki, MPAndroidChart z JitPack, zależności Firebase).
4. Sparuj telefon z modułem BT w ustawieniach systemowych (potrzebne w obu modułach).
5. Uruchom aplikację na telefonie (nie na emulatorze — emulator nie ma BT).
6. Zaloguj się kontem Google z listy dozwolonych.

## Format danych BT (musi zgadzać się z firmware)
Linia CSV: `kat;X;Y;L\r\n` — separator `;`, przecinek dziesiętny, `L` = "brak" gdy brak suwmiarki.

## Pochodzenie kodu

Ten projekt jest wydzieleniem dwóch modułów (Trasowanie, Element cylindryczny) z repozytorium
`TrasownicaBT` (`pl.trasownica.bt` → `pl.rampt.app`). Logika pomiarowa i UI tych modułów są
przeniesione bez zmian; dodano wyłącznie ekran logowania i weryfikację dostępu.
