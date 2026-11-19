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
        var warnMissingAntigravityPlugin: Boolean = true,
        var decompiledCacheMaxGiB: Int = DEFAULT_DECOMPILED_CACHE_MAX_GIB,
        var trigramCacheMaxMiB: Int = DEFAULT_TRIGRAM_CACHE_MAX_MIB,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var warnMissingClaudePlugin: Boolean
        get() = state.warnMissingClaudePlugin
        set(value) { state.warnMissingClaudePlugin = value }

    var warnMissingAntigravityPlugin: Boolean
        get() = state.warnMissingAntigravityPlugin
        set(value) { state.warnMissingAntigravityPlugin = value }

    var decompiledCacheMaxGiB: Int
        get() = state.decompiledCacheMaxGiB
        set(value) { state.decompiledCacheMaxGiB = value }

    var trigramCacheMaxMiB: Int
        get() = state.trigramCacheMaxMiB
        set(value) { state.trigramCacheMaxMiB = value }

    companion object {
        const val DEFAULT_DECOMPILED_CACHE_MAX_GIB: Int = 6
        const val DEFAULT_TRIGRAM_CACHE_MAX_MIB: Int = 512
        val DECOMPILED_CACHE_MAX_GIB_RANGE: IntRange = 1..1024
        val TRIGRAM_CACHE_MAX_MIB_RANGE: IntRange = 64..65536

        fun getInstance(): MixinMcpAppSettings =
            ApplicationManager.getApplication().getService(MixinMcpAppSettings::class.java)
    }
}
