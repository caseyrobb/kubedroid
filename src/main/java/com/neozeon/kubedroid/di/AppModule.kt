package com.neozeon.kubedroid.di

import android.content.Context
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.CipherVault
import com.neozeon.kubedroid.data.local.KeystoreCipherVault
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.registry.ClusterRegistry
import com.neozeon.kubedroid.data.registry.DataStoreClusterRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BlobDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun cipherVault(): CipherVault = KeystoreCipherVault()

    /** Pinned in spec 03 Configuration: filesDir/connections. */
    @Provides
    @Singleton
    @BlobDir
    fun blobDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "connections")

    @Provides
    @Singleton
    fun blobStore(@BlobDir dir: File, vault: CipherVault): BlobStore = BlobStore(dir, vault)

    @Provides
    @Singleton
    fun clusterRegistry(@ApplicationContext context: Context): ClusterRegistry =
        DataStoreClusterRegistry(context)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun wallClock(): WallClock = WallClock { TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) }

    /** Retry backoff for ResourceGateway (spec 04 "Reliability"); tests inject their own. */
    @Provides
    @Singleton
    fun retryBackoff(): RetryBackoff = RetryBackoff { delay(it) }
}

