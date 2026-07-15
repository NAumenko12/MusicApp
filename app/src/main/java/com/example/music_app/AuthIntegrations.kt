package com.example.music_app

interface EmailCodeSender {
    fun sendCode(email: String, code: String): Result<Unit>
}

class PrototypeEmailCodeSender : EmailCodeSender {
    override fun sendCode(email: String, code: String): Result<Unit> {
        return Result.failure(
            IllegalStateException("Email server is not connected. Prototype code is available.")
        )
    }
}

data class SocialAuthRequest(
    val provider: String,
    val emailHint: String,
)

data class SocialAuthResult(
    val provider: String,
    val email: String,
)

class SocialAuthManager {
    fun signIn(request: SocialAuthRequest): Result<SocialAuthResult> {
        val email = request.emailHint.trim().lowercase()
        if (!email.contains("@")) {
            return Result.failure(IllegalArgumentException("Enter an email first to test social sign-in"))
        }
        return Result.success(SocialAuthResult(provider = request.provider, email = email))
    }
}
