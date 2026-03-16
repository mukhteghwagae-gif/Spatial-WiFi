package com.wifi.visualizer.ui.onboarding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.wifi.visualizer.databinding.ActivityOnboardingBinding

data class OnboardingPage(val title: String, val description: String, val iconRes: Int)

/**
 * Simple 3-step onboarding shown only on first launch.
 * Uses ViewPager2 + TabLayout dots.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages = listOf(
        OnboardingPage(
            "Welcome to Wi-Fi Visualizer AR",
            "Walk around your space and see Wi-Fi signal strength as coloured 3D pillars anchored in the real world.",
            android.R.drawable.ic_menu_camera
        ),
        OnboardingPage(
            "Colour Legend",
            "🟢 Green  > -50 dBm — Excellent\n🟡 Yellow  -50 to -70 — Good\n🟠 Orange  -70 to -80 — Fair\n🔴 Red      < -80 dBm — Poor\n\nPillar height also scales with strength.",
            android.R.drawable.ic_menu_info_details
        ),
        OnboardingPage(
            "Permissions & Tips",
            "Grant Camera, Location, and Nearby Devices permissions when prompted.\n\n• Walk slowly for best accuracy.\n• Export data as CSV for further analysis.\n• Use the 2-D heatmap view to see your floor plan.",
            android.R.drawable.ic_menu_mylocation
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = OnboardingAdapter(pages)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabDots, binding.viewPager) { _, _ -> }.attach()

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.lastIndex) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener { finishOnboarding() }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.btnNext.text = if (position == pages.lastIndex) "Get Started" else "Next"
                binding.btnSkip.visibility = if (position == pages.lastIndex)
                    android.view.View.GONE else android.view.View.VISIBLE
            }
        })
    }

    private fun finishOnboarding() {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply()
        finish()
    }
}
