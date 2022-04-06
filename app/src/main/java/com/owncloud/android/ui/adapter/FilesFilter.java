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

import android.text.TextUtils;
import android.widget.Filter;

import com.owncloud.android.datamodel.OCFile;

import java.util.Locale;
import java.util.Vector;

class FilesFilter extends Filter {
    private final OCFileListAdapter ocFileListAdapter;

    public FilesFilter(OCFileListAdapter ocFileListAdapter) {
        this.ocFileListAdapter = ocFileListAdapter;
    }

    @Override
    protected FilterResults performFiltering(CharSequence constraint) {
        FilterResults results = new FilterResults();
        Vector<OCFile> filteredFiles = new Vector<>();

        if (!TextUtils.isEmpty(constraint)) {
            for (OCFile file : ocFileListAdapter.getAllFiles()) {
                if (file.getParentRemotePath().equals(ocFileListAdapter.getCurrentDirectory().getRemotePath()) &&
                    file.getFileName().toLowerCase(Locale.getDefault()).contains(
                        constraint.toString().toLowerCase(Locale.getDefault())) &&
                    !filteredFiles.contains(file)) {
                    filteredFiles.add(file);
                }
            }
        }

        results.values = filteredFiles;
        results.count = filteredFiles.size();

        return results;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void publishResults(CharSequence constraint, FilterResults results) {
        Vector<OCFile> ocFiles = (Vector<OCFile>) results.values;

        ocFileListAdapter.updateFilteredResults(ocFiles);
    }
}
