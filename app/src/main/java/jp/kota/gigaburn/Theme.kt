package jp.kota.gigaburn

import android.graphics.Color

/**
 * テーマパレット。UIの色はすべてここから解決する。
 * 監査D3で MainActivity から分離し、監査B1でタブの選択色もパレットに集約した
 * （以前は TAB_SELECTED と選択時文字色だけがパレットの外にあった）。
 */
data class Palette(
    val dark: Boolean,
    val background: Int,
    val barSurface: Int,
    val divider: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val tabUnselected: Int,
    val tabSelected: Int,
    val tabSelectedText: Int,
    val tabRippleUnselected: Int,
    val buttonBackground: Int,
    val buttonText: Int,
    val graphGrid: Int,
    val graphText: Int
)

// ダーク：従来（v0.8まで）の常時黒の見た目
val DARK_PALETTE = Palette(
    dark = true,
    background = Color.BLACK,
    barSurface = Color.rgb(0x1C, 0x1C, 0x1E), // 純黒のコンテンツ領域と分ける面色
    divider = Color.rgb(189, 189, 189),
    textPrimary = Color.WHITE,
    textSecondary = Color.rgb(189, 189, 189), // #BDBDBD
    tabUnselected = Color.rgb(189, 189, 189), // #BDBDBD：未選択でも読める明るいグレー
    tabSelected = Color.rgb(33, 150, 243), // 選択中タブの青
    tabSelectedText = Color.WHITE,
    tabRippleUnselected = Color.argb(70, 255, 255, 255),
    buttonBackground = Color.rgb(0x2C, 0x2C, 0x2E),
    buttonText = Color.WHITE,
    graphGrid = Color.rgb(0x3A, 0x3A, 0x3C),
    graphText = Color.rgb(189, 189, 189)
)

// ライト：白基調。選択中タブの青＋白文字はダークと共通
val LIGHT_PALETTE = Palette(
    dark = false,
    background = Color.rgb(0xFA, 0xFA, 0xFA),
    barSurface = Color.rgb(0xF0, 0xF0, 0xF0),
    divider = Color.rgb(0xC8, 0xC8, 0xC8),
    textPrimary = Color.rgb(0x21, 0x21, 0x21),
    textSecondary = Color.rgb(0x61, 0x61, 0x61),
    tabUnselected = Color.rgb(0x61, 0x61, 0x61),
    tabSelected = Color.rgb(33, 150, 243),
    tabSelectedText = Color.WHITE,
    tabRippleUnselected = Color.argb(40, 0, 0, 0),
    buttonBackground = Color.rgb(0xE0, 0xE0, 0xE0),
    buttonText = Color.rgb(0x21, 0x21, 0x21),
    graphGrid = Color.rgb(0xD6, 0xD6, 0xD6),
    graphText = Color.rgb(0x61, 0x61, 0x61)
)
