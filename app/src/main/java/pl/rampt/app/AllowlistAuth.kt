package pl.rampt.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Logowanie kontem Google + sprawdzenie, czy zalogowany e-mail jest na liście dozwolonych
 * użytkowników RamPT. Lista jest trzymana w Firestore, w kolekcji "allowedEmails" - jeden
 * dokument na e-mail (ID dokumentu = e-mail małymi literami), treść dokumentu bez znaczenia.
 * Reguły bezpieczeństwa Firestore (patrz SETUP_FIREBASE.md) muszą pozwalać zalogowanemu
 * użytkownikowi odczytać WYŁĄCZNIE dokument o ID równym jego własnemu e-mailowi z tokenu Auth -
 * dzięki temu obecność/brak dokumentu jest wiarygodnym i bezpiecznym sygnałem dostępu.
 *
 * Konfiguracja projektu Firebase (google-services.json, włączenie logowania Google, dodanie
 * dozwolonych e-maili) jest krokiem, który trzeba wykonać ręcznie w konsoli Firebase - patrz
 * SETUP_FIREBASE.md w katalogu głównym repozytorium.
 */
object AllowlistAuth {

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    suspend fun signInWithGoogleIdToken(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        return result.user ?: error("Logowanie nie zwróciło danych użytkownika")
    }

    suspend fun jestNaLiscieDozwolonych(email: String): Boolean {
        val doc = db.collection("allowedEmails").document(email.lowercase()).get().await()
        return doc.exists()
    }

    fun wyloguj() = auth.signOut()
}
