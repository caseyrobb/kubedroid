package com.neozeon.kubedroid.ui.resources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.model.ClusterConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * Spec 04 stage 1: resolves the connection behind [BrowseKey] for the browse
 * top bar. Stages 2+ grow this into the catalog grid state.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repository: ClusterRepository,
) : ViewModel() {

    fun connectionFor(id: UUID): StateFlow<ClusterConnection?> =
        repository.connections
            .map { list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
