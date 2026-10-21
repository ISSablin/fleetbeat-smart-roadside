package com.fleetbeat.roadside.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthState(val email: String = "", val password: String = "",
                     val loading: Boolean = true, val profile: Profile? = null,
                     val error: String? = null)

class AuthViewModel(private val repository: AuthRepository, private val closeClient: () -> Unit) : ViewModel() {
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()

    init { perform { mutable.update { it.copy(profile = repository.restore()) } } }

    fun emailChanged(value: String) { mutable.update { it.copy(email = value, error = null) } }
    fun passwordChanged(value: String) { mutable.update { it.copy(password = value, error = null) } }

    fun login() {
        val current = mutable.value
        if (current.loading) return
        if (current.email.isBlank() || current.password.isEmpty()) {
            mutable.update { it.copy(error = "Enter your email and password.") }
            return
        }
        perform {
            val profile = repository.login(current.email, current.password)
            mutable.update { it.copy(profile = profile, password = "") }
        }
    }

    fun refresh() {
        if (!mutable.value.loading) perform {
            val profile = repository.profile()
            mutable.update { it.copy(profile = profile) }
        }
    }

    fun logout() {
        if (!mutable.value.loading) perform {
            repository.logout()
            mutable.value = AuthState(loading = true)
        }
    }

    private fun perform(action: suspend () -> Unit) {
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = if (error is AuthFailure) error.message else
                    "Could not connect. Check your connection and device date and time, then try again."
                mutable.update {
                    it.copy(error = message, password = "", profile = if (error is AuthFailure && error.invalidSession) null else it.profile)
                }
            } finally { mutable.update { it.copy(loading = false) } }
        }
    }

    override fun onCleared() { closeClient(); super.onCleared() }
}
