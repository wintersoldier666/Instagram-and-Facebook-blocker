package com.quell.app.ui.settings

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.card.MaterialCardView
import com.quell.app.databinding.FragmentSettingsBinding
import com.quell.app.service.LocalVpnService
import com.quell.app.service.MonitoringForegroundService
import com.quell.app.util.BlockingModePrefs
import com.quell.app.util.CrashLogger
import com.quell.app.util.PermissionHelper

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    // VPN permission request — fires after user confirms or denies the system VPN dialog
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            applyVpnMode()
        } else {
            Toast.makeText(requireContext(), "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupModeCards()
        setupDebugButtons()

        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe
            clearListeners()
            binding.switchReels.isChecked       = settings.blockInstagramReels
            binding.switchExplore.isChecked     = settings.blockInstagramExplore
            binding.switchInstaDms.isChecked    = settings.blockInstagramDMs
            binding.switchMarketplace.isChecked = settings.blockFacebookMarketplace
            binding.switchWatch.isChecked       = settings.blockFacebookWatch
            binding.switchGaming.isChecked      = settings.blockFacebookGaming
            binding.switchShowPopup.isChecked   = settings.showUsagePopup
            attachListeners()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh mode badges when returning from accessibility settings
        updateModeUI()
    }

    // -------------------------------------------------------------------------
    // Mode selector
    // -------------------------------------------------------------------------

    private fun setupModeCards() {
        binding.btnSelectAccessibility.setOnClickListener {
            // Stop VPN if running
            stopVpnService()
            BlockingModePrefs.setMode(requireContext(), BlockingModePrefs.MODE_ACCESSIBILITY)
            // Restart monitoring service (it will stop polling because accessibility is on)
            restartMonitoringService()
            updateModeUI()
            Toast.makeText(requireContext(),
                "Accessibility mode selected — enable the service in Accessibility Settings",
                Toast.LENGTH_LONG).show()
        }

        binding.btnSelectUsageStats.setOnClickListener {
            // Stop VPN if running
            stopVpnService()
            BlockingModePrefs.setMode(requireContext(), BlockingModePrefs.MODE_USAGE_STATS)
            restartMonitoringService()
            updateModeUI()
            Toast.makeText(requireContext(),
                "Usage Stats mode active — disable accessibility service for full banking safety",
                Toast.LENGTH_LONG).show()
        }

        binding.btnSelectVpn.setOnClickListener {
            val vpnIntent = VpnService.prepare(requireContext())
            if (vpnIntent != null) {
                // Need user to grant VPN permission
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                applyVpnMode()
            }
        }

        updateModeUI()
    }

    private fun applyVpnMode() {
        BlockingModePrefs.setMode(requireContext(), BlockingModePrefs.MODE_VPN)
        // Start VPN service for DNS blocking
        requireContext().startService(
            Intent(requireContext(), LocalVpnService::class.java)
        )
        // Also start monitoring service for overlay feedback
        restartMonitoringService()
        updateModeUI()
        Toast.makeText(requireContext(),
            "VPN mode active — Instagram & Facebook DNS will be blocked",
            Toast.LENGTH_LONG).show()
    }

    /**
     * Highlight the active mode card and show/hide ACTIVE badges.
     */
    private fun updateModeUI() {
        val ctx = requireContext()
        val mode = BlockingModePrefs.getMode(ctx)
        val activeColor = ctx.getColor(com.quell.app.R.color.green_accent)
        val inactiveColor = 0x00000000  // transparent = no stroke

        fun updateCard(card: MaterialCardView, badgeView: View, isActive: Boolean) {
            card.strokeColor = if (isActive) activeColor else
                ctx.getColor(com.quell.app.R.color.divider)
            badgeView.visibility = if (isActive) View.VISIBLE else View.GONE
        }

        updateCard(binding.cardModeAccessibility, binding.badgeModeAccessibility,
            mode == BlockingModePrefs.MODE_ACCESSIBILITY)
        updateCard(binding.cardModeUsageStats, binding.badgeModeUsageStats,
            mode == BlockingModePrefs.MODE_USAGE_STATS)
        updateCard(binding.cardModeVpn, binding.badgeModeVpn,
            mode == BlockingModePrefs.MODE_VPN)
    }

    private fun restartMonitoringService() {
        requireContext().startForegroundService(
            Intent(requireContext(), MonitoringForegroundService::class.java)
        )
    }

    private fun stopVpnService() {
        LocalVpnService.instance?.let {
            requireContext().startService(
                Intent(requireContext(), LocalVpnService::class.java)
                    .setAction(LocalVpnService.ACTION_STOP)
            )
        }
    }

    // -------------------------------------------------------------------------
    // Debug buttons
    // -------------------------------------------------------------------------

    private fun setupDebugButtons() {
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
        }
        binding.btnShareLog.setOnClickListener {
            val logFile = CrashLogger.getLogFile(requireContext())
            if (!logFile.exists() || logFile.length() == 0L) {
                Toast.makeText(requireContext(),
                    "No crash log found — app hasn't crashed yet!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", logFile)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Quell crash log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share bug log"))
        }
        binding.btnClearLog.setOnClickListener {
            CrashLogger.clearLog(requireContext())
            Toast.makeText(requireContext(), "Crash log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Content-lock switches
    // -------------------------------------------------------------------------

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
        binding.switchReels.setOnCheckedChangeListener       { _, v -> viewModel.setBlockInstagramReels(v) }
        binding.switchExplore.setOnCheckedChangeListener     { _, v -> viewModel.setBlockInstagramExplore(v) }
        binding.switchInstaDms.setOnCheckedChangeListener    { _, v -> viewModel.setBlockInstagramDMs(v) }
        binding.switchMarketplace.setOnCheckedChangeListener { _, v -> viewModel.setBlockFacebookMarketplace(v) }
        binding.switchWatch.setOnCheckedChangeListener       { _, v -> viewModel.setBlockFacebookWatch(v) }
        binding.switchGaming.setOnCheckedChangeListener      { _, v -> viewModel.setBlockFacebookGaming(v) }
        binding.switchShowPopup.setOnCheckedChangeListener   { _, v -> viewModel.setShowUsagePopup(v) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
