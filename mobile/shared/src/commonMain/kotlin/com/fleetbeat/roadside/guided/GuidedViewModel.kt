package com.fleetbeat.roadside.guided

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GuidedState(val open: Boolean = false, val loading: Boolean = false,
    val situations: List<SymptomSituation> = emptyList(), val selected: SymptomSituation? = null,
    val choice: SymptomChoice? = null, val error: String? = null)

class GuidedViewModel(private val service: GuidedService, private val expired: (String) -> Unit,
    private val closeClient: () -> Unit = {}) : ViewModel() {
    private val mutable = MutableStateFlow(GuidedState())
    val state = mutable.asStateFlow()
    private var owner: Int? = null
    private var generation = 0
    private var operation: Job? = null
    fun bindUser(id: Int?) {
        if (id != owner) { closeScreen(); mutable.value = GuidedState(); owner = id }
    }
    fun open() {
        if (owner == null) return
        mutable.update { it.copy(open = true, selected = null, choice = null, error = null) }
        if (mutable.value.situations.isEmpty()) reload()
    }
    fun reload() {
        if (!mutable.value.open || mutable.value.loading) return
        val epoch = ++generation
        mutable.update { it.copy(loading = true, selected = null, choice = null, error = null) }
        operation = viewModelScope.launch {
            try {
                val result = withTimeout(20_000) { service.situations() }
                if (generation == epoch) mutable.update { it.copy(situations = result) }
            } catch (_: TimeoutCancellationException) {
                if (generation == epoch) mutable.update { it.copy(error = "Loading symptoms timed out. Try again.") }
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) {
                if (generation == epoch) {
                    if (error is AuthFailure && error.invalidSession) expired(error.message ?: "Please sign in again.")
                    mutable.update { it.copy(error = if (error is GuidedFailure || error is AuthFailure) error.message
                        else "Could not load symptoms. Check your connection and try again.") }
                }
            } finally { if (generation == epoch) mutable.update { it.copy(loading = false) } }
        }
    }
    fun select(id: String) {
        if (mutable.value.loading || !mutable.value.open) return
        mutable.value.situations.find { it.id == id }?.let { item -> mutable.update { it.copy(selected = item, choice = null, error = null) } }
    }
    fun choose(id: String) {
        if (!mutable.value.open) return
        mutable.value.selected?.followup?.choices?.find { it.id == id }?.let { item -> mutable.update { it.copy(choice = item, error = null) } }
    }
    fun preview(): String? {
        val state = mutable.value
        if (!state.open || state.loading) return null
        val situation = state.selected ?: return null
        if (situation.entry == "freeform") return ""
        val choice = state.choice ?: return null
        val text = "Help diagnose these symptoms with read-only checks.\nProblem: ${situation.title}\n${situation.followup?.question}\nObservation: ${choice.label}"
        if (text.length > 600) { mutable.update { it.copy(error = "This selection is too long. Use AI diagnostic to describe it instead.") }; return null }
        return text
    }
    fun back() {
        if (mutable.value.selected != null) mutable.update { it.copy(selected = null, choice = null, error = null) }
        else closeScreen()
    }
    fun closeScreen() {
        generation++; operation?.cancel(); operation = null
        mutable.update { it.copy(open = false, loading = false, selected = null, choice = null, error = null) }
    }
    override fun onCleared() { closeClient(); super.onCleared() }
}
