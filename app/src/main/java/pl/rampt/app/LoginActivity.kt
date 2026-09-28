package pl.rampt.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import pl.rampt.app.databinding.ActivityLoginBinding

/** Ekran startowy: logowanie kontem Google + weryfikacja e-maila na liście dozwolonych
 *  (patrz [AllowlistAuth] i SETUP_FIREBASE.md w repo). */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnGoogleSignIn.setOnClickListener { rozpocznijLogowanie() }

        // Sesja Firebase przetrwa restart appki - weryfikujemy ją ponownie przy każdym starcie,
        // na wypadek gdyby administrator usunął e-mail z listy dozwolonych w międzyczasie.
        val zalogowanyEmail = AllowlistAuth.currentUser?.email
        if (zalogowanyEmail != null) sprawdzListeIWejdz(zalogowanyEmail)
    }

    private fun rozpocznijLogowanie() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        ustawStan(trwa = true, komunikat = "Logowanie…")
        lifecycleScope.launch {
            try {
                val credentialManager = CredentialManager.create(this@LoginActivity)
                val result = credentialManager.getCredential(this@LoginActivity, request)
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                val user = AllowlistAuth.signInWithGoogleIdToken(googleIdTokenCredential.idToken)
                val email = user.email
                if (email == null) {
                    AllowlistAuth.wyloguj()
                    ustawStan(trwa = false, komunikat = "Konto Google bez adresu e-mail — nie można zweryfikować dostępu.")
                    return@launch
                }
                sprawdzListeIWejdz(email)
            } catch (e: GetCredentialException) {
                ustawStan(trwa = false, komunikat = "Logowanie anulowane.")
            } catch (e: Exception) {
                ustawStan(trwa = false, komunikat = "Błąd logowania: ${e.message}")
            }
        }
    }

    private fun sprawdzListeIWejdz(email: String) {
        ustawStan(trwa = true, komunikat = "Sprawdzanie dostępu…")
        lifecycleScope.launch {
            val dozwolony = try {
                AllowlistAuth.jestNaLiscieDozwolonych(email)
            } catch (e: Exception) {
                ustawStan(trwa = false, komunikat = "Błąd sprawdzania dostępu: ${e.message}")
                return@launch
            }
            if (dozwolony) {
                startActivity(Intent(this@LoginActivity, MenuActivity::class.java))
                finish()
            } else {
                AllowlistAuth.wyloguj()
                ustawStan(trwa = false, komunikat = "Brak dostępu dla $email.\nPoproś administratora RamPT o dodanie tego adresu.")
            }
        }
    }

    private fun ustawStan(trwa: Boolean, komunikat: String) {
        b.progress.visibility = if (trwa) View.VISIBLE else View.GONE
        b.btnGoogleSignIn.visibility = if (trwa) View.GONE else View.VISIBLE
        b.tvStatus.text = komunikat
    }
}
