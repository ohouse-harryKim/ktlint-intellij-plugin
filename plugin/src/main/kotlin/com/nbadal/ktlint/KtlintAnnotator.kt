package com.nbadal.ktlint

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity.ERROR
import com.intellij.lang.annotation.HighlightSeverity.WARNING
import com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.refactoring.suggested.startOffset
import com.nbadal.ktlint.KtlintConfigStorage.KtlintMode.DISABLED
import com.nbadal.ktlint.KtlintConfigStorage.KtlintMode.ENABLED
import com.nbadal.ktlint.KtlintConfigStorage.KtlintMode.NOT_INITIALIZED
import com.nbadal.ktlint.actions.ForceFormatIntention
import com.nbadal.ktlint.actions.KtlintModeIntention
import com.nbadal.ktlint.actions.KtlintRuleSuppressIntention
import com.pinterest.ktlint.rule.engine.api.LintError

class KtlintAnnotator : ExternalAnnotator<List<LintError>, List<LintError>>() {
    private val ktlint = Key<FileEditorReference>("ktlint")

    private data class FileEditorReference(
        val editorHashCode: Int,
        val modificationTimestamp: Long,
    )

    override fun collectInformation(
        psiFile: PsiFile,
        editor: Editor,
        hasErrors: Boolean,
    ): List<LintError>? =
        if (hasErrors) {
            null
        } else {
            editor
                .document
                .getUserData(ktlint)
                .let { fileEditorReference ->
                    if (fileEditorReference?.editorHashCode == editor.hashCode() &&
                        fileEditorReference.modificationTimestamp == editor.document.modificationStamp
                    ) {
                        // If ktlint resulted in an error, last time that the document was processed, then do not repeat that unless the
                        // file is opened into another editor panel, or when the document is changed. This prevents the same error
                        // notification from being displayed multiple times.
                        null
                    } else {
                        ktlintLint(psiFile, "KtlintAnnotator")
                            .also { ktlintResult ->
                                if (ktlintResult.status != KtlintResult.Status.SUCCESS) {
                                    // On error, store some state in the user data as it does not make sense to restart ktlint on the
                                    // document.
                                    editor.document.putUserData(
                                        ktlint,
                                        FileEditorReference(editor.hashCode(), editor.document.modificationStamp),
                                    )
                                }
                            }.lintErrors
                    }
                }
        }

    override fun doAnnotate(collectedInfo: List<LintError>?): List<LintError>? =
        collectedInfo?.sortedWith(compareBy(LintError::line).thenComparingInt(LintError::col))

    override fun apply(
        psiFile: PsiFile,
        errors: List<LintError>?,
        holder: AnnotationHolder,
    ) {
        if (psiFile.project.config().ktlintMode == ENABLED) {
            applyWhenPluginIsEnabled(psiFile, errors, holder)
        } else {
            applyWhenPluginIsNotEnabled(psiFile, errors, holder)
        }
    }

    private fun applyWhenPluginIsEnabled(
        psiFile: PsiFile,
        errors: List<LintError>?,
        holder: AnnotationHolder,
    ) {
        // Showing all errors which can be autocorrected is distracting, and can lead to waste of developer time in case the developer
        // starts fixing the errors manually. So display a warning with the number of errors that can be autocorrected.
        errors
            ?.count { it.canBeAutoCorrected }
            ?.takeIf { it > 0 }
            ?.let { count ->
                holder
                    .newAnnotation(WARNING, "This file contains $count lint violations which can be autocorrected by ktlint")
                    .range(TextRange(0, 0))
                    .withFix(ForceFormatIntention())
                    .create()
            }

        errors
            ?.filter {
                // Showing all errors which can be autocorrected is distracting, and can lead to waste of developer time in case the
                // developer starts fixing the errors manually.
                !it.canBeAutoCorrected
            }?.forEach { lintError ->
                errorTextRange(psiFile, lintError)
                    ?.let { errorTextRange ->
                        holder
                            .newAnnotation(ERROR, lintError.errorMessage())
                            .range(errorTextRange)
                            .withFix(KtlintRuleSuppressIntention(lintError))
                            .withFix(KtlintModeIntention(DISABLED))
                            .create()
                    }
            }
    }

    private fun applyWhenPluginIsNotEnabled(
        psiFile: PsiFile,
        errors: List<LintError>?,
        holder: AnnotationHolder,
    ) {
        val countCanBeAutoCorrected = errors?.count { it.canBeAutoCorrected } ?: 0
        val countCanNotBeAutoCorrected = errors?.count { !it.canBeAutoCorrected } ?: 0

        val annotationBuilder =
            when {
                countCanBeAutoCorrected > 0 && countCanNotBeAutoCorrected > 0 -> {
                    holder
                        .newAnnotation(
                            WEAK_WARNING,
                            "This file contains $countCanBeAutoCorrected lint violations which can be autocorrected by ktlint and " +
                                "$countCanNotBeAutoCorrected lint violations that should be corrected manually",
                        ).range(TextRange(0, 0))
                        .withFix(ForceFormatIntention())
                }
                countCanBeAutoCorrected > 0 -> {
                    holder
                        .newAnnotation(
                            WEAK_WARNING,
                            "This file contains $countCanBeAutoCorrected lint violations which can be autocorrected by ktlint",
                        ).range(TextRange(0, 0))
                        .withFix(ForceFormatIntention())
                }
                countCanNotBeAutoCorrected > 0 -> {
                    holder
                        .newAnnotation(
                            WEAK_WARNING,
                            "This file contains $countCanNotBeAutoCorrected ktlint violations that should be corrected manually",
                        ).range(TextRange(0, 0))
                        .withFix(ForceFormatIntention())
                }

                else -> {
                    return
                }
            }

        annotationBuilder.withFix(KtlintModeIntention(ENABLED))
        if (psiFile.project.config().ktlintMode == NOT_INITIALIZED) {
            annotationBuilder.withFix(KtlintModeIntention(DISABLED))
        }
        annotationBuilder.create()
    }

    private fun LintError.errorMessage(): String = "$detail (${ruleId.value})"

    private fun errorTextRange(
        psiFile: PsiFile,
        lintError: LintError,
    ): TextRange? {
        val document = psiFile.viewProvider.document!!
        return if (document.textLength == 0) {
            // It is not possible to draw an annotation on empty file
            null
        } else {
            psiFile
                .findElementAt(lintError.offsetFromStartOf(document))
                ?.let { TextRange.from(it.startOffset, it.textLength) }
                ?: TextRange(lintError.lineStartOffset(document), lintError.getLineEndOffset(document))
        }
    }

    private fun LintError.offsetFromStartOf(document: Document) =
        with(document) {
            val lineStartOffset = lineStartOffset(this)
            (lineStartOffset + (col - 1))
                .coerceIn(lineStartOffset, textLength)
        }

    private fun LintError.lineStartOffset(document: Document) =
        with(document) {
            getLineStartOffset((line - 1).coerceIn(0, lineCount - 1))
                .coerceIn(0, textLength)
        }

    private fun LintError.getLineEndOffset(document: Document) =
        with(document) {
            getLineEndOffset((line - 1).coerceIn(0, lineCount - 1))
                .coerceIn(0, textLength)
        }
}
