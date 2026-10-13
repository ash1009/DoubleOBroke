package app.brokeometer.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.brokeometer.ui.theme.Display
import app.brokeometer.ui.theme.LocalBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The profile picture, or the first letter of the name on amber when there is none. */
@Composable
fun Avatar(file: File, version: Long, name: String, size: Dp, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    val picture by produceState<ImageBitmap?>(initialValue = null, file, version) {
        value = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(brand.action),
        contentAlignment = Alignment.Center,
    ) {
        val shown = picture
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = "Profile picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "B",
                style = TextStyle(
                    fontFamily = Display,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp,
                ),
                color = brand.onAction,
            )
        }
    }
}
