package dev.mixinmcp.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "MixinMcpAppSettings", storages = [Storage("mixinmcp.xml")])
class MixinMcpAppSettings : PersistentStateComponent<MixinMcpAppSettings.State> {

    data class State(
        var warnMissingClaudePlugin: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var warnMissingClaudePlugin: Boolean
        get() = state.warnMissingClaudePlugin
        set(value) { state.warnMissingClaudePlugin = value }

    companion object {
        fun getInstance(): MixinMcpAppSettings =
            ApplicationManager.getApplication().getService(MixinMcpAppSettings::class.java)
    }
}
