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
package org.eclipse.che.ide.api.editor.reconciler;

import com.google.gwt.json.client.JSONObject;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.ide.api.editor.EditorInput;
import org.eclipse.che.ide.api.editor.document.Document;
import org.eclipse.che.ide.api.editor.document.DocumentHandle;
import org.eclipse.che.ide.api.editor.events.DocumentChangeEvent;
import org.eclipse.che.ide.api.editor.partition.DocumentPartitioner;
import org.eclipse.che.ide.api.editor.text.Region;
import org.eclipse.che.ide.api.editor.text.RegionImpl;
import org.eclipse.che.ide.api.editor.text.TypedRegion;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.event.EditorSettingsChangedEvent;
import org.eclipse.che.ide.util.loging.Log;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link Reconciler}.
 * Also this implementation provide autosave function.
 * Autosave will performed before 'reconcile'.
 */
/*
   Maybe this class not proper place for autosave function, but for this issue: https://jira.codenvycorp.com/browse/IDEX-2099
   we need to save file content before 'reconcile'.
 */
public class ReconcilerWithAutoSave implements Reconciler {

    private static final int DELAY = 2000;


    private final Map<String, ReconcilingStrategy> strategies;

    private final String partition;

    private final DocumentPartitioner partitioner;

    private DirtyRegionQueue dirtyRegionQueue;
    private DocumentHandle   documentHandle;
    private TextEditor       editor;
    private       boolean autoSaveEnabled = true;
    private final Timer   autoSaveTimer   = new Timer() {

        @Override
        public void run() {
            Log.error(getClass(), "before save " + ReconcilerWithAutoSave.this.hashCode());
            save();
        }
    };

    @AssistedInject
    public ReconcilerWithAutoSave(@Assisted final String partition,
                                  @Assisted final DocumentPartitioner partitioner) {
//        Log.error(getClass(), "++++++++++++++++++++++++++++++ ReconcilerWithAutoSave constructor "+ this.hashCode());
        this.partition = partition;
        strategies = new HashMap<>();
        this.partitioner = partitioner;
    }

    private void reconcilerDocumentChanged() {
        for (String key : strategies.keySet()) {
            ReconcilingStrategy reconcilingStrategy = strategies.get(key);
            reconcilingStrategy.setDocument(documentHandle.getDocument());
        }

        autoSaveTimer.cancel();
        autoSaveTimer.schedule(DELAY);
    }

    @Override
    public void install(TextEditor editor) {
        this.editor = editor;
        this.dirtyRegionQueue = new DirtyRegionQueue();

        reconcilerDocumentChanged();
    }

    @Override
    public void uninstall() {
        autoSaveTimer.cancel();
        for (ReconcilingStrategy strategy : strategies.values()) {
            strategy.closeReconciler();
        }
    }

    private void save() {
        Log.error(getClass(), "save " + autoSaveEnabled);
        if (autoSaveEnabled) {
            Log.error(getClass(), "save editor.isDirty() " + editor.isDirty());
            if (editor.isDirty()) {
                editor.doSave(new AsyncCallback<EditorInput>() {
                    @Override
                    public void onFailure(Throwable throwable) {
                        Log.error(ReconcilerWithAutoSave.class, throwable);
                    }

                    @Override
                    public void onSuccess(EditorInput editorInput) {
                        Log.error(getClass(), "Success");
                        processNextRegion();

                    }
                });
                return;
            }
        }
        processNextRegion();
    }

    private void processNextRegion() {
        //TODO bug when open java file with errors - no highlighting errors

        Log.error(getClass(), "dirtyRegionQueue.getSize " + dirtyRegionQueue.getSize());
        while (dirtyRegionQueue.getSize() > 0) {
            final DirtyRegion region = dirtyRegionQueue.removeNextDirtyRegion();
            process(region);
        }

    }

