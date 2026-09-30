package com.v2ray.ang.colitu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.ui.ColituMainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColituCriticalFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ColituMainActivity>()

    @Test
    fun tourLeadsToSignUpAndSignIn() {
        ColituLoc.setLanguage("en")
        compose.onNodeWithText(ColituLoc["onb.skip"]).performClick()
        compose.onNodeWithText(ColituLoc["auth.email"]).assertIsDisplayed()
        compose.onNodeWithText(ColituLoc["auth.register"]).performClick()
        compose.onNodeWithText(ColituLoc["auth.passwordRepeat"]).assertIsDisplayed()
        compose.onNodeWithText(ColituLoc["auth.terms"]).assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun clearSession() = ColituTokenManager.clear()
    }
}
