package com.socialguard.blocker.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.socialguard.blocker.databinding.FragmentStatsBinding
import com.socialguard.blocker.util.TimeUtils
import java.text.SimpleDateFormat
import java.util.*

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StatsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        viewModel.loadStats()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadStats()
    }

    private fun setupObservers() {
        viewModel.instagramStats.observe(viewLifecycleOwner) { stats ->
            binding.tvInstagramToday.text = TimeUtils.formatDuration(stats.todayMinutes)
            binding.tvInstagramWeekly.text = TimeUtils.formatDuration(stats.weeklyTotal)
            populateChart(binding.chartInstagram, stats, Color.parseColor("#E1306C"))
        }

        viewModel.facebookStats.observe(viewLifecycleOwner) { stats ->
            binding.tvFacebookToday.text = TimeUtils.formatDuration(stats.todayMinutes)
            binding.tvFacebookWeekly.text = TimeUtils.formatDuration(stats.weeklyTotal)
            populateChart(binding.chartFacebook, stats, Color.parseColor("#1877F2"))
        }
    }

    private fun populateChart(chart: BarChart, stats: AppStats, barColor: Int) {
        // Generate last 7 day labels
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val labelSdf = SimpleDateFormat("EEE", Locale.getDefault())
        val labels = mutableListOf<String>()
        val entries = mutableListOf<BarEntry>()

        val cal = Calendar.getInstance()
        for (i in 6 downTo 0) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dateStr = sdf.format(cal.time)
            val label = labelSdf.format(cal.time)
            labels.add(label)
            val minutes = stats.dailyMinutes[dateStr] ?: 0L
            entries.add(BarEntry((6 - i).toFloat(), minutes.toFloat()))
        }

        val dataSet = BarDataSet(entries, stats.appName).apply {
            color = barColor
            valueTextColor = Color.WHITE
            valueTextSize = 10f
            setDrawValues(false)
        }

        chart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setFitBars(true)
            setDrawGridBackground(false)
            setDrawBorders(false)
            axisRight.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                setDrawGridLines(false)
                textColor = Color.WHITE
            }
            axisLeft.apply {
                textColor = Color.WHITE
                setDrawGridLines(true)
                gridColor = Color.parseColor("#33FFFFFF")
                axisMinimum = 0f
            }

            animateY(500)
            invalidate()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
