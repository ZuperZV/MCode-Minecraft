package com.github.zuperzv.mcodeminecraft.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "MCodeModelViewerSettings",
    storages = [Storage("mcodeminecraft.xml")]
)
class ModelViewerSettings : PersistentStateComponent<ModelViewerSettings.State> {

    data class State(
        var viewMode: String = "textured",
        var orthographic: Boolean = false,
        var gridEnabled: Boolean = false
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun setViewMode(value: String) {
        state.viewMode = value
    }

    fun setOrthographic(value: Boolean) {
        state.orthographic = value
    }

    fun setGridEnabled(value: Boolean) {
        state.gridEnabled = value
    }
}
