package com.quell.app.ui.timelocks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.quell.app.databinding.FragmentTimelocksBinding
import com.quell.app.util.TimeUtils

private const val SEEK_DAILY_MIN = 5

class TimeLocksFragment : Fragment() {

    private var _binding: FragmentTimelocksBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TimeLocksViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimelocksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe

            binding.switchTimeLock.setOnCheckedChangeListener(null)
            binding.switchSessionLimit.setOnCheckedChangeListener(null)
            binding.switchDailyLimit.setOnCheckedChangeListener(null)

            binding.switchTimeLock.isChecked = settings.timeLockEnabled
            binding.tvStartTime.text = TimeUtils.formatTime(settings.timeLockStartHour, settings.timeLockStartMinute)
            binding.tvEndTime.text = TimeUtils.formatTime(settings.timeLockEndHour, settings.timeLockEndMinute)
            updateTimeLockEnabled(settings.timeLockEnabled)

            binding.switchSessionLimit.isChecked = settings.sessionLimitEnabled
            binding.seekBarSession.progress = settings.sessionLimitMinutes
            binding.tvSessionMinutes.text = "${settings.sessionLimitMinutes} minutes"
            updateSessionLimitEnabled(settings.sessionLimitEnabled)

            binding.switchDailyLimit.isChecked = settings.dailyLimitEnabled
            binding.seekDailyInstagram.progress = settings.dailyLimitMinutesInstagram - SEEK_DAILY_MIN
            binding.tvDailyInstagramMinutes.text = "${settings.dailyLimitMinutesInstagram} min"
            binding.seekDailyFacebook.progress = settings.dailyLimitMinutesFacebook - SEEK_DAILY_MIN
            binding.tvDailyFacebookMinutes.text = "${settings.dailyLimitMinutesFacebook} min"
            updateDailyLimitEnabled(settings.dailyLimitEnabled)

            // Re-attach listeners
            binding.switchTimeLock.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setTimeLockEnabled(isChecked)
                updateTimeLockEnabled(isChecked)
            }
            binding.switchSessionLimit.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setSessionLimitEnabled(isChecked)
                updateSessionLimitEnabled(isChecked)
            }
            binding.switchDailyLimit.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setDailyLimitEnabled(isChecked)
                updateDailyLimitEnabled(isChecked)
            }
        }

        // Start time picker
        binding.btnPickStartTime.setOnClickListener {
            val current = viewModel.settings.value
            showTimePicker(
                "Block Start Time",
                current?.timeLockStartHour ?: 22,
                current?.timeLockStartMinute ?: 0
            ) { h, m ->
                viewModel.setTimeLockStart(h, m)
            }
        }

        // End time picker
        binding.btnPickEndTime.setOnClickListener {
            val current = viewModel.settings.value
            showTimePicker(
                "Block End Time",
                current?.timeLockEndHour ?: 7,
                current?.timeLockEndMinute ?: 0
            ) { h, m ->
                viewModel.setTimeLockEnd(h, m)
            }
        }

        // Session limit slider
        binding.seekBarSession.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = maxOf(1, progress)
                binding.tvSessionMinutes.text = "$minutes minutes"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val minutes = maxOf(1, seekBar?.progress ?: 5)
                viewModel.setSessionLimitMinutes(minutes)
            }
        })

        // Daily limit sliders
        binding.seekDailyInstagram.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = maxOf(SEEK_DAILY_MIN, progress + SEEK_DAILY_MIN)
                binding.tvDailyInstagramMinutes.text = "$minutes min"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val minutes = maxOf(SEEK_DAILY_MIN, (seekBar?.progress ?: 25) + SEEK_DAILY_MIN)
                viewModel.setDailyLimitInstagram(minutes)
            }
        })

        binding.seekDailyFacebook.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = maxOf(SEEK_DAILY_MIN, progress + SEEK_DAILY_MIN)
                binding.tvDailyFacebookMinutes.text = "$minutes min"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val minutes = maxOf(SEEK_DAILY_MIN, (seekBar?.progress ?: 25) + SEEK_DAILY_MIN)
                viewModel.setDailyLimitFacebook(minutes)
            }
        })
    }

    private fun showTimePicker(title: String, hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            onSet(picker.hour, picker.minute)
        }

        picker.show(childFragmentManager, "time_picker")
    }

    private fun updateTimeLockEnabled(enabled: Boolean) {
        binding.btnPickStartTime.isEnabled = enabled
        binding.btnPickEndTime.isEnabled = enabled
        binding.tvStartTime.alpha = if (enabled) 1f else 0.4f
        binding.tvEndTime.alpha = if (enabled) 1f else 0.4f
        binding.tvTimeLockDesc.text = if (enabled)
            "Apps will be blocked in the set time window"
        else
            "Enable to block apps during specific hours"
    }

    private fun updateSessionLimitEnabled(enabled: Boolean) {
        binding.seekBarSession.isEnabled = enabled
        binding.tvSessionMinutes.alpha = if (enabled) 1f else 0.4f
        binding.tvSessionDesc.text = if (enabled)
            "Each app session will be limited to the set duration"
        else
            "Enable to limit how long you can use the app per session"
    }

    private fun updateDailyLimitEnabled(enabled: Boolean) {
        binding.layoutDailyLimitControls.alpha = if (enabled) 1f else 0.4f
        binding.seekDailyInstagram.isEnabled = enabled
        binding.seekDailyFacebook.isEnabled = enabled
        binding.tvDailyLimitDesc.text = if (enabled)
            "Apps will be blocked once you hit the daily cap"
        else
            "Block apps once you hit the daily usage cap"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
