package jp.kota.gigaburn

import android.graphics.Color

/**
* テーマパレット。UIの色はすべてここから解決する。
* 監査D3で MainActivity から分離し、監査B1でタブの選択色もパレットに集約した
* （以前は TAB_SELECTED と選択時文字色だけがパレットの外にあった）。
*
*
* デジタル庁デザインシステム（DADS）のデザイントークンを土台に再構成した実験パレット。
* トークン値は公式 npm パッケージ @digital-go-jp/design-tokens v2.1.0
* （registry.npmjs.org から取得した dist/tokens.css / tokens-simple.css）から転記。
* 詳細な対応表と判断理由はリポジトリ直下の DADS.md を参照。
* DADSにダークテーマの公式定義はないため、ダーク側は同じニュートラルグレーの
* 階調を反転方向に割り当てた「ローカルデザインシステム」側の判断である。
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
/** セカンダリボタン（DADSアウトラインボタン）の背景 */
val buttonBackground: Int,
/** セカンダリボタン（DADSアウトラインボタン）の文字色 */
val buttonText: Int,
/** セカンダリボタン（DADSアウトラインボタン）の枠線色 */
val buttonBorder: Int,
/** プライマリボタン（DADS塗りボタン）の背景 */
val primaryBackground: Int,
/** プライマリボタン（DADS塗りボタン）の文字色 */
val primaryText: Int,
/** テキスト入力欄の枠線色 */
val inputBorder: Int,
/** GIGA-BURNのアイデンティティとして残す唯一のアクセント（プログレスバー専用） */
val brandAccent: Int,
val graphGrid: Int,
val graphText: Int
)

// GIGA-BURNのブランド色：アプリアイコンの赤（colors.xml の ic_launcher_background と同値）。
// DADSの「ローカルデザインシステム」手順に倣い、色の骨格はDADSに寄せ、
// ブランド色はこの1か所（進捗表示）だけに残す。
private val GIGABURN_RED = Color.rgb(0xA5, 0x1D, 0x0F)

// ライト：DADS標準。白背景＋本文 #333333（solid-gray-800）＋キーカラー Blue-900。
val LIGHT_PALETTE = Palette(
dark = false,
background = Color.rgb(0xFF, 0xFF, 0xFF), // neutral white #FFFFFF
barSurface = Color.rgb(0xF2, 0xF2, 0xF2), // solid-gray-50 #F2F2F2
divider = Color.rgb(0xCC, 0xCC, 0xCC), // solid-gray-200 #CCCCCC
textPrimary = Color.rgb(0x33, 0x33, 0x33), // solid-gray-800 #333333（DADS本文色）
textSecondary = Color.rgb(0x66, 0x66, 0x66), // solid-gray-600 #666666
tabUnselected = Color.rgb(0x66, 0x66, 0x66), // solid-gray-600
tabSelected = Color.rgb(0x00, 0x17, 0xC1), // primitive blue-900 #0017C1（DADSキーカラー）
tabSelectedText = Color.WHITE,
tabRippleUnselected = Color.argb(40, 0, 0, 0),
buttonBackground = Color.WHITE, // アウトラインボタンは白背景
buttonText = Color.rgb(0x00, 0x17, 0xC1), // blue-900
buttonBorder = Color.rgb(0x00, 0x17, 0xC1), // blue-900
primaryBackground = Color.rgb(0x00, 0x17, 0xC1), // blue-900（塗りボタン）
primaryText = Color.WHITE,
inputBorder = Color.rgb(0x66, 0x66, 0x66), // solid-gray-600
brandAccent = GIGABURN_RED,
graphGrid = Color.rgb(0xCC, 0xCC, 0xCC), // solid-gray-200
graphText = Color.rgb(0x66, 0x66, 0x66) // solid-gray-600
)

// ダーク：DADS公式にダーク定義がないため、同じニュートラル階調でのローカル割り当て。
// 選択色・プライマリは視認性のため blue-800 #0031D8 に一段明るくしている（DADSプリミティブ内）。
val DARK_PALETTE = Palette(
dark = true,
background = Color.rgb(0x1A, 0x1A, 0x1A), // solid-gray-900 #1A1A1A
barSurface = Color.rgb(0x33, 0x33, 0x33), // solid-gray-800 #333333
divider = Color.rgb(0x4D, 0x4D, 0x4D), // solid-gray-700 #4D4D4D
textPrimary = Color.WHITE,
textSecondary = Color.rgb(0xCC, 0xCC, 0xCC), // solid-gray-200
tabUnselected = Color.rgb(0xCC, 0xCC, 0xCC), // solid-gray-200
tabSelected = Color.rgb(0x00, 0x31, 0xD8), // primitive blue-800 #0031D8
tabSelectedText = Color.WHITE,
tabRippleUnselected = Color.argb(70, 255, 255, 255),
buttonBackground = Color.rgb(0x1A, 0x1A, 0x1A), // 背景と同色＋枠線で示すアウトラインボタン
buttonText = Color.WHITE,
buttonBorder = Color.rgb(0xCC, 0xCC, 0xCC), // solid-gray-200
primaryBackground = Color.rgb(0x00, 0x31, 0xD8), // blue-800（塗りボタン）
primaryText = Color.WHITE,
inputBorder = Color.rgb(0xB3, 0xB3, 0xB3), // solid-gray-300 #B3B3B3
brandAccent = GIGABURN_RED,
graphGrid = Color.rgb(0x4D, 0x4D, 0x4D), // solid-gray-700
graphText = Color.rgb(0xCC, 0xCC, 0xCC) // solid-gray-200
)
