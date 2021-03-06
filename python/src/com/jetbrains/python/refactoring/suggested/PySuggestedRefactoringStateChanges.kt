// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.refactoring.suggested

import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.refactoring.suggested.SuggestedRefactoringState
import com.intellij.refactoring.suggested.SuggestedRefactoringStateChanges
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport
import com.intellij.refactoring.suggested.range
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.codeInsight.typing.PyTypingTypeProvider
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyParameter
import com.jetbrains.python.psi.PySingleStarParameter
import com.jetbrains.python.psi.PySlashParameter
import com.jetbrains.python.psi.impl.ParamHelper
import com.jetbrains.python.psi.types.PyCallableParameter
import com.jetbrains.python.psi.types.TypeEvalContext

internal class PySuggestedRefactoringStateChanges(support: PySuggestedRefactoringSupport) : SuggestedRefactoringStateChanges(support) {

  override fun signature(declaration: PsiElement, prevState: SuggestedRefactoringState?): SuggestedRefactoringSupport.Signature? {
    return findStateChanges(declaration).signature(declaration, prevState)
  }

  override fun parameterMarkerRanges(declaration: PsiElement): List<TextRange?> {
    return findStateChanges(declaration).parameterMarkerRanges(declaration)
  }

  override fun updateState(state: SuggestedRefactoringState, declaration: PsiElement): SuggestedRefactoringState {
    return findStateChanges(declaration).updateNewState(state, declaration, super.updateState(state, declaration))
  }

  override fun guessParameterIdByMarkers(markerRange: TextRange, prevState: SuggestedRefactoringState): Any? {
    return super.guessParameterIdByMarkers(markerRange, prevState)
           ?: findStateChanges(prevState.declaration).guessParameterIdByMarker(markerRange, prevState)
  }

  private fun findStateChanges(declaration: PsiElement): StateChangesInternal {
    return sequenceOf(ChangeSignatureStateChanges(this), RenameStateChanges).first { it.isApplicable(declaration) }
  }

  private interface StateChangesInternal {

    fun isApplicable(declaration: PsiElement): Boolean
    fun signature(declaration: PsiElement, prevState: SuggestedRefactoringState?): SuggestedRefactoringSupport.Signature?
    fun parameterMarkerRanges(declaration: PsiElement): List<TextRange?>

    fun updateNewState(prevState: SuggestedRefactoringState,
                       declaration: PsiElement,
                       newState: SuggestedRefactoringState): SuggestedRefactoringState = newState

    fun guessParameterIdByMarker(markerRange: TextRange, prevState: SuggestedRefactoringState): Any? = null
  }

  private class ChangeSignatureStateChanges(private val mainStateChanges: PySuggestedRefactoringStateChanges) : StateChangesInternal {

    companion object {
      private val DISAPPEARED_RANGES =
        Key.create<Map<RangeMarker, Any>>("PySuggestedRefactoringStateChanges.ChangeSignature.DISAPPEARED_RANGES")
    }

    override fun isApplicable(declaration: PsiElement): Boolean = PySuggestedRefactoringSupport.isAvailableForChangeSignature(declaration)

    override fun signature(declaration: PsiElement, prevState: SuggestedRefactoringState?): SuggestedRefactoringSupport.Signature? {
      val signature = createSignatureData(declaration as PyFunction) ?: return null
      return if (prevState == null) signature
      else mainStateChanges.matchParametersWithPrevState(signature, declaration, prevState)
    }

    override fun parameterMarkerRanges(declaration: PsiElement): List<TextRange?> {
      return (declaration as PyFunction).parameterList.parameters.map(this::getParameterMarker)
    }

    override fun updateNewState(prevState: SuggestedRefactoringState,
                                declaration: PsiElement,
                                newState: SuggestedRefactoringState): SuggestedRefactoringState {
      val initialSignature = prevState.oldSignature
      val prevSignature = prevState.newSignature
      val newSignature = newState.newSignature

      val idsPresent = newSignature.parameters.map { it.id }.toSet()

      val disappearedRanges = (prevState.additionalData[DISAPPEARED_RANGES] ?: emptyMap())
        .filter { it.key.isValid && it.value !in idsPresent }
        .toMutableMap()

      prevSignature.parameters
        .asSequence()
        .map { it.id }
        .filter { id -> id !in idsPresent && initialSignature.parameterById(id) != null }
        .mapNotNull { id -> prevState.parameterMarkers.firstOrNull { it.parameterId == id } }
        .filter { it.rangeMarker.isValid }
        .associateByTo(disappearedRanges, { it.rangeMarker }, { it.parameterId })

      return newState.withAdditionalData(DISAPPEARED_RANGES, disappearedRanges)
    }

    override fun guessParameterIdByMarker(markerRange: TextRange, prevState: SuggestedRefactoringState): Any? {
      val disappearedRanges = prevState.additionalData[DISAPPEARED_RANGES] ?: return null
      return disappearedRanges.entries.firstOrNull { it.key.isValid && it.key.range == markerRange }?.value
    }

    private fun createSignatureData(function: PyFunction): SuggestedRefactoringSupport.Signature? {
      val name = function.name ?: return null
      val parametersData = createParametersData(function) ?: return null
      return SuggestedRefactoringSupport.Signature.create(name, PyTypingTypeProvider.ANY, parametersData, null)
    }

    private fun getParameterMarker(parameter: PyParameter): TextRange? {
      val asNamed = parameter.asNamed
      if (asNamed != null) {
        if (asNamed.isPositionalContainer) return getChildRange(parameter, PyTokenTypes.MULT)
        if (asNamed.isKeywordContainer) return getChildRange(parameter, PyTokenTypes.EXP)
      }

      return PsiTreeUtil.skipWhitespacesForward(parameter)
        ?.takeIf { it.elementType == PyTokenTypes.COMMA || it.elementType == PyTokenTypes.RPAR }
        ?.textRange
    }

    private fun createParametersData(function: PyFunction): List<SuggestedRefactoringSupport.Parameter>? {
      val context = TypeEvalContext.codeAnalysis(function.project, function.containingFile)
      return function.getParameters(context).map { createParameterData(it) ?: return null }
    }

    private fun getChildRange(element: PsiElement, type: IElementType): TextRange? = element.node.findChildByType(type)?.textRange

    private fun createParameterData(parameter: PyCallableParameter): SuggestedRefactoringSupport.Parameter? {
      val name = when (parameter.parameter) {
        is PySlashParameter -> PySlashParameter.TEXT
        is PySingleStarParameter -> PySingleStarParameter.TEXT
        else -> ParamHelper.getNameInSignature(parameter)
      }

      return SuggestedRefactoringSupport.Parameter(
        Any(),
        name,
        PyTypingTypeProvider.ANY,
        PySuggestedRefactoringSupport.ParameterData(parameter.defaultValueText)
      )
    }
  }

  private object RenameStateChanges : StateChangesInternal {

    override fun isApplicable(declaration: PsiElement): Boolean = PySuggestedRefactoringSupport.isAvailableForRename(declaration)

    override fun signature(declaration: PsiElement, prevState: SuggestedRefactoringState?): SuggestedRefactoringSupport.Signature? {
      val name = (declaration as PsiNameIdentifierOwner).name ?: return null
      return SuggestedRefactoringSupport.Signature.create(name, null, emptyList(), null)
    }

    override fun parameterMarkerRanges(declaration: PsiElement): List<TextRange?> = emptyList()
  }
}