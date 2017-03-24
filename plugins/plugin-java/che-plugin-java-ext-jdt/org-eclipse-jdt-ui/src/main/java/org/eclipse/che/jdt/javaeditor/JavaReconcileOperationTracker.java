/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.jdt.javaeditor;

import org.eclipse.che.api.core.jsonrpc.RequestHandlerConfigurator;
import org.eclipse.che.api.core.jsonrpc.RequestTransmitter;
import org.eclipse.che.ide.ext.java.shared.dto.ReconcileResult;
import org.eclipse.che.ide.ext.java.shared.dto.TextChange;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JavaModel;
import org.eclipse.jdt.internal.core.JavaModelManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * Receive a reconcile operation calls from client.
 *
 * @author Roman Nikitenko
 */
@Singleton
public class JavaReconcileOperationTracker {
    private static final String    OUTGOING_METHOD = "event:java-reconcile-state-changed";
    private static final String    INCOMING_METHOD = "track:java-editor-reconcile-operation";
    private static final JavaModel MODEL           = JavaModelManager.getJavaModelManager().getJavaModel();

    private final RequestTransmitter transmitter;
    private final JavaReconciler     javaReconciler;


    @Inject
    public JavaReconcileOperationTracker(RequestTransmitter transmitter, JavaReconciler javaReconciler) {
        this.transmitter = transmitter;
        this.javaReconciler = javaReconciler;
    }

    @Inject
    public void configureHandler(RequestHandlerConfigurator configurator) {
        configurator.newConfiguration()
                    .methodName(INCOMING_METHOD)
                    .paramsAsDto(TextChange.class)
                    .noResult()
                    .withConsumer(getReconcileOperationTrackingConsumer());
    }

    private BiConsumer<String, TextChange> getReconcileOperationTrackingConsumer() {
        return (String endpointId, TextChange change) -> {
            try {
                final IJavaProject javaProject = MODEL.getJavaProject(change.getProjectPath());
                final ReconcileResult reconcileResult = javaReconciler.reconcile(javaProject, change);
                transmitter.transmitOneToNone(endpointId, OUTGOING_METHOD, reconcileResult);
            } catch (JavaModelException e) {
                //TODO ????
                e.printStackTrace();
            }
        };
    }
}
