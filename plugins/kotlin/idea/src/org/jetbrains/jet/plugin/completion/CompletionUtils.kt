/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.completion

import com.intellij.openapi.util.Key
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.AutoCompletionPolicy
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.codeInsight.completion.CompletionService
import com.intellij.codeInsight.completion.CompletionProgressIndicator
import org.jetbrains.jet.lang.descriptors.ClassDescriptor
import org.jetbrains.jet.lang.descriptors.PackageViewDescriptor
import org.jetbrains.jet.lang.descriptors.PackageFragmentDescriptor
import org.jetbrains.jet.lang.descriptors.ClassKind
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor
import org.jetbrains.jet.plugin.util.IdeDescriptorRenderers
import com.intellij.codeInsight.completion.PrefixMatcher
import org.jetbrains.jet.lang.resolve.name.Name
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.completion.InsertionContext
import org.jetbrains.jet.plugin.completion.handlers.CastReceiverInsertHandler
import org.jetbrains.jet.lang.descriptors.ClassifierDescriptor

enum class ItemPriority {
    MULTIPLE_ARGUMENTS_ITEM
    DEFAULT
    NAMED_PARAMETER
}

val ITEM_PRIORITY_KEY = Key<ItemPriority>("ITEM_PRIORITY_KEY")

fun LookupElement.assignPriority(priority: ItemPriority): LookupElement {
    putUserData(ITEM_PRIORITY_KEY, priority)
    return this
}

fun LookupElement.suppressAutoInsertion() = AutoCompletionPolicy.NEVER_AUTOCOMPLETE.applyPolicy(this)

fun LookupElement.shouldCastReceiver(): LookupElement {
    return object: LookupElementDecorator<LookupElement>(this) {
        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            CastReceiverInsertHandler.handleInsert(context, getDelegate())
        }
    }
}

val KEEP_OLD_ARGUMENT_LIST_ON_TAB_KEY = Key<Unit>("KEEP_OLD_ARGUMENT_LIST_ON_TAB_KEY")

fun LookupElement.keepOldArgumentListOnTab(): LookupElement {
    putUserData(KEEP_OLD_ARGUMENT_LIST_ON_TAB_KEY, Unit)
    return this
}

fun rethrowWithCancelIndicator(exception: ProcessCanceledException): ProcessCanceledException {
    val indicator = CompletionService.getCompletionService().getCurrentCompletion() as CompletionProgressIndicator

    // Force cancel to avoid deadlock in CompletionThreading.delegateWeighing()
    if (!indicator.isCanceled()) {
        indicator.cancel()
    }

    return exception
}

fun qualifiedNameForSourceCode(descriptor: ClassifierDescriptor): String? {
    val name = descriptor.getName()
    if (name.isSpecial()) return null
    val nameString = IdeDescriptorRenderers.SOURCE_CODE.renderName(name)
    val qualifier = qualifierName(descriptor.getContainingDeclaration())
    return if (qualifier != null && qualifier != "") qualifier + "." + nameString else nameString
}

private fun qualifierName(descriptor: DeclarationDescriptor): String? = when (descriptor) {
    is ClassDescriptor -> if (descriptor.getKind() != ClassKind.CLASS_OBJECT) qualifiedNameForSourceCode(descriptor) else qualifierName(descriptor.getContainingDeclaration())
    is PackageViewDescriptor -> IdeDescriptorRenderers.SOURCE_CODE.renderFqName(descriptor.getFqName())
    is PackageFragmentDescriptor -> IdeDescriptorRenderers.SOURCE_CODE.renderFqName(descriptor.fqName)
    else -> null
}

fun PrefixMatcher.asNameFilter() = { (name: Name) -> !name.isSpecial() && prefixMatches(name.getIdentifier()) }

fun LookupElementPresentation.prependTailText(text: String, grayed: Boolean) {
    val tails = getTailFragments()
    clearTail()
    appendTailText(text, grayed)
    tails.forEach { appendTailText(it.text, it.isGrayed()) }
}

enum class CallableWeight {
    local // local non-extension
    thisClassMember
    baseClassMember
    thisTypeExtension
    baseTypeExtension
    global // global non-extension
    notApplicableReceiverNullable
}

val CALLABLE_WEIGHT_KEY = Key<CallableWeight>("CALLABLE_WEIGHT_KEY")
