package com.quell.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.quell.app.databinding.FragmentSettingsBinding
import com.quell.app.util.CrashLogger

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDebugButtons()

        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe
            // Detach listeners before updating UI to avoid re-triggering saves
            clearListeners()

            binding.switchReels.isChecked = settings.blockInstagramReels
            binding.switchExplore.isChecked = settings.blockInstagramExplore
            binding.switchInstaDms.isChecked = settings.blockInstagramDMs
            binding.switchMarketplace.isChecked = settings.blockFacebookMarketplace
            binding.switchWatch.isChecked = settings.blockFacebookWatch
            binding.switchGaming.isChecked = settings.blockFacebookGaming
            binding.switchShowPopup.isChecked = settings.showUsagePopup

            attachListeners()
        }
    }

    private fun setupDebugButtons() {
        binding.btnShareLog.setOnClickListener {
            val logFile = CrashLogger.getLogFile(requireContext())
            if (!logFile.exists() || logFile.length() == 0L) {
                Toast.makeText(requireContext(), "No crash log found — app hasn't crashed yet!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Quell crash log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share bug log"))
        }

        binding.btnClearLog.setOnClickListener {
            CrashLogger.clearLog(requireContext())
            Toast.makeText(requireContext(), "Crash log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearListeners() {
        binding.switchReels.setOnCheckedChangeListener(null)
        binding.switchExplore.setOnCheckedChangeListener(null)
        binding.switchInstaDms.setOnCheckedChangeListener(null)
        binding.switchMarketplace.setOnCheckedChangeListener(null)
        binding.switchWatch.setOnCheckedChangeListener(null)
        binding.switchGaming.setOnCheckedChangeListener(null)
        binding.switchShowPopup.setOnCheckedChangeListener(null)
    }

    private fun attachListeners() {
        binding.switchReels.setOnCheckedChangeListener { _, v -> viewModel.setBlockInstagramReels(v) }
        binding.switchExplore.setOnCheckedChangeListener { _, v -> viewModel.setBlockInstagramExplore(v) }
        binding.switchInstaDms.setOnCheckedChangeListener { _, v -> viewModel.setBlockInstagramDMs(v) }
        binding.switchMarketplace.setOnCheckedChangeListener { _, v -> viewModel.setBlockFacebookMarketplace(v) }
        binding.switchWatch.setOnCheckedChangeListener { _, v -> viewModel.setBlockFacebookWatch(v) }
        binding.switchGaming.setOnCheckedChangeListener { _, v -> viewModel.setBlockFacebookGaming(v) }
        binding.switchShowPopup.setOnCheckedChangeListener { _, v -> viewModel.setShowUsagePopup(v) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
