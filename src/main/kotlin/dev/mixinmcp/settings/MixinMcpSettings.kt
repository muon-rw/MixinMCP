package dev.mixinmcp.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "MixinMcpSettings", storages = [Storage("mixinmcp.xml")])
class MixinMcpSettings : PersistentStateComponent<MixinMcpSettings.State> {

    data class State(
        var autoInjectCursorRules: Boolean = true,
        var overwriteExistingRules: Boolean = true,
        var warnMissingGradlePlugin: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var autoInjectCursorRules: Boolean
        get() = state.autoInjectCursorRules
        set(value) { state.autoInjectCursorRules = value }

    var overwriteExistingRules: Boolean
        get() = state.overwriteExistingRules
        set(value) { state.overwriteExistingRules = value }

    var warnMissingGradlePlugin: Boolean
        get() = state.warnMissingGradlePlugin
        set(value) { state.warnMissingGradlePlugin = value }

    companion object {
        fun getInstance(project: Project): MixinMcpSettings =
            project.getService(MixinMcpSettings::class.java)
    }
}
