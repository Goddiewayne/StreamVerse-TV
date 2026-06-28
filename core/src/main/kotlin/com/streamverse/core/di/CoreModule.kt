package com.streamverse.core.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamverse.core.data.local.FavoriteDao
import com.streamverse.core.data.local.StreamVerseDatabase
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.fast.FastTvClient
import com.streamverse.core.data.remote.independent.IndependentClient
import com.streamverse.core.data.remote.iptv.FreeTvClient
import com.streamverse.core.data.remote.iptv.IptvClient
import com.streamverse.core.data.remote.premium.PremiumClient
import com.streamverse.core.data.remote.radio.RadioBrowserClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.StreamVerseDispatchers
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

    /**
     * One shared OkHttpClient: a single connection pool + dispatcher for all source fetches
     * (was 9 separate clients), with a 50 MB HTTP disk cache. A network interceptor makes
     * playlist/API responses cacheable, and an offline interceptor serves stale cache when there's
     * no connectivity — so a previously-loaded catalogue still works offline. Per-client timeouts
     * are layered on via okHttpClient.newBuilder() so the pool/cache stay shared.
     */
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
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            // Total per-request cap so no single source can stall startup indefinitely. Source
            // clients that need longer (IPTV/FastTV) raise it via newBuilder().
            .callTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // Make responses cacheable (many CDNs send no-cache); cache for 10 min.
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=600")
                    .build()
            }
            // Offline: serve up to a week-old cached copy instead of failing.
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
        Room.databaseBuilder(
            context,
            StreamVerseDatabase::class.java,
            "streamverse.db"
        ).build()

    @Provides
    @Singleton
    fun provideFavoriteDao(database: StreamVerseDatabase): FavoriteDao =
        database.favoriteDao()

    @Provides
    @Singleton
    fun provideDlhdClient(
        gson: Gson,
        dispatchers: StreamVerseDispatchers,
    ): DlhdClient = DlhdClient(gson, dispatchers)

    @Provides
    @Singleton
    fun provideStmifyClient(
        gson: Gson,
        dispatchers: StreamVerseDispatchers,
    ): StmifyClient = StmifyClient(gson, dispatchers)

    @Provides
    @Singleton
    fun provideIptvClient(
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): IptvClient = IptvClient(dispatchers, okHttpClient)

    @Provides
    @Singleton
    fun provideFreeTvClient(
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): FreeTvClient = FreeTvClient(dispatchers, okHttpClient)

    @Provides
    @Singleton
    fun provideRadioBrowserClient(
        gson: Gson,
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): RadioBrowserClient = RadioBrowserClient(gson, dispatchers, okHttpClient)

    @Provides
    @Singleton
    fun provideFastTvClient(
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
    ): FastTvClient = FastTvClient(dispatchers, okHttpClient)

    @Provides
    @Singleton
    fun providePremiumClient(
        dispatchers: StreamVerseDispatchers,
        okHttpClient: okhttp3.OkHttpClient,
        @ApplicationContext context: Context,
    ): PremiumClient = PremiumClient(dispatchers, okHttpClient, context)

    @Provides
    @Singleton
    fun provideIndependentClient(): IndependentClient = IndependentClient()

    @Provides
    @Singleton
    fun provideStreamResolver(
        dlhdClient: DlhdClient,
        stmifyClient: StmifyClient,
        dispatchers: StreamVerseDispatchers,
        youTubeLiveResolver: com.streamverse.core.util.YouTubeLiveResolver,
    ): StreamResolver = StreamResolver(dlhdClient, stmifyClient, dispatchers, youTubeLiveResolver)

    @Provides
    @Singleton
    fun provideChannelRepository(
        dlhdClient: DlhdClient,
        stmifyClient: StmifyClient,
        iptvClient: IptvClient,
        freeTvClient: FreeTvClient,
        radioBrowserClient: RadioBrowserClient,
        fastTvClient: FastTvClient,
        premiumClient: PremiumClient,
        independentClient: IndependentClient,
        cacheManager: com.streamverse.core.data.ChannelCacheManager,
        sourcePreferences: com.streamverse.core.data.SourcePreferences,
        dispatchers: StreamVerseDispatchers,
        @ApplicationContext appContext: Context,
    ): ChannelRepository = ChannelRepository(
        dlhdClient,
        stmifyClient,
        iptvClient,
        freeTvClient,
        radioBrowserClient,
        fastTvClient,
        premiumClient,
        independentClient,
        cacheManager,
        sourcePreferences,
        dispatchers,
        appContext,
    )

    @Provides
    @Singleton
    fun provideStreamPreResolver(
        streamResolver: StreamResolver,
    ): StreamPreResolver = StreamPreResolver(streamResolver)

    @Provides
    @Singleton
    fun provideFavoritesRepository(
        favoriteDao: FavoriteDao,
    ): FavoritesRepository = FavoritesRepository(favoriteDao)
}
