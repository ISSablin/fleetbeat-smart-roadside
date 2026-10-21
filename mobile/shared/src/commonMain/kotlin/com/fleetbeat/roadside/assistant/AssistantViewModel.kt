package com.fleetbeat.roadside.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AssistantState(val open: Boolean = false, val question: String = "", val busy: Boolean = false,
                          val voiceActive: Boolean = false, val advice: Advice? = null, val error: String? = null, val notice: String? = null)

class AssistantViewModel(private val service: AdviceService, private val expired: (String) -> Unit,
                         private val closeClient: () -> Unit = {}) : ViewModel() {
    private val mutable = MutableStateFlow(AssistantState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var generation = 0
    private var owner: Int? = null
    private var voiceSequence = 0
    private var pendingVoice: Pair<Int, Int>? = null
    fun bindUser(id: Int?) { if (owner != id) { closeScreen(); owner = id } }
    fun open(question: String = "") {
        generation++; operation?.cancel(); pendingVoice = null
        mutable.value = AssistantState(open = true, question = question.take(600))
    }
    fun question(value: String) {
        if (!mutable.value.busy && !mutable.value.voiceActive) mutable.update { it.copy(question = value.take(600), advice = null, error = null, notice = null) }
    }
    fun beginVoiceInput(): Int? {
        val state = mutable.value
        if (!state.open || state.busy || state.voiceActive) return null
        if (state.question.length >= 600) {
            mutable.update { it.copy(notice = "Shorten your question before adding more speech.") }; return null
        }
        val token = ++voiceSequence
        pendingVoice = token to generation
        mutable.update { it.copy(voiceActive = true, error = null, notice = null) }
        return token
    }
    fun voiceResult(token: Int, transcript: String? = null, cancelled: Boolean = false, failure: String? = null) {
        if (pendingVoice != token to generation || !mutable.value.open) return
        pendingVoice = null
        val text = transcript?.trim().orEmpty()
        mutable.update { state ->
            when {
                cancelled -> state.copy(voiceActive = false, notice = "Voice input cancelled.")
                failure != null -> state.copy(voiceActive = false, error = failure)
                text.isEmpty() -> state.copy(voiceActive = false, error = "No speech recognized. Try again.")
                else -> {
                    val combined = listOf(state.question.trimEnd(), text).filter { it.isNotEmpty() }.joinToString(" ")
                    state.copy(voiceActive = false, question = combined.take(600), advice = null, error = null,
                        notice = if (combined.length > 600) "Voice text added up to the 600-character limit. Review before sending."
                            else "Voice text added. Review it before sending.")
                }
            }
        }
    }
    fun send() {
        if (mutable.value.busy || mutable.value.voiceActive || !mutable.value.open) return
        val text = mutable.value.question.trim()
        if (text.isEmpty()) { mutable.update { it.copy(error = "Enter a diagnostic question.") }; return }
        val epoch = generation
        mutable.update { it.copy(question = text, busy = true, advice = null, error = null, notice = null) }
        operation = viewModelScope.launch {
            try {
                val result = withTimeout(80_000) { service.ask(text) }
                if (generation == epoch) {
                    if (result.stub || result.body.isBlank()) throw AdviceFailure("The AI service did not return generated guidance.")
                    mutable.update { it.copy(advice = result) }
                }
            } catch (error: TimeoutCancellationException) {
                if (generation == epoch) mutable.update { it.copy(error = "The AI request timed out. You can try again.") }
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) {
                if (generation == epoch) {
                    if (error is AuthFailure && error.invalidSession) expired(error.message ?: "Please sign in again.")
                    mutable.update { it.copy(error = if (error is AdviceFailure || error is AuthFailure) error.message
                        else "Could not reach the AI service. Check your connection and try again.") }
                }
            } finally { if (generation == epoch) mutable.update { it.copy(busy = false) } }
        }
    }
    fun stopWaiting() {
        generation++; operation?.cancel(); operation = null
        mutable.update { it.copy(busy = false, notice = "Stopped waiting. The server may still finish the request.") }
    }
    fun closeScreen() { generation++; operation?.cancel(); operation = null; pendingVoice = null; mutable.value = AssistantState() }
    override fun onCleared() { closeClient(); super.onCleared() }
}
