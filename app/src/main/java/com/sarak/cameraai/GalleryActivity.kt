package com.sarak.cameraai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sarak.cameraai.record.Clip
import com.sarak.cameraai.record.ClipStore
import com.sarak.cameraai.util.Format

class GalleryActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = ClipAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        supportActionBar?.title = getString(R.string.gallery)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        list = findViewById(R.id.clipList)
        empty = findViewById(R.id.emptyText)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val clips = ClipStore.list(this)
        adapter.submit(clips)
        empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun play(clip: Clip) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(clip.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.Holder>() {
        private val items = mutableListOf<Clip>()

        fun submit(newItems: List<Clip>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_clip, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val clip = items[position]
            val seconds = (clip.durationMs / 1000).toInt()
            holder.title.text = "Take ${clip.takeTag}"
            holder.meta.text = buildString {
                append(Format.humanDate(clip.dateMs))
                append(" · ")
                append(Format.fileSize(clip.sizeBytes))
                if (seconds > 0) {
                    append(" · ")
                    append(Format.clock(clip.durationMs))
                }
            }
            holder.itemView.setOnClickListener { play(clip) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.clipTitle)
            val meta: TextView = view.findViewById(R.id.clipMeta)
        }
    }
}
