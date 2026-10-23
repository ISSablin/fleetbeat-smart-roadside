package com.fleetbeat.roadside.auth

data class SavedSession(val token: String, val expiresAt: Long)
interface SessionStore {
    fun load(): SavedSession?
    fun save(session: SavedSession)
    fun clear()
}

class AuthRepository(private val api: AuthApi, private val store: SessionStore,
                     private val now: () -> Long) {
    private var session: SavedSession? = null

    suspend fun restore(): Profile? {
        session = store.load()
        val saved = session ?: return null
        if (saved.expiresAt <= now()) {
            logout()
            return null
        }
        return profile()
    }

    suspend fun login(email: String, password: String): Profile {
        val token = api.login(email, password)
        val profile = api.profile(token.access_token)
        val saved = SavedSession(token.access_token, now() + token.expires_in * 1000)
        store.save(saved)
        session = saved
        return profile
    }

    suspend fun profile(): Profile {
        val saved = session ?: throw AuthFailure("Please sign in again.", true)
        try {
            return api.profile(saved.token)
        } catch (error: AuthFailure) {
            if (error.invalidSession) logout()
            throw error
        }
    }

    fun accessToken(): String {
        val saved = session ?: throw AuthFailure("Please sign in again.", true)
        if (saved.expiresAt <= now()) throw AuthFailure("Your session expired. Please sign in again.", true)
        return saved.token
    }

    fun logout() {
        store.clear()
        session = null
    }
}
