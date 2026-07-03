package com.afif.flix

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

data class Bookmark(val url: String, val title: String)

class BookmarkBottomSheet : BottomSheetDialogFragment() {

    interface BookmarkClickListener {
        fun onBookmarkClicked(url: String)
        fun onBookmarkDeleted(url: String)
    }

    private var listener: BookmarkClickListener? = null
    private lateinit var rvBookmarks: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvBookmarkCount: TextView
    private lateinit var btnClearBookmarks: View
    private lateinit var adapter: BookmarksAdapter
    private val bookmarkList = mutableListOf<Bookmark>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is BookmarkClickListener) {
            listener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_bookmarks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvBookmarks = view.findViewById(R.id.rv_bookmarks)
        tvEmpty = view.findViewById(R.id.tv_empty)
        tvBookmarkCount = view.findViewById(R.id.tv_bookmark_count)
        btnClearBookmarks = view.findViewById(R.id.btn_clear_bookmarks)

        rvBookmarks.layoutManager = LinearLayoutManager(context)
        adapter = BookmarksAdapter()
        rvBookmarks.adapter = adapter
        btnClearBookmarks.setOnClickListener {
            confirmClearBookmarks()
        }

        loadBookmarks()
    }

    private fun loadBookmarks() {
        val sharedPrefs = requireContext().getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
        bookmarkList.clear()
        
        val allEntries = sharedPrefs.all
        for ((url, title) in allEntries) {
            if (title is String) {
                bookmarkList.add(Bookmark(url, title))
            }
        }

        tvBookmarkCount.text = getString(R.string.bookmarks_count, bookmarkList.size)
        btnClearBookmarks.visibility = if (bookmarkList.isEmpty()) View.GONE else View.VISIBLE

        if (bookmarkList.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvBookmarks.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvBookmarks.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun confirmClearBookmarks() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_bookmarks_title)
            .setMessage(R.string.clear_bookmarks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                val sharedPrefs = requireContext().getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
                sharedPrefs.edit().clear().apply()
                listener?.onBookmarkDeleted("")
                loadBookmarks()
            }
            .show()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    inner class BookmarksAdapter : RecyclerView.Adapter<BookmarksAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_bookmark_title)
            val tvUrl: TextView = view.findViewById(R.id.tv_bookmark_url)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val bookmark = bookmarkList[position]
            holder.tvTitle.text = bookmark.title
            holder.tvUrl.text = bookmark.url

            holder.itemView.setOnClickListener {
                listener?.onBookmarkClicked(bookmark.url)
                dismiss()
            }

            holder.btnDelete.setOnClickListener {
                val sharedPrefs = requireContext().getSharedPreferences("AfifFlixBookmarks", Context.MODE_PRIVATE)
                sharedPrefs.edit().remove(bookmark.url).apply()
                listener?.onBookmarkDeleted(bookmark.url)
                loadBookmarks()
            }
        }

        override fun getItemCount(): Int = bookmarkList.size
    }
}
