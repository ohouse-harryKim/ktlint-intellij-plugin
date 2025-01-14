package com.nbadal.ktlint

import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.ActionOnSave
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

class KtlintActionOnSave : ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean = project.ktlintEnabled()

    override fun processDocuments(
        project: Project,
        documents: Array<out Document>,
    ) {
        if (project.config().formatOnSave) {
            val psiFiles =
                with(FileDocumentManager.getInstance()) {
                    documents
                        .mapNotNull { getFile(it) }
                        .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                }

            if (psiFiles.any { it.language == EDITOR_CONFIG_LANGUAGE }) {
                // Save all ".editorconfig" files before processing other changed documents so the changed ".editorconfig" files are taken
                // into account while processing those documents.
                psiFiles
                    .filter { it.language == EDITOR_CONFIG_LANGUAGE }
                    .forEach {
                        FileDocumentManager.getInstance().saveDocument(it.viewProvider.document)
                    }

                // Reset KtlintRuleEngine as it has cached the '.editorconfig'
                project.config().resetKtlintRuleEngine()

                // Format all files in open editors
                FileEditorManager
                    .getInstance(project)
                    .openFiles
                    .forEach { virtualFile ->
                        PsiManager
                            .getInstance(project)
                            .findFile(virtualFile)
                            ?.let { psiFile -> ktlintFormat(psiFile, "KtlintActionOnSave") }
                    }
            } else {
                // Only format files which were modified
                psiFiles.forEach { psiFile -> ktlintFormat(psiFile, "KtlintActionOnSave") }
            }
        }
    }
}

private val EDITOR_CONFIG_LANGUAGE = Language.findLanguageByID("EditorConfig")
