package com.afif.flix

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.afif.flix.databinding.ActivityAuthBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val googleSignInClient by lazy {
        val webClientId = getString(R.string.default_web_client_id)
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignIn.setOnClickListener { submit(false) }
        binding.btnSignUp.setOnClickListener { submit(true) }
        binding.btnGoogleSignIn.setOnClickListener { startGoogleSignIn() }
        binding.btnContinueAsGuest.setOnClickListener {
            setLoading(true)
            auth.signInAnonymously()
                .addOnCompleteListener { result ->
                    setLoading(false)
                    if (result.isSuccessful) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this, result.exception?.message ?: "Guest access failed", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GOOGLE_SIGN_IN_REQUEST_CODE) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(Exception::class.java)
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                signInWithGoogleCredential(credential)
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(this, e.message ?: "Google sign-in failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun submit(isSignUp: Boolean) {
        val email = binding.etEmail.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (email.isBlank() || password.length < 6) {
            Toast.makeText(this, "Enter a valid email and 6+ character password", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        val task = if (isSignUp) {
            auth.createUserWithEmailAndPassword(email, password)
        } else {
            auth.signInWithEmailAndPassword(email, password)
        }

        task.addOnCompleteListener { result ->
            setLoading(false)
            if (result.isSuccessful) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, result.exception?.message ?: "Auth failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startGoogleSignIn() {
        setLoading(true)
        googleSignInClient.signOut().addOnCompleteListener {
            startActivityForResult(googleSignInClient.signInIntent, GOOGLE_SIGN_IN_REQUEST_CODE)
        }
    }

    private fun signInWithGoogleCredential(credential: com.google.firebase.auth.AuthCredential) {
        auth.signInWithCredential(credential).addOnCompleteListener { result ->
            setLoading(false)
            if (result.isSuccessful) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, result.exception?.message ?: "Google sign-in failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressAuth.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSignIn.isEnabled = !loading
        binding.btnSignUp.isEnabled = !loading
        binding.btnGoogleSignIn.isEnabled = !loading
        binding.btnContinueAsGuest.isEnabled = !loading
    }

    companion object {
        private const val GOOGLE_SIGN_IN_REQUEST_CODE = 9001
    }
}
