// app/src/main/java/com/northmark/vibescan/auth/FirebaseAuthManager.kt
package com.northmark.vibescan.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.*

class FirebaseAuthManager {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "FirebaseAuthManager"
        private const val USERS_COLLECTION = "users"
    }

    data class AuthResult(
        val success: Boolean,
        val message: String,
        val user: FirebaseUser? = null
    )

    data class UserProfile(
        val uid: String = "",
        val email: String = "",
        val companyName: String = "",
        val displayName: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val lastLogin: Long = System.currentTimeMillis()
    )

    /**
     * Get current authenticated user
     */
    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean = auth.currentUser != null

    /**
     * Register new user with email and password
     */
    suspend fun registerUser(
        email: String,
        password: String,
        companyName: String
    ): AuthResult {
        return try {
            Log.d(TAG, "Starting registration for: $email")

            // Create user in Firebase Auth
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user

            if (user != null) {
                Log.d(TAG, "Firebase user created: ${user.uid}")

                // Update display name
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(companyName)
                    .build()
                user.updateProfile(profileUpdates).await()

                // Create user profile in Firestore
                val userProfile = UserProfile(
                    uid = user.uid,
                    email = email,
                    companyName = companyName,
                    displayName = companyName,
                    createdAt = System.currentTimeMillis(),
                    lastLogin = System.currentTimeMillis()
                )

                firestore.collection(USERS_COLLECTION)
                    .document(user.uid)
                    .set(userProfile)
                    .await()

                Log.d(TAG, "User profile created in Firestore")

                // Send email verification
                user.sendEmailVerification().await()
                Log.d(TAG, "Verification email sent")

                AuthResult(
                    success = true,
                    message = "Registration successful! Please check your email for verification.",
                    user = user
                )
            } else {
                AuthResult(success = false, message = "Failed to create user account")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            AuthResult(
                success = false,
                message = e.localizedMessage ?: "Registration failed"
            )
        }
    }

    /**
     * Login user with email and password
     */
    suspend fun loginUser(email: String, password: String): AuthResult {
        return try {
            Log.d(TAG, "Starting login for: $email")

            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user

            if (user != null) {
                // Update last login time
                firestore.collection(USERS_COLLECTION)
                    .document(user.uid)
                    .update("lastLogin", System.currentTimeMillis())
                    .await()

                Log.d(TAG, "Login successful: ${user.uid}")

                AuthResult(
                    success = true,
                    message = "Login successful",
                    user = user
                )
            } else {
                AuthResult(success = false, message = "Login failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            val message = when {
                e.message?.contains("password") == true -> "Incorrect password"
                e.message?.contains("no user record") == true -> "No account found with this email"
                e.message?.contains("network") == true -> "Network error. Please check your connection"
                else -> e.localizedMessage ?: "Login failed"
            }
            AuthResult(success = false, message = message)
        }
    }

    /**
     * Logout current user
     */
    fun logout() {
        auth.signOut()
        Log.d(TAG, "User logged out")
    }

    /**
     * Reset password
     */
    suspend fun resetPassword(email: String): AuthResult {
        return try {
            auth.sendPasswordResetEmail(email).await()
            AuthResult(
                success = true,
                message = "Password reset email sent. Please check your inbox."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            AuthResult(
                success = false,
                message = e.localizedMessage ?: "Failed to send reset email"
            )
        }
    }

    /**
     * Get user profile from Firestore
     */
    suspend fun getUserProfile(uid: String): UserProfile? {
        return try {
            val doc = firestore.collection(USERS_COLLECTION)
                .document(uid)
                .get()
                .await()

            doc.toObject(UserProfile::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user profile", e)
            null
        }
    }

    /**
     * Update user profile
     */
    suspend fun updateUserProfile(uid: String, updates: Map<String, Any>): Boolean {
        return try {
            firestore.collection(USERS_COLLECTION)
                .document(uid)
                .update(updates)
                .await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update user profile", e)
            false
        }
    }
}