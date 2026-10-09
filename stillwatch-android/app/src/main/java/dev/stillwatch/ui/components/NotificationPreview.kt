package dev.stillwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.stillwatch.ui.model.NotificationContent
import dev.stillwatch.ui.theme.Spacing

/** Content study only: deliberately not a recreation of an OS lock screen. */
@Composable
fun NotificationContentPreview(content: NotificationContent, privacyMode: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Android 알림 문구 미리보기", style = MaterialTheme.typography.labelMedium)
        InfoCard {
            Text("StillWatch", style = MaterialTheme.typography.labelMedium)
            Text(if (privacyMode) "StillWatch 알림" else content.title, style = MaterialTheme.typography.titleMedium)
            Text(if (privacyMode) content.publicBody else content.body, style = MaterialTheme.typography.bodyLarge)
        }
        Note("문구와 정보 순서 예시입니다. 실제 배치·알림 표시·전달은 Android와 앱 설정에 따라 달라집니다.")
    }
}
