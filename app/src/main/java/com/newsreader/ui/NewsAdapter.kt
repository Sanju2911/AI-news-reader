package com.newsreader.ui

import android.graphics.Color
import android.view.*
import android.widget.*
import com.google.android.material.card.MaterialCardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.newsreader.R
import com.newsreader.data.NewsItem

class NewsAdapter(
    private val onItemClick: (Int) -> Unit
) : ListAdapter<NewsItem, NewsAdapter.NewsViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NewsViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_news, parent, false)
        return NewsViewHolder(view)
    }

    override fun onBindViewHolder(holder: NewsViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class NewsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView:     MaterialCardView = itemView as MaterialCardView
        private val tvTitle:      TextView    = itemView.findViewById(R.id.tv_title)
        private val tvSimplified: TextView    = itemView.findViewById(R.id.tv_simplified)
        private val tvSource:     TextView    = itemView.findViewById(R.id.tv_source)
        private val tvStatus:     TextView    = itemView.findViewById(R.id.tv_status)
        private val ivImage:      ImageView   = itemView.findViewById(R.id.iv_image)
        private val btnPlay:      ImageButton = itemView.findViewById(R.id.btn_play)
        private val progressBar:  ProgressBar = itemView.findViewById(R.id.progress_loading)
        private val tvIndex:      TextView    = itemView.findViewById(R.id.tv_index)

        fun bind(item: NewsItem, position: Int) {
            tvTitle.text  = item.originalTitle
            tvSource.text = item.sourceName
            tvIndex.text  = "${position + 1}"

            // Highlight currently playing card
            if (item.isCurrentlyPlaying) {
                cardView.setCardBackgroundColor(Color.parseColor("#2A1E14"))
                cardView.strokeColor = Color.parseColor("#E07A3A")
                cardView.strokeWidth = 3
            } else {
                cardView.setCardBackgroundColor(Color.parseColor("#1E1E1E"))
                cardView.strokeColor = Color.parseColor("#2A2A2A")
                cardView.strokeWidth = 1
            }

            // AI report text & status
            when {
                item.isLoading -> {
                    tvSimplified.text = "Generating audio..."
                    tvSimplified.setTextColor(0xFFE07A3A.toInt())
                    tvStatus.text = "⏳ Processing"
                    tvStatus.setTextColor(0xFFE07A3A.toInt())
                }
                item.simplifiedText.isNotEmpty() -> {
                    tvSimplified.text = item.simplifiedText
                    tvSimplified.setTextColor(0xFFAAAAAA.toInt())
                    when {
                        item.isCurrentlyPlaying && item.audioBase64 != null -> {
                            tvStatus.text = "▶ Now playing"
                            tvStatus.setTextColor(0xFFE07A3A.toInt())
                        }
                        item.audioBase64 != null -> {
                            tvStatus.text = "✓ Ready"
                            tvStatus.setTextColor(0xFF4CAF50.toInt())
                        }
                        else -> {
                            tvStatus.text = "♪ Tap to play"
                            tvStatus.setTextColor(0xFF888888.toInt())
                        }
                    }
                }
                else -> {
                    tvSimplified.text = "Tap to generate report"
                    tvSimplified.setTextColor(0xFF666666.toInt())
                    tvStatus.text = "⬡ Pending"
                    tvStatus.setTextColor(0xFF666666.toInt())
                }
            }

            progressBar.visibility = if (item.isLoading) View.VISIBLE else View.GONE
            btnPlay.visibility     = if (item.isLoading) View.GONE    else View.VISIBLE

            // Play/pause icon for currently playing item
            if (item.isCurrentlyPlaying && item.audioBase64 != null) {
                btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                btnPlay.setImageResource(android.R.drawable.ic_media_play)
            }

            // Image with rounded corners
            if (!item.imageUrl.isNullOrEmpty()) {
                ivImage.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(item.imageUrl)
                    .transform(CenterCrop(), RoundedCorners(24))
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(ivImage)
            } else {
                ivImage.visibility = View.GONE
            }

            btnPlay.setOnClickListener  { onItemClick(position) }
            itemView.setOnClickListener { onItemClick(position) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NewsItem>() {
        override fun areItemsTheSame(a: NewsItem, b: NewsItem) = a.id == b.id
        override fun areContentsTheSame(a: NewsItem, b: NewsItem) =
            a.simplifiedText == b.simplifiedText &&
            a.isLoading == b.isLoading &&
            a.audioBase64 == b.audioBase64 &&
            a.isCurrentlyPlaying == b.isCurrentlyPlaying
    }
}
