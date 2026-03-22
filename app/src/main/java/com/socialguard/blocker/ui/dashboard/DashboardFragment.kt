package com.socialguard.blocker.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.socialguard.blocker.databinding.FragmentDashboardBinding
import com.socialguard.blocker.util.PermissionHelper
import com.socialguard.blocker.util.TimeUtils

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPermissionCards()
        setupObservers()
        setupToggles()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUsage()
        updatePermissionStatus()
    }

    private fun setupPermissionCards() {
        binding.cardPermAccessibility.setOnClickListener {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
        }
        binding.cardPermOverlay.setOnClickListener {
            startActivity(PermissionHelper.overlayPermissionIntent(requireContext()))
        }
        binding.cardPermUsage.setOnClickListener {
            startActivity(PermissionHelper.usageStatsSettingsIntent())
        }
    }

    private fun setupObservers() {
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe

            // Prevent toggle listener from firing during update
            binding.switchBlockInstagram.setOnCheckedChangeListener(null)
            binding.switchBlockFacebook.setOnCheckedChangeListener(null)

            binding.switchBlockInstagram.isChecked = settings.blockInstagram
            binding.switchBlockFacebook.isChecked = settings.blockFacebook

            updateBlockStatusUI(settings.blockInstagram, settings.blockFacebook)

            // Streak badge
            if (settings.currentStreak > 0) {
                binding.cardStreak.visibility = View.VISIBLE
                binding.tvStreakCount.text = settings.currentStreak.toString()
            } else {
                binding.cardStreak.visibility = View.GONE
            }

            setupToggles()
        }

        viewModel.instagramMinutes.observe(viewLifecycleOwner) { minutes ->
            binding.tvInstagramTime.text = TimeUtils.formatDuration(minutes)
            binding.progressInstagram.progress = minOf(minutes.toInt(), 180)
        }

        viewModel.facebookMinutes.observe(viewLifecycleOwner) { minutes ->
            binding.tvFacebookTime.text = TimeUtils.formatDuration(minutes)
            binding.progressFacebook.progress = minOf(minutes.toInt(), 180)
        }
    }

    private fun setupToggles() {
        binding.switchBlockInstagram.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBlockInstagram(isChecked)
        }
        binding.switchBlockFacebook.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBlockFacebook(isChecked)
        }
    }

    private fun updateBlockStatusUI(blockInsta: Boolean, blockFb: Boolean) {
        binding.chipInstagramStatus.text = if (blockInsta) "Blocked" else "Allowed"
        binding.chipInstagramStatus.isSelected = blockInsta

        binding.chipFacebookStatus.text = if (blockFb) "Blocked" else "Allowed"
        binding.chipFacebookStatus.isSelected = blockFb
    }

    private fun updatePermissionStatus() {
        val ctx = requireContext()
        binding.tvPermAccessibility.text = if (PermissionHelper.isAccessibilityServiceEnabled(ctx))
            "✓ Accessibility Service Active" else "✗ Enable Accessibility Service"
        binding.tvPermOverlay.text = if (PermissionHelper.canDrawOverlays(ctx))
            "✓ Overlay Permission Granted" else "✗ Grant Overlay Permission"
        binding.tvPermUsage.text = if (PermissionHelper.hasUsageStatsPermission(ctx))
            "✓ Usage Stats Granted" else "✗ Grant Usage Stats Access"

        val allGranted = PermissionHelper.allPermissionsGranted(ctx)
        binding.cardPermissions.visibility = if (allGranted) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
