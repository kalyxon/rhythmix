package com.rhythmix.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.rhythmix.app.R
import com.rhythmix.app.databinding.ActivityMainBinding
import com.rhythmix.app.model.Song

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var currentScreen: AppScreen? = null
    private var currentFragment: Fragment? = null
    private var activeTheme: String? = null
    private var lastMiniSongId: String? = null
    private var lastMiniPlaying: Boolean? = null
    private var pendingArtworkSongId: String? = null

    val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onFolderSelected(uri)
        }
    }

    private val artworkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val songId = pendingArtworkSongId
        pendingArtworkSongId = null
        if (uri != null && songId != null) {
            viewModel.saveSongArtwork(songId, uri)
        }
    }

    fun chooseArtwork(song: Song) {
        pendingArtworkSongId = song.id
        artworkPickerLauncher.launch(arrayOf("image/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("rhythmix_prefs", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("current_theme_id", "emerald") ?: "emerald"
        activeTheme = savedTheme
        setTheme(getThemeResId(savedTheme))

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.bottomNav.miniPlayer.miniBackgroundArtwork.setRenderEffect(
                RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP)
            )
        } else {
            binding.bottomNav.miniPlayer.miniBackgroundArtwork.alpha = 0.18f
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets -> insets }

        // Observe Theme selection
        viewModel.themeId.observe(this) { theme ->
            if (activeTheme != null && activeTheme != theme) {
                activeTheme = theme
                recreate() // Apply new theme dynamically
            }
        }

        // Nav tab clicks (5 tabs)
        binding.bottomNav.navHome.setOnClickListener { viewModel.navigate(AppScreen.Home) }
        binding.bottomNav.navLibrary.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.bottomNav.navAlbums.setOnClickListener { viewModel.navigate(AppScreen.Albums) }
        binding.bottomNav.navAnalytics.setOnClickListener { viewModel.navigate(AppScreen.Analytics) }
        binding.bottomNav.navSettings.setOnClickListener { viewModel.navigate(AppScreen.Settings) }

        // Mini player in bottom nav
        binding.bottomNav.miniPlayer.root.setOnClickListener { viewModel.navigate(AppScreen.Player) }
        binding.bottomNav.miniPlayer.miniArtwork.setOnClickListener {
            viewModel.playerState.value?.currentSong?.let(::chooseArtwork)
        }
        binding.bottomNav.miniPlayer.btnPlayPause.setOnClickListener { viewModel.playerController.togglePlay() }
        binding.bottomNav.miniPlayer.btnNext.setOnClickListener { viewModel.playerController.skipNext() }
        binding.bottomNav.miniPlayer.btnPrev.setOnClickListener { viewModel.playerController.skipPrev() }

        // Observe screen changes
        viewModel.screen.observe(this) { screen ->
            if (screen == currentScreen) return@observe
            currentScreen = screen

            when (screen) {
                AppScreen.Onboarding       -> { showFragment(screen, OnboardingFragment()); showNav(false) }
                AppScreen.Scanning         -> { showFragment(screen, ScanningFragment());   showNav(false) }
                AppScreen.Player           -> { showFragment(screen, PlayerFragment());      showNav(false) }
                is AppScreen.AlbumDetail  -> { showFragment(screen, AlbumDetailFragment.newInstance(screen.albumId)); showNav(false) }
                is AppScreen.SongDetail   -> { showFragment(screen, SongDetailFragment.newInstance(screen.songId)); showNav(false) }
                AppScreen.Home             -> { showFragment(screen, HomeFragment());        showNav(true); setNavActive(0) }
                AppScreen.Library          -> { showFragment(screen, LibraryFragment());     showNav(true); setNavActive(1) }
                AppScreen.Albums           -> { showFragment(screen, AlbumsFragment());      showNav(true); setNavActive(2) }
                AppScreen.Analytics        -> { showFragment(screen, AnalyticsFragment());   showNav(true); setNavActive(3) }
                AppScreen.Settings         -> { showFragment(screen, SettingsFragment());    showNav(true); setNavActive(4) }
            }
        }

        // Mini player state in bottom nav
        viewModel.playerState.observe(this) { state ->
            val song = state.currentSong
            val mini = binding.bottomNav.miniPlayer
            if (song != null) {
                if (mini.root.visibility != View.VISIBLE) {
                    mini.root.visibility = View.VISIBLE
                    if (!UiMotion.isReduced(this)) {
                        mini.root.alpha = 0f
                        mini.root.translationY = 8f
                        mini.root.animate().alpha(1f).translationY(0f).setDuration(220).start()
                    }
                }
                if (song.id != lastMiniSongId) {
                    lastMiniSongId = song.id
                    mini.tvTitle.text = song.title
                    mini.tvArtist.text = song.artist
                    ArtworkLoader.load(song, mini.miniArtwork)
                    ArtworkLoader.load(song, mini.miniBackgroundArtwork)
                }
                if (lastMiniPlaying != state.isPlaying) {
                    lastMiniPlaying = state.isPlaying
                    mini.btnPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    mini.miniEqualizer.isPlaying = state.isPlaying
                }
                val progress = if (state.durationMs > 0) {
                    (state.positionMs * 1000 / state.durationMs).toInt().coerceIn(0, 1000)
                } else 0
                mini.miniProgress.setProgress(progress, !UiMotion.isReduced(this))
                mini.btnPlayPause.contentDescription =
                    if (state.isPlaying) "Pause ${song.title}" else "Play ${song.title}"
            } else {
                mini.root.visibility = View.GONE
                lastMiniSongId = null
                lastMiniPlaying = null
            }
        }

        viewModel.artworkUpdated.observe(this) { songId ->
            if (songId != null && songId == viewModel.playerState.value?.currentSong?.id) {
                lastMiniSongId = null
                viewModel.playerState.value?.let { state ->
                    val song = state.currentSong ?: return@let
                    val mini = binding.bottomNav.miniPlayer
                    ArtworkLoader.load(song, mini.miniArtwork)
                    ArtworkLoader.load(song, mini.miniBackgroundArtwork)
                    lastMiniSongId = song.id
                }
            }
        }

        // Toasts
        viewModel.toastMessage.observe(this) { msg ->
            if (msg != null) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); viewModel.toastShown() }
        }
    }

    private fun getThemeResId(themeId: String): Int {
        return when (themeId) {
            "cyber"  -> R.style.Theme_Rhythmix_Cyberpunk
            "sunset" -> R.style.Theme_Rhythmix_Sunset
            "cyan"   -> R.style.Theme_Rhythmix_Cyan
            "amoled" -> R.style.Theme_Rhythmix_Amoled
            else     -> R.style.Theme_Rhythmix
        }
    }

    private fun showFragment(screen: AppScreen, fragment: Fragment) {
        val tag = when (screen) {
            is AppScreen.AlbumDetail -> "${screen.javaClass.name}:${screen.albumId}"
            is AppScreen.SongDetail -> "${screen.javaClass.name}:${screen.songId}"
            else -> screen.javaClass.name
        }
        val fm = supportFragmentManager
        val target = fm.findFragmentByTag(tag) ?: fragment

        fm.beginTransaction().apply {
            setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
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
        val nav = binding.bottomNav.root
        if (visible) {
            if (nav.visibility == View.VISIBLE) return
            nav.animate().cancel()
            nav.visibility = View.VISIBLE
            if (!UiMotion.isReduced(this)) {
                nav.alpha = 0f
                nav.animate().alpha(1f).setDuration(220).start()
            }
        } else if (nav.visibility == View.VISIBLE) {
            nav.animate().cancel()
            if (UiMotion.isReduced(this)) {
                nav.visibility = View.GONE
            } else {
                nav.animate().alpha(0f).setDuration(150).withEndAction {
                    nav.visibility = View.GONE
                    nav.alpha = 1f
                }.start()
            }
        }
    }

    private fun setNavActive(index: Int) {
        val typedValue = TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        val accent = typedValue.data
        val inactive = getColor(R.color.nav_inactive)

        val icons = listOf(
            binding.bottomNav.navHomeIcon to binding.bottomNav.navHomeIndicator,
            binding.bottomNav.navLibraryIcon to binding.bottomNav.navLibraryIndicator,
            binding.bottomNav.navAlbumsIcon to binding.bottomNav.navAlbumsIndicator,
            binding.bottomNav.navAnalyticsIcon to binding.bottomNav.navAnalyticsIndicator,
            binding.bottomNav.navSettingsIcon to binding.bottomNav.navSettingsIndicator
        )
        icons.forEachIndexed { i, (icon, indicator) ->
            val color = if (i == index) accent else inactive
            icon.setColorFilter(color)
            indicator.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
            if (!UiMotion.isReduced(this)) {
                val scale = if (i == index) 1.04f else 1f
                icon.animate().scaleX(scale).scaleY(scale).setDuration(160).start()
                if (i == index) {
                    indicator.scaleX = 0.4f
                    indicator.animate().scaleX(1f).setDuration(180).start()
                }
            } else {
                icon.scaleX = 1f
                icon.scaleY = 1f
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (viewModel.screen.value) {
            AppScreen.Player,
            is AppScreen.AlbumDetail -> viewModel.navigate(AppScreen.Albums)
            is AppScreen.SongDetail -> viewModel.navigate(AppScreen.Library)
            AppScreen.Library,
            AppScreen.Albums,
            AppScreen.Analytics,
            AppScreen.Settings       -> viewModel.navigate(AppScreen.Home)
            else                     -> super.onBackPressed()
        }
    }
}
