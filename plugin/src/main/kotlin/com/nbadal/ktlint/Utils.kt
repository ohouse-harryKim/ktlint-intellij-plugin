package com.nbadal.ktlint

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.pinterest.ktlint.core.LintError

fun Project.config() = ServiceManager.getService(this, KtlintConfigStorage::class.java)!!

/** @return the element specified by the error */
fun PsiFile.errorElement(error: LintError): PsiElement? =
    viewProvider.document?.let { doc ->
        if (error.line >= doc.lineCount) return null
        return findElementAt(doc.getLineStartOffset(error.line - 1) + error.col - 1)
    }
