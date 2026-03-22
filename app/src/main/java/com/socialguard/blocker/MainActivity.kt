package com.quell.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.quell.app.databinding.ActivityMainBinding
import com.quell.app.service.MonitoringForegroundService
import com.quell.app.util.PermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        startMonitoringService()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.visibility = View.VISIBLE
        }
    }

    private fun startMonitoringService() {
        if (PermissionHelper.isAccessibilityServiceEnabled(this)) {
            try {
                val intent = Intent(this, MonitoringForegroundService::class.java)
                startForegroundService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
