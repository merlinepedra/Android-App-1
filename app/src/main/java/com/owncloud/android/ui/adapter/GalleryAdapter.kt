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

package com.owncloud.android.ui.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import com.afollestad.sectionedrecyclerview.SectionedRecyclerViewAdapter
import com.afollestad.sectionedrecyclerview.SectionedViewHolder
import com.owncloud.android.databinding.GalleryHeaderBinding
import com.owncloud.android.databinding.GridItemBinding
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.GalleryItems
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.utils.DisplayUtils
import java.util.Calendar
import java.util.Date

class GalleryAdapter(val context: Context) : SectionedRecyclerViewAdapter<SectionedViewHolder>() {
    private var storageManager: FileDataStorageManager? = null
    private var files: List<GalleryItems> = mutableListOf()

    init {
        shouldShowFooters(false)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionedViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            GalleryHeaderViewHolder(
                GalleryHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        } else {
            GalleryItemViewHolder(
                GridItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun onBindViewHolder(
        holder: SectionedViewHolder?,
        section: Int,
        relativePosition: Int,
        absolutePosition: Int
    ) {
        if (holder != null) {
            val itemViewHolder = holder as GalleryItemViewHolder
            itemViewHolder.binding.Filename.text = files[section].files[relativePosition].decryptedFileName
        }
    }

    override fun getItemCount(section: Int): Int {
        return files[section].files.size
    }

    override fun getSectionCount(): Int {
        return files.size
    }

    override fun onBindHeaderViewHolder(holder: SectionedViewHolder?, section: Int, expanded: Boolean) {
        if (holder != null) {
            val headerViewHolder = holder as GalleryHeaderViewHolder
            val galleryItem = files[section]

            headerViewHolder.binding.date.text = DisplayUtils.getMonthYear(galleryItem.date, context)
        }
    }

    override fun onBindFooterViewHolder(holder: SectionedViewHolder?, section: Int) {
        TODO("Not yet implemented")
    }

    fun showAllGalleryItems(storageManager: FileDataStorageManager) {
        if (this.storageManager == null) {
            this.storageManager = storageManager
        }
        val items = storageManager.allGalleryItems

        files = items
            .groupBy { firstOfMonth(it.creationTimestamp) }
            .map { GalleryItems(it.key, it.value) }
            .sortedBy { it.date }.reversed()

        //FileStorageUtils.sortOcFolderDescDateModifiedWithoutFavoritesFirst(mFiles)

        Handler(Looper.getMainLooper()).post { notifyDataSetChanged() }
    }

    private fun firstOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.time = Date(timestamp * 1000)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMinimum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)

        return cal.timeInMillis
    }

    fun isEmpty(): Boolean {
        return files.isEmpty()
    }

    fun getItem(position: Int): OCFile {
        TODO("Not yet implemented")
    }
}
