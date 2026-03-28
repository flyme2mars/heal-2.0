package com.example.mychat.di

import com.example.mychat.network.HealNetworkClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHealNetworkClient(): HealNetworkClient {
        return HealNetworkClient()
    }
}
