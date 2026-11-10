package com.sarak.cameraai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sarak.cameraai.util.Format
import java.io.File

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
        val clips = clips()
        adapter.submit(clips)
        empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun clips(): List<File> {
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "CameraAI")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun play(file: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(this, "No video player found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.Holder>() {
        private val items = mutableListOf<File>()

        fun submit(newItems: List<File>) {
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
            val file = items[position]
            val takeTag = file.name.removePrefix("CCTV_").substringBeforeLast("_")
            holder.title.text = "Take $takeTag"
            holder.meta.text = "${Format.humanDate(file.lastModified())} · ${Format.fileSize(file.length())}"
            holder.itemView.setOnClickListener { play(file) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.clipTitle)
            val meta: TextView = view.findViewById(R.id.clipMeta)
        }
    }
}
