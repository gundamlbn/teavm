/*
 *  Copyright 2019 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.c.analyze;

import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.MethodDependency;
import org.teavm.interop.Import;
import org.teavm.interop.Structure;
import org.teavm.model.AnnotationReader;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReader;

public class InteropDependencyListener extends AbstractDependencyListener {
    @Override
    public void classReached(DependencyAgent agent, String className) {
        if (agent.getClassHierarchy().isSuperType(Structure.class.getName(), className, false)) {
            ClassReader cls = agent.getClassSource().get(className);
            if (cls != null) {
                for (FieldReader field : cls.getFields()) {
                    if (!field.hasModifier(ElementModifier.STATIC)) {
                        agent.linkField(field.getReference());
                    }
                }
            }
        }
    }

    @Override
    public void methodReached(DependencyAgent agent, MethodDependency method) {
        if (method.isMissing() || !method.getMethod().hasModifier(ElementModifier.NATIVE)) {
            return;
        }

        AnnotationReader importAnnot = method.getMethod().getAnnotations().get(Import.class.getName());
        if (importAnnot == null) {
            return;
        }

        if (method.getReference().getReturnType().isObject("java.lang.String")) {
            method.getResult().propagate(agent.getType("java.lang.String"));
        }
    }
}
