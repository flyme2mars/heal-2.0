package com.example.mychat

import android.app.Application

import com.google.android.fhir.FhirEngineConfiguration

import com.google.android.fhir.FhirEngineProvider

import dagger.hilt.android.HiltAndroidApp



@HiltAndroidApp

class ChatApp : Application() {

    override fun onCreate() {

        super.onCreate()

        

        // Initialize FHIR Engine with Encryption

        FhirEngineProvider.init(

            FhirEngineConfiguration(

                enableEncryptionIfSupported = true,

                databaseErrorStrategy = com.google.android.fhir.DatabaseErrorStrategy.RECREATE_AT_OPEN

            )

        )

    }

}
