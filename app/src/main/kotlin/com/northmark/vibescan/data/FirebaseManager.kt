package com.northmark.vibescan.data

import android.util.Log
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.northmark.vibescan.engine.Diagnosis

/**
 * FirebaseManager — Handles real-time cloud sync for vibration measurements.
 */
class FirebaseManager(private val nodeId: String) {

    private val database: FirebaseDatabase? = try {
        Firebase.database
    } catch (e: Exception) {
        Log.e("FirebaseManager", "Firebase not initialized: ${e.message}")
        null
    }

    private val readingsRef: DatabaseReference? = database?.getReference("readings")?.child(nodeId)

    fun uploadReading(assetId: Long, assetName: String, d: Diagnosis) {
        val ref = readingsRef ?: return
        val timestamp = System.currentTimeMillis()
        val readingId = ref.push().key ?: return

        val data = mapOf(
            "assetId" to assetId,
            "assetName" to assetName,
            "health" to d.health,
            "rms" to d.rmsMms,
            "rms_ms2" to d.rmsMs2,
            "kurtosis" to d.kurtosis,
            "crest" to d.crest,
            "dominant_hz" to d.dominantHz,
            "iso_zone" to d.isoZone.toString(),
            "bpfo_energy" to d.bpfoEnergy,
            "bpfi_energy" to d.bpfiEnergy,
            "timestamp" to timestamp,
            "mounting_quality" to d.mountingQuality
        )

        ref.child(readingId).setValue(data)
            .addOnSuccessListener {
                Log.d("FirebaseManager", "Successfully uploaded reading: $readingId")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseManager", "Failed to upload reading", e)
            }
    }
}
