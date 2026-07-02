package com.streamverse.core.di

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamverse.core.data.ChannelCacheManager
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.ChannelNavigationEngine
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.catalogue.CatalogueClient
import com.streamverse.core.data.epg.EpgManager
import com.streamverse.core.data.local.FavoriteDao
import com.streamverse.core.data.local.StreamVerseDatabase
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.hosted.HostedIndexClient
import com.streamverse.core.data.remote.MergedIndexClient
import com.streamverse.core.data.remote.stmify.PrimeVideoClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.data.repository.ChannelRankingEngine
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.data.SmartCacheManager
import com.streamverse.core.data.source.PlaybackResolver
import com.streamverse.core.data.source.SourceRegistry
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.util.SourceResolutionEngine
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.StreamVerseDispatchers
import com.streamverse.core.util.YouTubeLiveResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): okhttp3.OkHttpClient {
        val cache = okhttp3.Cache(
            directory = java.io.File(context.cacheDir, "http_cache"),
            maxSize = 50L * 1024 * 1024,
        )
        fun isOnline(): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return okhttp3.OkHttpClient.Builder()
            .cache(cache)
            .connectionPool(okhttp3.ConnectionPool(10, 30, java.util.concurrent.TimeUnit.SECONDS))
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=600")
                    .build()
            }
            .addInterceptor { chain ->
                val request = if (!isOnline()) {
                    chain.request().newBuilder()
                        .header("Cache-Control", "public, only-if-cached, max-stale=604800")
                        .build()
                } else chain.request()
                chain.proceed(request)
            }
            .build()
    }

    @Provides @Singleton
    fun provideDispatchers(): StreamVerseDispatchers = StreamVerseDispatchers()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StreamVerseDatabase =
        androidx.room.Room.databaseBuilder(context, StreamVerseDatabase::class.java, "streamverse.db")
            .fallbackToDestructiveMigration().build()

    @Provides @Singleton
    fun provideFavoriteDao(database: StreamVerseDatabase): FavoriteDao = database.favoriteDao()

    @Provides @Singleton
    fun provideChannelCacheManager(@ApplicationContext context: Context): ChannelCacheManager =
        ChannelCacheManager(context)

    @Provides @Singleton
    fun provideCatalogueClient(
        @ApplicationContext context: Context,
        gson: Gson,
        okHttpClient: okhttp3.OkHttpClient,
        cacheManager: ChannelCacheManager,
    ): CatalogueClient = CatalogueClient(context, gson, okHttpClient, cacheManager)

    @Provides @Singleton
    fun provideChannelRepository(
        catalogueClient: CatalogueClient,
        sourcePreferences: SourcePreferences,
        dispatchers: StreamVerseDispatchers,
        cacheManager: ChannelCacheManager,
        @ApplicationContext context: Context,
    ): ChannelRepository = ChannelRepository(
        catalogueClient, sourcePreferences, dispatchers, cacheManager, context,
    )

    @Provides @Singleton
    fun provideFavoritesRepository(favoriteDao: FavoriteDao): FavoritesRepository =
        FavoritesRepository(favoriteDao)

    @Provides @Singleton
    fun provideChannelNavigationEngine(sourcePreferences: SourcePreferences): ChannelNavigationEngine =
        ChannelNavigationEngine(sourcePreferences)

    @Provides @Singleton
    fun provideProviderRegistry(): ProviderRegistry = ProviderRegistry()

    @Provides @Singleton
    fun provideSourceResolutionEngine(
        sourceHealth: SourceHealthPreferences,
        providerRegistry: ProviderRegistry,
    ): SourceResolutionEngine = SourceResolutionEngine(sourceHealth, providerRegistry)

    @Provides @Singleton
    fun provideEpgManager(
        dlhdClient: DlhdClient,
        dispatchers: StreamVerseDispatchers,
        @ApplicationContext context: Context,
    ): EpgManager = EpgManager(dlhdClient, dispatchers, context)

    @Provides @Singleton
    fun provideSmartCacheManager(
        @ApplicationContext context: Context,
        channelCacheManager: ChannelCacheManager,
        epgManager: EpgManager,
    ): SmartCacheManager = SmartCacheManager(context, channelCacheManager, epgManager)

    @Provides @Singleton
    fun provideProgrammeRepository(
        channelRepository: ChannelRepository,
        sourcePreferences: SourcePreferences,
        epgManager: EpgManager,
        channelRankingEngine: com.streamverse.core.data.repository.ChannelRankingEngine,
    ): ProgrammeRepository = ProgrammeRepository(
        channelRepository, sourcePreferences, epgManager, channelRankingEngine,
    )

    @Provides @Singleton
    fun provideStreamResolver(
        dlhdClient: DlhdClient,
        stmifyClient: StmifyClient,
        primeVideoClient: PrimeVideoClient,
        dispatchers: StreamVerseDispatchers,
        youTubeLiveResolver: YouTubeLiveResolver,
    ): StreamResolver = StreamResolver(dlhdClient, stmifyClient, primeVideoClient, dispatchers, youTubeLiveResolver)

    @Provides @Singleton
    fun provideStreamPreResolver(
        streamResolver: StreamResolver,
        providerRegistry: ProviderRegistry,
    ): StreamPreResolver = StreamPreResolver(streamResolver, providerRegistry)

    @Provides @Singleton
    fun provideSourceRegistry(): SourceRegistry = SourceRegistry()

    @Provides @Singleton
    fun provideChannelRankingEngine(providerRegistry: ProviderRegistry): ChannelRankingEngine =
        ChannelRankingEngine(providerRegistry)

    @Provides @Singleton
    fun providePlaybackResolver(
        streamResolver: StreamResolver,
        sourceResolutionEngine: SourceResolutionEngine,
        sourceHealth: SourceHealthPreferences,
        sourceRegistry: SourceRegistry,
        dispatchers: StreamVerseDispatchers,
    ): PlaybackResolver = PlaybackResolver(
        streamResolver, sourceResolutionEngine, sourceHealth, sourceRegistry, dispatchers,
    )

    // Source clients kept for DI compatibility with EpgManager, StreamResolver, PlaybackResolver
    @Provides @Singleton
    fun provideDlhdClient(gson: Gson, dispatchers: StreamVerseDispatchers): DlhdClient =
        DlhdClient(gson, dispatchers)

    @Provides @Singleton
    fun provideStmifyClient(gson: Gson, dispatchers: StreamVerseDispatchers): StmifyClient =
        StmifyClient(gson, dispatchers)

    @Provides @Singleton
    fun providePrimeVideoClient(
        gson: Gson,
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): PrimeVideoClient = PrimeVideoClient(gson, dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideYouTubeLiveResolver(): YouTubeLiveResolver = YouTubeLiveResolver()

    // Retained for backward compat — old PlaybackResolver path still references them
    @Provides @Singleton
    fun provideMergedIndexClient(
        gson: Gson,
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): MergedIndexClient = MergedIndexClient(gson, dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideHostedIndexClient(
        gson: Gson,
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): HostedIndexClient = HostedIndexClient(gson, dispatchers, okHttpClient)
}
