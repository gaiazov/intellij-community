/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.TailType;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.TailTypeDecorator;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.xml.TagNameReference;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import com.intellij.xml.XmlTagNameProvider;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author yole
 */
public class TagNameReferenceCompletionProvider extends CompletionProvider<CompletionParameters> {
  public static LookupElement[] getTagNameVariants(final @NotNull XmlTag tag, final String prefix) {
    List<LookupElement> elements = new ArrayList<LookupElement>();
    for (XmlTagNameProvider tagNameProvider : XmlTagNameProvider.EP_NAME.getExtensions()) {
      tagNameProvider.addTagNameVariants(elements, tag, prefix);
    }
    return elements.toArray(new LookupElement[elements.size()]);
  }

  @Override
  protected void addCompletions(@NotNull CompletionParameters parameters,
                                ProcessingContext context,
                                @NotNull CompletionResultSet result) {
    PsiReference reference = parameters.getPosition().getContainingFile().findReferenceAt(parameters.getOffset());
    if (reference instanceof TagNameReference) {
      TagNameReference tagNameReference = (TagNameReference)reference;
      PsiElement element = tagNameReference.getElement();
      if (element instanceof XmlTag) {
        if (!tagNameReference.isStartTagFlag()) {
          result.addElement(createClosingTagLookupElement((XmlTag)element, false, tagNameReference.getNameElement()));
        }
        else {
          XmlTag tag = (XmlTag) element;
          LookupElement[] variants = getTagNameVariants(tag, tag.getNamespacePrefix());
          result.addAllElements(Arrays.asList(variants));
        }
      }
    }
  }

  public static LookupElement createClosingTagLookupElement(XmlTag tag, boolean includePrefix, ASTNode nameElement) {
    LookupElementBuilder
      builder = LookupElementBuilder.create(includePrefix || !nameElement.getText().contains(":") ? tag.getName() : tag.getLocalName());
    return TailTypeDecorator.withTail(AutoCompletionPolicy.GIVE_CHANCE_TO_OVERWRITE.applyPolicy(builder),
                                      TailType.createSimpleTailType('>'));
  }

}
