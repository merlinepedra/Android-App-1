/*
 *
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * Copyright (C) 2022 Tobias Kaminsky
 * Copyright (C) 2022 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.elyeproj.loaderviewlibrary.LoaderImageView;
import com.nextcloud.client.account.User;
import com.nextcloud.client.preferences.AppPreferences;
import com.owncloud.android.R;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.ThumbnailsCacheManager;
import com.owncloud.android.files.services.FileDownloader;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.services.OperationsService;
import com.owncloud.android.ui.activity.ComponentsGetter;
import com.owncloud.android.ui.interfaces.OCFileListFragmentInterface;
import com.owncloud.android.utils.BitmapUtils;
import com.owncloud.android.utils.MimeTypeUtil;
import com.owncloud.android.utils.theme.ThemeColorUtils;
import com.owncloud.android.utils.theme.ThemeDrawableUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

public class OCFileListDelegate {
    private static final String TAG = OCFileListDelegate.class.getSimpleName();
    private Set<OCFile> checkedFiles = new HashSet<>();
    private OCFile highlightedItem;
    private Context context;
    private OCFileListFragmentInterface ocFileListFragmentInterface;
    private User user;
    private FileDataStorageManager storageManager;
    private boolean hideItemOptions;
    private AppPreferences preferences;
    private boolean gridView;
    private boolean multiSelect = false;
    private boolean showMetadata;
    private List<ThumbnailsCacheManager.ThumbnailGenerationTask> asyncTasks = new ArrayList<>();
    private final ComponentsGetter transferServiceGetter;
    private boolean showShareAvatar;

    public OCFileListDelegate(Context context,
                              OCFileListFragmentInterface ocFileListFragmentInterface,
                              User user,
                              FileDataStorageManager storageManager,
                              boolean hideItemOptions,
                              AppPreferences preferences,
                              boolean gridView,
                              ComponentsGetter transferServiceGetter,
                              boolean showMetadata,
                              boolean showShareAvatar) {
        this.context = context;
        this.ocFileListFragmentInterface = ocFileListFragmentInterface;
        this.user = user;
        this.storageManager = storageManager;
        this.hideItemOptions = hideItemOptions;
        this.preferences = preferences;
        this.gridView = gridView;
        this.transferServiceGetter = transferServiceGetter;
        this.showMetadata = showMetadata;
        this.showShareAvatar = showShareAvatar;
    }

    public void setHighlightedItem(OCFile highlightedItem) {
        this.highlightedItem = highlightedItem;
    }

    public boolean isCheckedFile(OCFile file) {
        return checkedFiles.contains(file);
    }

    public void addCheckedFile(OCFile file) {
        checkedFiles.add(file);
        highlightedItem = null;
    }

    public void removeCheckedFile(OCFile file) {
        checkedFiles.remove(file);
    }

    public void addToCheckedFiles(List<OCFile> files) {
        checkedFiles.addAll(files);
    }

    public Set<OCFile> getCheckedItems() {
        return checkedFiles;
    }

    public void setCheckedItem(Set<OCFile> files) {
        checkedFiles.clear();
        checkedFiles.addAll(files);
    }

    public void clearCheckedItems() {
        checkedFiles.clear();
    }

    public static void setThumbnail(OCFile file,
                                    ImageView thumbnailView,
                                    User user,
                                    FileDataStorageManager storageManager,
                                    List<ThumbnailsCacheManager.ThumbnailGenerationTask> asyncTasks,
                                    boolean gridView,
                                    Context context) {
        OCFileListDelegate.setThumbnail(file,
                                        thumbnailView,
                                        user,
                                        storageManager,
                                        asyncTasks,
                                        gridView,
                                        context,
                                        null,
                                        null);
    }

    public void bindGridViewHolder(ListGridImageViewHolder gridViewHolder, OCFile file) {
        gridViewHolder.getThumbnail().setTag(file.getFileId());
        setThumbnail(file,
                     gridViewHolder.getThumbnail(),
                     user,
                     storageManager,
                     asyncTasks,
                     gridView,
                     context,
                     gridViewHolder.getShimmerThumbnail(),
                     preferences);

        if (highlightedItem != null && file.getFileId() == highlightedItem.getFileId()) {
            gridViewHolder.getItemLayout().setBackgroundColor(context.getResources()
                                                                  .getColor(R.color.selected_item_background));
        } else if (isCheckedFile(file)) {
            gridViewHolder.getItemLayout().setBackgroundColor(context.getResources()
                                                                  .getColor(R.color.selected_item_background));
            gridViewHolder.getCheckbox().setImageDrawable(
                ThemeDrawableUtils.tintDrawable(R.drawable.ic_checkbox_marked,
                                                ThemeColorUtils.primaryColor(context)));
        } else {
            gridViewHolder.getItemLayout().setBackgroundColor(context.getResources().getColor(R.color.bg_default));
            gridViewHolder.getCheckbox().setImageResource(R.drawable.ic_checkbox_blank_outline);
        }

        gridViewHolder.getItemLayout().setOnClickListener(v -> ocFileListFragmentInterface.onItemClicked(file));

        if (!hideItemOptions) {
            gridViewHolder.getItemLayout().setLongClickable(true);
            gridViewHolder.getItemLayout().setOnLongClickListener(v ->
                                                                      ocFileListFragmentInterface.onLongItemClicked(file));
        }

        // unread comments
        if (file.getUnreadCommentsCount() > 0) {
            gridViewHolder.getUnreadComments().setVisibility(View.VISIBLE);
            gridViewHolder.getUnreadComments().setOnClickListener(view -> ocFileListFragmentInterface
                .showActivityDetailView(file));
        } else {
            gridViewHolder.getUnreadComments().setVisibility(View.GONE);
        }

        // multiSelect (Checkbox)
        if (multiSelect) {
            gridViewHolder.getCheckbox().setVisibility(View.VISIBLE);
        } else {
            gridViewHolder.getCheckbox().setVisibility(View.GONE);
        }

        // download state
        gridViewHolder.getLocalFileIndicator().setVisibility(View.INVISIBLE);   // default first

        if (showMetadata) {
            OperationsService.OperationsServiceBinder operationsServiceBinder = transferServiceGetter.getOperationsServiceBinder();
            FileDownloader.FileDownloaderBinder fileDownloaderBinder = transferServiceGetter.getFileDownloaderBinder();
            FileUploader.FileUploaderBinder fileUploaderBinder = transferServiceGetter.getFileUploaderBinder();
            if (operationsServiceBinder != null && operationsServiceBinder.isSynchronizing(user, file)) {
                //synchronizing
                gridViewHolder.getLocalFileIndicator().setImageResource(R.drawable.ic_synchronizing);
                gridViewHolder.getLocalFileIndicator().setVisibility(View.VISIBLE);

            } else if (fileDownloaderBinder != null && fileDownloaderBinder.isDownloading(user, file)) {
                // downloading
                gridViewHolder.getLocalFileIndicator().setImageResource(R.drawable.ic_synchronizing);
                gridViewHolder.getLocalFileIndicator().setVisibility(View.VISIBLE);

            } else if (fileUploaderBinder != null && fileUploaderBinder.isUploading(user, file)) {
                //uploading
                gridViewHolder.getLocalFileIndicator().setImageResource(R.drawable.ic_synchronizing);
                gridViewHolder.getLocalFileIndicator().setVisibility(View.VISIBLE);

            } else if (file.getEtagInConflict() != null) {
                // conflict
                gridViewHolder.getLocalFileIndicator().setImageResource(R.drawable.ic_synchronizing_error);
                gridViewHolder.getLocalFileIndicator().setVisibility(View.VISIBLE);

            } else if (file.isDown()) {
                gridViewHolder.getLocalFileIndicator().setImageResource(R.drawable.ic_synced);
                gridViewHolder.getLocalFileIndicator().setVisibility(View.VISIBLE);
            }

            gridViewHolder.getFavorite().setVisibility(file.isFavorite() ? View.VISIBLE : View.GONE);
        } else {
            gridViewHolder.getLocalFileIndicator().setVisibility(View.GONE);
            gridViewHolder.getFavorite().setVisibility(View.GONE);
        }

        if (gridView || hideItemOptions || (file.isFolder() && !file.canReshare())) {
            gridViewHolder.getShared().setVisibility(View.GONE);
        } else {
            showShareIcon(gridViewHolder, file);
        }
    }

    private void showShareIcon(ListGridImageViewHolder gridViewHolder, OCFile file) {
        ImageView sharedIconView = gridViewHolder.getShared();

        if (gridViewHolder instanceof OCFileListItemViewHolder || file.getUnreadCommentsCount() == 0) {
            sharedIconView.setVisibility(View.VISIBLE);

            if (file.isSharedWithSharee() || file.isSharedWithMe()) {
                if (showShareAvatar) {
                    sharedIconView.setVisibility(View.GONE);
                } else {
                    sharedIconView.setVisibility(View.VISIBLE);
                    sharedIconView.setImageResource(R.drawable.shared_via_users);
                    sharedIconView.setContentDescription(context.getString(R.string.shared_icon_shared));
                }
            } else if (file.isSharedViaLink()) {
                sharedIconView.setImageResource(R.drawable.shared_via_link);
                sharedIconView.setContentDescription(context.getString(R.string.shared_icon_shared_via_link));
            } else {
                sharedIconView.setImageResource(R.drawable.ic_unshared);
                sharedIconView.setContentDescription(context.getString(R.string.shared_icon_share));
            }
            sharedIconView.setOnClickListener(view -> ocFileListFragmentInterface.onShareIconClick(file));
        } else {
            sharedIconView.setVisibility(View.GONE);
        }
    }

    public static void setThumbnail(OCFile file,
                                    ImageView thumbnailView,
                                    User user,
                                    FileDataStorageManager storageManager,
                                    List<ThumbnailsCacheManager.ThumbnailGenerationTask> asyncTasks,
                                    boolean gridView,
                                    Context context,
                                    LoaderImageView shimmerThumbnail,
                                    AppPreferences preferences) {
        if (file.isFolder()) {
            stopShimmer(shimmerThumbnail, thumbnailView);
            thumbnailView.setImageDrawable(MimeTypeUtil
                                               .getFolderTypeIcon(file.isSharedWithMe() || file.isSharedWithSharee(),
                                                                  file.isSharedViaLink(), file.isEncrypted(),
                                                                  file.getMountType(), context));
        } else {
            if (file.getRemoteId() != null && file.isPreviewAvailable()) {
                // Thumbnail in cache?
                Bitmap thumbnail = ThumbnailsCacheManager.getBitmapFromDiskCache(
                    ThumbnailsCacheManager.PREFIX_THUMBNAIL + file.getRemoteId()
                                                                                );

                if (thumbnail != null && !file.isUpdateThumbnailNeeded()) {
                    stopShimmer(shimmerThumbnail, thumbnailView);

                    if (MimeTypeUtil.isVideo(file)) {
                        Bitmap withOverlay = ThumbnailsCacheManager.addVideoOverlay(thumbnail);
                        thumbnailView.setImageBitmap(withOverlay);
                    } else {
                        if (gridView) {
                            BitmapUtils.setRoundedBitmapForGridMode(thumbnail, thumbnailView);
                        } else {
                            BitmapUtils.setRoundedBitmap(thumbnail, thumbnailView);
                        }
                    }
                } else {
                    // generate new thumbnail
                    if (ThumbnailsCacheManager.cancelPotentialThumbnailWork(file, thumbnailView)) {
                        try {
                            final ThumbnailsCacheManager.ThumbnailGenerationTask task =
                                new ThumbnailsCacheManager.ThumbnailGenerationTask(thumbnailView,
                                                                                   storageManager,
                                                                                   user,
                                                                                   asyncTasks,
                                                                                   gridView);
                            if (thumbnail == null) {
                                Drawable drawable = MimeTypeUtil.getFileTypeIcon(file.getMimeType(),
                                                                                 file.getFileName(),
                                                                                 user,
                                                                                 context);
                                if (drawable == null) {
                                    drawable = ResourcesCompat.getDrawable(context.getResources(),
                                                                           R.drawable.file_image,
                                                                           null);
                                }
                                int px = ThumbnailsCacheManager.getThumbnailDimension();
                                thumbnail = BitmapUtils.drawableToBitmap(drawable, px, px);
                            }
                            final ThumbnailsCacheManager.AsyncThumbnailDrawable asyncDrawable =
                                new ThumbnailsCacheManager.AsyncThumbnailDrawable(context.getResources(),
                                                                                  thumbnail, task);

                            if (shimmerThumbnail != null && shimmerThumbnail.getVisibility() == View.GONE) {
                                if (gridView) {
                                    configShimmerGridImageSize(shimmerThumbnail, preferences.getGridColumns());
                                }
                                startShimmer(shimmerThumbnail, thumbnailView);
                            }

                            task.setListener(new ThumbnailsCacheManager.ThumbnailGenerationTask.Listener() {
                                @Override
                                public void onSuccess() {
                                    stopShimmer(shimmerThumbnail, thumbnailView);
                                }

                                @Override
                                public void onError() {
                                    stopShimmer(shimmerThumbnail, thumbnailView);
                                }
                            });

                            thumbnailView.setImageDrawable(asyncDrawable);
                            asyncTasks.add(task);
                            task.execute(new ThumbnailsCacheManager.ThumbnailGenerationTaskObject(file,
                                                                                                  file.getRemoteId()));
                        } catch (IllegalArgumentException e) {
                            Log_OC.d(TAG, "ThumbnailGenerationTask : " + e.getMessage());
                        }
                    }
                }

                if ("image/png".equalsIgnoreCase(file.getMimeType())) {
                    thumbnailView.setBackgroundColor(context.getResources().getColor(R.color.bg_default));
                }
            } else {
                stopShimmer(shimmerThumbnail, thumbnailView);
                thumbnailView.setImageDrawable(MimeTypeUtil.getFileTypeIcon(file.getMimeType(),
                                                                            file.getFileName(),
                                                                            user,
                                                                            context));
            }
        }
    }

    private static void startShimmer(LoaderImageView thumbnailShimmer, ImageView thumbnailView) {
        thumbnailShimmer.setImageResource(R.drawable.background);
        thumbnailShimmer.resetLoader();
        thumbnailView.setVisibility(View.GONE);
        thumbnailShimmer.setVisibility(View.VISIBLE);
    }

    private static void stopShimmer(@Nullable LoaderImageView thumbnailShimmer, ImageView thumbnailView) {
        if (thumbnailShimmer != null) {
            thumbnailShimmer.setVisibility(View.GONE);
        }

        thumbnailView.setVisibility(View.VISIBLE);
    }

    private static void configShimmerGridImageSize(LoaderImageView thumbnailShimmer, float gridColumns) {
        FrameLayout.LayoutParams targetLayoutParams = (FrameLayout.LayoutParams) thumbnailShimmer.getLayoutParams();

        try {
            final Point screenSize = getScreenSize(thumbnailShimmer.getContext());
            final int marginLeftAndRight = targetLayoutParams.leftMargin + targetLayoutParams.rightMargin;
            final int size = Math.round(screenSize.x / gridColumns - marginLeftAndRight);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            params.setMargins(targetLayoutParams.leftMargin,
                              targetLayoutParams.topMargin,
                              targetLayoutParams.rightMargin,
                              targetLayoutParams.bottomMargin);
            thumbnailShimmer.setLayoutParams(params);
        } catch (Exception exception) {
            Log_OC.e("ConfigShimmer", exception.getMessage());
        }
    }

    private static Point getScreenSize(Context context) throws Exception {
        final WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            final Point displaySize = new Point();
            windowManager.getDefaultDisplay().getSize(displaySize);
            return displaySize;
        } else {
            throw new Exception("WindowManager not found");
        }
    }

    public void cancelAllPendingTasks() {
        for (ThumbnailsCacheManager.ThumbnailGenerationTask task : asyncTasks) {
            if (task != null) {
                task.cancel(true);
                if (task.getGetMethod() != null) {
                    Log_OC.d(TAG, "cancel: abort get method directly");
                    task.getGetMethod().abort();
                }
            }
        }

        asyncTasks.clear();
    }

    public boolean isMultiSelect() {
        return multiSelect;
    }

    public void setMultiSelect(boolean bool) {
        multiSelect = bool;
    }

    public void setShowShareAvatar(boolean bool) {
        showShareAvatar = bool;
    }
}
