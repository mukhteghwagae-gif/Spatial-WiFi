package com.wifi.visualizer.ui.onboarding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wifi.visualizer.databinding.ItemOnboardingPageBinding

class OnboardingAdapter(private val pages: List<OnboardingPage>) :
    RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

    inner class PageViewHolder(private val b: ItemOnboardingPageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(page: OnboardingPage) {
            b.tvTitle.text       = page.title
            b.tvDescription.text = page.description
            b.ivIcon.setImageResource(page.iconRes)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val b = ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(b)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) =
        holder.bind(pages[position])

    override fun getItemCount() = pages.size
}
