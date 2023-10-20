package com.nbadal.ktlint

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Tag

@State(
    name = "KtlintFormatPluginProjectConfiguration",
    storages = [Storage("ktlint-plugin.xml")],
)
@Service(Service.Level.PROJECT)
class KtlintConfigStorage : PersistentStateComponent<KtlintConfigStorage> {
    @Tag
    var enableKtlint = true

    @Tag
    var baselinePath: String? = null

    @Tag
    var externalJarPaths: List<String> = emptyList()

    override fun getState(): KtlintConfigStorage = this

    override fun loadState(state: KtlintConfigStorage) {
        println("KtlintConfigStorage: loading state, enable ktlint format plugin: ${state.enableKtlint}")
        this.enableKtlint = state.enableKtlint
        this.baselinePath = state.baselinePath
        this.externalJarPaths = state.externalJarPaths
    }
}
