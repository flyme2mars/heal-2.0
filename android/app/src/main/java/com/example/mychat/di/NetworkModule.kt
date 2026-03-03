package com.example.mychat.di

import com.example.mychat.network.ReminiNetworkClient
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
    fun provideReminiNetworkClient(): ReminiNetworkClient {
        return ReminiNetworkClient()
    }
}
