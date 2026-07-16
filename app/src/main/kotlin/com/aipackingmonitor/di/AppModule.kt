package com.aipackingmonitor.di

import android.content.Context
import androidx.room.Room
import com.aipackingmonitor.data.AlertEventRepository
import com.aipackingmonitor.data.AppDatabase
import com.aipackingmonitor.data.RoomAlertEventRepository
import com.aipackingmonitor.device.AlertController
import com.aipackingmonitor.device.AndroidAlertController
import com.aipackingmonitor.domain.MonitoringStateMachine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "packing-monitor.db",
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAlertEventDao(database: AppDatabase) = database.alertEventDao()

    @Provides
    fun provideMonitoringStateMachine(): MonitoringStateMachine = MonitoringStateMachine()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    abstract fun bindAlertEventRepository(
        repository: RoomAlertEventRepository,
    ): AlertEventRepository

    @Binds
    abstract fun bindAlertController(
        controller: AndroidAlertController,
    ): AlertController
}
