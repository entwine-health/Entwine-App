// Invite-only enrollment (R-ENR-05): one code, nothing else. Also shows the
// one-time confirmation after an in-app deletion request (R-DEL-04).
package health.entwine.lucy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.R

@Composable
internal fun EnrollScreen(vm: AppViewModel, deletedDone: Boolean) {
    var code by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.entwine_wordmark),
            contentDescription = null, // decorative brand mark
            modifier = Modifier.fillMaxWidth(0.72f),
        )
        Spacer(Modifier.height(32.dp))
        if (deletedDone) {
            NoticeBanner(stringResource(R.string.delete_done), edge = EntwineCyan)
            Spacer(Modifier.height(16.dp))
        }
        Text(
            stringResource(R.string.enroll_title),
            fontSize = 26.sp,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.enroll_code), fontSize = 18.sp) },
            textStyle = TextStyle(fontSize = 28.sp, letterSpacing = 4.sp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.enroll_failed),
                color = MaterialTheme.colorScheme.error,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                busy = true
                vm.enroll(code) { ok -> busy = false; failed = !ok }
            },
            enabled = !busy && code.count(Char::isDigit) >= 4,
            modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.enroll_go), fontSize = 22.sp)
            }
        }
    }
}
