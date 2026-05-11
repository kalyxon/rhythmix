package com.rhythmix.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.rhythmix.app.R
import com.rhythmix.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * SAF Folder Picker
     * ─────────────────────────────────────────────────────────────────────────
     * ACTION_OPEN_DOCUMENT_TREE lets the user pick ANY folder on their device.
     * We request both READ and WRITE so we can:
     *   • READ: enumerate .mp3 files
     *   • WRITE: create/update rhythmix_data.json in that folder
     *
     * takePersistableUriPermission() (called in StorageManager) makes this
     * permission survive app reinstalls — which is the entire point of this approach.
     * ─────────────────────────────────────────────────────────────────────────
     */
    val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onFolderSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigate to the right screen
        viewModel.screen.observe(this) { screen ->
            when (screen) {
                AppScreen.Onboarding -> showFragment(OnboardingFragment())
                AppScreen.Scanning   -> showFragment(ScanningFragment())
                AppScreen.Home       -> showFragment(HomeFragment())
                AppScreen.Library    -> showFragment(LibraryFragment())
                AppScreen.Analytics  -> showFragment(AnalyticsFragment())
                AppScreen.Player     -> showFragment(PlayerFragment())
                AppScreen.Settings   -> showFragment(SettingsFragment())
            }
        }

        // Toast messages
        viewModel.toastMessage.observe(this) { msg ->
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.toastShown()
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    override fun onBackPressed() {
        val current = viewModel.screen.value
        when (current) {
            AppScreen.Player     -> viewModel.navigate(AppScreen.Home)
            AppScreen.Library    -> viewModel.navigate(AppScreen.Home)
            AppScreen.Analytics  -> viewModel.navigate(AppScreen.Home)
            AppScreen.Settings   -> viewModel.navigate(AppScreen.Home)
            AppScreen.Home       -> super.onBackPressed()
            else                 -> super.onBackPressed()
        }
    }
}
