package com.nbadal.ktlint.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import com.nbadal.ktlint.config
import com.nbadal.ktlint.doLint
import com.nbadal.ktlint.errorElement

class KtlintInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
        val config = file.project.config()
        val errorLevel = if (config.treatAsErrors) ProblemHighlightType.ERROR else ProblemHighlightType.WARNING
        return doLint(file, config, false).uncorrectedErrors.mapNotNull { err ->
            file.errorElement(err)?.let { element ->
                manager.createProblemDescriptor(
                    element,
                    "${err.detail} (${err.ruleId})",
                    null as LocalQuickFix?,
                    errorLevel,
                    false
                )
            }
        }.toTypedArray()
    }
}
