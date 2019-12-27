/*
 * Copyright (c) 2018 Minecrell (https://github.com/Minecrell)
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.cadixdev.mercury.remapper;

import static org.cadixdev.mercury.util.BombeBindings.convertSignature;

import java.lang.reflect.Modifier;

import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.MethodParameterMapping;
import org.cadixdev.mercury.RewriteContext;
import org.cadixdev.mercury.analysis.MercuryInheritanceProvider;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * Remaps only methods and fields.
 */
class SimpleRemapperVisitor extends ASTVisitor {

    final RewriteContext context;
    final MappingSet mappings;
    private final InheritanceProvider inheritanceProvider;

    SimpleRemapperVisitor(RewriteContext context, MappingSet mappings) {
        this.context = context;
        this.mappings = mappings;
        this.inheritanceProvider = MercuryInheritanceProvider.get(context.getMercury());
    }

    final void updateIdentifier(SimpleName node, String newName) {
        if (!node.getIdentifier().equals(newName)) {
            this.context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, newName, null);
        }
    }

    private void remapMethod(SimpleName node, IMethodBinding binding) {
        ITypeBinding declaringClass = binding.getDeclaringClass();

        if (declaringClass.getBinaryName() == null) {
            return;
        }

        ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());

        if (binding.isConstructor()) {
            updateIdentifier(node, classMapping.getSimpleDeobfuscatedName());
        } else {
            classMapping.complete(this.inheritanceProvider, declaringClass);

            MethodMapping mapping = classMapping.getMethodMapping(convertSignature(binding)).orElse(null);
            if (mapping == null) {
                return;
            }

            updateIdentifier(node, mapping.getDeobfuscatedName());
        }
    }

    private void remapField(SimpleName node, IVariableBinding binding) {
        if (!binding.isField()) {
            if (binding.isParameter()) remapParameter(node, binding);
            return;
        }

        ITypeBinding declaringClass = binding.getDeclaringClass();
        if (declaringClass == null || declaringClass.getBinaryName() == null) {
            return;
        }

        ClassMapping<?, ?> classMapping = this.mappings.getClassMapping(declaringClass.getBinaryName()).orElse(null);
        if (classMapping == null) {
            return;
        }

        FieldMapping mapping = classMapping.computeFieldMapping(convertSignature(binding)).orElse(null);
        if (mapping == null) {
            return;
        }

        updateIdentifier(node, mapping.getDeobfuscatedName());
    }

    private void remapParameter(SimpleName node, IVariableBinding binding) {
        ITypeBinding declaringClass = binding.getDeclaringClass();
        if (declaringClass == null || declaringClass.getBinaryName() == null) {
            return;
        }

        ClassMapping<?, ?> classMapping = this.mappings.getClassMapping(declaringClass.getBinaryName()).orElse(null);
        if (classMapping == null) {
            return;
        }

        IMethodBinding methodBinding = binding.getDeclaringMethod();
        assert methodBinding != null: "Parameter binding without real method owner? " + binding;
        if (!methodBinding.isConstructor()) {
            classMapping.complete(this.inheritanceProvider, declaringClass);
        }

        MethodMapping methodMapping = classMapping.getMethodMapping(convertSignature(methodBinding)).orElse(null);
        if (methodMapping == null) {
            return;
        }

        int index = binding.getVariableId();
        if (index > 0) {
            ITypeBinding[] parameterTypes = methodBinding.getParameterTypes();

            for (int i = 0, arg = index; i < arg; i++) {
                ITypeBinding parameterType = parameterTypes[i];
                if (parameterType.isPrimitive()) continue; //Not going to be a multiple slot type

                String parameterTypeName = parameterType.getName(); //Could also use ITypeBinding#getBinaryName
                if ("long".equals(parameterTypeName) || "double".equals(parameterTypeName)) index++;
            }
        }
        //Bump the index for non-static methods for this to be (technically) index 0
        if (!Modifier.isStatic(methodBinding.getModifiers())) index++;

        MethodParameterMapping mapping = methodMapping.getParameterMapping(index).orElse(null);
        if (mapping == null) {
            return;
        }

        updateIdentifier(node, mapping.getDeobfuscatedName());
    }

    protected void visit(SimpleName node, IBinding binding) {
        switch (binding.getKind()) {
            case IBinding.METHOD:
                remapMethod(node, ((IMethodBinding) binding).getMethodDeclaration());
                break;
            case IBinding.VARIABLE:
                remapField(node, ((IVariableBinding) binding).getVariableDeclaration());
                break;
        }
    }

    @Override
    public final boolean visit(SimpleName node) {
        IBinding binding = node.resolveBinding();
        if (binding != null) {
            visit(node, binding);
        }
        return false;
    }

}
