package com.rhythmix.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.rhythmix.app.R
import com.rhythmix.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var currentScreen: AppScreen? = null
    private var currentFragment: Fragment? = null

    val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist permission immediately
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onFolderSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets -> insets }

        // Nav tab clicks
        binding.bottomNav.navHome.setOnClickListener { viewModel.navigate(AppScreen.Home) }
        binding.bottomNav.navLibrary.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.bottomNav.navAnalytics.setOnClickListener { viewModel.navigate(AppScreen.Analytics) }
        binding.bottomNav.navSettings.setOnClickListener { viewModel.navigate(AppScreen.Settings) }

        // Mini player in bottom nav — use .root because miniPlayer is an <include> binding
        binding.bottomNav.miniPlayer.root.setOnClickListener { viewModel.navigate(AppScreen.Player) }
        binding.bottomNav.miniPlayer.btnPlayPause.setOnClickListener { viewModel.playerController.togglePlay() }
        binding.bottomNav.miniPlayer.btnNext.setOnClickListener { viewModel.playerController.skipNext() }
        binding.bottomNav.miniPlayer.btnPrev.setOnClickListener { viewModel.playerController.skipPrev() }

        // Observe screen changes
        viewModel.screen.observe(this) { screen ->
            if (screen == currentScreen) return@observe
            currentScreen = screen

            when (screen) {
                AppScreen.Onboarding -> { showFragment(screen, OnboardingFragment()); showNav(false) }
                AppScreen.Scanning   -> { showFragment(screen, ScanningFragment());   showNav(false) }
                AppScreen.Player     -> { showFragment(screen, PlayerFragment());      showNav(false) }
                AppScreen.Home       -> { showFragment(screen, HomeFragment());        showNav(true); setNavActive(0) }
                AppScreen.Library    -> { showFragment(screen, LibraryFragment());     showNav(true); setNavActive(1) }
                AppScreen.Analytics  -> { showFragment(screen, AnalyticsFragment());   showNav(true); setNavActive(2) }
                AppScreen.Settings   -> { showFragment(screen, SettingsFragment());    showNav(true); setNavActive(3) }
            }
        }

        // Mini player state in bottom nav
        viewModel.playerState.observe(this) { state ->
            val song = state.currentSong
            val mini = binding.bottomNav.miniPlayer
            if (song != null) {
                mini.root.visibility = android.view.View.VISIBLE
                mini.tvTitle.text = song.title
                mini.tvArtist.text = song.artist
                mini.btnPlayPause.setImageResource(
                    if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                // Color the art thumbnail
                var hash = 0
                for (c in song.id) hash = (hash * 31 + c.code) or 0
                val colors = listOf(0xFFC8FF00, 0xFF9B6DFF, 0xFFFF4D8F, 0xFF00D4FF, 0xFFFF8C42, 0xFF1DB954)
                val color = colors[Math.abs(hash) % colors.size].toInt()
                mini.miniArtContainer.setBackgroundColor(color)
                mini.miniArtLetter.text = song.title.firstOrNull()?.uppercase() ?: "♪"
                mini.miniArtLetter.setTextColor(android.graphics.Color.BLACK)
            } else {
                mini.root.visibility = android.view.View.GONE
            }
        }

        // Toasts
        viewModel.toastMessage.observe(this) { msg ->
            if (msg != null) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); viewModel.toastShown() }
        }
    }

    private fun showFragment(screen: AppScreen, fragment: Fragment) {
        val tag = screen.javaClass.name
        val fm = supportFragmentManager
        val target = fm.findFragmentByTag(tag) ?: fragment

        fm.beginTransaction().apply {
            currentFragment?.let { hide(it) }
            if (target.isAdded) {
                show(target)
            } else {
                add(R.id.fragment_container, target, tag)
            }
        }.commit()

        currentFragment = target
    }

    private fun showNav(visible: Boolean) {
        binding.bottomNav.root.visibility =
            if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setNavActive(index: Int) {
        val accent = getColor(R.color.accent)
        val inactive = getColor(R.color.nav_inactive)
        val icons = listOf(
            binding.bottomNav.navHomeIcon to binding.bottomNav.navHomeLabel,
            binding.bottomNav.navLibraryIcon to binding.bottomNav.navLibraryLabel,
            binding.bottomNav.navAnalyticsIcon to binding.bottomNav.navAnalyticsLabel,
            binding.bottomNav.navSettingsIcon to binding.bottomNav.navSettingsLabel
        )
        icons.forEachIndexed { i, (icon, label) ->
            val color = if (i == index) accent else inactive
            icon.setColorFilter(color)
            label.setTextColor(color)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (viewModel.screen.value) {
            AppScreen.Player     -> viewModel.navigate(AppScreen.Home)
            AppScreen.Library,
            AppScreen.Analytics,
            AppScreen.Settings   -> viewModel.navigate(AppScreen.Home)
            else                 -> super.onBackPressed()
        }
    }
}
