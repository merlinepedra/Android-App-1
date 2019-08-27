/*
 * ownCloud Android client application
 *
 * @author David A. Velasco
 * @author Chris Narkiewicz
 * Copyright (C) 2016 ownCloud GmbH.
 * Copyright (C) 2019 Chris Narkiewicz <hello@ezaquarii.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.operations;

import android.accounts.Account;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.evernote.android.job.JobRequest;
import com.evernote.android.job.util.Device;
import com.google.gson.reflect.TypeToken;
import com.nextcloud.client.device.PowerManagementService;
import com.nextcloud.client.network.ConnectivityService;
import com.owncloud.android.datamodel.ArbitraryDataProvider;
import com.owncloud.android.datamodel.DecryptedFolderMetadata;
import com.owncloud.android.datamodel.EncryptedFolderMetadata;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.ThumbnailsCacheManager;
import com.owncloud.android.datamodel.UploadsStorageManager;
import com.owncloud.android.db.OCUpload;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener;
import com.owncloud.android.lib.common.network.ProgressiveDataTransfer;
import com.owncloud.android.lib.common.operations.OperationCancelledException;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.e2ee.GetMetadataRemoteOperation;
import com.owncloud.android.lib.resources.e2ee.LockFileRemoteOperation;
import com.owncloud.android.lib.resources.e2ee.StoreMetadataRemoteOperation;
import com.owncloud.android.lib.resources.e2ee.UnlockFileRemoteOperation;
import com.owncloud.android.lib.resources.e2ee.UpdateMetadataRemoteOperation;
import com.owncloud.android.lib.resources.files.ChunkedFileUploadRemoteOperation;
import com.owncloud.android.lib.resources.files.ExistenceCheckRemoteOperation;
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation;
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation;
import com.owncloud.android.lib.resources.files.model.RemoteFile;
import com.owncloud.android.operations.common.SyncOperation;
import com.owncloud.android.utils.EncryptionUtils;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.MimeType;
import com.owncloud.android.utils.MimeTypeUtil;
import com.owncloud.android.utils.UriUtils;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.lukhnos.nnio.file.Files;
import org.lukhnos.nnio.file.Paths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.RequiresApi;


/**
 * Operation performing the update in the ownCloud server
 * of a file that was modified locally.
 */
public class UploadFileOperation extends SyncOperation {

    private static final String TAG = UploadFileOperation.class.getSimpleName();

    public static final int CREATED_BY_USER = 0;
    public static final int CREATED_AS_INSTANT_PICTURE = 1;
    public static final int CREATED_AS_INSTANT_VIDEO = 2;

    /**
     * OCFile which is to be uploaded.
     */
    private OCFile mFile;

    /**
     * Original OCFile which is to be uploaded in case file had to be renamed
     * (if forceOverwrite==false and remote file already exists).
     */
    private OCFile mOldFile;
    private String mRemotePath;
    private String mFolderUnlockToken;
    private boolean mRemoteFolderToBeCreated;
    private boolean mForceOverwrite;
    private int mLocalBehaviour;
    private int mCreatedBy;
    private boolean mOnWifiOnly;
    private boolean mWhileChargingOnly;
    private boolean mIgnoringPowerSaveMode;

    private boolean mWasRenamed;
    private long mOCUploadId;
    /**
     * Local path to file which is to be uploaded (before any possible renaming or moving).
     */
    private String mOriginalStoragePath;
    private final Set<OnDatatransferProgressListener> mDataTransferListeners = new HashSet<>();
    private OnRenameListener mRenameUploadListener;

    private final AtomicBoolean mCancellationRequested = new AtomicBoolean(false);
    private final AtomicBoolean mUploadStarted = new AtomicBoolean(false);

    private Context mContext;

    private UploadFileRemoteOperation mUploadOperation;

    private RequestEntity mEntity;

    final private Account mAccount;
    final private OCUpload mUpload;
    final private UploadsStorageManager uploadsStorageManager;
    final private ConnectivityService connectivityService;
    final private PowerManagementService powerManagementService;

    private boolean encryptedAncestor;

    public static OCFile obtainNewOCFileToUpload(String remotePath, String localPath, String mimeType) {

        // MIME type
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = MimeTypeUtil.getBestMimeTypeByFilename(localPath);
        }

        OCFile newFile = new OCFile(remotePath);
        newFile.setStoragePath(localPath);
        newFile.setLastSyncDateForProperties(0);
        newFile.setLastSyncDateForData(0);

        // size
        if (!TextUtils.isEmpty(localPath)) {
            File localFile = new File(localPath);
            newFile.setFileLength(localFile.length());
            newFile.setLastSyncDateForData(localFile.lastModified());
        } // don't worry about not assigning size, the problems with localPath
        // are checked when the UploadFileOperation instance is created


        newFile.setMimeType(mimeType);

