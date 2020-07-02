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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclaration;

/**
 * Remaps only methods and fields.
 */
class SimpleRemapperVisitor extends ASTVisitor {
    private class MethodFrame {
        private final Set<IVariableBinding> params = Collections.newSetFromMap(new IdentityHashMap<>());
        public final IMethodBinding method;

        public MethodFrame(IMethodBinding method, List<? extends VariableDeclaration> parameters) {
            this.method = method;

            short nextArg = 0;
            for (VariableDeclaration parameter : parameters) {
                IVariableBinding binding = parameter.resolveBinding();
                parameterTracker.put(binding, nextArg++);
                params.add(binding);
            }
        }

        void onPop() {
            parameterTracker.keySet().removeAll(params);
        }
    }
    final RewriteContext context;
    final MappingSet mappings;
    private final InheritanceProvider inheritanceProvider;
    private final Deque<MethodFrame> methodStack = new ArrayDeque<>(8);
    final Map<IVariableBinding, Short> parameterTracker = new IdentityHashMap<>();

    SimpleRemapperVisitor(RewriteContext context, MappingSet mappings) {
        this.context = context;
        this.mappings = mappings;
        this.inheritanceProvider = MercuryInheritanceProvider.get(context.getMercury());
    }

    private void pushMethod(IMethodBinding method, List<? extends VariableDeclaration> parameters) {
        assert method != null;
        methodStack.addFirst(new MethodFrame(method, parameters));
    }

    public int getParameterIndex(IMethodBinding method, IVariableBinding parameter) {
        assert parameter.isParameter();

        return parameterTracker.getOrDefault(parameter, (short) -1);
    }

    private void popMethod(IMethodBinding method) {
        assert method != null;
        MethodFrame frame = methodStack.removeFirst();
        assert frame.method.isEqualTo(method): "";
        frame.onPop();
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
        IMethodBinding methodBinding = binding.getDeclaringMethod();
        if (methodBinding == null) {
            return;
        }

        ITypeBinding declaringClass = methodBinding.getDeclaringClass();
        assert methodBinding != null: "Parameter binding without real method owner? " + binding + " => " + methodBinding;

        ClassMapping<?, ?> classMapping = this.mappings.getClassMapping(declaringClass.getBinaryName()).orElse(null);
        if (classMapping == null) {
            return;
        }

        if (!methodBinding.isConstructor()) {
            classMapping.complete(this.inheritanceProvider, declaringClass);
        }

        MethodMapping methodMapping = classMapping.getMethodMapping(convertSignature(methodBinding)).orElse(null);
        if (methodMapping == null) {
            return;
        }

        int index = getParameterIndex(methodBinding, binding);
        assert index >= 0 && index < Arrays.stream(methodBinding.getParameterTypes()).mapToInt(parameterType -> {
            if (!parameterType.isPrimitive()) return 1;

            String parameterTypeName = parameterType.getName();
            return "long".equals(parameterTypeName) || "double".equals(parameterTypeName) ? 2 : 1;
        }).sum() + (Modifier.isStatic(methodBinding.getModifiers()) ? 0 : 1):
            "Lost count (" + index + ") of arguments in " + methodBinding.getMethodDeclaration() + " whilst counting " + binding + " in " + methodBinding;

        if (index > 0) {
            ITypeBinding[] parameterTypes = methodBinding.getParameterTypes();

            for (int i = 0, arg = index; i < arg; i++) {
                ITypeBinding parameterType = parameterTypes[i];
                if (!parameterType.isPrimitive()) continue; //Not going to be a multiple slot type

                String parameterTypeName = parameterType.getName(); //Could also use ITypeBinding#getBinaryName
                if ("long".equals(parameterTypeName) || "double".equals(parameterTypeName)) index++;
            }
        }
        //Bump the index for non-static methods for this to be (technically) index 0
        if (!Modifier.isStatic(methodBinding.getModifiers())) index++;

        MethodParameterMapping mapping;
        try {
            mapping = methodMapping.getParameterMapping(index).orElse(null);
        } catch (IndexOutOfBoundsException e) {
            mapping = null; //Clearly don't have a mapping for this parameter
        }

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
    @SuppressWarnings("unchecked") //The JDT Javadoc says this is fine
    public boolean visit(MethodDeclaration node) {
        pushMethod(node.resolveBinding(), node.parameters());
        return true;
    }

    @Override
    @SuppressWarnings("unchecked") //The JDT Javadoc says this is fine
    public boolean visit(LambdaExpression node) {
        pushMethod(node.resolveMethodBinding(), node.parameters());
        return true;
    }

    @Override
    public final boolean visit(SimpleName node) {
        IBinding binding = node.resolveBinding();
        if (binding != null) {
            visit(node, binding);
        }
        return false;
    }

    @Override
    public void endVisit(LambdaExpression node) {
        popMethod(node.resolveMethodBinding());
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        popMethod(node.resolveBinding());
    }
}
