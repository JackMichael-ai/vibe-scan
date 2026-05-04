// app/src/main/java/com/northmark/vibescan/ui/auth/LogoutDialogFragment.kt
package com.northmark.vibescan.ui.auth

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.northmark.vibescan.auth.FirebaseAuthManager
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.utils.AppPrefs

class LogoutDialogFragment : DialogFragment() {

    private val authManager = FirebaseAuthManager()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Sign Out") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private fun performLogout() {
        // Firebase logout
        authManager.logout()

        // Clear local preferences
        AppPrefs.getInstance(requireContext()).signOut()

        // Navigate to login
        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    companion object {
        const val TAG = "LogoutDialog"
    }
}