        return newFile;
    }

    public UploadFileOperation(UploadsStorageManager uploadsStorageManager,
                               ConnectivityService connectivityService,
                               PowerManagementService powerManagementService,
                               Account account,
                               OCFile file,
                               OCUpload upload,
                               boolean forceOverwrite,
                               int localBehaviour,
                               Context context,
                               boolean onWifiOnly,
                               boolean whileChargingOnly
    ) {
        if (account == null) {
            throw new IllegalArgumentException("Illegal NULL account in UploadFileOperation " + "creation");
        }
        if (upload == null) {
            throw new IllegalArgumentException("Illegal NULL file in UploadFileOperation creation");
        }
        if (TextUtils.isEmpty(upload.getLocalPath())) {
            throw new IllegalArgumentException(
                    "Illegal file in UploadFileOperation; storage path invalid: "
                            + upload.getLocalPath());
        }

        this.uploadsStorageManager = uploadsStorageManager;
        this.connectivityService = connectivityService;
        this.powerManagementService = powerManagementService;
        mAccount = account;
        mUpload = upload;
        if (file == null) {
            mFile = obtainNewOCFileToUpload(
                    upload.getRemotePath(),
                    upload.getLocalPath(),
                    upload.getMimeType()
            );
        } else {
            mFile = file;
        }
        mOnWifiOnly = onWifiOnly;
        mWhileChargingOnly = whileChargingOnly;
        mRemotePath = upload.getRemotePath();
        mForceOverwrite = forceOverwrite;
        mLocalBehaviour = localBehaviour;
        mOriginalStoragePath = mFile.getStoragePath();
        mContext = context;
        mOCUploadId = upload.getUploadId();
        mCreatedBy = upload.getCreatedBy();
        mRemoteFolderToBeCreated = upload.isCreateRemoteFolder();
        // Ignore power save mode only if user explicitly created this upload
        mIgnoringPowerSaveMode = mCreatedBy == CREATED_BY_USER;
        mFolderUnlockToken = upload.getFolderUnlockToken();
    }

    public boolean isWifiRequired() {
        return mOnWifiOnly;
    }

    public boolean isChargingRequired() {
        return mWhileChargingOnly;
    }

    public boolean isIgnoringPowerSaveMode() { return mIgnoringPowerSaveMode; }

    public Account getAccount() {
        return mAccount;
    }

    public String getFileName() {
        return (mFile != null) ? mFile.getFileName() : null;
    }

    public OCFile getFile() {
        return mFile;
    }

    /**
     * If remote file was renamed, return original OCFile which was uploaded. Is
     * null is file was not renamed.
     */
    public OCFile getOldFile() {
        return mOldFile;
    }

    public String getOriginalStoragePath() {
        return mOriginalStoragePath;
    }

    public String getStoragePath() {
        return mFile.getStoragePath();
    }

    public String getRemotePath() {
        return mFile.getRemotePath();
    }

    public String getDecryptedRemotePath() {
        return mFile.getDecryptedRemotePath();
    }

    public String getMimeType() {
        return mFile.getMimeType();
    }

    public int getLocalBehaviour() {
        return mLocalBehaviour;
    }

    public void setRemoteFolderToBeCreated() {
        mRemoteFolderToBeCreated = true;
    }

    public boolean wasRenamed() {
        return mWasRenamed;
    }

    public void setCreatedBy(int createdBy) {
        mCreatedBy = createdBy;
        if (createdBy < CREATED_BY_USER || CREATED_AS_INSTANT_VIDEO < createdBy) {
            mCreatedBy = CREATED_BY_USER;
        }
    }

    public int getCreatedBy() {
        return mCreatedBy;
    }

    public boolean isInstantPicture() {
        return mCreatedBy == CREATED_AS_INSTANT_PICTURE;
    }

    public boolean isInstantVideo() {
        return mCreatedBy == CREATED_AS_INSTANT_VIDEO;
    }

    public void setOCUploadId(long id) {
        mOCUploadId = id;
    }

    public long getOCUploadId() {
        return mOCUploadId;
    }

    public Set<OnDatatransferProgressListener> getDataTransferListeners() {
        return mDataTransferListeners;
    }

    public void addDataTransferProgressListener(OnDatatransferProgressListener listener) {
        synchronized (mDataTransferListeners) {
            mDataTransferListeners.add(listener);
        }
        if (mEntity != null) {
            ((ProgressiveDataTransfer) mEntity).addDataTransferProgressListener(listener);
        }
        if (mUploadOperation != null) {
            mUploadOperation.addDataTransferProgressListener(listener);
        }
    }

    public void removeDataTransferProgressListener(OnDatatransferProgressListener listener) {
        synchronized (mDataTransferListeners) {
            mDataTransferListeners.remove(listener);
        }
        if (mEntity != null) {
            ((ProgressiveDataTransfer) mEntity).removeDataTransferProgressListener(listener);
        }
        if (mUploadOperation != null) {
            mUploadOperation.removeDataTransferProgressListener(listener);
        }
    }

    public void addRenameUploadListener(OnRenameListener listener) {
        mRenameUploadListener = listener;
    }

    public Context getContext() {
        return mContext;
    }

    @Override
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    protected RemoteOperationResult run(OwnCloudClient client) {
        mCancellationRequested.set(false);
        mUploadStarted.set(true);

        for (OCUpload ocUpload : uploadsStorageManager.getAllStoredUploads()) {
            if (ocUpload.getUploadId() == getOCUploadId()) {
                ocUpload.setFileSize(0);
                uploadsStorageManager.updateUpload(ocUpload);
                break;
            }
        }

        String remoteParentPath = new File(getRemotePath()).getParent();
        remoteParentPath = remoteParentPath.endsWith(OCFile.PATH_SEPARATOR) ?
                remoteParentPath : remoteParentPath + OCFile.PATH_SEPARATOR;

        OCFile parent = getStorageManager().getFileByPath(remoteParentPath);

        // in case of a fresh upload with subfolder, where parent does not exist yet
        if (parent == null && (mFolderUnlockToken == null || mFolderUnlockToken.isEmpty())) {
            // try to create folder
            RemoteOperationResult result = grantFolderExistence(remoteParentPath, client);

            if (!result.isSuccess()) {
                return result;
            }

            parent = getStorageManager().getFileByPath(remoteParentPath);

            if (parent == null) {
                return new RemoteOperationResult(false, "Parent folder not found", HttpStatus.SC_NOT_FOUND);
            }
        }

        // parent file is not null anymore:
        // - it was created on fresh upload or
        // - resume of encrypted upload, then parent file exists already as unlock is only for direct parent

        mFile.setParentId(parent.getFileId());

        // try to unlock folder with stored token, e.g. when upload needs to be resumed or app crashed
        // the parent folder should exist as it is a resume of a broken upload
        if (mFolderUnlockToken != null && !mFolderUnlockToken.isEmpty()) {
            UnlockFileRemoteOperation unlockFileOperation = new UnlockFileRemoteOperation(parent.getLocalId(),
                mFolderUnlockToken);
            RemoteOperationResult unlockFileOperationResult = unlockFileOperation.execute(client);

            if (!unlockFileOperationResult.isSuccess()) {
                return unlockFileOperationResult;
            }
        }

        // check if any parent is encrypted
        encryptedAncestor = FileStorageUtils.checkEncryptionStatus(parent, getStorageManager());
        mFile.setEncrypted(encryptedAncestor);

        if (encryptedAncestor) {
            Log_OC.d(TAG, "encrypted upload");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                return encryptedUpload(client, parent);
            } else {
                Log_OC.e(TAG, "Encrypted upload on old Android API");
                return new RemoteOperationResult(ResultCode.OLD_ANDROID_API);
            }
        } else {
            Log_OC.d(TAG, "normal upload");
            return normalUpload(client);
        }
    }

    @SuppressLint("AndroidLintUseSparseArrays") // gson cannot handle sparse arrays easily, therefore use hashmap
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private RemoteOperationResult encryptedUpload(OwnCloudClient client, OCFile parentFile) {
        RemoteOperationResult result = null;
        File temporalFile = null;
        File originalFile = new File(mOriginalStoragePath);
        File expectedFile = null;
        FileLock fileLock = null;
        long size;

        boolean metadataExists = false;
        String token = null;

        ArbitraryDataProvider arbitraryDataProvider = new ArbitraryDataProvider(getContext().getContentResolver());

        String privateKey = arbitraryDataProvider.getValue(getAccount().name, EncryptionUtils.PRIVATE_KEY);
        String publicKey = arbitraryDataProvider.getValue(getAccount().name, EncryptionUtils.PUBLIC_KEY);

        try {
            // check conditions
            result = checkConditions(originalFile);

            if (result != null) {
                return result;
            }
            /***** E2E *****/

            // Lock folder
            LockFileRemoteOperation lockFileOperation = new LockFileRemoteOperation(parentFile.getLocalId());
            RemoteOperationResult lockFileOperationResult = lockFileOperation.execute(client);

            if (lockFileOperationResult.isSuccess()) {
                token = (String) lockFileOperationResult.getData().get(0);
                // immediately store it
                mUpload.setFolderUnlockToken(token);
                uploadsStorageManager.updateUpload(mUpload);
            } else if (lockFileOperationResult.getHttpCode() == HttpStatus.SC_FORBIDDEN) {
                throw new UploadException("Forbidden! Please try again later.)");
            } else {
                throw new UploadException("Unknown error!");
            }

            // Update metadata
            GetMetadataRemoteOperation getMetadataOperation = new GetMetadataRemoteOperation(parentFile.getLocalId());
            RemoteOperationResult getMetadataOperationResult = getMetadataOperation.execute(client);

            DecryptedFolderMetadata metadata;

            if (getMetadataOperationResult.isSuccess()) {
                metadataExists = true;

                // decrypt metadata
                String serializedEncryptedMetadata = (String) getMetadataOperationResult.getData().get(0);


                EncryptedFolderMetadata encryptedFolderMetadata = EncryptionUtils.deserializeJSON(
                        serializedEncryptedMetadata, new TypeToken<EncryptedFolderMetadata>() {
                        });

                metadata = EncryptionUtils.decryptFolderMetaData(encryptedFolderMetadata, privateKey);

            } else if (getMetadataOperationResult.getHttpCode() == HttpStatus.SC_NOT_FOUND) {
                // new metadata
                metadata = new DecryptedFolderMetadata();
                metadata.setMetadata(new DecryptedFolderMetadata.Metadata());
                metadata.getMetadata().setMetadataKeys(new HashMap<>());
                String metadataKey = EncryptionUtils.encodeBytesToBase64String(EncryptionUtils.generateKey());
                String encryptedMetadataKey = EncryptionUtils.encryptStringAsymmetric(metadataKey, publicKey);
                metadata.getMetadata().getMetadataKeys().put(0, encryptedMetadataKey);
            } else {
                // TODO error
                throw new UploadException("something wrong");
            }

            /**** E2E *****/

            // check name collision
            checkNameCollision(client, metadata, parentFile.isEncrypted());

            String expectedPath = FileStorageUtils.getDefaultSavePathFor(mAccount.name, mFile);
            expectedFile = new File(expectedPath);

            result = copyFile(originalFile, expectedPath);
            if (!result.isSuccess()) {
                return result;
            }

            // Get the last modification date of the file from the file system
            Long timeStampLong = originalFile.lastModified() / 1000;
            String timeStamp = timeStampLong.toString();

            /***** E2E *****/

            // Key, always generate new one
            byte[] key = EncryptionUtils.generateKey();

            // IV, always generate new one
            byte[] iv = EncryptionUtils.randomBytes(EncryptionUtils.ivLength);

            EncryptionUtils.EncryptedFile encryptedFile = EncryptionUtils.encryptFile(mFile, key, iv);

            // new random file name, check if it exists in metadata
            String encryptedFileName = UUID.randomUUID().toString().replaceAll("-", "");

            while (metadata.getFiles().get(encryptedFileName) != null) {
                encryptedFileName = UUID.randomUUID().toString().replaceAll("-", "");
            }

            mFile.setEncryptedFileName(encryptedFileName);

            File encryptedTempFile = File.createTempFile("encFile", encryptedFileName);
            FileOutputStream fileOutputStream = new FileOutputStream(encryptedTempFile);
            fileOutputStream.write(encryptedFile.encryptedBytes);
            fileOutputStream.close();

            /***** E2E *****/

            FileChannel channel = null;
            try {
                channel = new RandomAccessFile(mFile.getStoragePath(), "rw").getChannel();
                fileLock = channel.tryLock();
            } catch (FileNotFoundException e) {
                // this basically means that the file is on SD card
                // try to copy file to temporary dir if it doesn't exist
                String temporalPath = FileStorageUtils.getInternalTemporalPath(mAccount.name, mContext) +
                        mFile.getRemotePath();
                mFile.setStoragePath(temporalPath);
                temporalFile = new File(temporalPath);

                Files.deleteIfExists(Paths.get(temporalPath));
                result = copy(originalFile, temporalFile);

                if (result.isSuccess()) {
                    if (temporalFile.length() == originalFile.length()) {
                        channel = new RandomAccessFile(temporalFile.getAbsolutePath(), "rw").getChannel();
                        fileLock = channel.tryLock();
                    } else {
                        result = new RemoteOperationResult(ResultCode.LOCK_FAILED);
                    }
                }
            }

            try {
                size = channel.size();
            } catch (IOException e1) {
                size = new File(mFile.getStoragePath()).length();
            }

            for (OCUpload ocUpload : uploadsStorageManager.getAllStoredUploads()) {
                if (ocUpload.getUploadId() == getOCUploadId()) {
                    ocUpload.setFileSize(size);
                    uploadsStorageManager.updateUpload(ocUpload);
                    break;
                }
            }

            /// perform the upload
            if (size > ChunkedFileUploadRemoteOperation.CHUNK_SIZE_MOBILE) {
                boolean onWifiConnection = connectivityService.isOnlineWithWifi();

                mUploadOperation = new ChunkedFileUploadRemoteOperation(encryptedTempFile.getAbsolutePath(),
                                                                        mFile.getParentRemotePath() + encryptedFileName,
                                                                        mFile.getMimeType(), mFile.getEtagInConflict(),
                                                                        timeStamp, onWifiConnection);
            } else {
                mUploadOperation = new UploadFileRemoteOperation(encryptedTempFile.getAbsolutePath(),
                        mFile.getParentRemotePath() + encryptedFileName, mFile.getMimeType(),
                        mFile.getEtagInConflict(), timeStamp);
            }

            for (OnDatatransferProgressListener mDataTransferListener : mDataTransferListeners) {
                mUploadOperation.addDataTransferProgressListener(mDataTransferListener);
            }

            if (mCancellationRequested.get()) {
                throw new OperationCancelledException();
            }

            result = mUploadOperation.execute(client);

            /// move local temporal file or original file to its corresponding
            // location in the Nextcloud local folder
            if (!result.isSuccess() && result.getHttpCode() == HttpStatus.SC_PRECONDITION_FAILED) {
                result = new RemoteOperationResult(ResultCode.SYNC_CONFLICT);
            }

            if (result.isSuccess()) {
                // upload metadata
                DecryptedFolderMetadata.DecryptedFile decryptedFile = new DecryptedFolderMetadata.DecryptedFile();
                DecryptedFolderMetadata.Data data = new DecryptedFolderMetadata.Data();
                data.setFilename(mFile.getFileName());
                data.setMimetype(mFile.getMimeType());
                data.setKey(EncryptionUtils.encodeBytesToBase64String(key));

                decryptedFile.setEncrypted(data);
                decryptedFile.setInitializationVector(EncryptionUtils.encodeBytesToBase64String(iv));
                decryptedFile.setAuthenticationTag(encryptedFile.authenticationTag);

                metadata.getFiles().put(encryptedFileName, decryptedFile);

                EncryptedFolderMetadata encryptedFolderMetadata = EncryptionUtils.encryptFolderMetadata(metadata,
                        privateKey);
                String serializedFolderMetadata = EncryptionUtils.serializeJSON(encryptedFolderMetadata);

                // upload metadata
                RemoteOperationResult uploadMetadataOperationResult;
                if (metadataExists) {
                    // update metadata
                    UpdateMetadataRemoteOperation storeMetadataOperation = new UpdateMetadataRemoteOperation(
                        parentFile.getLocalId(), serializedFolderMetadata, token);
                    uploadMetadataOperationResult = storeMetadataOperation.execute(client);
                } else {
                    // store metadata
                    StoreMetadataRemoteOperation storeMetadataOperation = new StoreMetadataRemoteOperation(
                        parentFile.getLocalId(), serializedFolderMetadata);
                    uploadMetadataOperationResult = storeMetadataOperation.execute(client);
                }

                if (!uploadMetadataOperationResult.isSuccess()) {
                    throw new UploadException();
                }
            }
        } catch (FileNotFoundException e) {
            Log_OC.d(TAG, mFile.getStoragePath() + " not exists anymore");
            result = new RemoteOperationResult(ResultCode.LOCAL_FILE_NOT_FOUND);
        } catch (OverlappingFileLockException e) {
            Log_OC.d(TAG, "Overlapping file lock exception");
            result = new RemoteOperationResult(ResultCode.LOCK_FAILED);
        } catch (Exception e) {
            result = new RemoteOperationResult(e);
        } finally {
            mUploadStarted.set(false);

            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException e) {
                    Log_OC.e(TAG, "Failed to unlock file with path " + mFile.getStoragePath());
                }
            }

            if (temporalFile != null && !originalFile.equals(temporalFile)) {
                temporalFile.delete();
            }
            if (result == null) {
                result = new RemoteOperationResult(ResultCode.UNKNOWN_ERROR);
            }

            logResult(result, mFile.getStoragePath(), mFile.getRemotePath());
        }

        if (result.isSuccess()) {
            handleSuccessfulUpload(temporalFile, expectedFile, originalFile, client);
            RemoteOperationResult unlockFolderResult = unlockFolder(parentFile, client, token);

            if (!unlockFolderResult.isSuccess()) {
                return unlockFolderResult;
            }
        } else if (result.getCode() == ResultCode.SYNC_CONFLICT) {
            getStorageManager().saveConflict(mFile, mFile.getEtagInConflict());
        }

        // delete temporal file
        if (temporalFile != null && temporalFile.exists() && !temporalFile.delete()) {
            Log_OC.e(TAG, "Could not delete temporal file " + temporalFile.getAbsolutePath());
        }

        return result;
    }

    private RemoteOperationResult unlockFolder(OCFile parentFolder, OwnCloudClient client, String token) {
        if (token != null) {
            return new UnlockFileRemoteOperation(parentFolder.getLocalId(), token).execute(client);
        } else {
            return new RemoteOperationResult(new Exception("No token available"));
        }
    }

    private RemoteOperationResult checkConditions(File originalFile) {
        RemoteOperationResult remoteOperationResult = null;

        // check that internet is not behind walled garden
        if (Device.getNetworkType(mContext).equals(JobRequest.NetworkType.ANY) ||
                connectivityService.isInternetWalled()) {
            remoteOperationResult =  new RemoteOperationResult(ResultCode.NO_NETWORK_CONNECTION);
        }

        // check that connectivity conditions are met and delays the upload otherwise
        if (mOnWifiOnly && !Device.getNetworkType(mContext).equals(JobRequest.NetworkType.UNMETERED)) {
            Log_OC.d(TAG, "Upload delayed until WiFi is available: " + getRemotePath());
            remoteOperationResult = new RemoteOperationResult(ResultCode.DELAYED_FOR_WIFI);
        }

        // check if charging conditions are met and delays the upload otherwise
        if (mWhileChargingOnly && !Device.getBatteryStatus(mContext).isCharging()
                && Device.getBatteryStatus(mContext).getBatteryPercent() < 1) {
            Log_OC.d(TAG, "Upload delayed until the device is charging: " + getRemotePath());
            remoteOperationResult =  new RemoteOperationResult(ResultCode.DELAYED_FOR_CHARGING);
        }

        // check that device is not in power save mode
        if (!mIgnoringPowerSaveMode && powerManagementService.isPowerSavingEnabled()) {
            Log_OC.d(TAG, "Upload delayed because device is in power save mode: " + getRemotePath());
            remoteOperationResult =  new RemoteOperationResult(ResultCode.DELAYED_IN_POWER_SAVE_MODE);
        }

        // check if the file continues existing before schedule the operation
        if (!originalFile.exists()) {
            Log_OC.d(TAG, mOriginalStoragePath + " not exists anymore");
            remoteOperationResult =  new RemoteOperationResult(ResultCode.LOCAL_FILE_NOT_FOUND);
        }

        return remoteOperationResult;
    }

    private RemoteOperationResult normalUpload(OwnCloudClient client) {
        RemoteOperationResult result = null;
        File temporalFile = null;
        File originalFile = new File(mOriginalStoragePath);
        File expectedFile = null;
        FileLock fileLock = null;
        long size;

        try {
            // check conditions
            result = checkConditions(originalFile);

            if (result != null) {
                return result;
            }

            // check name collision
            checkNameCollision(client, null, false);

            String expectedPath = FileStorageUtils.getDefaultSavePathFor(mAccount.name, mFile);
            expectedFile = new File(expectedPath);

            result = copyFile(originalFile, expectedPath);
            if (!result.isSuccess()) {
                return result;
            }

            // Get the last modification date of the file from the file system
            Long timeStampLong = originalFile.lastModified() / 1000;
            String timeStamp = timeStampLong.toString();

            FileChannel channel = null;
            try {
                channel = new RandomAccessFile(mFile.getStoragePath(), "rw").getChannel();
                fileLock = channel.tryLock();
            } catch (FileNotFoundException e) {
                // this basically means that the file is on SD card
                // try to copy file to temporary dir if it doesn't exist
                String temporalPath = FileStorageUtils.getInternalTemporalPath(mAccount.name, mContext) +
                        mFile.getRemotePath();
                mFile.setStoragePath(temporalPath);
                temporalFile = new File(temporalPath);

                Files.deleteIfExists(Paths.get(temporalPath));
                result = copy(originalFile, temporalFile);

                if (result.isSuccess()) {
                    if (temporalFile.length() == originalFile.length()) {
                        channel = new RandomAccessFile(temporalFile.getAbsolutePath(), "rw").getChannel();
                        fileLock = channel.tryLock();
                    } else {
                        result = new RemoteOperationResult(ResultCode.LOCK_FAILED);
                    }
                }
            }

            try {
                size = channel.size();
            } catch (Exception e1) {
                size = new File(mFile.getStoragePath()).length();
            }

            for (OCUpload ocUpload : uploadsStorageManager.getAllStoredUploads()) {
                if (ocUpload.getUploadId() == getOCUploadId()) {
                    ocUpload.setFileSize(size);
                    uploadsStorageManager.updateUpload(ocUpload);
                    break;
                }
            }

            // perform the upload
            if (size > ChunkedFileUploadRemoteOperation.CHUNK_SIZE_MOBILE) {
                boolean onWifiConnection = connectivityService.isOnlineWithWifi();

                mUploadOperation = new ChunkedFileUploadRemoteOperation(mFile.getStoragePath(),
                                                                        mFile.getRemotePath(), mFile.getMimeType(),
                                                                        mFile.getEtagInConflict(),
                                                                        timeStamp, onWifiConnection);
            } else {
                mUploadOperation = new UploadFileRemoteOperation(mFile.getStoragePath(),
                        mFile.getRemotePath(), mFile.getMimeType(), mFile.getEtagInConflict(), timeStamp);
            }

            for (OnDatatransferProgressListener mDataTransferListener : mDataTransferListeners) {
                mUploadOperation.addDataTransferProgressListener(mDataTransferListener);
            }

            if (mCancellationRequested.get()) {
                throw new OperationCancelledException();
            }

            if (result.isSuccess() && mUploadOperation != null) {
                result = mUploadOperation.execute(client);

                /// move local temporal file or original file to its corresponding
                // location in the Nextcloud local folder
                if (!result.isSuccess() && result.getHttpCode() == HttpStatus.SC_PRECONDITION_FAILED) {
                    result = new RemoteOperationResult(ResultCode.SYNC_CONFLICT);
                }
            }
        } catch (FileNotFoundException e) {
            Log_OC.d(TAG, mOriginalStoragePath + " not exists anymore");
            result = new RemoteOperationResult(ResultCode.LOCAL_FILE_NOT_FOUND);
        } catch (OverlappingFileLockException e) {
            Log_OC.d(TAG, "Overlapping file lock exception");
            result = new RemoteOperationResult(ResultCode.LOCK_FAILED);
        } catch (Exception e) {
            result = new RemoteOperationResult(e);
        } finally {
            mUploadStarted.set(false);

            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException e) {
                    Log_OC.e(TAG, "Failed to unlock file with path " + mOriginalStoragePath);
                }
            }

            if (temporalFile != null && !originalFile.equals(temporalFile)) {
                temporalFile.delete();
            }

            if (result == null) {
                result = new RemoteOperationResult(ResultCode.UNKNOWN_ERROR);
            }

            logResult(result, mOriginalStoragePath, mRemotePath);
        }

        if (result.isSuccess()) {
            handleSuccessfulUpload(temporalFile, expectedFile, originalFile, client);
        } else if (result.getCode() == ResultCode.SYNC_CONFLICT) {
            getStorageManager().saveConflict(mFile, mFile.getEtagInConflict());
        }

        // delete temporal file
        if (temporalFile != null && temporalFile.exists() && !temporalFile.delete()) {
            Log_OC.e(TAG, "Could not delete temporal file " + temporalFile.getAbsolutePath());
        }

        return result;
    }

    private void logResult(RemoteOperationResult result, String sourcePath, String targetPath) {
        if (result.isSuccess()) {
            Log_OC.i(TAG, "Upload of " + sourcePath + " to " + targetPath + ": " + result.getLogMessage());
        } else {
            if (result.getException() != null) {
                if (result.isCancelled()) {
                    Log_OC.w(TAG, "Upload of " + sourcePath + " to " + targetPath + ": "
                        + result.getLogMessage());
                } else {
                    Log_OC.e(TAG, "Upload of " + sourcePath + " to " + targetPath + ": "
                        + result.getLogMessage(), result.getException());
                }
            } else {
                Log_OC.e(TAG, "Upload of " + sourcePath + " to " + targetPath + ": " + result.getLogMessage());
            }
        }
    }

    private RemoteOperationResult copyFile(File originalFile, String expectedPath) throws OperationCancelledException,
            IOException {
        if (mLocalBehaviour == FileUploader.LOCAL_BEHAVIOUR_COPY && !mOriginalStoragePath.equals(expectedPath)) {
            String temporalPath = FileStorageUtils.getInternalTemporalPath(mAccount.name, mContext) +
                mFile.getRemotePath();
            mFile.setStoragePath(temporalPath);
            File temporalFile = new File(temporalPath);

            return copy(originalFile, temporalFile);
        }

        if (mCancellationRequested.get()) {
            throw new OperationCancelledException();
        }

        return new RemoteOperationResult(ResultCode.OK);
    }

    private void checkNameCollision(OwnCloudClient client, DecryptedFolderMetadata metadata, boolean encrypted)
            throws OperationCancelledException {
        /// automatic rename of file to upload in case of name collision in server
        Log_OC.d(TAG, "Checking name collision in server");
        if (!mForceOverwrite) {
            String remotePath = getAvailableRemotePath(client, mRemotePath, metadata, encrypted);
            mWasRenamed = !remotePath.equals(mRemotePath);
            if (mWasRenamed) {
                createNewOCFile(remotePath);
                Log_OC.d(TAG, "File renamed as " + remotePath);
            }
            mRemotePath = remotePath;
            mRenameUploadListener.onRenameUpload();
        }

        if (mCancellationRequested.get()) {
            throw new OperationCancelledException();
        }
    }

    private void handleSuccessfulUpload(File temporalFile, File expectedFile, File originalFile,
                                        OwnCloudClient client) {
        switch (mLocalBehaviour) {
            case FileUploader.LOCAL_BEHAVIOUR_FORGET:
            default:
                mFile.setStoragePath("");
                saveUploadedFile(client);
                break;

            case FileUploader.LOCAL_BEHAVIOUR_DELETE:
                originalFile.delete();
                getStorageManager().deleteFileInMediaScan(originalFile.getAbsolutePath());
                saveUploadedFile(client);
                break;

            case FileUploader.LOCAL_BEHAVIOUR_COPY:
                if (temporalFile != null) {
                    try {
                        move(temporalFile, expectedFile);
                    } catch (IOException e) {
                        Log_OC.e(TAG, e.getMessage());
                    }
                }
                mFile.setStoragePath(expectedFile.getAbsolutePath());
                saveUploadedFile(client);
                FileDataStorageManager.triggerMediaScan(expectedFile.getAbsolutePath());
                break;

            case FileUploader.LOCAL_BEHAVIOUR_MOVE:
                String expectedPath = FileStorageUtils.getDefaultSavePathFor(mAccount.name, mFile);
                File newFile = new File(expectedPath);

                try {
                    move(originalFile, newFile);
                } catch (IOException e) {
                    Log.e(TAG, "Error moving file", e);
                }
                getStorageManager().deleteFileInMediaScan(originalFile.getAbsolutePath());
                mFile.setStoragePath(newFile.getAbsolutePath());
                saveUploadedFile(client);
                FileDataStorageManager.triggerMediaScan(newFile.getAbsolutePath());
                break;
        }
    }

    /**
     * Checks the existence of the folder where the current file will be uploaded both
     * in the remote server and in the local database.
     * <p/>
     * If the upload is set to enforce the creation of the folder, the method tries to
     * create it both remote and locally.
     *
     * @param pathToGrant Full remote path whose existence will be granted.
     * @return An {@link OCFile} instance corresponding to the folder where the file
     * will be uploaded.
     */
    private RemoteOperationResult grantFolderExistence(String pathToGrant, OwnCloudClient client) {
        RemoteOperation operation = new ExistenceCheckRemoteOperation(pathToGrant, false);
        RemoteOperationResult result = operation.execute(client);
        if (!result.isSuccess() && result.getCode() == ResultCode.FILE_NOT_FOUND && mRemoteFolderToBeCreated) {
            SyncOperation syncOp = new CreateFolderOperation(pathToGrant, true);
            result = syncOp.execute(client, getStorageManager());
        }
        if (result.isSuccess()) {
            OCFile parentDir = getStorageManager().getFileByPath(pathToGrant);
            if (parentDir == null) {
                parentDir = createLocalFolder(pathToGrant);
            }
            if (parentDir != null) {
                result = new RemoteOperationResult(ResultCode.OK);
            } else {
                result = new RemoteOperationResult(ResultCode.CANNOT_CREATE_FILE);
            }
        }
        return result;
    }

    private OCFile createLocalFolder(String remotePath) {
        String parentPath = new File(remotePath).getParent();
        parentPath = parentPath.endsWith(OCFile.PATH_SEPARATOR) ?
                parentPath : parentPath + OCFile.PATH_SEPARATOR;
        OCFile parent = getStorageManager().getFileByPath(parentPath);
        if (parent == null) {
            parent = createLocalFolder(parentPath);
        }
        if (parent != null) {
            OCFile createdFolder = new OCFile(remotePath);
            createdFolder.setMimeType(MimeType.DIRECTORY);
            createdFolder.setParentId(parent.getFileId());
            getStorageManager().saveFile(createdFolder);
            return createdFolder;
        }
        return null;
    }


    /**
     * Create a new OCFile mFile with new remote path. This is required if forceOverwrite==false.
     * New file is stored as mFile, original as mOldFile.
     *
     * @param newRemotePath new remote path
     */
    private void createNewOCFile(String newRemotePath) {
        // a new OCFile instance must be created for a new remote path
        OCFile newFile = new OCFile(newRemotePath);
        newFile.setCreationTimestamp(mFile.getCreationTimestamp());
        newFile.setFileLength(mFile.getFileLength());
        newFile.setMimeType(mFile.getMimeType());
        newFile.setModificationTimestamp(mFile.getModificationTimestamp());
        newFile.setModificationTimestampAtLastSyncForData(
                mFile.getModificationTimestampAtLastSyncForData()
        );
        newFile.setEtag(mFile.getEtag());
        newFile.setLastSyncDateForProperties(mFile.getLastSyncDateForProperties());
        newFile.setLastSyncDateForData(mFile.getLastSyncDateForData());
        newFile.setStoragePath(mFile.getStoragePath());
        newFile.setParentId(mFile.getParentId());
        mOldFile = mFile;
        mFile = newFile;
    }

    /**
     * Checks if remotePath does not exist in the server and returns it, or adds
     * a suffix to it in order to avoid the server file is overwritten.
     *
     * @param client     OwnCloud client
     * @param remotePath remote path of the file
     * @param metadata   metadata of encrypted folder
     * @return new remote path
     */
    private String getAvailableRemotePath(OwnCloudClient client, String remotePath, DecryptedFolderMetadata metadata,
                                          boolean encrypted) {
        boolean check = existsFile(client, remotePath, metadata, encrypted);
        if (!check) {
            return remotePath;
        }

        int pos = remotePath.lastIndexOf('.');
        String suffix;
        String extension = "";
        String remotePathWithoutExtension = "";
        if (pos >= 0) {
            extension = remotePath.substring(pos + 1);
            remotePathWithoutExtension = remotePath.substring(0, pos);
        }
        int count = 2;
        do {
            suffix = " (" + count + ")";
            if (pos >= 0) {
                check = existsFile(client, remotePathWithoutExtension + suffix + "." + extension, metadata, encrypted);
            } else {
                check = existsFile(client, remotePath + suffix, metadata, encrypted);
            }
            count++;
        } while (check);

        if (pos >= 0) {
            return remotePathWithoutExtension + suffix + "." + extension;
        } else {
            return remotePath + suffix;
        }
    }

    private boolean existsFile(OwnCloudClient client, String remotePath, DecryptedFolderMetadata metadata,
                               boolean encrypted) {
        if (encrypted) {
            String fileName = new File(remotePath).getName();

            for (DecryptedFolderMetadata.DecryptedFile file : metadata.getFiles().values()) {
                if (file.getEncrypted().getFilename().equalsIgnoreCase(fileName)) {
                    return true;
                }
            }

            return false;
        } else {
            ExistenceCheckRemoteOperation existsOperation = new ExistenceCheckRemoteOperation(remotePath, false);
            RemoteOperationResult result = existsOperation.execute(client);
            return result.isSuccess();
        }
    }

    /**
     * Allows to cancel the actual upload operation. If actual upload operating
     * is in progress it is cancelled, if upload preparation is being performed
     * upload will not take place.
     */
    public void cancel() {
        if (mUploadOperation == null) {
            if (mUploadStarted.get()) {
                Log_OC.d(TAG, "Cancelling upload during upload preparations.");
                mCancellationRequested.set(true);
            } else {
                Log_OC.e(TAG, "No upload in progress. This should not happen.");
            }
        } else {
            Log_OC.d(TAG, "Cancelling upload during actual upload operation.");
            mUploadOperation.cancel();
        }
    }

    /**
     * As soon as this method return true, upload can be cancel via cancel().
     */
    public boolean isUploadInProgress() {
        return mUploadStarted.get();

    }

    /**
     * TODO rewrite with homogeneous fail handling, remove dependency on {@link RemoteOperationResult},
     * TODO     use Exceptions instead
     *
     * @param sourceFile Source file to copy.
     * @param targetFile Target location to copy the file.
     * @return {@link RemoteOperationResult}
     * @throws IOException exception if file cannot be accessed
     */
    private RemoteOperationResult copy(File sourceFile, File targetFile) throws IOException {
        Log_OC.d(TAG, "Copying local file");

        if (FileStorageUtils.getUsableSpace() < sourceFile.length()) {
            return new RemoteOperationResult(ResultCode.LOCAL_STORAGE_FULL); // error when the file should be copied
        } else {
            Log_OC.d(TAG, "Creating temporal folder");
            File temporalParent = targetFile.getParentFile();

            if (!temporalParent.mkdirs() && !temporalParent.isDirectory()) {
                return new RemoteOperationResult(ResultCode.CANNOT_CREATE_FILE);
            }

            Log_OC.d(TAG, "Creating temporal file");
            if (!targetFile.createNewFile() && !targetFile.isFile()) {
                return new RemoteOperationResult(ResultCode.CANNOT_CREATE_FILE);
            }

            Log_OC.d(TAG, "Copying file contents");
            InputStream in = null;
            OutputStream out = null;

            try {
                if (!mOriginalStoragePath.equals(targetFile.getAbsolutePath())) {
                    // In case document provider schema as 'content://'
                    if (mOriginalStoragePath.startsWith(UriUtils.URI_CONTENT_SCHEME)) {
                        Uri uri = Uri.parse(mOriginalStoragePath);
                        in = mContext.getContentResolver().openInputStream(uri);
                    } else {
                        in = new FileInputStream(sourceFile);
                    }
                    out = new FileOutputStream(targetFile);
                    int nRead;
                    byte[] buf = new byte[4096];
                    while (!mCancellationRequested.get() &&
                            (nRead = in.read(buf)) > -1) {
                        out.write(buf, 0, nRead);
                    }
                    out.flush();

                } // else: weird but possible situation, nothing to copy

                if (mCancellationRequested.get()) {
                    return new RemoteOperationResult(new OperationCancelledException());
                }
            } catch (Exception e) {
                return new RemoteOperationResult(ResultCode.LOCAL_STORAGE_NOT_COPIED);
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Exception e) {
                    Log_OC.d(TAG, "Weird exception while closing input stream for " +
                            mOriginalStoragePath + " (ignoring)", e);
                }
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (Exception e) {
                    Log_OC.d(TAG, "Weird exception while closing output stream for " +
                            targetFile.getAbsolutePath() + " (ignoring)", e);
                }
            }
        }
        return new RemoteOperationResult(ResultCode.OK);
    }


    /**
     * TODO rewrite with homogeneous fail handling, remove dependency on {@link RemoteOperationResult},
     * TODO     use Exceptions instead
     * TODO refactor both this and 'copy' in a single method
     *
     * @param sourceFile Source file to move.
     * @param targetFile Target location to move the file.
     * @throws IOException exception if file cannot be read/wrote
     */
    private void move(File sourceFile, File targetFile) throws IOException {

        if (!targetFile.equals(sourceFile)) {
            File expectedFolder = targetFile.getParentFile();
            expectedFolder.mkdirs();

            if (expectedFolder.isDirectory()) {
                if (!sourceFile.renameTo(targetFile)) {
                    // try to copy and then delete
                    targetFile.createNewFile();
                    FileChannel inChannel = new FileInputStream(sourceFile).getChannel();
                    FileChannel outChannel = new FileOutputStream(targetFile).getChannel();
                    try {
                        inChannel.transferTo(0, inChannel.size(), outChannel);
                        sourceFile.delete();
                    } catch (Exception e) {
                        mFile.setStoragePath(""); // forget the local file
                        // by now, treat this as a success; the file was uploaded
                        // the best option could be show a warning message
                    } finally {
                        if (inChannel != null) {
                            inChannel.close();
                        }
                        if (outChannel != null) {
                            outChannel.close();
                        }
                    }
                }

            } else {
                mFile.setStoragePath("");
            }
        }
    }

    /**
     * Saves a OC File after a successful upload.
     * <p>
     * A PROPFIND is necessary to keep the props in the local database
     * synchronized with the server, specially the modification time and Etag
     * (where available)
     */
    private void saveUploadedFile(OwnCloudClient client) {
        OCFile file = mFile;
        if (file.fileExists()) {
            file = getStorageManager().getFileById(file.getFileId());
        }
        long syncDate = System.currentTimeMillis();
        file.setLastSyncDateForData(syncDate);

        // new PROPFIND to keep data consistent with server
        // in theory, should return the same we already have
        // TODO from the appropriate OC server version, get data from last PUT response headers, instead
        // TODO     of a new PROPFIND; the latter may fail, specially for chunked uploads
        String path;
        if (encryptedAncestor) {
            path = file.getParentRemotePath() + mFile.getEncryptedFileName();
        } else {
            path = getRemotePath();
        }

        ReadFileRemoteOperation operation = new ReadFileRemoteOperation(path);
        RemoteOperationResult result = operation.execute(client);
        if (result.isSuccess()) {
            updateOCFile(file, (RemoteFile) result.getData().get(0));
            file.setLastSyncDateForProperties(syncDate);
        } else {
            Log_OC.e(TAG, "Error reading properties of file after successful upload; this is gonna hurt...");
        }

        if (mWasRenamed) {
            OCFile oldFile = getStorageManager().getFileByPath(mOldFile.getRemotePath());
            if (oldFile != null) {
                oldFile.setStoragePath(null);
                getStorageManager().saveFile(oldFile);
                getStorageManager().saveConflict(oldFile, null);
            }
            // else: it was just an automatic renaming due to a name
            // coincidence; nothing else is needed, the storagePath is right
            // in the instance returned by mCurrentUpload.getFile()
        }
        file.setUpdateThumbnailNeeded(true);
        getStorageManager().saveFile(file);
        getStorageManager().saveConflict(file, null);

        FileDataStorageManager.triggerMediaScan(file.getStoragePath());

        // generate new Thumbnail
        final ThumbnailsCacheManager.ThumbnailGenerationTask task =
                new ThumbnailsCacheManager.ThumbnailGenerationTask(getStorageManager(), mAccount);
        task.execute(new ThumbnailsCacheManager.ThumbnailGenerationTaskObject(file, file.getRemoteId()));
    }

    private void updateOCFile(OCFile file, RemoteFile remoteFile) {
        file.setCreationTimestamp(remoteFile.getCreationTimestamp());
        file.setFileLength(remoteFile.getLength());
        file.setMimeType(remoteFile.getMimeType());
        file.setModificationTimestamp(remoteFile.getModifiedTimestamp());
        file.setModificationTimestampAtLastSyncForData(remoteFile.getModifiedTimestamp());
        file.setEtag(remoteFile.getEtag());
        file.setRemoteId(remoteFile.getRemoteId());
    }

    public interface OnRenameListener {

        void onRenameUpload();
    }

}
