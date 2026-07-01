package com.streamverse.core.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.local.ChannelSearchDao
import com.streamverse.core.data.local.FavoriteDao
import com.streamverse.core.data.local.StreamVerseDatabase
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.free.FreeLiveClient
import com.streamverse.core.data.remote.hosted.HostedIndexClient
import com.streamverse.core.data.remote.MergedIndexClient
import com.streamverse.core.data.remote.broadcaster.BroadcasterClient
import com.streamverse.core.data.remote.iptv.IptvClient
import com.streamverse.core.data.remote.radio.RadioBrowserClient
import com.streamverse.core.data.remote.stmify.PrimeVideoClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.data.remote.youtube.YouTubeTvClient
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.data.source.BroadcasterProviderAdapter
import com.streamverse.core.data.source.DlhdProviderAdapter
import com.streamverse.core.data.source.FreeChannelProviderAdapter
import com.streamverse.core.data.source.GlobalIndexProviderAdapter
import com.streamverse.core.data.source.HealthMonitor
import com.streamverse.core.data.source.YouTubeProviderAdapter
import com.streamverse.core.data.source.LogicalChannelMatcher
import com.streamverse.core.data.source.MetadataAggregator
import com.streamverse.core.data.source.PlaybackResolver
import com.streamverse.core.data.source.ProviderAdapter
import com.streamverse.core.data.source.RadioProviderAdapter
import com.streamverse.core.data.source.SourceRegistry
import com.streamverse.core.data.source.SourceRegistryInitializer
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.data.source.StmifyProviderAdapter
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.StreamVerseDispatchers
import com.streamverse.core.util.SourceResolutionEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
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

    @Provides
    @Singleton
    fun provideDispatchers(): StreamVerseDispatchers = StreamVerseDispatchers()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StreamVerseDatabase =
        Room.databaseBuilder(context, StreamVerseDatabase::class.java, "streamverse.db")
            .fallbackToDestructiveMigration().build()

    @Provides @Singleton
    fun provideFavoriteDao(database: StreamVerseDatabase): FavoriteDao = database.favoriteDao()

    @Provides @Singleton
    fun provideChannelSearchDao(database: StreamVerseDatabase): ChannelSearchDao = database.channelSearchDao()

    @Provides @Singleton
    fun provideDlhdClient(gson: Gson, dispatchers: StreamVerseDispatchers): DlhdClient = DlhdClient(gson, dispatchers)

    @Provides @Singleton
    fun provideStmifyClient(gson: Gson, dispatchers: StreamVerseDispatchers): StmifyClient = StmifyClient(gson, dispatchers)

    @Provides @Singleton
    fun providePrimeVideoClient(gson: Gson, dispatchers: StreamVerseDispatchers, okHttpClient: okhttp3.OkHttpClient): PrimeVideoClient = PrimeVideoClient(gson, dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideHostedIndexClient(gson: Gson, dispatchers: StreamVerseDispatchers, okHttpClient: okhttp3.OkHttpClient): HostedIndexClient = HostedIndexClient(gson, dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideMergedIndexClient(gson: Gson, dispatchers: StreamVerseDispatchers, okHttpClient: okhttp3.OkHttpClient): MergedIndexClient = MergedIndexClient(gson, dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideIptvClient(dispatchers: StreamVerseDispatchers, okHttpClient: okhttp3.OkHttpClient): IptvClient = IptvClient(dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideRadioBrowserClient(gson: Gson, dispatchers: StreamVerseDispatchers, okHttpClient: okhttp3.OkHttpClient): RadioBrowserClient = RadioBrowserClient(gson, dispatchers, okHttpClient)

    @Provides @Singleton
    fun provideFreeLiveClient(dispatchers: StreamVerseDispatchers, @ApplicationContext context: Context, okHttpClient: okhttp3.OkHttpClient): FreeLiveClient = FreeLiveClient(dispatchers, context, okHttpClient)

    @Provides @Singleton
    fun provideBroadcasterClient(dispatchers: StreamVerseDispatchers, @ApplicationContext context: Context): BroadcasterClient = BroadcasterClient(dispatchers, context)

    @Provides @Singleton
    fun provideSourceResolutionEngine(sourceHealth: com.streamverse.core.data.SourceHealthPreferences, providerRegistry: ProviderRegistry): SourceResolutionEngine = SourceResolutionEngine(sourceHealth, providerRegistry)

    @Provides @Singleton
    fun provideStreamResolver(dlhdClient: DlhdClient, stmifyClient: StmifyClient, primeVideoClient: PrimeVideoClient, dispatchers: StreamVerseDispatchers, youTubeLiveResolver: com.streamverse.core.util.YouTubeLiveResolver): StreamResolver = StreamResolver(dlhdClient, stmifyClient, primeVideoClient, dispatchers, youTubeLiveResolver)

    @Provides @Singleton
    fun provideSourceRegistry(): SourceRegistry = SourceRegistry()

    @Provides @Singleton
    fun provideLogicalChannelMatcher(): LogicalChannelMatcher = LogicalChannelMatcher()

    @Provides @Singleton
    fun provideMetadataAggregator(): MetadataAggregator = MetadataAggregator()

    @Provides @Singleton
    fun provideHealthMonitor(sourceRegistry: SourceRegistry): HealthMonitor = HealthMonitor(sourceRegistry)

    @Provides @Singleton
    fun providePlaybackResolver(streamResolver: StreamResolver, sourceResolutionEngine: SourceResolutionEngine, sourceHealth: com.streamverse.core.data.SourceHealthPreferences, sourceRegistry: SourceRegistry, dispatchers: StreamVerseDispatchers): PlaybackResolver = PlaybackResolver(streamResolver, sourceResolutionEngine, sourceHealth, sourceRegistry, dispatchers)

    @Provides @Singleton
    fun provideSourceRegistryInitializer(registry: SourceRegistry, providers: List<@JvmSuppressWildcards ProviderAdapter>): SourceRegistryInitializer = SourceRegistryInitializer(registry, providers)

    @Provides @Singleton
    fun provideChannelRepository(
        dlhdClient: DlhdClient,
        stmifyClient: StmifyClient,
        primeVideoClient: PrimeVideoClient,
        hostedIndexClient: HostedIndexClient,
        mergedIndexClient: MergedIndexClient,
        iptvClient: IptvClient,
        radioBrowserClient: RadioBrowserClient,
        broadcasterClient: BroadcasterClient,
        freeLiveClient: FreeLiveClient,
        youtubeTvClient: YouTubeTvClient,
        cacheManager: com.streamverse.core.data.ChannelCacheManager,
        smartCacheManager: com.streamverse.core.data.SmartCacheManager,
        sourcePreferences: com.streamverse.core.data.SourcePreferences,
        dispatchers: com.streamverse.core.util.StreamVerseDispatchers,
        sourceRegistry: SourceRegistry,
        channelMatcher: LogicalChannelMatcher,
        metadataAggregator: MetadataAggregator,
        registryInitializer: SourceRegistryInitializer,
        providerRegistry: ProviderRegistry,
        channelSearchDao: ChannelSearchDao,
        @ApplicationContext appContext: Context,
    ): ChannelRepository = ChannelRepository(
        dlhdClient, stmifyClient, primeVideoClient, hostedIndexClient,
        mergedIndexClient,
        iptvClient,
        radioBrowserClient,
        broadcasterClient, freeLiveClient,
        youtubeTvClient,
        cacheManager, smartCacheManager, sourcePreferences,
        dispatchers, sourceRegistry, channelMatcher, metadataAggregator,
        registryInitializer, providerRegistry, channelSearchDao, appContext,
    )

    @Provides @Singleton
    fun provideStreamPreResolver(streamResolver: StreamResolver, providerRegistry: ProviderRegistry): StreamPreResolver = StreamPreResolver(streamResolver, providerRegistry)

    @Provides @Singleton
    fun provideFavoritesRepository(favoriteDao: FavoriteDao): FavoritesRepository = FavoritesRepository(favoriteDao)

    @Provides @Singleton
    fun provideEpgManager(dlhdClient: DlhdClient, dispatchers: StreamVerseDispatchers, @ApplicationContext context: Context): com.streamverse.core.data.epg.EpgManager = com.streamverse.core.data.epg.EpgManager(dlhdClient, dispatchers, context)

    @Provides @Singleton
    fun provideSmartCacheManager(@ApplicationContext context: Context, channelCacheManager: com.streamverse.core.data.ChannelCacheManager, epgManager: com.streamverse.core.data.epg.EpgManager): com.streamverse.core.data.SmartCacheManager = com.streamverse.core.data.SmartCacheManager(context, channelCacheManager, epgManager)

    @Provides @Singleton
    fun provideChannelNavigationEngine(sourcePreferences: com.streamverse.core.data.SourcePreferences): com.streamverse.core.data.ChannelNavigationEngine = com.streamverse.core.data.ChannelNavigationEngine(sourcePreferences)

    @Provides @Singleton
    fun provideGlobalIndexProviderAdapter(hostedIndexClient: HostedIndexClient, dispatchers: StreamVerseDispatchers): GlobalIndexProviderAdapter = GlobalIndexProviderAdapter(hostedIndexClient, dispatchers)

    @Provides @Singleton
    fun provideDlhdProviderAdapter(dlhdClient: DlhdClient, dispatchers: StreamVerseDispatchers): DlhdProviderAdapter = DlhdProviderAdapter(dlhdClient, dispatchers)

    @Provides @Singleton
    fun provideStmifyProviderAdapter(stmifyClient: StmifyClient, primeVideoClient: PrimeVideoClient, dispatchers: StreamVerseDispatchers): StmifyProviderAdapter = StmifyProviderAdapter(stmifyClient, primeVideoClient, dispatchers)

    @Provides @Singleton
    fun provideRadioProviderAdapter(radioBrowserClient: RadioBrowserClient, dispatchers: StreamVerseDispatchers): RadioProviderAdapter = RadioProviderAdapter(radioBrowserClient, dispatchers)

    @Provides @Singleton
    fun provideBroadcasterProviderAdapter(broadcasterClient: BroadcasterClient, dispatchers: StreamVerseDispatchers): BroadcasterProviderAdapter = BroadcasterProviderAdapter(broadcasterClient, dispatchers)

    @Provides @Singleton
    fun provideFreeChannelProviderAdapter(freeLiveClient: FreeLiveClient, dispatchers: StreamVerseDispatchers): FreeChannelProviderAdapter = FreeChannelProviderAdapter(freeLiveClient, dispatchers)

    @Provides @Singleton
    fun provideYouTubeProviderAdapter(youtubeTvClient: YouTubeTvClient, dispatchers: StreamVerseDispatchers): YouTubeProviderAdapter = YouTubeProviderAdapter(youtubeTvClient, dispatchers)

    @Provides @Singleton
    fun provideAllProviderAdapters(
        globalIndex: GlobalIndexProviderAdapter,
        dlhd: DlhdProviderAdapter,
        stmify: StmifyProviderAdapter,
        radio: RadioProviderAdapter,
        broadcaster: BroadcasterProviderAdapter,
        freeChannel: FreeChannelProviderAdapter,
        youTube: YouTubeProviderAdapter,
    ): List<ProviderAdapter> = listOf(
        globalIndex, dlhd, stmify,
        radio, broadcaster, freeChannel, youTube,
    )
}
