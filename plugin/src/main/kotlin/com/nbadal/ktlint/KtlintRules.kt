package com.nbadal.ktlint

import com.pinterest.ktlint.core.RuleProvider
import com.pinterest.ktlint.core.RuleSetProvider
import com.pinterest.ktlint.core.RuleSetProviderV2
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import java.net.URLClassLoader
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

object KtlintRules {
    fun rulesets(paths: List<String>, experimental: Boolean) = paths.loadRulesets(experimental, true)

    private fun List<String>.loadRulesets(
        loadExperimental: Boolean,
        loadStandard: Boolean
    ): Map<String, Set<RuleProvider>> = toFilesURIList()
        .plus(
            // Ensure that always at least one element exists in this list so that the rule sets provided by the KtLint
            // CLI module itself will be found even in case no JAR files are specified
            null
        )
        // Remove JAR files which were provided multiple times
        .distinct()
        .flatMap { url ->
            getRuleProvidersFromJar(url)
                .filterKeys { loadExperimental || it != "experimental" }
                .filterKeys { loadStandard || it != "standard" }
                .entries
        }
        .associate { it.toPair() }

    private fun getRuleProvidersFromJar(
        url: URL?
    ): Map<String, Set<RuleProvider>> {
        return try {
            ServiceLoader
                .load(
                    RuleSetProviderV2::class.java,
                    URLClassLoader(listOfNotNull(url).toTypedArray(), RuleSetProviderV2::class.java.classLoader)
                ).filter {
                    // The KtLint-root (CLI) module includes the standard and experimental rule sets. When those rule sets
                    // are also included in the specified JAR (url != null) then ignore them.
                    url == null || it.id !in KTLINT_RULE_SETS
                }.associate { ruleSetProviderV2 -> ruleSetProviderV2.id to ruleSetProviderV2.getRuleProviders() }
                .ifEmpty { getLegacyRuleProvidersFromJar(url) }
        } catch (e: ServiceConfigurationError) {
            try {
                getLegacyRuleProvidersFromJar(url)
            } catch (e: ServiceConfigurationError) {
                if (url != null) {
                    // TODO: Warn:
                    """
                    JAR ${url.path}, provided as command line argument, does not contain a custom ruleset provider.
                        Check following:
                          - Does the jar contain an implementation of the RuleSetProviderV2 interface?
                          - Does the jar contain a resource file with name "com.pinterest.ktlint.core.RuleSetProviderV2"?
                          - Is the resource file located in directory "src/main/resources/META-INF/services"?
                          - Does the resource file contain the fully qualified class name of the class implementing the RuleSetProvider interface?
                    """.trimIndent()
                }
                emptyMap()
            }
        }
    }

    private fun getLegacyRuleProvidersFromJar(url: URL?): Map<String, Set<RuleProvider>> =
        ServiceLoader
            .load(
                RuleSetProvider::class.java,
                URLClassLoader(listOfNotNull(url).toTypedArray(), RuleSetProvider::class.java.classLoader)
            ).filter {
                // The standard and experimental KtLint rule sets are included the ktlint CLI module itself (url ==
                // null). When those rule set are also included in the specified JAR (url != null) then ignore them.
                url == null || it.get().id !in KTLINT_RULE_SETS
            }.associate { ruleSetProviderV1 ->
                // The original rule set provider interface contained a getter for obtaining the ruleSet. This
                // getter then should provide a RuleSet containing new instances of each rule. Starting from KtLint
                // 0.47 the rule engine need more fine-grained control when to create a new instance of a Rule. For
                // backwards compatibility in KtLint 0.47 the RuleProviders are created in a memory expensive way.
                // It will be removed in KtLint 0.48.
                val ruleSet = ruleSetProviderV1.get()
                val ruleProviders =
                    ruleSet
                        .rules
                        .mapIndexed { index, _ -> RuleProvider(ruleSetProviderV1, index) }
                        .toSet()
                ruleSet.id to ruleProviders
            }.also {
                if (url != null) {
                    if (it.isEmpty()) {
                        // TODO: Warn:
                        """
                        JAR ${url.path}, provided as command line argument, does not contain a custom ruleset provider.
                            Check following:
                              - Does the jar contain an implementation of the RuleSetProviderV2 interface?
                              - Does the jar contain a resource file with name "com.pinterest.ktlint.core.RuleSetProviderV2"?
                              - Is the resource file located in directory "src/main/resources/META-INF/services"?
                              - Does the resource file contain the fully qualified class name of the class implementing the RuleSetProvider interface?
                        """.trimIndent()
                    } else if (it.values.isNotEmpty()) {
                        // TODO: Warn:
                        "JAR ${url.path}, provided as command line argument, contains a custom ruleset provider which " +
                            "will *NOT* be compatible with the next KtLint version (0.48). Contact the maintainer of " +
                            "this ruleset. This JAR is not maintained by the KtLint project."
                    }
                }
            }

    private fun List<String>.toFilesURIList() = map {
        val jarFile = File(it)
        if (!jarFile.exists()) {
            throw FileNotFoundException(it)
        }
        jarFile.toURI().toURL()
    }

    private val KTLINT_RULE_SETS = listOf("standard", "experimental")
}
