package com.lunapunks.claudeassistant

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val assistantRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            statusText.text = "Claude is now your default assistant!\n\nLong-press Home or swipe from the corner to activate."
        } else {
            statusText.text = "Claude was not set as default assistant.\nTap the button to try again."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val setDefaultBtn = findViewById<Button>(R.id.setDefaultBtn)
        val testBtn = findViewById<Button>(R.id.testBtn)

        setDefaultBtn.setOnClickListener { requestAssistantRole() }
        testBtn.setOnClickListener {
            startActivity(Intent(this, AssistActivity::class.java))
        }

        // Check if already default
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            statusText.text = "Claude is your default assistant.\n\nLong-press Home or swipe from the corner to activate."
        }
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            assistantRoleLauncher.launch(intent)
        } else {
            statusText.text = "Assistant role not available on this device."
        }
    }
}
