package com.rhythmix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rhythmix.app.R
import com.rhythmix.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

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

        viewModel.themeId.observe(viewLifecycleOwner) { theme ->
            binding.themeEmerald.isChecked = theme == "emerald"
            binding.themeCyber.isChecked = theme == "cyber"
            binding.themeSunset.isChecked = theme == "sunset"
            binding.themeCyan.isChecked = theme == "cyan"
            binding.themeAmoled.isChecked = theme == "amoled"
        }

        binding.themeEmerald.setOnClickListener { viewModel.setTheme("emerald") }
        binding.themeCyber.setOnClickListener { viewModel.setTheme("cyber") }
        binding.themeSunset.setOnClickListener { viewModel.setTheme("sunset") }
        binding.themeCyan.setOnClickListener { viewModel.setTheme("cyan") }
        binding.themeAmoled.setOnClickListener { viewModel.setTheme("amoled") }
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

        // Clear History with Text Input Confirmation ("CLEAR")
        binding.btnClearData.setOnClickListener {
            showClearConfirmationDialog()
        }
    }

    private fun showClearConfirmationDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val msgView = TextView(ctx).apply {
            text = "This will permanently remove all play history and custom albums from rhythmix_data.json. Your music files remain untouched.\n\nType \"CLEAR\" below to confirm:"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setLineSpacing(0f, 1.3f)
        }

        val input = EditText(ctx).apply {
            hint = "Type CLEAR"
            setSingleLine()
            setBackgroundResource(R.drawable.bg_search)
            val p = (12 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setTextColor(ctx.getColor(R.color.text_primary))
            setHintTextColor(ctx.getColor(R.color.text_tertiary))
        }

        container.addView(msgView)
        container.addView(input)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Confirm History Reset")
            .setView(container)
            .setPositiveButton("Clear History") { _, _ ->
                viewModel.clearAnalytics()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        val confirmBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        confirmBtn.isEnabled = false

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                confirmBtn.isEnabled = s?.toString()?.trim() == "CLEAR"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
