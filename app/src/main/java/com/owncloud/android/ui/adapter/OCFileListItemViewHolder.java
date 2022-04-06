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

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.elyeproj.loaderviewlibrary.LoaderImageView;
import com.owncloud.android.databinding.ListItemBinding;
import com.owncloud.android.ui.AvatarGroupLayout;

import androidx.recyclerview.widget.RecyclerView;

class OCFileListItemViewHolder extends RecyclerView.ViewHolder implements ListItemViewHolder {
    protected ListItemBinding binding;

    OCFileListItemViewHolder(ListItemBinding binding) {
        super(binding.getRoot());
        this.binding = binding;
        this.binding.favoriteAction.getDrawable().mutate();
    }

    @Override
    public TextView getFileSize() {
        return binding.fileSize;
    }

    @Override
    public View getFileSizeSeparator() {
        return binding.fileSeparator;
    }

    @Override
    public TextView getLastModification() {
        return binding.lastMod;
    }

    @Override
    public ImageView getOverflowMenu() {
        return binding.overflowMenu;
    }

    @Override
    public AvatarGroupLayout getSharedAvatars() {
        return binding.sharedAvatars;
    }

    @Override
    public TextView getFileName() {
        return binding.Filename;
    }

    @Override
    public ImageView getThumbnail() {
        return binding.thumbnail;
    }

    @Override
    public LoaderImageView getShimmerThumbnail() {
        return binding.thumbnailShimmer;
    }

    @Override
    public ImageView getFavorite() {
        return binding.favoriteAction;
    }

    @Override
    public ImageView getLocalFileIndicator() {
        return binding.localFileIndicator;
    }

    @Override
    public ImageView getShared() {
        return binding.sharedIcon;
    }

    @Override
    public ImageView getCheckbox() {
        return binding.customCheckbox;
    }

    @Override
    public View getItemLayout() {
        return binding.ListItemLayout;
    }

    @Override
    public ImageView getUnreadComments() {
        return binding.unreadComments;
    }
}
