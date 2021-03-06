/*   ownCloud Android client application
 *   Copyright (C) 2012-2014 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.operations;

import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.resources.files.CopyFileRemoteOperation;
import com.owncloud.android.operations.common.SyncOperation;


/**
 * Operation copying an {@link OCFile} to a different folder.
 *
 * @author David A. Velasco
 */
public class CopyFileOperation extends SyncOperation {

    private final String srcPath;
    private String targetParentPath;

    /**
     * Constructor
     *
     * @param srcPath          Remote path of the {@link OCFile} to move.
     * @param targetParentPath Path to the folder where the file will be copied into.
     */
    public CopyFileOperation(String srcPath, String targetParentPath, FileDataStorageManager storageManager) {
        super(storageManager);

        this.srcPath = srcPath;
        this.targetParentPath = targetParentPath;
        if (!this.targetParentPath.endsWith(OCFile.PATH_SEPARATOR)) {
            this.targetParentPath += OCFile.PATH_SEPARATOR;
        }
    }

    /**
     * Performs the operation.
     *
     * @param client Client object to communicate with the remote ownCloud server.
     */
    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        /// 1. check copy validity
        if (targetParentPath.startsWith(srcPath)) {
            return new RemoteOperationResult(ResultCode.INVALID_COPY_INTO_DESCENDANT);
        }
        OCFile file = getStorageManager().getFileByPath(srcPath);
        if (file == null) {
            return new RemoteOperationResult(ResultCode.FILE_NOT_FOUND);
        }

        /// 2. remote copy
        String targetPath = targetParentPath + file.getFileName();
        if (file.isFolder()) {
            targetPath += OCFile.PATH_SEPARATOR;
        }
        RemoteOperationResult result = new CopyFileRemoteOperation(srcPath, targetPath, false).execute(client);

        /// 3. local copy
        if (result.isSuccess()) {
            getStorageManager().copyLocalFile(file, targetPath);
        }
        // TODO handle ResultCode.PARTIAL_COPY_DONE in client Activity, for the moment

        return result;
    }
}
