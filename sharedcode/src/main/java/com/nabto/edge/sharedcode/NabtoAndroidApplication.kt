package com.nabto.edge.sharedcode

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import com.nabto.edge.client.NabtoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module

private fun appModule(client: NabtoClient, scanner: NabtoDeviceScanner, scope: CoroutineScope, connectivityManager: ConnectivityManager) =
    module {
        single {
            Room.databaseBuilder(
                androidApplication(),
                DeviceDatabase::class.java,
                "device-database"
            ).build()
        }

        single<NabtoRepository> { NabtoRepositoryImpl(androidApplication(), client, scope, scanner) }

        single<NabtoConnectionManager> {
            NabtoConnectionManagerImpl(get(), client, connectivityManager)
        }

        single<NabtoBookmarksRepository> {
            NabtoBookmarksRepositoryImpl(get(), get(), scope)
        }
    }

open class NabtoAndroidApplication : Application() {
    private val nabtoClient: NabtoClient by lazy { NabtoClient.create(this) }
    private val scanner: NabtoDeviceScanner by lazy { NabtoDeviceScanner(nabtoClient) }
    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startKoin {
            androidLogger()
            androidContext(this@NabtoAndroidApplication)
            modules(appModule(nabtoClient, scanner, applicationScope, connectivityManager))
        }
    }
}