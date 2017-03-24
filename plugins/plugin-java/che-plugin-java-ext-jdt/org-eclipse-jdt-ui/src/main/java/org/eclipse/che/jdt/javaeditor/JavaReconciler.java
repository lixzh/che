/*******************************************************************************
 * Copyright (c) 2012-2015 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/

package org.eclipse.che.jdt.javaeditor;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.dto.server.DtoFactory;
import org.eclipse.che.ide.ext.java.shared.dto.HighlightedPosition;
import org.eclipse.che.ide.ext.java.shared.dto.Problem;
import org.eclipse.che.ide.ext.java.shared.dto.ReconcileResult;
import org.eclipse.che.ide.ext.java.shared.dto.TextChange;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.core.ClassFileWorkingCopy;
import org.eclipse.jdt.internal.ui.javaeditor.DocumentAdapter;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * @author Evgen Vidolob
 */
@Singleton
public class JavaReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(JavaReconciler.class);


    //TODO clean up when close/refresh
    private final Map<String, WorkingCopyOwner> workingCopyOwnersStorage;
    private final Map<String, ProblemRequestor> problemRequestorStorage;


    private final SemanticHighlightingReconciler semanticHighlighting;

    @Inject
    public JavaReconciler(SemanticHighlightingReconciler semanticHighlighting) {
        this.semanticHighlighting = semanticHighlighting;
        workingCopyOwnersStorage = new HashMap<>();
        problemRequestorStorage = new HashMap<>();
    }

    public ReconcileResult reconcile(IJavaProject javaProject, String fqn) throws JavaModelException {
        final IType type = getType(fqn, javaProject);
        final ProblemRequestor requestor = new ProblemRequestor();
        final WorkingCopyOwner wcOwner = new WorkingCopyOwner() {
            public IProblemRequestor getProblemRequestor(ICompilationUnit unit) {
                return requestor;
            }

            @Override
            public IBuffer createBuffer(ICompilationUnit workingCopy) {
                return new DocumentAdapter(workingCopy, (IFile)workingCopy.getResource());
            }
        };

        try {
            return reconcile(type, javaProject, wcOwner, requestor);
        } finally {
            final ICompilationUnit compilationUnit = type.getCompilationUnit().getWorkingCopy(wcOwner, null);
            if (compilationUnit != null && compilationUnit.isWorkingCopy()) {
                try {
                    compilationUnit.getBuffer().close();
                    compilationUnit.discardWorkingCopy();
                } catch (JavaModelException e) {
                    //ignore
                }
            }
        }
    }

    //TODO close buffer and discard working copy when save
    public ReconcileResult reconcile(IJavaProject javaProject, TextChange change) throws JavaModelException {
        final String fqn = change.getFQN();
        final IType type = getType(fqn, javaProject);

        final String wcOwnerID = change.getWorkingCopyOwnerID();
        checkState(!isNullOrEmpty(wcOwnerID), "Can not recognize working copy owner for " + fqn);

        final WorkingCopyOwner wcOwner = getWcOwner(wcOwnerID);
        final ProblemRequestor requestor = problemRequestorStorage.get(wcOwnerID);
        final ICompilationUnit compilationUnit = type.getCompilationUnit().getWorkingCopy(wcOwner, null);
        final int offset = change.getOffset();
        final String text = change.getText();
        final int removedCharCount = change.getRemovedCharCount();

        TextEdit textEdit = null;
        if (text != null && !text.isEmpty()) {
            textEdit = new InsertEdit(offset, text);
        } else if (removedCharCount > 0) {
            textEdit = new DeleteEdit(offset, removedCharCount);
        }

        if (textEdit != null) {
            compilationUnit.applyTextEdit(textEdit, null);
        }

        ReconcileResult reconcileResult = reconcile(type, javaProject, wcOwner, requestor);
        reconcileResult.setWorkingCopyOwnerID(wcOwnerID);
        return reconcileResult;
    }

    private ReconcileResult reconcile(IType type, IJavaProject javaProject, WorkingCopyOwner wcOwner, ProblemRequestor requestor)
            throws JavaModelException {
        List<HighlightedPosition> positions;
        try {
            requestor.reset();

            final ICompilationUnit workingCopy = type.getCompilationUnit().getWorkingCopy(wcOwner, null);
            final CompilationUnit compilationUnit = workingCopy.reconcile(AST.JLS8, true, wcOwner, null);
            positions = semanticHighlighting.reconcileSemanticHighlight(compilationUnit);

            if (workingCopy instanceof ClassFileWorkingCopy) {
                //we don't wont to show any errors from ".class" files
                requestor.reset();
            }
        } catch (JavaModelException e) {
            LOG.error("Can't reconcile class: " + type.getFullyQualifiedName() + " in project:" + javaProject.getPath().toOSString(), e);
            throw e;
        }

        ReconcileResult result = DtoFactory.getInstance().createDto(ReconcileResult.class);
        result.setProblems(convertProblems(requestor.problems));
        result.setHighlightedPositions(positions);
        return result;
    }

    private WorkingCopyOwner getWcOwner(String id) {
        if (workingCopyOwnersStorage.containsKey(id)) {
            return workingCopyOwnersStorage.get(id);
        }

        final ProblemRequestor requestor = new ProblemRequestor();
        final WorkingCopyOwner wcOwner = new WorkingCopyOwner() {
            public IProblemRequestor getProblemRequestor(ICompilationUnit unit) {
                return requestor;
            }

            @Override
            public IBuffer createBuffer(ICompilationUnit workingCopy) {
                return new DocumentAdapter(workingCopy, (IFile)workingCopy.getResource());
            }
        };

        workingCopyOwnersStorage.put(id, wcOwner);
        problemRequestorStorage.put(id, requestor);
        return wcOwner;
    }

    private List<Problem> convertProblems(List<IProblem> problems) {
        List<Problem> result = new ArrayList<>(problems.size());
        for (IProblem problem : problems) {
            result.add(convertProblem(problem));
        }
        return result;
    }

    private Problem convertProblem(IProblem problem) {
        Problem result = DtoFactory.getInstance().createDto(Problem.class);

        result.setArguments(Arrays.asList(problem.getArguments()));
        result.setID(problem.getID());
        result.setMessage(problem.getMessage());
        result.setOriginatingFileName(new String(problem.getOriginatingFileName()));
        result.setError(problem.isError());
        result.setWarning(problem.isWarning());
        result.setSourceEnd(problem.getSourceEnd());
        result.setSourceStart(problem.getSourceStart());
        result.setSourceLineNumber(problem.getSourceLineNumber());

        return result;
    }

    private IType getType(String fqn, IJavaProject javaProject) throws JavaModelException {
        checkState(!isNullOrEmpty(fqn), "Incorrect fully qualified name is specified");

        final IType type = javaProject.findType(fqn);
        checkState(type != null, "Can not find type for " + fqn);
        checkState(!type.isBinary(), "Can't reconcile binary type: " + fqn);
        return type;
    }

    private class ProblemRequestor implements IProblemRequestor {

        private List<IProblem> problems = new ArrayList<>();

        @Override
        public void acceptProblem(IProblem problem) {
            problems.add(problem);
        }

        @Override
        public void beginReporting() {

        }

        @Override
        public void endReporting() {

        }

        @Override
        public boolean isActive() {
            return true;
        }

        public void reset() {
            problems.clear();
        }
    }
}
