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
package org.eclipse.che.ide.ext.java.shared.dto;

import org.eclipse.che.dto.shared.DTO;

/**
 * DTO represents the information about the text change of a file.
 *
 * @author Roman Nikitenko
 */
@DTO
public interface TextChange extends Change {

    /** Returns the ID of the working copy owner */
    String getWorkingCopyOwnerID();

    /** Sets the ID of the working copy owner */
    void setWorkingCopyOwnerID(String id);

    TextChange withWorkingCopyOwnerID(String id);

    /** Returns the path to the project that contains the modified file */
    String getProjectPath();

    /** Sets the path to the project that contains the modified file */
    void setProjectPath(String path);

    TextChange withProjectPath(String path);

    /** Returns the fully qualified name of the file that was changed */
    String getFQN();

    /** Sets the fully qualified name of the file that was changed */
    void setFQN(String fqn);

    TextChange withFQN(String fqn);

    /** Returns the number of characters removed from the file. */
    int getRemovedCharCount();

    /** Sets the number of characters removed from the file. */
    void setRemovedCharCount(int removedCharCount);

    TextChange withRemovedCharCount(int removedCharCount);

    TextChange withOffset(int offset);

    TextChange withLength(int length);

    TextChange withText(String text);
}
