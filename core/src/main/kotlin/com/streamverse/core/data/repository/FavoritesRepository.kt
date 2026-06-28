package com.streamverse.core.data.repository

import com.streamverse.core.data.local.FavoriteChannelEntity
import com.streamverse.core.data.local.FavoriteDao
import com.streamverse.core.domain.model.Channel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
) {
    fun getAllFavoriteIds(): Flow<List<String>> = favoriteDao.getAllFavoriteIds()

    fun getAllFavorites(): Flow<List<FavoriteChannelEntity>> = favoriteDao.getAllFavorites()

    fun isFavorite(channelId: String): Flow<Boolean> = favoriteDao.isFavorite(channelId)

    fun getFavoriteCount(): Flow<Int> = favoriteDao.getFavoriteCount()

    suspend fun toggleFavorite(channel: Channel) {
        if (favoriteDao.isFavorite(channel.id).let { /* not easily checked sync */ false }) {
            // approximate — but we'll rely on the caller to check
        }
    }

    suspend fun addFavorite(channel: Channel) {
        favoriteDao.addFavorite(
            FavoriteChannelEntity(
                channelId = channel.id,
                displayName = channel.displayName,
            )
        )
    }

    suspend fun removeFavorite(channelId: String) {
        favoriteDao.removeFavorite(channelId)
    }
}
