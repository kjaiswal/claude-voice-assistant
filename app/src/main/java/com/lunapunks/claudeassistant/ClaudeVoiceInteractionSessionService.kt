package com.lunapunks.claudeassistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class ClaudeVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return ClaudeVoiceInteractionSession(this)
    }
}

class ClaudeVoiceInteractionSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // When invoked as assistant, just launch AssistActivity
        val intent = android.content.Intent(context, AssistActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        finish()
    }
}
