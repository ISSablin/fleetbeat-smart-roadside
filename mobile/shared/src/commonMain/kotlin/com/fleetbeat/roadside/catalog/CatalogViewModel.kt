package com.fleetbeat.roadside.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.auth.AuthFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CatalogStage { BRANDS, MODELS, YEARS, CONTROL_UNITS, SUMMARY }

data class CatalogState(
    val open: Boolean = false,
    val stage: CatalogStage = CatalogStage.BRANDS,
    val brand: CatalogItem? = null, val model: CatalogItem? = null, val year: CatalogItem? = null,
    val selectedUnit: CatalogItem? = null,
    val items: List<CatalogItem> = emptyList(), val query: String = "",
    val loading: Boolean = false, val error: String? = null,
) {
    val filtered: List<CatalogItem> get() = items.filter {
        query.isBlank() || (it.title + " " + it.subtitle).contains(query.trim(), ignoreCase = true)
    }
    val title: String get() = when (stage) {
        CatalogStage.BRANDS -> "Select truck brand"
        CatalogStage.MODELS -> "Select model"
        CatalogStage.YEARS -> "Select year"
        CatalogStage.CONTROL_UNITS -> "Control units"
        CatalogStage.SUMMARY -> "Selected control unit"
    }
    val vehicleName: String get() = listOfNotNull(brand?.title, model?.title, year?.title).joinToString(" · ")
}

class CatalogViewModel(private val catalog: VehicleCatalog,
                       private val sessionExpired: (String) -> Unit,
                       private val closeClient: () -> Unit = {}) : ViewModel() {
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private val history = mutableListOf<CatalogState>()
    private var request: Job? = null
    private var generation = 0
    private var ownerId: Int? = null

    fun bindUser(userId: Int?) {
        if (ownerId != userId) {
            reset()
            ownerId = userId
        }
    }

    fun open() {
        reset()
        mutable.value = CatalogState(open = true)
        load()
    }

    fun reset() {
        generation++
        request?.cancel()
        history.clear()
        mutable.value = CatalogState()
    }

    fun search(query: String) { mutable.update { it.copy(query = query) } }

    fun select(item: CatalogItem) {
        val current = mutable.value
        if (current.loading || current.stage == CatalogStage.SUMMARY || item !in current.items) return
        history.add(current)
        mutable.value = when (current.stage) {
            CatalogStage.BRANDS -> current.copy(stage = CatalogStage.MODELS, brand = item)
            CatalogStage.MODELS -> current.copy(stage = CatalogStage.YEARS, model = item)
            CatalogStage.YEARS -> current.copy(stage = CatalogStage.CONTROL_UNITS, year = item)
            CatalogStage.CONTROL_UNITS -> current.copy(stage = CatalogStage.SUMMARY, selectedUnit = item)
            CatalogStage.SUMMARY -> current
        }.copy(items = emptyList(), query = "", error = null)
        if (mutable.value.stage != CatalogStage.SUMMARY) load()
    }

    fun back() {
        generation++
        request?.cancel()
        if (history.isEmpty()) reset() else mutable.value = history.removeAt(history.lastIndex)
    }

    fun retry() { if (mutable.value.open && !mutable.value.loading) load() }

    private fun load() {
        val current = mutable.value
        val version = ++generation
        request?.cancel()
        mutable.update { it.copy(loading = true, error = null, items = emptyList()) }
        request = viewModelScope.launch {
            try {
                val items = when (current.stage) {
                    CatalogStage.BRANDS -> catalog.brands()
                    CatalogStage.MODELS -> catalog.models(current.brand!!.id)
                    CatalogStage.YEARS -> catalog.years(current.model!!.id)
                    CatalogStage.CONTROL_UNITS -> catalog.controlUnits(current.model!!.id, current.year!!.id)
                    CatalogStage.SUMMARY -> emptyList()
                }
                if (version == generation) mutable.update { it.copy(items = items) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == generation) {
                    if (error is AuthFailure && error.invalidSession) {
                        reset()
                        sessionExpired(error.message ?: "Please sign in again.")
                    } else {
                        val message = if (error is CatalogFailure) error.message else
                            "Could not load the catalog. Check your connection and try again."
                        mutable.update { it.copy(error = message) }
                    }
                }
            } finally {
                if (version == generation) mutable.update { it.copy(loading = false) }
            }
        }
    }

    override fun onCleared() { request?.cancel(); closeClient(); super.onCleared() }
}