    /**
     * Processes a dirty region. If the dirty region is <code>null</code> the whole document is consider being dirty. The dirty region is
     * partitioned by the document and each partition is handed over to a reconciling strategy registered for the partition's content type.
     *
     * @param dirtyRegion the dirty region to be processed
     */
    protected void process(final DirtyRegion dirtyRegion) {

        Region region = dirtyRegion;

//        Log.error(getClass(), "/////document length " + getDocument().getContents().length());
        if (region == null) {
//            Log.error(getClass(), "*********** region == null");
            region = new RegionImpl(0, getDocument().getContents().length());
        }

        final List<TypedRegion> regions = computePartitioning(region.getOffset(),
                                                              region.getLength());

        for (final TypedRegion r : regions) {
            final ReconcilingStrategy strategy = getReconcilingStrategy(r.getType());
            if (strategy == null) {
                continue;
            }

            if (dirtyRegion != null) {
                strategy.reconcile(dirtyRegion, r);
            } else {
                strategy.reconcile(r);
            }
        }
    }

    /**
     * Computes and returns the partitioning for the given region of the input document of the reconciler's connected text viewer.
     *
     * @param offset the region offset
     * @param length the region length
     * @return the computed partitioning
     */
    private List<TypedRegion> computePartitioning(final int offset, final int length) {
        return partitioner.computePartitioning(offset, length);
    }

    /**
     * Returns the input document of the text view this reconciler is installed on.
     *
     * @return the reconciler document
     */
    protected Document getDocument() {
        return documentHandle.getDocument();
    }

    /**
     * Creates a dirty region for a document event and adds it to the queue.
     *
     * @param event the document event for which to create a dirty region
     */
    private void createDirtyRegion(final DocumentChangeEvent event) {
//        Log.error(getClass(), "-------------------- type offset " + event.getOffset());
//        Log.error(getClass(), "-------------------- type text " + event.getText());
//        Log.error(getClass(), "-------------------- type length " + event.getLength());
//        Log.error(getClass(), "-------------------- type RemoveCharCount " + event.getRemoveCharCount());
        if (event.getRemoveCharCount() == 0 && event.getText() != null && !event.getText().isEmpty()) {
//            Log.error(getClass(), "**** INSERT ");
            // Insert
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getLength(),
                                                            DirtyRegion.INSERT,
                                                            event.getText()));

        } else if (event.getText() == null || event.getText().isEmpty()) {
//            Log.error(getClass(), "**** REMOVE ");
            // Remove
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getRemoveCharCount(),
                                                            DirtyRegion.REMOVE,
                                                            null));

        } else {
//            Log.error(getClass(), "**** REMOVE + INSERT ");
            // Replace (Remove + Insert)
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getRemoveCharCount(),
                                                            DirtyRegion.REMOVE,
                                                            null));
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getLength(),
                                                            DirtyRegion.INSERT,
                                                            event.getText()));
        }
    }

    @Override
    public ReconcilingStrategy getReconcilingStrategy(final String contentType) {
        return strategies.get(contentType);
    }

    @Override
    public String getDocumentPartitioning() {
        return partition;
    }

    @Override
    public void addReconcilingStrategy(final String contentType, final ReconcilingStrategy strategy) {
        strategies.put(contentType, strategy);
    }

    @Override
    public void onDocumentChange(final DocumentChangeEvent event) {
        Log.error(getClass(), "onDocumentChange ");
        if (documentHandle == null || !documentHandle.isSameAs(event.getDocument())) {
            Log.error(getClass(), "onDocumentChange RETURN ");
            return;
        }
        createDirtyRegion(event);
        autoSaveTimer.cancel();
        autoSaveTimer.schedule(DELAY);
    }

    @Override
    public DocumentHandle getDocumentHandle() {
        return this.documentHandle;
    }

    @Override
    public void setDocumentHandle(final DocumentHandle handle) {
        this.documentHandle = handle;
    }

    public boolean isAutoSaveEnabled() {
        return autoSaveEnabled;
    }

    public void disableAutoSave() {
        autoSaveEnabled = false;
        Log.error(getClass(), "/// disableAutoSave " + autoSaveEnabled);
        autoSaveTimer.cancel();
    }

    public void enableAutoSave() {
        autoSaveEnabled = true;
        Log.error(getClass(), "/// enableAutoSave " + autoSaveEnabled);
        autoSaveTimer.schedule(DELAY);
    }
}
