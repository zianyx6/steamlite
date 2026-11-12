package org.steamlite

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.util.Locale

/** App-level dialogs survive the login card being replaced by the account screen. */
@Composable
fun SteamLoginDialogs(steam: SteamConnection) {
    val online by steam.online.collectAsState()
    val busy by steam.busy.collectAsState()
    val guard by steam.guard.collectAsState()
    val needsCode by steam.needsGuardCode.collectAsState()
    val succeeded by steam.loginSucceeded.collectAsState()
    val phonePending by steam.phoneConfirmationPending.collectAsState()
    val submitting by steam.guardCodeSubmitting.collectAsState()
    val scanning by steam.qrLogin.collectAsState()
    if (scanning && !online) {
        val challenge by steam.qrChallenge.collectAsState()
        val status by steam.status.collectAsState()
        SteamQrDialog(challenge, status, steam::cancelLogin)
        return
    }
    when (loginDialogState(online, busy, guard, succeeded)) {
        LoginDialogState.GUARD -> SteamGuardDialog(guard, needsCode, steam::submitCode, steam::cancelLogin, phonePending, submitting)
        LoginDialogState.SUCCESS -> {
            LaunchedEffect(Unit) { delay(1800); steam.loginSucceeded.value = false }
            LoginSuccessDialog { steam.loginSucceeded.value = false }
        }
        LoginDialogState.NONE -> Unit
    }
}

@Composable
private fun SteamQrDialog(challenge: String?, status: String, cancel: () -> Unit) {
    val qr = remember(challenge) {
        challenge?.let { url ->
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            android.graphics.Bitmap.createBitmap(
                pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888
            ).asImageBitmap()
        }
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = RoundedCornerShape(26.dp), color = Surface1) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Steam 扫码登录", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Text1)
                Text("用另一台设备打开 Steam 手机应用，在 Steam Guard 中扫码并确认。", color = Text2,
                    fontSize = 13.sp, textAlign = TextAlign.Center)
                if (qr != null) {
                    Image(qr, contentDescription = "Steam 登录二维码", modifier = Modifier.size(216.dp).background(Color.White))
                } else {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Accent)
                }
                Text(status, fontSize = 12.sp, color = Text3, textAlign = TextAlign.Center)
                GhostButton("取消", cancel, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun SteamGuardDialog(prompt: String, needsCode: Boolean, submit: (String) -> Unit, cancel: () -> Unit, phonePending: Boolean = false, submitting: Boolean = false) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun confirm() {
        if (needsCode && !submitting && value.length == 5) { keyboard?.hide(); submit(value); value = "" }
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = Surface1, contentColor = Text1) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(54.dp).background(AccentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    SteamIcon(Glyph.Shield, 28.dp, Accent)
                }
                Spacer(Modifier.height(16.dp))
                Text("Steam Guard 验证", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(prompt, fontSize = 12.5.sp, lineHeight = 19.sp, color = if (prompt.contains("错误")) Danger else Text2, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (phonePending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                        Text("正在等待手机确认", fontSize = 12.sp, color = Text2, modifier = Modifier.padding(start = 10.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (needsCode) {
                    LaunchedEffect(phonePending) { if (!phonePending) { delay(100); focus.requestFocus(); keyboard?.show() } }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.uppercase(Locale.ROOT).filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(5) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        enabled = !submitting,
                        singleLine = true,
                        placeholder = { Text(if (phonePending) "输入 5 位令牌码" else "输入 5 位验证码", fontSize = 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, color = Text1),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = LineStrong, cursorColor = Accent)
                    )
                    Spacer(Modifier.height(16.dp))
                    AccentButton(if (submitting) "正在验证令牌码…" else "验证并登录", { confirm() }, Modifier.fillMaxWidth(), enabled = !submitting && value.length == 5)
                } else if (!phonePending) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("验证完成后将自动继续", fontSize = 11.sp, color = Text3)
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("取消登录", cancel, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun LoginSuccessDialog(close: () -> Unit) {
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(24.dp), color = Surface1, contentColor = Text1) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).background(AccentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    SteamIcon(Glyph.Check, 40.dp, Accent, 2.6f)
                }
                Spacer(Modifier.height(20.dp))
                Text("登录成功", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("已连接 Steam", fontSize = 13.sp, color = Text2)
                Spacer(Modifier.height(22.dp))
                AccentButton("完成", close, Modifier.fillMaxWidth())
            }
        }
    }
}
