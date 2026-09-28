// Wersje wtyczek. Android Studio może zaproponować nowsze - można się zgodzić.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Wymagana przez Firebase (wczytuje app/google-services.json i generuje konfigurację SDK).
    id("com.google.gms.google-services") version "4.4.2" apply false
}
