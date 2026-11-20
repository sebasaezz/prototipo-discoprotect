package cl.innova.nfc.reader

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
internal fun StaffBrand(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.discoprotect_banner),
        contentDescription = stringResource(R.string.staff_brand_name),
        modifier = modifier,
    )
}
