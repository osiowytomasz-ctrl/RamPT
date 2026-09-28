# Konfiguracja dostępu RamPT (Firebase + Google Play)

RamPT ogranicza dostęp do wąskiego grona osób na dwóch niezależnych poziomach. Oba trzeba
skonfigurować ręcznie — jest to jednorazowa konfiguracja w konsolach Google/Firebase, której nie
da się zrobić z poziomu samego kodu.

1. **W aplikacji**: logowanie kontem Google + sprawdzenie e-maila na liście w Firestore.
2. **W Google Play**: kanał testów zamkniętych z listą e-maili testerów.

Bez kroku 1 (a konkretnie bez pliku `google-services.json`) **projekt się nie zbuduje** —
wtyczka `com.google.gms.google-services` wymaga tego pliku.

---

## Część A — projekt Firebase (logowanie + lista dostępu)

### A1. Załóż projekt Firebase
1. Wejdź na [console.firebase.google.com](https://console.firebase.google.com) i kliknij
   **Dodaj projekt**. Nazwa dowolna, np. „RamPT”.
2. Statystyki Google Analytics — nieobowiązkowe, można pominąć.

### A2. Dodaj aplikację Android do projektu
1. Na stronie głównej projektu kliknij ikonę Androida („Dodaj aplikację”).
2. **Nazwa pakietu Android**: `pl.rampt.app` (musi się dokładnie zgadzać z `applicationId`
   w `app/build.gradle.kts`).
3. Pseudonim aplikacji: dowolny, np. „RamPT”.
4. **SHA-1 certyfikatu podpisywania** — wymagany dla logowania Google. Na razie dodaj SHA-1
   klucza debugowego (do testów lokalnych); klucz z Google Play Console (patrz część B) dodasz
   tu później tym samym przyciskiem „Dodaj odcisk palca”:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
   Skopiuj wartość `SHA1:`.
5. Pobierz wygenerowany **`google-services.json`** i zapisz go jako `app/google-services.json`
   w tym repozytorium (dokładnie w tym miejscu — obok `app/build.gradle.kts`). Ten plik jest w
   `.gitignore`, więc nie trafi do repo — każdy, kto buduje appkę, wgrywa go osobno.

### A3. Włącz logowanie Google w Firebase Auth
1. W menu po lewej: **Build → Authentication → Get started**.
2. Zakładka **Sign-in method** → włącz dostawcę **Google**.
3. Zapisz.

### A4. Załóż bazę Firestore i wgraj listę dozwolonych e-maili
1. **Build → Firestore Database → Create database** (tryb produkcyjny, region np. `eur3`).
2. Po utworzeniu bazy przejdź do zakładki **Rules** i wklej zawartość pliku
   [`firestore.rules`](firestore.rules) z tego repozytorium, potem **Publish**.
   Te reguły pozwalają zalogowanemu użytkownikowi sprawdzić WYŁĄCZNIE własny dostęp — nikt nie
   może wylistować ani podejrzeć cudzych e-maili.
3. Zakładka **Data** → **Start collection** → ID kolekcji: `allowedEmails`.
4. Dla każdej osoby, której chcesz dać dostęp, dodaj dokument:
   - **ID dokumentu**: jej adres e-mail Google, **małymi literami** (np. `jan.kowalski@gmail.com`).
   - Zawartość dokumentu: dowolna, może zostać pusta / jedno pole np. `dodano: <data>` —
     liczy się tylko istnienie dokumentu.
5. Żeby dodać/usunąć osobę później, po prostu dodaj/usuń jej dokument w tej kolekcji — appka
   sprawdza listę przy każdym logowaniu, bez potrzeby publikowania nowej wersji.

### A5. (Release) dodaj SHA-1 klucza produkcyjnego
Gdy będziesz podpisywać appkę kluczem release (do publikacji w Google Play), dodaj też SHA-1
tego klucza w **Ustawienia projektu Firebase → Twoje aplikacje → Dodaj odcisk palca**, inaczej
logowanie Google nie zadziała na podpisanej wersji.
Jeśli używasz **Play App Signing** (zalecane, domyślne w Play Console), SHA-1 do dodania
znajdziesz w Play Console → **Konfiguracja → Integralność aplikacji → Podpisywanie aplikacji**.

---

## Część B — Google Play: ograniczenie widoczności do listy e-maili

To druga, niezależna warstwa — kontroluje, kto w ogóle zobaczy/pobierze appkę ze sklepu
(niezależnie od logowania w środku).

1. Załóż appkę w [Play Console](https://play.google.com/console) (konto Play Console Developer,
   jednorazowa opłata rejestracyjna Google).
2. **Testowanie → Testy zamknięte (Closed testing)** → utwórz nowy kanał (np. „RamPT — zespół”).
3. W sekcji **Testerzy** dodaj listę e-maili (te same adresy, które dodałeś do `allowedEmails`
   w Firestore) — albo bezpośrednio jako listę adresów e-mail, albo przez grupę Google.
4. Wgraj build (AAB) i opublikuj do tego kanału.
5. Osoby z listy dostają link „Zostań testerem” — po jego otwarciu i akceptacji mogą pobrać
   appkę ze sklepu.

> To w pełni zgodne z polityką Google Play — testy zamknięte z listą e-maili to oficjalny,
> zalecany mechanizm ograniczania dystrybucji do konkretnej grupy osób.

---

## Podsumowanie: dodawanie / usuwanie osoby z dostępu

| Co zmienić | Gdzie |
|---|---|
| Kto widzi appkę w Google Play | Play Console → kanał testów zamkniętych → lista testerów |
| Kto może się zalogować w appce | Firebase Console → Firestore → kolekcja `allowedEmails` |

Obie listy warto trzymać zsynchronizowane (te same adresy), ale to dwa osobne miejsca do edycji.
