package com.nitish.still.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nitish.still.R

// Define Font Families
val HeadingFont = FontFamily(
    Font(R.font.heading, FontWeight.Bold)
)

val TextFont = FontFamily(
    Font(R.font.text, FontWeight.Normal)
)

// Set up the Typography object with larger sizes
val AppTypography = Typography(
    // Headings
    displayLarge = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 60.sp, // Increased
        color = BrandTextPrimary
    ),
    displaySmall = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp, // Increased
        color = BrandTextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp, // Increased
        color = BrandTextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp, // Increased
        color = BrandTextPrimary
    ),

    // Body Text
    bodyLarge = TextStyle(
        fontFamily = TextFont,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp, // Increased
        color = BrandTextSecondary
    ),
    // Default for Buttons
    labelLarge = TextStyle(
        fontFamily = TextFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp // Increased
    )
)
