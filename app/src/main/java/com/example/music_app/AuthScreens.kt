package com.example.music_app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.music_app.ui.theme.MutedText
import com.example.music_app.ui.theme.NeonGreen
import com.example.music_app.ui.theme.Panel

@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit,
    onSocialLogin: (String, String) -> Unit,
    onRegisterClick: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        title = stringResource(R.string.auth_title),
        subtitle = stringResource(R.string.auth_login_subtitle)
    ) {
        AuthTabs(active = "in", onLoginClick = {}, onRegisterClick = onRegisterClick)
        AuthTextField(email, { email = it }, stringResource(R.string.email), KeyboardType.Email)
        AuthTextField(password, { password = it }, stringResource(R.string.password), KeyboardType.Password, password = true)
        TextButton(onClick = {}, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.forgot_password), color = NeonGreen)
        }
        PrimaryAction(text = stringResource(R.string.sign_in)) { onLogin(email, password) }

        Text(
            text = stringResource(R.string.continue_with),
            style = MaterialTheme.typography.labelLarge,
            color = MutedText,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SocialButton("", "") { onSocialLogin("Apple", email) }
            SocialButton("G", "") { onSocialLogin("Google", email) }
            SocialButton("VK", "") { onSocialLogin("VK", email) }
        }
    }
}

@Composable
fun RegisterScreen(
    onRegister: (String, String, String, String) -> Unit,
    onSocialRegister: (String, String) -> Unit,
    onLoginClick: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        title = stringResource(R.string.auth_title),
        subtitle = stringResource(R.string.auth_register_subtitle)
    ) {
        AuthTabs(active = "up", onLoginClick = onLoginClick, onRegisterClick = {})
        AuthTextField(name, { name = it }, stringResource(R.string.name))
        AuthTextField(phone, { phone = it }, stringResource(R.string.phone), KeyboardType.Phone)
        AuthTextField(email, { email = it }, stringResource(R.string.email), KeyboardType.Email)
        AuthTextField(password, { password = it }, stringResource(R.string.password), KeyboardType.Password, password = true)
        PrimaryAction(text = stringResource(R.string.sign_up)) { onRegister(name, email, phone, password) }
        Text(
            text = stringResource(R.string.continue_with),
            style = MaterialTheme.typography.labelLarge,
            color = MutedText,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SocialButton("", "") { onSocialRegister("Apple", email) }
            SocialButton("G", "") { onSocialRegister("Google", email) }
            SocialButton("VK", "") { onSocialRegister("VK", email) }
        }
    }
}

@Composable
private fun ColumnScope.AuthTabs(active: String, onLoginClick: () -> Unit, onRegisterClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(top = 2.dp)
            .then(Modifier),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        AuthTabButton(stringResource(R.string.login_tab), active == "in", Modifier.weight(1f), onLoginClick)
        AuthTabButton(stringResource(R.string.register_tab), active == "up", Modifier.weight(1f), onRegisterClick)
    }
}

@Composable
private fun AuthTabButton(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) NeonGreen else Panel,
            contentColor = if (active) Color.Black else MutedText
        ),
        modifier = modifier.height(68.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun CodeScreen(
    email: String,
    codePreview: String,
    onConfirm: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        title = stringResource(R.string.code_title),
        subtitle = stringResource(R.string.code_subtitle, email)
    ) {
        AuthTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it.filter(Char::isDigit) },
            label = stringResource(R.string.code_digits),
            keyboardType = KeyboardType.Number
        )
        PrimaryAction(text = stringResource(R.string.confirm)) { onConfirm(code) }
        if (codePreview.isNotBlank()) {
            Text(
                text = stringResource(R.string.prototype_code, codePreview),
                color = Color(0xFF59635F),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            TextButton(onClick = onResend) { Text(stringResource(R.string.resend_code)) }
        }
    }
}

@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(22.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    )
}
