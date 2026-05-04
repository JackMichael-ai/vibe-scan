// app/src/main/java/com/northmark/vibescan/ui/auth/LoginActivity.kt
package com.northmark.vibescan.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.northmark.vibescan.auth.FirebaseAuthManager
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.databinding.ActivityLoginBinding
import com.northmark.vibescan.ui.main.MainActivity
import com.northmark.vibescan.utils.AppPrefs
import kotlinx.coroutines.*

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val authManager = FirebaseAuthManager()
    private var isRegistering = false

    companion object {
        private const val TAG = "LoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        // Check if already logged in
        if (authManager.isLoggedIn()) {
            navigateToMain()
            return
        }

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            Log.d(TAG, "Login button clicked")
            performLogin()
        }

        binding.btnRegister.setOnClickListener {
            Log.d(TAG, "Register button clicked")
            performRegister()
        }

        binding.tvToggleMode.setOnClickListener {
            Log.d(TAG, "Toggle mode clicked")
            toggleMode()
        }

        binding.tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun toggleMode() {
        isRegistering = !isRegistering
        Log.d(TAG, "Toggling to ${if (isRegistering) "register" else "login"} mode")

        if (isRegistering) {
            binding.tilCompanyName.visibility = View.VISIBLE
            binding.btnLogin.visibility = View.GONE
            binding.btnRegister.visibility = View.VISIBLE
            binding.tvToggleMode.text = "Already have an account? Login"
            binding.tvTitle.text = "Create Account"
            binding.tvForgotPassword.visibility = View.GONE
        } else {
            binding.tilCompanyName.visibility = View.GONE
            binding.btnLogin.visibility = View.VISIBLE
            binding.btnRegister.visibility = View.GONE
            binding.tvToggleMode.text = "Don't have an account? Register"
            binding.tvTitle.text = "Welcome Back"
            binding.tvForgotPassword.visibility = View.VISIBLE
        }
    }

    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        Log.d(TAG, "performLogin called - Email: $email")

        if (!validateLoginInput(email, password)) return

        setLoading(true)

        scope.launch {
            try {
                val result = authManager.loginUser(email, password)

                if (result.success) {
                    // Save user info to AppPrefs
                    val prefs = AppPrefs.getInstance(this@LoginActivity)
                    result.user?.let { user ->
                        prefs.setAuthToken(user.uid)
                        prefs.setUserEmail(user.email ?: "")
                        prefs.setTenantId(user.uid)
                    }

                    Toast.makeText(
                        this@LoginActivity,
                        "Welcome back!",
                        Toast.LENGTH_SHORT
                    ).show()

                    navigateToMain()
                } else {
                    Toast.makeText(
                        this@LoginActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login exception", e)
                Toast.makeText(
                    this@LoginActivity,
                    "An error occurred. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun performRegister() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val company = binding.etCompanyName.text.toString().trim()

        Log.d(TAG, "performRegister called - Email: $email, Company: $company")

        if (!validateRegisterInput(email, password, company)) return

        setLoading(true)

        scope.launch {
            try {
                val result = authManager.registerUser(email, password, company)

                if (result.success) {
                    Toast.makeText(
                        this@LoginActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()

                    // Switch to login mode
                    toggleMode()

                    // Pre-fill email
                    binding.etEmail.setText(email)
                } else {
                    Toast.makeText(
                        this@LoginActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Register exception", e)
                Toast.makeText(
                    this@LoginActivity,
                    "Registration failed. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun validateLoginInput(email: String, password: String): Boolean {
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun validateRegisterInput(email: String, password: String, company: String): Boolean {
        if (email.isEmpty() || password.isEmpty() || company.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun showForgotPasswordDialog() {
        val email = binding.etEmail.text.toString().trim()

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Reset Password")
        builder.setMessage("Enter your email address to receive a password reset link.")

        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.setText(email)
        input.inputType = android.text.InputType.TYPE_TEXT_EMAIL_ADDRESS
        builder.setView(input)

        builder.setPositiveButton("Send") { _, _ ->
            val resetEmail = input.text.toString().trim()
            if (resetEmail.isNotEmpty()) {
                sendPasswordReset(resetEmail)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun sendPasswordReset(email: String) {
        scope.launch {
            setLoading(true)
            try {
                val result = authManager.resetPassword(email)
                Toast.makeText(this@LoginActivity, result.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    "Failed to send reset email",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnRegister.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etCompanyName.isEnabled = !loading
        binding.tvToggleMode.isEnabled = !loading
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}