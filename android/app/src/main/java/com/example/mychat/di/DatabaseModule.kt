package com.example.mychat.di

import android.content.Context
import androidx.room.Room
import com.example.mychat.data.HealthDatabase
import com.example.mychat.data.HealthDocumentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideHealthDatabase(@ApplicationContext context: Context): HealthDatabase {
        return Room.databaseBuilder(
            context,
            HealthDatabase::class.java,
            "health_vault.db"
        ).fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideHealthDocumentDao(database: HealthDatabase): HealthDocumentDao {
        return database.healthDocumentDao()
    }
}
