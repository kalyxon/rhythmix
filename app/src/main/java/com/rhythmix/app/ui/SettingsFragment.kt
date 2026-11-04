package com.rhythmix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.rhythmix.app.R
import com.rhythmix.app.databinding.FragmentSettingsBinding
import com.rhythmix.app.model.Song

class SettingsFragment : Fragment() {

    private data class ThemeOption(val id: String, val colorRes: Int)

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var updatingThemeSwitches = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)
        val main = requireActivity() as MainActivity

        binding.tvFolderPath.text = viewModel.storage.getSavedFolderName()
        binding.tvFolderUri.text = viewModel.storage.getSavedFolderUri()?.toString() ?: "—"
        binding.tvDataFile.text = "rhythmix_data.json"
        binding.tvDataNote.text =
            "Analytics and custom albums are saved directly inside your music folder. " +
            "Uninstalling and reinstalling the app won't delete them — " +
            "just pick the same folder again to restore everything."

        viewModel.songs.observe(viewLifecycleOwner) { binding.tvSongCount.text = "${it.size}" }
        viewModel.analytics.observe(viewLifecycleOwner) { binding.tvEventCount.text = "${it.playEvents.size}" }
        viewModel.reduceMotion.observe(viewLifecycleOwner) { enabled ->
            if (binding.switchReduceMotion.isChecked != enabled) {
                binding.switchReduceMotion.isChecked = enabled
            }
        }

        val themeSwitches = themeSwitches()
        themeSwitches.forEach { (switch, option) ->
            val swatch = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_theme_swatch)
                ?: error("Theme swatch drawable is missing")
            DrawableCompat.setTint(swatch, requireContext().getColor(option.colorRes))
            switch.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch, null, null, null)
            switch.setOnCheckedChangeListener { button, checked ->
                if (updatingThemeSwitches) return@setOnCheckedChangeListener
                if (checked) {
                    updatingThemeSwitches = true
                    try {
                        themeSwitches.forEach { (otherSwitch, _) ->
                            otherSwitch.isChecked = otherSwitch == button
                        }
                    } finally {
                        updatingThemeSwitches = false
                    }
                    viewModel.setTheme(option.id)
                } else if (viewModel.themeId.value == option.id) {
                    updatingThemeSwitches = true
                    button.isChecked = true
                    updatingThemeSwitches = false
                }
            }
        }

        viewModel.themeId.observe(viewLifecycleOwner) { theme ->
            if (themeSwitches.none { (_, option) -> option.id == theme }) {
                viewModel.setTheme("emerald")
                return@observe
            }
            updatingThemeSwitches = true
            try {
                themeSwitches.forEach { (switch, option) ->
                    switch.isChecked = option.id == theme
                }
            } finally {
                updatingThemeSwitches = false
            }
        }

        binding.switchReduceMotion.setOnCheckedChangeListener { _, checked ->
            viewModel.setReduceMotion(checked)
        }

        binding.btnChangeFolder.setOnClickListener { main.folderPickerLauncher.launch(null) }
        binding.btnRescan.isEnabled = viewModel.storage.hasSavedFolder()
        binding.btnRescan.alpha = if (binding.btnRescan.isEnabled) 1f else 0.45f
        binding.btnRescan.setOnClickListener {
            viewModel.rescan()
            viewModel.navigate(AppScreen.Scanning)
        }
        binding.btnRestoreRemoved.isEnabled = viewModel.storage.hasSavedFolder()
        binding.btnRestoreRemoved.alpha = if (binding.btnRestoreRemoved.isEnabled) 1f else 0.45f
        binding.btnRestoreRemoved.setOnClickListener {
            viewModel.getRemovedSongs(::showRestoreRemovedSongsDialog)
        }

        // Clear History with Text Input Confirmation ("CLEAR")
        binding.btnClearData.setOnClickListener {
            showClearConfirmationDialog()
        }
    }

    private fun showRestoreRemovedSongsDialog(songs: List<Song>) {
        if (songs.isEmpty()) {
            val hasRemovedSongs = viewModel.hasRemovedSongs()
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (hasRemovedSongs) "Restore hidden songs?" else "No removed songs")
                .setMessage(
                    if (hasRemovedSongs) {
                        "Some songs were hidden before their details could be saved. " +
                            "Restore all songs found in your selected music folder?"
                    } else {
                        "Songs you remove stay in the selected music folder. There are no hidden songs to restore right now."
                    }
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(if (hasRemovedSongs) "Restore all" else "Got it", null)
                .show()
            if (hasRemovedSongs) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    viewModel.restoreAllRemovedSongs()
                    viewModel.navigate(AppScreen.Scanning)
                }
            }
            return
        }

        val selected = BooleanArray(songs.size)
        val labels = songs.map { "${it.title} · ${it.artist}" }.toTypedArray()
        lateinit var dialog: AlertDialog
        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore songs")
            .setMessage("Choose songs to add back to your library.")
            .setMultiChoiceItems(labels, selected) { _, which, checked ->
                selected[which] = checked
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.any { it }
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore selected", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener {
                val selectedSongs = songs.indices
                    .filter { selected[it] }
                    .map { songs[it] }
                dialog.dismiss()
                viewModel.restoreSongs(selectedSongs)
                viewModel.navigate(AppScreen.Scanning)
            }
        }
    }

    private fun showClearConfirmationDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Type CLEAR"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.bg_search)
            val p = (12 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setTextColor(ctx.getColor(R.color.text_primary))
            setHintTextColor(ctx.getColor(R.color.text_tertiary))
            textSize = 14f
        }

        val horizontalPadding = (24 * ctx.resources.displayMetrics.density).toInt()
        val inputContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Clear listening history?")
            .setMessage(
                "This permanently deletes play history and custom albums from " +
                    "rhythmix_data.json. Your music files will stay untouched.\n\n" +
                    "Type CLEAR to confirm."
            )
            .setView(inputContainer)
            .setPositiveButton("Clear history") { _, _ ->
                viewModel.clearAnalytics()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        val confirmBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        confirmBtn.isEnabled = false
        confirmBtn.setTextColor(ctx.getColor(R.color.red))

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                confirmBtn.isEnabled = s?.toString()?.trim() == "CLEAR"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun themeSwitches(): List<Pair<MaterialSwitch, ThemeOption>> = listOf(
        binding.switchThemeEmerald to ThemeOption("emerald", R.color.emerald_accent),
        binding.switchThemeCyber to ThemeOption("cyber", R.color.cyber_accent),
        binding.switchThemeSunset to ThemeOption("sunset", R.color.sunset_accent),
        binding.switchThemeCyan to ThemeOption("cyan", R.color.cyan_accent),
        binding.switchThemeAmoled to ThemeOption("amoled", R.color.amoled_accent),
        binding.switchThemeRose to ThemeOption("rose", R.color.rose_accent),
        binding.switchThemeForest to ThemeOption("forest", R.color.forest_accent),
        binding.switchThemeIndigo to ThemeOption("indigo", R.color.indigo_accent)
    )

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
