package com.nbadal.ktlint

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.nbadal.ktlint.KtlintConfigStorage.KtlintMode.ENABLED
import com.pinterest.ktlint.cli.reporter.core.api.KtlintCliError
import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintParseException
import com.pinterest.ktlint.rule.engine.api.KtLintRuleException
import com.pinterest.ktlint.rule.engine.api.LintError
import org.ec4j.core.parser.ParseException
import java.nio.file.Files
import java.nio.file.Path

internal fun ktlintLint(
    psiFile: PsiFile,
    triggeredBy: String,
) = if (psiFile.virtualFile.isKotlinFile()) {
    executeKtlintFormat(psiFile, triggeredBy, false)
} else {
    EMPTY_LINT_ERRORS
}

internal fun ktlintFormat(
    psiFile: PsiFile,
    triggeredBy: String,
    forceFormat: Boolean = false,
) {
    if (psiFile.virtualFile.isKotlinFile()) {
        val project = psiFile.project
        val document = psiFile.viewProvider.document
        PsiDocumentManager
            .getInstance(project)
            .doPostponedOperationsAndUnblockDocument(document)
        WriteCommandAction.runWriteCommandAction(project) {
            executeKtlintFormat(psiFile, triggeredBy, true, forceFormat)
        }
        FileDocumentManager.getInstance().saveDocument(document)
        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
        PsiDocumentManager
            .getInstance(project)
            .doPostponedOperationsAndUnblockDocument(document)
    }
}

private fun executeKtlintFormat(
    psiFile: PsiFile,
    triggeredBy: String,
    writeFormattedCode: Boolean = false,
    forceFormat: Boolean = false,
): List<LintError> {
    val project = psiFile.project
    if (project.config().ktlintMode != ENABLED && !forceFormat) {
        return EMPTY_LINT_ERRORS
    }

    println("Start ktlintFormat on file '${psiFile.virtualFile.name}' triggered by '$triggeredBy'")

    project
        .config()
        .ruleSetProviders
        .takeIf { !it.isLoaded }
        ?.let {
            KtlintNotifier
                .notifyErrorWithSettings(
                    project = project,
                    title = "Error in external ruleset JAR",
                    message =
                        """
                        One or more of the external rule set JAR's defined in the ktlint settings, can not be loaded.
                        Error: ${project.config().ruleSetProviders.error.orEmpty()}
                        """.trimMargin(),
                )
            return EMPTY_LINT_ERRORS
        }

    // KtLint wants the full file path in order to search for .editorconfig files
    val baselineErrors = project.baselineErrors(psiFile.virtualFile.path)

    val lintErrors = mutableListOf<LintError>()
    try {
        val formattedCode =
            project
                .config()
                .ktlintRuleEngine(psiFile.findEditorConfigDirectoryPath())
                ?.format(
                    // The psiFile may contain unsaved changes. So create a snippet based on content of the psiFile *and*
                    // with the same path as that psiFile so that the correct '.editorconfig' is picked up by ktlint.
                    Code.fromSnippet(
                        // Get the content via the PsiDocumentManager instead of from "psiFile.text" directly. In case
                        // the content of an active editor window is changed via a global find and replace, the document
                        // text is updated but the Psi (and PsiFile) have not yet been changed.
                        content = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!.text,
                        script = psiFile.virtualFile.path.endsWith(".kts"),
                        // TODO: de-comment when parameter is supported in Ktlint 1.1.0
                        // path = psiFile.virtualFile.toNioPath(),
                    ),
                ) { error, _ ->
                    when {
                        // TODO: remove exclusion of rule "standard:filename" as this now results in false positives. When
                        //  using "Code.fromSnippet" in Ktlint 1.0.0, the filename "File.kt" or "File.kts" is being used
                        //  instead of the real name of the file. With fix in Ktlint 1.1.0 the filename will be based on
                        //  parameter "path" and the rule will no longer cause false positives.
                        error.ruleId.value == "standard:filename" -> {
                            println("Ignore rule '${error.ruleId.value}'")
                        }

                        error.isIgnoredInBaseline(baselineErrors) -> Unit
                        else -> lintErrors.add(error)
                    }
                }
                ?: return EMPTY_LINT_ERRORS
                    .also { println("Could not create ktlintRuleEngine for path '${psiFile.virtualFile.path}'") }
        if (writeFormattedCode) {
            psiFile.viewProvider.document.setText(formattedCode)
        }
        println("Finished ktlintFormat on file '${psiFile.virtualFile.name}' triggered by '$triggeredBy' successfully")
    } catch (ktLintParseException: KtLintParseException) {
        // Most likely the file contains a compilation error which prevents it from being parsed. The user should resolve those errors.
        // The stacktrace is excluded from the message as it would distract from resolving the error.
        KtlintNotifier.notifyWarning(
            project = project,
            title = "Parsing error",
            message =
                """
                File '${psiFile.virtualFile.path}' can not be parsed by ktlint. Please resolve all (compilation) errors first.
                Error: ${ktLintParseException.message}
                """.trimIndent(),
        )
        return EMPTY_LINT_ERRORS
    } catch (ktLintRuleException: KtLintRuleException) {
        KtlintNotifier.notifyError(
            project = project,
            title = "KtLintRuleException",
            message =
                """
                An error occurred in a rule. Please see stacktrace below for rule that caused the problem and contact maintainer of the
                rule when the error can be reproduced.
                ${ktLintRuleException.stackTraceToString()}
                """.trimIndent(),
        )
        return EMPTY_LINT_ERRORS
    } catch (parseException: ParseException) {
        KtlintNotifier.notifyError(
            project = project,
            title = "Invalid editorconfig",
            message =
                """
                An error occurred while reading the '.editorconfig':
                ${parseException.message}
                """.trimIndent(),
        )
        return EMPTY_LINT_ERRORS
    } catch (exception: Exception) {
        KtlintNotifier.notifyError(
            project = project,
            title = "Invalid editorconfig",
            message =
                """
                An error occurred while processing file '${psiFile.virtualFile.path}':
                ${exception.printStackTrace()}
                """.trimIndent(),
        )
        return EMPTY_LINT_ERRORS
    }

    return lintErrors
}

private fun LintError.isIgnoredInBaseline(baselineErrors: List<KtlintCliError>) =
    baselineErrors
        .any { baselineError ->
            baselineError.line == line &&
                baselineError.col == col &&
                baselineError.ruleId == ruleId.value &&
                baselineError.status == KtlintCliError.Status.BASELINE_IGNORED
        }

private fun PsiFile.findEditorConfigDirectoryPath(): Path? {
    var directory = virtualFile.toNioPath().parent
    while (Files.notExists(directory.resolve(".editorconfig")) && directory.parent != null) {
        directory = directory.parent
    }
    return directory
}

private fun Project.baselineErrors(filePath: String) =
    config()
        .baseline
        .lintErrorsPerFile[filePath.pathRelativeTo(basePath)]
        .orEmpty()

private fun String.pathRelativeTo(projectBasePath: String?): String =
    if (projectBasePath.isNullOrBlank()) {
        this
    } else {
        removePrefix(projectBasePath).removePrefix("/")
    }

private val EMPTY_LINT_ERRORS = emptyList<LintError>()
